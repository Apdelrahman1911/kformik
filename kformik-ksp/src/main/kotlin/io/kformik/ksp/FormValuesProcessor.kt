package io.kformik.ksp

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance

/**
 * Symbol processor that emits a `<Name>Paths` object and a `<Name>Updater` for every `@FormValues`
 * data class.
 *
 * Example input:
 *
 * ```kotlin
 * @FormValues data class LoginValues(val email: String, val password: String)
 * ```
 *
 * Generated output:
 *
 * ```kotlin
 * object LoginValuesPaths {
 *     const val email: String = "email"
 *     const val password: String = "password"
 * }
 * ```
 *
 * Nested example:
 *
 * ```kotlin
 * @FormValues data class AddressValues(val city: String, val country: String)
 * @FormValues data class UserValues(val name: String, val address: AddressValues)
 * ```
 *
 * Generated:
 *
 * ```kotlin
 * object UserValuesPaths {
 *     const val name: String = "name"
 *     object address {
 *         const val city: String = "address.city"
 *         const val country: String = "address.country"
 *     }
 * }
 * ```
 *
 * Limitations (deliberate in this proof-of-concept):
 *  - `List<...>` properties are emitted as plain `String` constants pointing at the array path
 *    (e.g. `friends: String = "friends"`). Per-index access still uses string literals like
 *    `"${LoginValuesPaths.friends}[0]"`.
 *  - Recursive type references would loop; the processor caps at depth 8.
 *
 * Unsupported targets (a `@FormValues` on a non-data / sealed / abstract / generic class, or a
 * class with no primary constructor) are reported via the KSP logger and skipped, rather than
 * silently emitting code that fails to compile.
 */
public class FormValuesProcessor(private val env: SymbolProcessorEnvironment) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val candidates = resolver.getSymbolsWithAnnotation(FormValues::class.qualifiedName!!)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        if (candidates.isEmpty()) return emptyList()

        // Validate each target up front and report unsupported shapes against the declaration,
        // turning a later "unresolved reference: copy" inside generated code into an actionable error.
        val targets = candidates.filter { decl ->
            val supported = isSupported(decl)
            if (!supported) env.logger.error(unsupportedMessage(decl), decl)
            supported
        }
        if (targets.isEmpty()) return emptyList()

        // Pre-build a map of (qualifiedName → KSClassDeclaration) so the recursive emitter can
        // inline nested @FormValues types.
        val byName: Map<String, KSClassDeclaration> = targets
            .mapNotNull { decl -> decl.qualifiedName?.asString()?.let { it to decl } }
            .toMap()

        targets.forEach { decl ->
            emit(decl, byName)
            emitUpdater(decl, byName)
        }
        return emptyList()
    }

    /** A target is supported only if `copy(...)` and a primary constructor exist and it is concrete & non-generic. */
    private fun isSupported(decl: KSClassDeclaration): Boolean =
        decl.classKind == ClassKind.CLASS &&
            Modifier.DATA in decl.modifiers &&
            decl.primaryConstructor != null &&
            Modifier.ABSTRACT !in decl.modifiers &&
            Modifier.SEALED !in decl.modifiers &&
            decl.typeParameters.isEmpty()

    private fun unsupportedMessage(decl: KSClassDeclaration): String {
        val name = decl.qualifiedName?.asString() ?: decl.simpleName.asString()
        return "@FormValues requires a concrete, non-generic `data class` with a primary constructor; " +
            "'$name' is not supported (must not be a plain/sealed/abstract/enum/interface class or have type parameters)."
    }

    private fun emit(decl: KSClassDeclaration, byName: Map<String, KSClassDeclaration>) {
        val pkg = decl.packageName.asString()
        val name = decl.simpleName.asString()
        val pathsName = "${name}Paths"

        val originating = collectOriginatingFiles(decl, byName)
        val file = env.codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = false, *originating.toTypedArray()),
            packageName = pkg,
            fileName = pathsName,
        )

        file.bufferedWriter().use { w ->
            if (pkg.isNotEmpty()) w.appendLine("package $pkg").appendLine()
            w.appendLine("// Generated by io.kformik.ksp.FormValuesProcessor. Do not edit.")
            w.appendLine("@Suppress(\"ConstPropertyName\", \"ObjectPropertyName\")")
            w.appendLine("object $pathsName {")
            emitProperties(w, decl, prefix = "", indent = "    ", byName, depth = 0)
            w.appendLine("}")
        }
    }

    /**
     * Emit a `<Name>Updater : io.kformik.ValuesUpdater<Name>` object alongside the Paths object.
     *
     * Casts use fully-qualified type names (package-independent, no imports), nested-type references
     * are package-qualified, identifiers that collide with hard keywords are backtick-escaped, and a
     * non-nullable field set to `null` yields a path-named error rather than an opaque NPE.
     */
    private fun emitUpdater(decl: KSClassDeclaration, byName: Map<String, KSClassDeclaration>) {
        val pkg = decl.packageName.asString()
        val name = decl.simpleName.asString()
        val updaterName = "${name}Updater"

        val originating = collectOriginatingFiles(decl, byName)
        val file = env.codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = false, *originating.toTypedArray()),
            packageName = pkg,
            fileName = updaterName,
        )

        // copy(...) only accepts primary-constructor parameters, so restrict to those (excludes
        // computed body vals and inherited properties that have no corresponding copy() argument).
        val ctorParamNames: Set<String> = decl.primaryConstructor?.parameters
            ?.filter { it.isVal || it.isVar }
            ?.mapNotNull { it.name?.asString() }
            ?.toSet()
            ?: emptySet()

        val props: List<UpdaterPropInfo> = decl.getAllProperties()
            .filter { it.simpleName.asString() in ctorParamNames }
            .map { p ->
                val resolved = p.type.resolve()
                val td = resolved.declaration as? KSClassDeclaration
                val fq = td?.qualifiedName?.asString()
                val nested = fq?.let { byName[it] }
                val isNested = nested != null && nested.classKind == ClassKind.CLASS
                UpdaterPropInfo(
                    name = p.simpleName.asString(),
                    renderedType = renderType(resolved),
                    isNested = isNested,
                    nestedSimpleName = nested?.simpleName?.asString(),
                    nestedPackage = nested?.packageName?.asString()?.takeIf { it.isNotEmpty() },
                    nullable = resolved.isMarkedNullable,
                )
            }
            .toList()

        file.bufferedWriter().use { w ->
            if (pkg.isNotEmpty()) w.appendLine("package $pkg").appendLine()
            w.appendLine("// Generated by io.kformik.ksp.FormValuesProcessor. Do not edit.")
            w.appendLine("@Suppress(\"UNCHECKED_CAST\", \"USELESS_CAST\")")
            w.appendLine("object $updaterName : io.kformik.ValuesUpdater<$name> {")

            // getAt — when (path) { "x" -> values.x; "nested.X" -> NestedUpdater.getAt(...); else -> null }
            w.appendLine("    override fun getAt(values: $name, path: String): Any? {")
            w.appendLine("        return when {")
            for (p in props) {
                w.appendLine("            path == \"${p.name}\" -> values.${esc(p.name)}")
                if (p.isNested && p.nestedSimpleName != null) {
                    // For a nullable nested field, null-guard the delegation (the nested getAt takes a
                    // non-null receiver); a null parent resolves to null, consistent with getAt's Any? return.
                    val getRecurse = if (p.nullable) {
                        "values.${esc(p.name)}?.let { ${nestedUpdaterRef(p)}.getAt(it, path.removePrefix(\"${p.name}.\")) }"
                    } else {
                        "${nestedUpdaterRef(p)}.getAt(values.${esc(p.name)}, path.removePrefix(\"${p.name}.\"))"
                    }
                    w.appendLine("            path.startsWith(\"${p.name}.\") -> $getRecurse")
                }
            }
            w.appendLine("            else -> null")
            w.appendLine("        }")
            w.appendLine("    }")
            w.appendLine()

            // setAt — when … copy(x = value as T) | copy(nested = NestedUpdater.setAt(...))
            w.appendLine("    override fun setAt(values: $name, path: String, value: Any?): $name {")
            w.appendLine("        return when {")
            for (p in props) {
                w.appendLine("            path == \"${p.name}\" -> values.copy(${esc(p.name)} = ${assignExpr(p)})")
                if (p.isNested && p.nestedSimpleName != null) {
                    // `renderedType` is the FQN (nullability-aware) of the nested type. For a nullable
                    // nested field the `?: error` guards recursion into null; for a non-null field it
                    // is unreachable but harmless.
                    w.appendLine("            path.startsWith(\"${p.name}.\") -> values.copy(${esc(p.name)} = ${nestedUpdaterRef(p)}.setAt(values.${esc(p.name)} as ${p.renderedType} ?: error(\"Cannot recurse into null ${p.name}\"), path.removePrefix(\"${p.name}.\"), value))")
                }
            }
            w.appendLine("            else -> error(\"Unknown path: \$path\")")
            w.appendLine("        }")
            w.appendLine("    }")
            w.appendLine()

            // leafPaths — flatten own props; for nested, prefix the child's leafPaths with "<prop>."
            w.appendLine("    override fun leafPaths(values: $name): Set<String> {")
            w.appendLine("        val out = mutableSetOf<String>()")
            for (p in props) {
                if (p.isNested && p.nestedSimpleName != null) {
                    // Parenthesize so `.forEach` binds to the whole elvis (not just the empty fallback)
                    // and give the empty set an explicit type argument.
                    val recv = if (p.nullable) {
                        "(values.${esc(p.name)}?.let { ${nestedUpdaterRef(p)}.leafPaths(it) } ?: emptySet<String>())"
                    } else {
                        "${nestedUpdaterRef(p)}.leafPaths(values.${esc(p.name)})"
                    }
                    w.appendLine("        $recv.forEach { out += \"${p.name}.\$it\" }")
                } else {
                    w.appendLine("        out += \"${p.name}\"")
                }
            }
            w.appendLine("        return out.toSet()")
            w.appendLine("    }")
            w.appendLine("}")
        }
    }

    /**
     * Render a type to a fully-qualified, type-argument-complete, nullability-aware string suitable
     * for a cast: e.g. `kotlin.String`, `kotlin.String?`, `kotlin.collections.List<kotlin.String>`,
     * `kotlin.collections.Map<kotlin.String, kotlin.Int>`. Fully-qualified names need no imports and
     * are package-independent; rendering the type arguments keeps collection casts compilable
     * (a bare `List` is not a legal Kotlin cast target).
     */
    private fun renderType(type: KSType): String {
        val decl = type.declaration
        val qn = (decl as? KSClassDeclaration)?.qualifiedName?.asString() ?: decl.simpleName.asString()
        val args = type.arguments
        val base = if (args.isEmpty()) {
            qn
        } else {
            val rendered = args.joinToString(", ") { arg ->
                if (arg.variance == Variance.STAR) "*"
                else arg.type?.resolve()?.let { renderType(it) } ?: "*"
            }
            "$qn<$rendered>"
        }
        return base + if (type.isMarkedNullable) "?" else ""
    }

    /** Right-hand side for `copy(name = …)`: a non-null field rejects a null write with a clear error. */
    private fun assignExpr(p: UpdaterPropInfo): String =
        if (p.nullable) {
            "value as ${p.renderedType}"
        } else {
            "(value ?: error(\"Field '${p.name}' is non-null and cannot be set to null\")) as ${p.renderedType}"
        }

    /** Package-qualified reference to a nested type's generated updater object. */
    private fun nestedUpdaterRef(p: UpdaterPropInfo): String =
        (p.nestedPackage?.let { "$it." } ?: "") + "${p.nestedSimpleName}Updater"

    private data class UpdaterPropInfo(
        val name: String,
        val renderedType: String,
        val isNested: Boolean,
        val nestedSimpleName: String?,
        val nestedPackage: String?,
        val nullable: Boolean,
    )

    /**
     * Collect every source file that the generated outputs for [decl] depend on.
     *
     * Returned set: `decl`'s own containing file, plus the containing files of every
     * transitively-reachable nested `@FormValues` declaration that the generated `*Paths` /
     * `*Updater` for [decl] embeds. Passing this set into `Dependencies(aggregating = false, …)`
     * gives KSP precise per-file invalidation.
     *
     * Cycles between `@FormValues` types are short-circuited by `visited`.
     */
    private fun collectOriginatingFiles(
        decl: KSClassDeclaration,
        byName: Map<String, KSClassDeclaration>,
        visited: MutableSet<String> = mutableSetOf(),
    ): Set<KSFile> {
        val qn = decl.qualifiedName?.asString() ?: return emptySet()
        if (!visited.add(qn)) return emptySet()
        val out = mutableSetOf<KSFile>()
        decl.containingFile?.let(out::add)
        for (prop in decl.getAllProperties()) {
            val typeDecl = prop.type.resolve().declaration as? KSClassDeclaration ?: continue
            val nestedQn = typeDecl.qualifiedName?.asString() ?: continue
            val nested = byName[nestedQn] ?: continue
            if (nested.classKind == ClassKind.CLASS) {
                out += collectOriginatingFiles(nested, byName, visited)
            }
        }
        return out
    }

    private fun emitProperties(
        w: java.io.BufferedWriter,
        decl: KSClassDeclaration,
        prefix: String,
        indent: String,
        byName: Map<String, KSClassDeclaration>,
        depth: Int,
    ) {
        if (depth > 8) return  // safety: cap recursion
        // Restrict to primary-constructor properties (the addressable, settable paths), matching the updater.
        val ctorParamNames: Set<String> = decl.primaryConstructor?.parameters
            ?.filter { it.isVal || it.isVar }
            ?.mapNotNull { it.name?.asString() }
            ?.toSet()
            ?: emptySet()
        val props: Sequence<KSPropertyDeclaration> =
            decl.getAllProperties().filter { it.simpleName.asString() in ctorParamNames }
        for (prop in props) {
            val propName = prop.simpleName.asString()
            val resolved = prop.type.resolve()
            val typeDecl = resolved.declaration as? KSClassDeclaration
            val qualifiedTypeName = typeDecl?.qualifiedName?.asString()
            val nestedDecl = qualifiedTypeName?.let { byName[it] }

            // Path STRING literals always use the raw property name (the runtime key); only the
            // emitted Kotlin identifier is backtick-escaped when it collides with a hard keyword.
            val joinedPath = if (prefix.isEmpty()) propName else "$prefix.$propName"

            if (nestedDecl != null && nestedDecl.classKind == ClassKind.CLASS) {
                // Emit a nested object scope for the @FormValues child type.
                w.appendLine("${indent}object ${esc(propName)} {")
                emitProperties(w, nestedDecl, joinedPath, "$indent    ", byName, depth + 1)
                // Also expose the parent path itself as a constant under the nested scope.
                // Identifier is backtick-quoted because `$` isn't a legal identifier character.
                w.appendLine("$indent    const val `\$path`: String = \"$joinedPath\"")
                w.appendLine("$indent}")
            } else {
                w.appendLine("${indent}const val ${esc(propName)}: String = \"$joinedPath\"")
            }
        }
    }

    private companion object {
        /** Kotlin hard keywords that cannot be used as plain identifiers (must be backtick-escaped). */
        private val HARD_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
            "in", "interface", "is", "null", "object", "package", "return", "super", "this",
            "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
        )

        /** Backtick-escape an identifier that collides with a hard keyword; otherwise return it as-is. */
        fun esc(name: String): String = if (name in HARD_KEYWORDS) "`$name`" else name
    }
}

public class FormValuesProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        FormValuesProcessor(environment)
}
