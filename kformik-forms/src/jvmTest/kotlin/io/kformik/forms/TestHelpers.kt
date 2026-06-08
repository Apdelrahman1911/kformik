package io.kformik.forms

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import io.kformik.FormikErrors
import io.kformik.FormikTouched
import io.kformik.compose.ComposeFormik

/**
 * Test-only host for [KformikForm]: drives one of the public renderers / cross-cutting parameters
 * inside [androidx.compose.ui.test.runComposeUiTest] and exposes the live [ComposeFormik] handle to
 * the test via [sink].
 *
 * Capturing the handle is done through [renderOverride] returning `false` — that's the documented
 * "fall through to default renderer" path (see `KformikForm.kt:85-89`). Along the way the override
 * fires the [sink] callback so the test scope receives the form without modifying library code.
 *
 * Every parameter mirrors the v1.9.2 public `KformikForm` signature one-for-one so tests can pin a
 * single parameter at a time. Defaults match the library defaults.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.renderHost(
    fields: Map<String, Field>,
    onSubmit: suspend (Map<String, Any?>) -> Unit = {},
    sink: ((ComposeFormik<Map<String, Any?>>) -> Unit)? = null,
    extraValidate: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
    validateAsync: (suspend (Map<String, Any?>) -> FormikErrors)? = null,
    validateDebounceMs: Long? = null,
    enableReinitialize: Boolean = false,
    validateOnMount: Boolean = false,
    validateOnBlur: Boolean = true,
    validateOnChange: Boolean = true,
    onError: ((Throwable) -> Unit)? = null,
    initialErrors: FormikErrors = FormikErrors.Empty,
    initialTouched: FormikTouched = FormikTouched.Empty,
    initialStatus: Any? = null,
    submitButton: (@Composable (onSubmit: () -> Unit, isValid: Boolean, isSubmitting: Boolean) -> Unit)? = null,
    footerSlot: (@Composable (form: ComposeFormik<Map<String, Any?>>) -> Unit)? = null,
) {
    setContent {
        KformikForm(
            fields = fields,
            onSubmit = onSubmit,
            extraValidate = extraValidate,
            validateAsync = validateAsync,
            validateDebounceMs = validateDebounceMs,
            enableReinitialize = enableReinitialize,
            validateOnMount = validateOnMount,
            validateOnBlur = validateOnBlur,
            validateOnChange = validateOnChange,
            onError = onError,
            initialErrors = initialErrors,
            initialTouched = initialTouched,
            initialStatus = initialStatus,
            submitButton = submitButton ?: { onSub, isValid, isSubmitting ->
                DefaultSubmitButton(onSub, isValid, isSubmitting)
            },
            footerSlot = footerSlot ?: {},
            renderOverride = { _, _, form ->
                sink?.invoke(form)
                false
            },
        )
    }
}

/**
 * Like [renderHost] but parameterises [fields] over an integer `seed` whose mutation forces
 * [KformikForm] to rebuild its controller. Returns the `seed` [MutableState] so tests can `.value++`
 * (or set a specific value) to trigger a rebuild from inside the test scope.
 *
 * Tests use this to verify that the stale-state regressions found in the v1.9.2 audit do not
 * reappear — every `remember { ... }` inside a renderer that is keyed too narrowly will be caught
 * by a `renderSeeded { … }` host: focus the field, bump the seed, assert the post-rebuild state
 * does not carry stale buffer / focus / dropdown / dialog state from the previous controller.
 *
 * The bump button is rendered with [Modifier.testTag] `"bump-seed"` so tests do
 * `onNodeWithTag("bump-seed").performClick()`. The button is a real Compose Button rather than
 * a programmatic mutation so the timing matches a real user action (one frame between click and
 * recomposition).
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.renderSeeded(
    fields: (seed: Int) -> Map<String, Field>,
    sink: ((ComposeFormik<Map<String, Any?>>) -> Unit)? = null,
    onSubmit: suspend (Map<String, Any?>) -> Unit = {},
    enableReinitialize: Boolean = false,
): MutableState<Int> {
    val seedState = mutableStateOf(0)
    setContent {
        val seed by seedState
        Column {
            KformikForm(
                fields = fields(seed),
                onSubmit = onSubmit,
                enableReinitialize = enableReinitialize,
                renderOverride = { _, _, form ->
                    sink?.invoke(form)
                    false
                },
            )
            Button(
                onClick = { seedState.value = seedState.value + 1 },
                modifier = Modifier.testTag("bump-seed"),
            ) { Text("bump") }
        }
    }
    return seedState
}
