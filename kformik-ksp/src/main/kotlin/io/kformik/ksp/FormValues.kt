package io.kformik.ksp

/**
 * Mark a `data class` (or any class with named properties) as a Kformik form-values shape. The
 * companion KSP processor in this module reads `@FormValues`-annotated declarations and emits a
 * sibling `<Name>Paths` object containing string constants for each property path.
 *
 * **Experimental.** Supports:
 *
 *  - Flat data classes — every primitive or `String` property gets a constant.
 *  - Nested `@FormValues`-marked types — paths are joined with `.`. The nested object is emitted
 *    as a nested companion-style object so paths like `UserPaths.address.city` work.
 *
 * **Not supported in this proof-of-concept:**
 *
 *  - `List<...>` (bracket-indexed paths). Users still write them as strings (`"friends[0].name"`).
 *  - Maps, sealed/abstract types, generic types.
 *  - Cross-module imports — the annotated type and any nested `@FormValues` it references must
 *    be in the same KSP-processed compilation.
 *
 * Apply via:
 *
 * ```kotlin
 * // app/build.gradle.kts
 * plugins { id("com.google.devtools.ksp") version "2.0.21-1.0.27" }
 * dependencies {
 *     implementation(project(":kformik"))
 *     // @FormValues is @Retention(SOURCE) and lives in :kformik-ksp, so it must be on the main
 *     // compile classpath too — the ksp(...) configuration alone does not provide it.
 *     compileOnly(project(":kformik-ksp"))
 *     ksp(project(":kformik-ksp"))
 * }
 * ```
 *
 * For external (Maven Central) consumers, the equivalent is
 * `compileOnly("io.github.apdelrahman1911:kformik-ksp:<version>")` alongside
 * `ksp("io.github.apdelrahman1911:kformik-ksp:<version>")`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation public class FormValues
