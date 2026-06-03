package io.kformik.forms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Contract for [defaultValueFor]: the type-driven fallback used when [Field.initialValue] is
 * null. Documented in the Field KDoc — every consumer-visible default lives here.
 */
class DefaultValuesTest {

    @Test fun text_default_isEmptyString() =
        assertEquals("", defaultValueFor(FieldType.Text))

    @Test fun email_default_isEmptyString() =
        assertEquals("", defaultValueFor(FieldType.Email))

    @Test fun password_default_isEmptyString() =
        assertEquals("", defaultValueFor(FieldType.Password))

    @Test fun multiline_default_isEmptyString() =
        assertEquals("", defaultValueFor(FieldType.Multiline))

    // Number defaults to null (changed in v1.8.1 from 0/0.0) so that required() actually enforces
    // "must enter a value" — 0 was treated as a value and silently passed the required check.
    // Consumers who want a starting numeric value pass an explicit Field(initialValue = 0) etc.
    @Test fun number_default_isNull_whenNotAsInt() =
        assertNull(defaultValueFor(FieldType.Number(asInt = false)))

    @Test fun number_default_isNull_whenAsInt() =
        assertNull(defaultValueFor(FieldType.Number(asInt = true)))

    @Test fun checkbox_default_isFalse() =
        assertEquals(false, defaultValueFor(FieldType.Checkbox))

    @Test fun switch_default_isFalse() =
        assertEquals(false, defaultValueFor(FieldType.Switch))

    @Test fun select_default_isFirstOptionValue() {
        val options = listOf(SelectOption("us", "USA"), SelectOption("eg", "Egypt"))
        assertEquals("us", defaultValueFor(FieldType.Select(options)))
    }

    @Test fun select_default_isNull_whenOptionsEmpty() =
        assertNull(defaultValueFor(FieldType.Select(emptyList())))

    @Test fun radio_default_isFirstOptionValue() {
        val options = listOf(SelectOption(1, "One"), SelectOption(2, "Two"))
        assertEquals(1, defaultValueFor(FieldType.Radio(options)))
    }

    @Test fun radio_default_isNull_whenOptionsEmpty() =
        assertNull(defaultValueFor(FieldType.Radio(emptyList())))

    @Test fun date_default_isNull() =
        assertNull(defaultValueFor(FieldType.Date))

    // Field.initialValue overrides the per-type default (verified via buildInitialValuesFrom).
    @Test fun buildInitialValues_field_initialValue_winsOverDefault() {
        val fields = mapOf(
            "name" to Field(type = FieldType.Text, initialValue = "Aisha"),
            "age" to Field(type = FieldType.Number(asInt = true)),  // no override → null (was 0 pre-1.8.1)
            "yearsExperience" to Field(type = FieldType.Number(asInt = true), initialValue = 5),  // override → 5
            "country" to Field(type = FieldType.Select(listOf(SelectOption("us", "USA"))))
        )
        val initial = buildInitialValuesFrom(fields)
        assertEquals("Aisha", initial["name"])
        assertNull(initial["age"], "Number with no initialValue defaults to null (v1.8.1+)")
        assertEquals(5, initial["yearsExperience"], "explicit initialValue overrides null default")
        assertEquals("us", initial["country"])
    }

    // v1.9.0: explicit-null initialValue is distinct from the omitted/default case. Select/Radio
    // default to the first option's value when initialValue is omitted; passing `initialValue = null`
    // means "start with no selection" and overrides the type default.
    @Test fun buildInitialValues_explicitNull_isPreservedAndDistinctFromOmitted() {
        val opts = listOf(SelectOption("us", "USA"), SelectOption("eg", "Egypt"))
        val fields = mapOf(
            "omitted" to Field(type = FieldType.Select(opts)),                  // default → first option ("us")
            "explicitNull" to Field(type = FieldType.Select(opts), initialValue = null),  // explicit → null
            "explicitValue" to Field(type = FieldType.Select(opts), initialValue = "eg"), // explicit → "eg"
        )
        val initial = buildInitialValuesFrom(fields)
        assertEquals("us", initial["omitted"], "omitted initialValue falls back to type default (first option)")
        assertNull(initial["explicitNull"], "explicit null overrides type default — Select 'no selection' UX")
        assertEquals("eg", initial["explicitValue"], "explicit value overrides type default")
    }

    /**
     * v1.9.0: nested-path field names build a properly-nested initial values map (via
     * MapValuesUpdater.setAt), not a flat map with literal dotted keys. The controller's
     * value resolution uses MapValuesUpdater to walk paths, so a flat key like `"user.email"`
     * would never resolve — `valueAt("user.email")` would return null even though
     * buildInitialValuesFrom thought it had seeded the field.
     */
    @Test fun buildInitialValues_nestedPaths_produceNestedStructure() {
        val fields = mapOf(
            "user.name" to Field(type = FieldType.Text, initialValue = "Aisha"),
            "user.email" to Field(type = FieldType.Email, initialValue = "a@example.com"),
            "items[0]" to Field(type = FieldType.Text, initialValue = "first"),
            "topLevel" to Field(type = FieldType.Text, initialValue = "yes"),
        )
        val initial = buildInitialValuesFrom(fields)
        // Top-level remains flat.
        assertEquals("yes", initial["topLevel"])
        // Nested: result["user"] is itself a Map with the two sub-fields.
        val user = initial["user"]
        assertEquals(
            mapOf("name" to "Aisha", "email" to "a@example.com"),
            user,
            "nested-path keys must produce nested maps, not literal dotted keys",
        )
        // List index path: result["items"] is a List with element 0 set.
        assertEquals(listOf("first"), initial["items"])
    }
}
