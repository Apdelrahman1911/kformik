@file:OptIn(ExperimentalMaterial3Api::class)

package io.kformik.forms

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.kformik.compose.ComposeFormik
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * Default Material 3 renderers for every [FieldType]. Centralised in one `when` block so adding a
 * new field type means editing exactly one place. Each renderer:
 *
 *  1. Subscribes to the field's [io.kformik.FieldBinding] via [ComposeFormik.fieldState], so the
 *     widget recomposes when *this field's* value/error/touched changes — not on every keystroke
 *     elsewhere. This also makes [KformikFields] work as a standalone entry point (without a
 *     [KformikForm] wrapper that subscribes to whole-form state).
 *  2. Writes back via `form.setFieldValue(name, …)` on user interaction.
 *  3. Marks the field as touched: on blur for text/number inputs (so the user sees "Required" only
 *     after they leave the field, mirroring Formik's `<Field>` default); on change for boolean /
 *     select / radio / date widgets (where there's no real "blur" event but the act of toggling /
 *     picking is the interaction signal).
 *  4. Surfaces error text via the binding's `displayError` (`error if touched else null`) — placed
 *     in `OutlinedTextField`'s `supportingText` for text-likes, or beneath the widget otherwise.
 *
 * File-level `@OptIn(ExperimentalMaterial3Api::class)` covers [ExposedDropdownMenuBox],
 * [DatePicker] / [DatePickerDialog], and [rememberDatePickerState].
 *
 * Deliberately avoids the `material-icons-extended` artifact (extra ~MB dependency) — password
 * masking is non-toggleable in the default renderer; the date trigger is a text button. Both are
 * trivial to add via `renderOverride` if a consumer wants icons.
 */
@Composable
internal fun DefaultFieldRenderer(
    name: String,
    field: Field,
    form: ComposeFormik<Map<String, Any?>>,
) {
    when (val t = field.type) {
        FieldType.Text      -> TextRenderer(name, field, form, KeyboardType.Text)
        FieldType.Email     -> TextRenderer(name, field, form, KeyboardType.Email)
        FieldType.Password  -> TextRenderer(name, field, form, KeyboardType.Password, visual = PasswordVisualTransformation())
        FieldType.Multiline -> TextRenderer(name, field, form, KeyboardType.Text, singleLine = false, maxLines = 6)
        is FieldType.Number -> NumberRenderer(name, field, form, asInt = t.asInt)
        FieldType.Checkbox  -> CheckboxRenderer(name, field, form)
        FieldType.Switch    -> SwitchRenderer(name, field, form)
        is FieldType.Select -> SelectRenderer(name, field, form, options = t.options)
        is FieldType.Radio  -> RadioRenderer(name, field, form, options = t.options)
        FieldType.Date      -> DateRenderer(name, field, form)
    }
}

private fun displayLabel(field: Field): String? =
    field.label?.let { if (field.required) "$it *" else it }

@Composable
private fun supportingText(field: Field, error: String?): (@Composable () -> Unit)? {
    if (error != null) return { Text(error) }
    val helper = field.helperText ?: return null
    return { Text(helper) }
}

/**
 * Modifier that marks the field as touched on focus *loss* — but only after the field has been
 * focused at least once. Without the `hadFocus` gate, the initial focus-state-snapshot (`isFocused
 * = false`) would mark the field as touched at first composition, surfacing errors before the user
 * has interacted with anything.
 */
@Composable
private fun blurTouches(
    form: ComposeFormik<Map<String, Any?>>,
    name: String,
): Modifier {
    var hadFocus by remember(name) { mutableStateOf(false) }
    return Modifier.onFocusChanged { fs ->
        if (fs.isFocused) {
            hadFocus = true
        } else if (hadFocus) {
            form.setFieldTouched(name, true)
        }
    }
}

// ────────────────────────────────────────────────────────────────────── text-family

@Composable
private fun TextRenderer(
    name: String,
    field: Field,
    form: ComposeFormik<Map<String, Any?>>,
    keyboardType: KeyboardType,
    visual: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    maxLines: Int = 1,
) {
    val binding by form.fieldState(name)
    val current = (binding.value as? String).orEmpty()
    val error = binding.displayError
    OutlinedTextField(
        value = current,
        onValueChange = { form.setFieldValue(name, it) },
        modifier = Modifier
            .fillMaxWidth()
            .then(blurTouches(form, name)),
        enabled = !field.disabled,
        label = displayLabel(field)?.let { { Text(it) } },
        placeholder = field.placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = supportingText(field, error),
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visual,
    )
}

@Composable
private fun NumberRenderer(
    name: String,
    field: Field,
    form: ComposeFormik<Map<String, Any?>>,
    asInt: Boolean,
) {
    val binding by form.fieldState(name)
    // The committed value's "natural" string form — what the user would see if they weren't
    // currently editing. Whatever's stored renders via toString(): Int → "5", Double → "5.0" /
    // "3.7", String → itself (the partial-input fallback below). Importantly we do NOT coerce
    // asInt=true Doubles via `.toLong().toString()`: that silently truncated 3.7 to "3", which
    // was a v1.8.0 bug. Schema rules min/max catch the wrong-type case at validation time.
    val canonical = when (val v = binding.value) {
        null -> ""
        else -> v.toString()
    }
    // Local display buffer. While the user is mid-edit, we render the EXACT typed string instead
    // of `canonical`, otherwise typing "0.10" would round-trip through Double 0.1 and snap back to
    // "0.1" on the very next recomposition (and the caret jumps). Buffer is cleared on focus loss
    // so the field resyncs to the canonical form once the user stops typing. Programmatic
    // setValue calls land in the canonical form immediately; the buffer only matters while the
    // user has focus.
    var displayBuffer by remember(name) { mutableStateOf<String?>(null) }
    var hadFocus by remember(name) { mutableStateOf(false) }
    val display = displayBuffer ?: canonical
    val error = binding.displayError
    OutlinedTextField(
        value = display,
        onValueChange = { input ->
            displayBuffer = input
            val parsed: Any? = when {
                input.isEmpty() -> null
                asInt -> input.toIntOrNull()
                // Locale normalization: accept ',' as decimal separator (German "1,5", French
                // "0,3"). The en-US thousands-separator pattern "1,234.56" is out of scope —
                // consumers in such locales sanitize via `renderOverride`.
                else -> input.replace(',', '.').toDoubleOrNull()
            }
            // Commit the parsed number when valid, else the raw string. Schema numeric rules
            // (min/max) ignore non-Number values, so a transient String during typing doesn't
            // throw — it just bypasses range checks until input parses again.
            form.setFieldValue(name, parsed ?: input)
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { fs ->
                if (fs.isFocused) {
                    hadFocus = true
                } else if (hadFocus) {
                    // Clear the buffer so the next recomposition shows the canonical form, then
                    // mark touched so blur-triggered validation surfaces any error.
                    displayBuffer = null
                    form.setFieldTouched(name, true)
                }
            },
        enabled = !field.disabled,
        label = displayLabel(field)?.let { { Text(it) } },
        placeholder = field.placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = supportingText(field, error),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (asInt) KeyboardType.Number else KeyboardType.Decimal,
        ),
    )
}

// ────────────────────────────────────────────────────────────────────── booleans

@Composable
private fun CheckboxRenderer(
    name: String,
    field: Field,
    form: ComposeFormik<Map<String, Any?>>,
) {
    val binding by form.fieldState(name)
    val checked = binding.value as? Boolean ?: false
    val error = binding.displayError
    // `Modifier.toggleable` on the Row makes the entire row (widget + label) a single click
    // target. The inner Checkbox passes `onCheckedChange = null` so the gesture isn't
    // double-dispatched. `role = Role.Checkbox` tells assistive tech the semantic shape.
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.toggleable(
                value = checked,
                enabled = !field.disabled,
                role = Role.Checkbox,
                onValueChange = { newChecked ->
                    form.setFieldValue(name, newChecked)
                    form.setFieldTouched(name, true)
                },
            ),
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                enabled = !field.disabled,
            )
            displayLabel(field)?.let { Text(it) }
        }
        when {
            error != null -> Text(error)
            field.helperText != null -> Text(field.helperText)
        }
    }
}

@Composable
private fun SwitchRenderer(
    name: String,
    field: Field,
    form: ComposeFormik<Map<String, Any?>>,
) {
    val binding by form.fieldState(name)
    val checked = binding.value as? Boolean ?: false
    val error = binding.displayError
    // Same pattern as CheckboxRenderer: the full Row (label + Switch) is one click target via
    // `Modifier.toggleable`. The pre-fix code only had Switch's own onCheckedChange, so the
    // `SpaceBetween` gap between label and switch was a dead zone — only the thumb itself was
    // interactive, surprising for both pointer and screen-reader users.
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = !field.disabled,
                    role = Role.Switch,
                    onValueChange = { newChecked ->
                        form.setFieldValue(name, newChecked)
                        form.setFieldTouched(name, true)
                    },
                ),
        ) {
            displayLabel(field)?.let { Text(it) }
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = !field.disabled,
            )
        }
        when {
            error != null -> Text(error)
            field.helperText != null -> Text(field.helperText)
        }
    }
}

// ────────────────────────────────────────────────────────────────────── select / radio

@Composable
private fun SelectRenderer(
    name: String,
    field: Field,
    form: ComposeFormik<Map<String, Any?>>,
    options: List<SelectOption>,
) {
    var expanded by remember { mutableStateOf(false) }
    val binding by form.fieldState(name)
    val currentValue = binding.value
    val currentLabel = options.firstOrNull { it.value == currentValue }?.label ?: ""
    val error = binding.displayError

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (!field.disabled) expanded = it },
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = { /* read-only — commits happen via DropdownMenuItem.onClick */ },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = !field.disabled),
            readOnly = true,
            enabled = !field.disabled,
            label = displayLabel(field)?.let { { Text(it) } },
            placeholder = field.placeholder?.let { { Text(it) } },
            isError = error != null,
            supportingText = supportingText(field, error),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        // ExposedDropdownMenu is an extension on ExposedDropdownMenuBoxScope; resolves implicitly
        // because this lambda's receiver is that scope.
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        form.setFieldValue(name, option.value)
                        form.setFieldTouched(name, true)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RadioRenderer(
    name: String,
    field: Field,
    form: ComposeFormik<Map<String, Any?>>,
    options: List<SelectOption>,
) {
    val binding by form.fieldState(name)
    val currentValue = binding.value
    val error = binding.displayError
    Column {
        displayLabel(field)?.let { Text(it) }
        options.forEach { option ->
            val selected = option.value == currentValue
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected,
                        enabled = !field.disabled,
                        onClick = {
                            form.setFieldValue(name, option.value)
                            form.setFieldTouched(name, true)
                        },
                    ),
            ) {
                // Pass `onClick = null` so the parent `selectable` handles the gesture as a single
                // event — otherwise tapping the button fires both handlers and `setFieldValue` is
                // double-dispatched.
                RadioButton(
                    selected = selected,
                    onClick = null,
                    enabled = !field.disabled,
                )
                Text(option.label)
            }
        }
        when {
            error != null -> Text(error)
            field.helperText != null -> Text(field.helperText)
        }
    }
}

// ────────────────────────────────────────────────────────────────────── date

/**
 * Date renderer. Stores value as ISO `"yyyy-MM-dd"` `String?`. Tap the row → Material 3 date
 * picker dialog. The "open picker" trigger is a text button to avoid pulling
 * `material-icons-extended` (~MB of icons we wouldn't otherwise need); consumers who want a
 * calendar icon can use `renderOverride` for the `FieldType.Date` field.
 */
@Composable
private fun DateRenderer(
    name: String,
    field: Field,
    form: ComposeFormik<Map<String, Any?>>,
) {
    val binding by form.fieldState(name)
    val current = binding.value as? String
    val error = binding.displayError
    var showPicker by remember { mutableStateOf(false) }

    // Detect taps anywhere on the field via the OutlinedTextField's interaction source.
    // `Modifier.clickable` on a `readOnly` OutlinedTextField is swallowed by the field's internal
    // pointer-input handling — only the trailing "Pick" button was clickable in v1.8.0. Routing
    // through `interactionSource.interactions` lets us observe taps on the field itself without
    // fighting the underlying gesture system. PressInteraction.Release fires when the user lifts
    // their finger on the field — the natural "tap" moment.
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource, field.disabled) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release && !field.disabled) {
                showPicker = true
            }
        }
    }

    OutlinedTextField(
        value = current.orEmpty(),
        onValueChange = { /* read-only */ },
        modifier = Modifier.fillMaxWidth(),
        readOnly = true,
        enabled = !field.disabled,
        label = displayLabel(field)?.let { { Text(it) } },
        placeholder = field.placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = supportingText(field, error),
        trailingIcon = {
            TextButton(
                onClick = { if (!field.disabled) showPicker = true },
                enabled = !field.disabled,
            ) { Text("Pick") }
        },
        interactionSource = interactionSource,
    )

    if (showPicker) {
        val initialMillis = current?.let(::parseIsoDateToUtcMillis)
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = state.selectedDateMillis
                    if (picked != null) {
                        form.setFieldValue(name, utcMillisToIsoDate(picked))
                    } else {
                        form.setFieldValue(name, null)
                    }
                    form.setFieldTouched(name, true)
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

private fun utcMillisToIsoDate(millis: Long): String {
    val date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
    val mm = date.monthNumber.toString().padStart(2, '0')
    val dd = date.dayOfMonth.toString().padStart(2, '0')
    return "${date.year}-$mm-$dd"
}

private fun parseIsoDateToUtcMillis(iso: String): Long? {
    val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return null
    return date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
}
