package io.kformik.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kformik.compose.ComposeFormik

/**
 * Layout-and-dispatch composable. Renders each `(name, Field)` entry in [fields] under [form],
 * using [DefaultFieldRenderer] for the field's [FieldType] unless [renderOverride] handles it.
 *
 * **Render order**: insertion order of the [fields] map. `kotlin.collections.mapOf` returns a
 * `LinkedHashMap`, so the canonical call site (`KformikFields(fields = mapOf(... to ...))`) is
 * deterministic. A `HashMap` will give nondeterministic order.
 *
 * **Custom rendering**: supply [renderOverride] to take over rendering for specific fields. The
 * callback returns `true` to indicate it handled the field; returning `false` falls back to the
 * default renderer. This lets you override a single field (e.g. a custom date picker) without
 * losing the default rendering for the rest:
 *
 * ```kotlin
 * KformikFields(
 *     fields = fields,
 *     form = form,
 *     renderOverride = { name, field, form ->
 *         if (name == "country") {
 *             MyCustomCountryPicker(form.value(name) as? String) { form.setFieldValue(name, it) }
 *             true                                              // handled — skip default
 *         } else false                                          // fall through to default
 *     },
 * )
 * ```
 *
 * Typical use is via [KformikForm], which wraps `KformikFields` plus the schema/state/submit
 * lifecycle. Call `KformikFields` directly if you want to manage `rememberFormik` yourself.
 */
@Composable
public fun KformikFields(
    fields: Map<String, Field>,
    form: ComposeFormik<Map<String, Any?>>,
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    renderOverride: (@Composable (
        name: String,
        field: Field,
        form: ComposeFormik<Map<String, Any?>>,
    ) -> Boolean)? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        fields.forEach { (name, field) ->
            val handled = renderOverride?.invoke(name, field, form) ?: false
            if (!handled) {
                DefaultFieldRenderer(name = name, field = field, form = form)
            }
        }
    }
}
