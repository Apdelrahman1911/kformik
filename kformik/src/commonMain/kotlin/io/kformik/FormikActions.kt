package io.kformik

/**
 * The "helpers bag" passed into `onSubmit`/`onReset` callbacks. Matches Formik's `FormikHelpers<V>`.
 *
 * Any method that may run validation is `suspend` — Kotlin's coroutines remove the sync/async
 * ambiguity that Formik navigates by sniffing for promises. The non-suspend setters
 * ([setFieldError], [setErrors], [setStatus], [setSubmitting], [setFormikState]) never run
 * validation and commit immediately.
 *
 * **`shouldValidate` convention:** when `null` (the default), value/array mutations fall back to
 * `FormikConfig.validateOnChange` and touched mutations to `FormikConfig.validateOnBlur`; pass
 * `true`/`false` to force or suppress validation for that one call.
 */
public interface FormikActions<V> {
    /**
     * Set the value at [name]. Runs validation per the `shouldValidate` convention above.
     *
     * **`null` semantics for `Map<String, Any?>` values:** writing `null` PRUNES the leaf from the
     * backing map (per [ValuesUpdater] / [MapValuesUpdater] contract — set-then-clear restores the
     * original map shape so `dirty` re-baselines correctly). After `setFieldValue(name, null)`,
     * `state.values.containsKey(name) == false`, NOT `containsKey == true with value null`. An
     * initial-values `null` (passed at construction or via `Field(initialValue = null)`) IS
     * preserved with the key intact; the prune behavior applies only to runtime writes.
     */
    suspend public fun setFieldValue(name: String, value: Any?, shouldValidate: Boolean? = null)

    /**
     * Updater-function form of [setFieldValue]. The updater receives the *current* value at the
     * path (read via the controller's [ValuesUpdater.getAt]) and returns the new value.
     * Mirrors Formik's `setFieldValue(field, (prev) => next)` API.
     */
    suspend public fun setFieldValue(name: String, updater: (Any?) -> Any?, shouldValidate: Boolean? = null)

    /**
     * Replace all form values. This updates only the live state — it does NOT move the
     * initial-values baseline, so the form may report `dirty == true` afterward. To load data as a
     * new pristine baseline, use `resetForm(FormikState(values = …))` /
     * `reinitialize(FormikInitialState(values = …))` instead.
     */
    suspend public fun setValues(values: V, shouldValidate: Boolean? = null)

    /** Updater-function form of [setValues]; receives the current values and returns the new ones. */
    suspend public fun setValues(updater: (V) -> V, shouldValidate: Boolean? = null)

    /** Mark [name] touched/untouched. Runs validation per the `shouldValidate` convention. */
    suspend public fun setFieldTouched(name: String, isTouched: Boolean = true, shouldValidate: Boolean? = null)

    /** Replace the entire touched map. Runs validation per the `shouldValidate` convention. */
    suspend public fun setTouched(touched: FormikTouched, shouldValidate: Boolean? = null)

    /** Imperatively set ([message] non-null) or clear ([message] null) the error at [name]. Does not validate. */
    public fun setFieldError(name: String, message: String?)

    /** Replace the entire errors map. Does not validate. */
    public fun setErrors(errors: FormikErrors)

    /** Set the freeform [status] object (e.g. a server message). Not interpreted by the controller. */
    public fun setStatus(status: Any?)

    /**
     * Set the `isSubmitting` flag directly (e.g. to re-enable a button after a custom flow).
     *
     * **This is a display flag only — it does NOT gate [submit].** The structural single-flight
     * for `submit()` is an internal `Mutex` that is held for the duration of an in-flight submit;
     * the `isSubmitting` boolean is a separately-managed display signal that consumers can flip
     * to drive button-enabled state from a custom flow. Calling `setSubmitting(true)` does not
     * cause a subsequent `submit()` to short-circuit — submit will run, take the structural lock,
     * and overwrite the flag itself. If you want to PREVENT a submit, gate the call site (e.g.
     * `Button(enabled = isValid && !isSubmitting)`); the controller does not enforce this for you.
     */
    public fun setSubmitting(isSubmitting: Boolean)

    /** Atomic, lambda-based state update (escape hatch). Does not validate. */
    public fun setFormikState(updater: (FormikState<V>) -> FormikState<V>)

    /** Validate the whole form (against [values] if given, else current values); returns the merged errors (empty = valid). */
    suspend public fun validateForm(values: V? = null): FormikErrors

    /** Validate a single field; returns its error message, or null if it passes. */
    suspend public fun validateField(name: String): String?

    /** Reset the form to its baseline (or to [nextState] if given), re-baselining `dirty`. */
    suspend public fun resetForm(nextState: FormikState<V>? = null)

    /** Touch every field, validate, and (only if valid) call `onSubmit`. Single-flight while submitting. */
    suspend public fun submit()
}
