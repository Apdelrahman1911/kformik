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

    @Test fun number_default_isZeroDouble_whenNotAsInt() =
        assertEquals(0.0, defaultValueFor(FieldType.Number(asInt = false)))

    @Test fun number_default_isZeroInt_whenAsInt() =
        assertEquals(0, defaultValueFor(FieldType.Number(asInt = true)))

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
            "age" to Field(type = FieldType.Number(asInt = true)),     // no override → 0
            "country" to Field(type = FieldType.Select(listOf(SelectOption("us", "USA"))))
        )
        val initial = buildInitialValuesFrom(fields)
        assertEquals("Aisha", initial["name"])
        assertEquals(0, initial["age"])
        assertEquals("us", initial["country"])
    }
}
