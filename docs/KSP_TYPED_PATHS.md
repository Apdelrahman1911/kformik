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
