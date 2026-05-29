# FieldArray helpers

Dynamic list editing for forms whose values include a `List<Any?>` at some path.

## API

`FormikController<V>.array(path: String): FieldArrayController<V>` returns a controller for the list at the given path.

```kotlin
val form = FormikController(FormikConfig(
    initialValues = mapOf<String, Any?>("friends" to listOf("alpha", "beta", "gamma")),
    onSubmit = { _, _ -> },
))

form.array("friends").push("delta")            // append
form.array("friends").unshift("first")          // prepend; returns new size
form.array("friends").pop()                     // removeLast, returns it
form.array("friends").insert(1, "newSecond")    // insert
form.array("friends").remove(0)                 // remove and return
form.array("friends").replace(2, "three")       // replace by index
form.array("friends").swap(0, 2)                // swap two indices
form.array("friends").move(2, 0)                // move from→to
form.array("friends").size()                    // 0 if path missing or not a list
form.array("friends").current()                 // List<Any?>, empty if missing
```

All mutations are `suspend` so they can run inside coroutines.

## Touched / errors alignment

Following Formik's `<FieldArray>` semantics:

| Helper    | Touched | Errors | Notes                                  |
|-----------|---------|--------|----------------------------------------|
| push      | no      | no     | adding a row shouldn't mark touched    |
| pop       | yes     | yes    | pops both arrays                       |
| unshift   | yes     | yes    | null prepended so indices stay aligned |
| insert    | yes     | yes    | null inserted at index                 |
| remove    | yes     | yes    | spliced both arrays                    |
| replace   | no      | no     | nothing structural changed             |
| swap      | yes     | yes    | swaps both                             |
| move      | yes     | yes    | moves both                             |

When alignment is performed, keys shaped like `path[idx]` or `path[idx].subfield` are re-indexed so they still point at the same logical row after the mutation. Keys at exactly `path` (e.g. a top-level error message about the whole list, like `"friends" → "Need at least 3 friends"`) are untouched.

Example:

```kotlin
form.setFieldError("friends[2]", "bad")
form.array("friends").remove(0)
// errors["friends[2]"] is now null; errors["friends[1]"] is "bad"
```

## Validation behavior

Every mutation respects `FormikConfig.validateOnChange` (default `true`). Pass `shouldValidate = false` to suppress validation for a single mutation, or `shouldValidate = true` to force it on a controller configured with `validateOnChange = false`.

## Nested paths

`array("user.tags")` and `array("groups[0].members")` both work — `MapValuesUpdater` parses dot/bracket paths uniformly.

## Errors

- `array("")` and `array("   ")` → `IllegalArgumentException("Field array path must not be blank")`.
- `remove(99)` / `replace(99, x)` / `swap(0, 99)` / `move(99, 0)` → `IllegalArgumentException` with the actual bounds.
- `insert(99, x)` is allowed for any index in `[0, size]` (inclusive at the end, matching `MutableList.add(index, value)`).

## See also

- Example: `examples/src/main/kotlin/fieldarray/FieldArrayExample.kt` (run with `./gradlew :examples:run -PrunExample=fieldarray`).
- Tests: `kformik/src/commonTest/kotlin/io/kformik/FieldArrayTest.kt` (28 tests).
- Source: `kformik/src/commonMain/kotlin/io/kformik/FormikFieldArray.kt`.
