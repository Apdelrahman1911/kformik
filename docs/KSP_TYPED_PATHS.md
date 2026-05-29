# Typed field paths via KSP (`:kformik-ksp`, experimental)

A KSP symbol processor that generates `<Name>Paths` objects from `@FormValues`-annotated `data class`es. The goal is to replace string-keyed paths (`"user.address.city"`) with compile-checked references (`UserValuesPaths.address.city`).

> **Experimental.** Single-module use only; not yet wired into the public examples. APIs and generated output may change. Production code should keep using string paths until the experimental flag is removed.

## How to use

```kotlin
// app/build.gradle.kts
plugins {
    kotlin("jvm")               // or kotlin("multiplatform") / kotlin("android")
    id("com.google.devtools.ksp") version "2.0.21-1.0.27"
}

dependencies {
    implementation(project(":kformik"))
    ksp(project(":kformik-ksp"))    // run the processor at compile time
}
```

```kotlin
// app/src/main/kotlin/Forms.kt
import io.kformik.ksp.FormValues

@FormValues
data class LoginValues(
    val email: String,
    val password: String,
)

@FormValues
data class AddressValues(val city: String, val country: String)

@FormValues
data class UserValues(val name: String, val address: AddressValues)
```

Generated (in `build/generated/ksp/main/kotlin/<package>/`):

```kotlin
// LoginValuesPaths.kt
object LoginValuesPaths {
    const val email: String = "email"
    const val password: String = "password"
}

// UserValuesPaths.kt
object UserValuesPaths {
    const val name: String = "name"
    object address {
        const val city: String = "address.city"
        const val country: String = "address.country"
        const val `$path`: String = "address"
    }
}
```

Usage in a form:

```kotlin
form.setFieldValue(LoginValuesPaths.email, "user@example.com")
form.setFieldValue(UserValuesPaths.address.city, "Lagos")
form.array(UserValuesPaths.address.`$path`)  // raw array path
```

The generated constants are plain `String` — they're 100% compatible with the existing string-based API. The benefit is **IDE auto-complete + refactor-safety**: rename `email` to `emailAddress` and the paths re-generate automatically; misspelling `LoginValuesPaths.emial` is a compile error.

## When does generation actually run?

KSP runs as part of the Kotlin compile pipeline — it cannot run "while typing" in any JVM annotation processor (kapt, KSP, Dagger, Room, Moshi, etc. all share this constraint). Pick whichever trigger fits your workflow:

| Trigger | What it does | Latency |
|---|---|---|
| IntelliJ **Build project automatically** (Settings → Build, Execution, Deployment → Compiler) | On every file save, runs incremental Kotlin compile. After the v1.5.0 KSP incrementality fix, KSP only re-processes the file you just touched (+ direct dependents) and writes only those outputs. | ~1–3 s per save |
| **Build → Make Project** (`⌘F9` / `Ctrl+F9`) in IntelliJ / Android Studio | Full project incremental build. KSP runs the same incremental logic. | Seconds to tens of seconds depending on project size |
| `./gradlew build` | Same as Make Project, from the CLI. | Same as above |
| `./gradlew generateKFormikTypedPaths` | Just the KSP-class tasks for whatever Kotlin / Android / KMP shape your project has. No test compile, no resource bundling, no APK packaging. **See snippet below.** | Typically 1–3 s in incremental mode |

Gradle **sync** alone does NOT run KSP — sync configures the build graph but doesn't execute compile tasks. After a fresh clone, you have to run one of the triggers above at least once to materialise the generated files.

## A dedicated `generateKFormikTypedPaths` task

Paste this into your `build.gradle.kts` to expose a clearly-named on-demand task:

```kotlin
tasks.register("generateKFormikTypedPaths") {
    group = "kformik"
    description = "Run KSP to generate @FormValues typed paths and ValuesUpdater objects (no full build)."

    // tasks.matching is lazy and project-shape-agnostic. Picks up whatever KSP tasks
    // your project shape registered:
    //   - JVM-only         : kspKotlin
    //   - Android          : kspDebugKotlin (+ kspReleaseKotlin if you also compile release)
    //   - KMP / multiplatform : kspCommonMainKotlinMetadata, kspKotlinJvm,
    //                           kspKotlinIosX64, kspKotlinIosArm64,
    //                           kspKotlinIosSimulatorArm64, etc.
    dependsOn(tasks.matching { it.name.startsWith("ksp") && it.name.contains("Kotlin") })
}
```

**Where it shows up**: in IntelliJ / Android Studio's **Gradle tool window** (View → Tool Windows → Gradle), under a section called **kformik**. Double-click `generateKFormikTypedPaths` to run it. Or from the CLI:

```bash
./gradlew generateKFormikTypedPaths
```

**Why this is useful**: it does the *minimum* work needed to refresh the `@FormValues` outputs. It does NOT run tests, does NOT compile non-Kotlin sources, does NOT bundle resources or build an APK. With the v1.5.0 incremental KSP fix this is typically a 1–3 second operation in a large project — short enough that you can bind it to a keyboard shortcut in IntelliJ (Settings → Keymap → search "generateKFormikTypedPaths") and run it on demand instead of leaving auto-build on full-time.

### Choosing between the two flows

- If you want generation to feel **automatic**: turn on IntelliJ's **Build project automatically** and never think about it again. KSP runs after every save, the fix keeps it under 3 s, generated symbols appear in your IDE shortly after.
- If you prefer the **explicit-trigger** style (CPU off when you're not editing, you press a key when you want regeneration): use `generateKFormikTypedPaths` from a keyboard shortcut. Same incremental KSP under the hood.

Both flows work in parallel — they're not exclusive.

> **Coming in a future release (v1.6.0+):** a small `kformik-gradle-plugin` artifact that auto-registers `generateKFormikTypedPaths` for you. Applying the plugin (`plugins { id("io.github.apdelrahman1911.kformik") version "..." }`) will replace the snippet above with a one-line `plugins { }` entry. The snippet remains the supported v1.5.0 path; both will coexist after the plugin lands.

## What's supported in v1.3-experimental

- ✅ Flat data classes (primitives, `String`, anything that's not `@FormValues`).
- ✅ Nested `@FormValues` types — paths join with `.`. Nested object scope exposes a `$path` constant that names the array path itself.
- ✅ Cross-file references within the same KSP-processed compilation.

## What's NOT supported (deferred)

- ❌ `List<...>`-typed properties. The list itself can be addressed (`object friends { const val $path = "friends" }`), but per-index access still uses string concatenation (`"${LoginValuesPaths.friends.$path}[0]"`). Index-typed accessors are a future enhancement.
- ❌ `Map<...>` properties.
- ❌ Sealed / abstract / generic / inline types.
- ❌ Cross-module annotation discovery — annotated types and their nested children must live in the same KSP-processed compilation.
- ❌ KMP source-set awareness. The processor runs against any classpath KSP sees; for `commonMain` types, apply KSP to that source set.

## Recursion safety

The emitter caps nested object recursion at depth 8 (a safety bound against pathological cyclic references). In practice no real form uses that depth.

## Tests

- `kformik-ksp/src/test/kotlin/io/kformik/ksp/FormValuesProcessorTest.kt` — pure-Kotlin tests for the path-flattening rule and provider-class wiring (`META-INF/services` resolves to a real `SymbolProcessorProvider`).
- Full KSP integration tests (`kotlin-compile-testing`) are out of scope for this phase. The proof is that the processor compiles, ships, and the META-INF wiring resolves.

## Roadmap

- v1.4 — add `kotlin-compile-testing`-driven integration tests that compile a `@FormValues` data class and assert the generated content byte-for-byte.
- v1.4 — emit a `ValuesUpdater` alongside the paths object (so `kformik-ksp` becomes a full typed-form helper, not just constants).
- v1.5 — `List<...>` index-aware accessors (e.g. `LoginValuesPaths.friends[0]` resolving statically when the index is a compile-time constant).
