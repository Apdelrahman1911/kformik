# Test coverage matrix

This document maps every public-API entry point of the four published Kformik modules to the test file(s) that pin its behaviour. It exists so a future contributor or a serious evaluator can see, at a glance, what is covered, on which platforms, and where to look when a behaviour regresses. Update this file whenever you (a) add a new public symbol — append it to the relevant per-module table with the test that owns it; or (b) fix a bug and pin it with a regression test — list the test in the v1.9.2-style regression section. Counts in this doc are point-in-time; refresh them whenever a test pass lands.

## 1. Test inventory

| Module             | Source set    | Files                         | @Tests | Notes                                                                                                                       |
| ------------------ | ------------- | ----------------------------- | ------ | --------------------------------------------------------------------------------------------------------------------------- |
| `:kformik`         | `commonTest`  | 21                            | 295    | Controller, schema, validation (sync + async), debounce, field arrays, lifecycle, reset, submit, edge cases, API contract.  |
| `:kformik`         | `jvmTest`     | 1 (`ConcurrencyStressTest`)   | 9      | Real-thread-pool stress: rapid-fire `setFieldValue`, parallel submit single-flight.                                         |
| `:kformik`         | `iosTest`     | 1 (`FormikIosBridgeTest`)     | 25     | Exhaustive iOS callback bridge contract.                                                                                    |
| `:kformik-compose` | `jvmTest`     | 4                             | 37     | `ComposeFormikTest`, `RememberFormikUiTest`, `RememberFormikRegressionTest`, `ComposeFormikLifecycleTest`.                  |
| `:kformik-forms`   | `jvmTest`     | 12                            | 149    | 7 renderer suites + `KformikFormUiTest` + `EdgeCasesUiTest` + `StaleStateRegressionTest` + `FormSchemaBuilderTest` + `DefaultValuesTest` + `TestHelpersSmokeTest` (helper file `TestHelpers.kt` is shared infrastructure, no `@Test`s). |
| `:kformik-ksp`     | `test`        | 5                             | 30     | kctfork processor compile + runtime tests: `FormValuesProcessorTest`, `FormValuesProcessorCompileTest`, `FormValuesProcessorHardeningTest`, `FormValuesUpdaterGenerationTest`, `ProcessorErrorMessagesTest`. |

To refresh counts, run a fresh build and read JUnit XML:
```sh
grep -h "tests=" build/test-results/jvmTest/*.xml | sed 's/.*tests="\([0-9]*\)".*/\1/'
```
Or count `@Test` annotations across the test source tree directly.

## 2. Per-platform coverage

| Surface | JVM | Android unit | iOS simulator | iOS device |
| --- | --- | --- | --- | --- |
| `:kformik` core (FormikController, FormSchema, FieldArray, async/debounce, ValuesUpdater) | yes — `commonTest` runs as `jvmTest` | not separate (logic is platform-shared; commonTest covers it) | yes — `iosSimulatorArm64Test` for commonTest + `FormikIosBridgeTest` | manual smoke only |
| `:kformik-compose` (`rememberFormik`, `ComposeFormik`, `fieldState`) | yes — headless `runComposeUiTest` | no | compiles for iOS targets; no UI-test harness on iOS-Native — JVM headless tests are the substitute | manual smoke via `sample-forms-cmp-app` |
| `:kformik-forms` (`KformikForm`, default renderers, `Field`/`FieldType`) | yes — headless `runComposeUiTest` (12 files added in this pass) | no | same — JVM headless tests are the substitute | manual smoke via `sample-forms-cmp-app` |
| `:kformik-ksp` (`FormValues` annotation, `FormValuesProcessor`) | yes — kctfork on JVM | n/a | n/a | n/a |

Policy: JVM is the fast/comprehensive suite, iOS simulator covers core logic only, no Android instrumented tests. Bugs that only manifest on iOS would require a follow-up `iosTest` pass for the UI modules; this is intentionally out of scope for the current pass (see Section 6).

## 3. Public-API to test-file mapping — `:kformik`

Source: [`kformik/api/jvm/kformik.api`](../kformik/api/jvm/kformik.api).

| API symbol | Documented behaviour | Tests |
| --- | --- | --- |
| `FormikController(FormikConfig)` constructor | Constructs a controller with initial state derived from config. | `FormikControllerTest.initialState_matchesConfig`, `EdgeCasesTest.emptyForm_initialState_isValid_andClean` |
| `FormikController.close()` | Cancels the controller scope; pending async work is cancelled. | `ConcurrencyLifecycleTest`, `EdgeCasesTest.validateAsync_returning_after_dispose_isSwallowed` |
| `FormikController.config: FormikConfig` | Exposes the immutable config passed at construction. | `ApiContractTest` (positive contract) |
| `FormikController.state: StateFlow<FormikState>` | Single source of truth for values/errors/touched/status/submitting/validating/submitCount. | `FormikControllerTest`, `ValidationTest`, `SubmitTest`, `ResetTest`, `EdgeCasesTest` |
| `FormikController.initialState: StateFlow<FormikInitialState>` | Snapshot of initial values/errors/touched/status; updated by `reinitialize`. | `ResetTest`, `EdgeCasesTest.reinitialize_replacesEdits_butPreservesSubmitCount` |
| `FormikController.dirty: StateFlow<Boolean>` | True when current values differ from initial via `deepEquals`. | `FormikControllerTest.dirty_isTrueAfterValueChange`, `dirty_becomesFalseAfterResetToCurrentValues` |
| `FormikController.isValid: StateFlow<Boolean>` | True when `state.errors.isEmpty`. | `FormikControllerTest.isValid_isFalseWithErrors`, `ValidationTest` |
| `FormikController.validateOnChange/Blur/Mount` getters | Mirror the corresponding config flags. | `ApiContractTest`, `ValidationTest` |
| `FormikController.enableReinitialize` | Mirrors config flag. | `ResetTest`, `EdgeCasesTest` |
| `FormikController.handleSubmit()` / `handleReset()` | Fire-and-forget bridge for UI-thread callers. | `SubmitTest`, `ResetTest` |
| `FormikController.submit()` (suspend) | Validates form; if valid, calls `onSubmit` exactly once (single-flight). | `SubmitTest`, `ConcurrencyLifecycleTest`, `EdgeCasesTest.rapidFire_submitClicks_singleFlight`, `ConcurrencyStressTest.submit_underContention_singleFlight_strictlyOnce` |
| `FormikController.resetForm(state)` (suspend) | Resets to `state` or current initial state. | `ResetTest`, `FormikControllerTest` |
| `FormikController.reinitialize(initialState)` (suspend) | Replaces initial state; when `enableReinitialize`, dirty edits are dropped. | `ResetTest`, `EdgeCasesTest.reinitialize_replacesEdits_butPreservesSubmitCount`, `EdgeCasesTest.reinitialize_withSameValues_isNoop` |
| `FormikController.setFieldValue(name, value, shouldValidate)` | Commits a value at path; runs validation if `validateOnChange`. | `FormikControllerTest.setFieldValue_updatesValueAtPath`, `setFieldValue_supportsNestedPaths`, `ValidationTest`, `ValidateDebounceTest` |
| `FormikController.setFieldValue(name, updater, shouldValidate)` | Functional setter against current value at path. | `FormikControllerTest`, `ApiContractTest` |
| `FormikController.setValues(values, shouldValidate)` | Replaces entire values map. | `FormikControllerTest.setValues_updatesEntireMap` |
| `FormikController.setValues(updater, shouldValidate)` | Functional values updater. | `FormikControllerTest.setValues_withUpdater_appliesAgainstCurrent` |
| `FormikController.setFieldTouched(name, isTouched, shouldValidate)` | Marks a single path touched. | `FormikControllerTest.setFieldTouched_setsTouchedFlag`, `ValidationTest` |
| `FormikController.setTouched(touched, shouldValidate)` | Replaces entire touched map. | `FormikControllerTest.setTouched_overwritesAllTouched` |
| `FormikController.setFieldError(name, error)` | Sets/clears an error at path (null clears). | `FormikControllerTest.setFieldError_setsError`, `setFieldError_nullClearsError`, `ApiContractTest` |
| `FormikController.setErrors(errors)` | Replaces entire errors map. | `FormikControllerTest.setErrors_overwritesAllErrors` |
| `FormikController.setStatus(status)` | Accepts any-typed status payload. | `FormikControllerTest.setStatus_setsStatus`, `ApiContractTest.setStatus_anyTypeAccepted` |
| `FormikController.setSubmitting(value)` | Toggles `isSubmitting` flag. | `FormikControllerTest.setSubmitting_setsFlag`, `ApiContractTest.setSubmitting_true_then_false_unblocksSubmit` |
| `FormikController.setFormikState(updater)` | Escape-hatch full-state mutator. | `FormikControllerTest.setFormikState_isAnEscapeHatch` |
| `FormikController.validateForm(values?)` (suspend) | Returns FormikErrors for given (or current) values. | `ValidationTest`, `ValidateAsyncTest` |
| `FormikController.validateField(name)` (suspend) | Runs field-level validation and (when cross-fields exist) cross rules. | `ValidationTest`, `FormSchemaTest`, `EdgeCasesTest.validator_throwingException_routes_to_onError` |
| `FormikController.valueAt(name)` | Reads value at path from current state. | `FormikControllerTest`, `EdgeCasesTest.value_atMissingPath_returnsNull_doesNotThrow` |
| `FormikController.errorAt(name)` | Reads error at path. | `FormikControllerTest`, `ApiContractTest` |
| `FormikController.touchedAt(name)` | Reads touched flag at path. | `FormikControllerTest`, `ApiContractTest` |
| `FormikController.field(name)` | Returns a `FieldBinding` snapshot for the given path. | `FieldOfTest`, `FieldRegistrationTest` |
| `FormikController.fieldFlow(name)` | Returns a per-field StateFlow of FieldBinding. | `FieldFlowTest` |
| `FormikController.registerField(name, validator)` / `unregisterField(name)` | Optional per-field sync validator hook. | `FieldRegistrationTest` |
| `FormikActions` (interface) | Contract implemented by `FormikController`; documents the action surface used by `ComposeFormik`. | `ApiContractTest`, all controller tests above |
| `FormikConfig(...)` | Data class holding initialValues/errors/touched/status, onSubmit/onReset, validate/validateAsync, schema, flags, scope, valuesUpdater, onError. | `FormikControllerTest`, `EdgeCasesTest`, `ValidateAsyncTest`, `ValidateDebounceTest`, `SubmitTest`, `ResetTest` — every test constructs one. |
| `FormikConfig.copy(...)` and component fns | Standard data-class machinery. | Documentary only — exercised indirectly; no dedicated test. |
| `FormikState(...)` data class + getters | `values`, `errors`, `touched`, `status`, `isSubmitting`, `isValidating`, `submitCount`. | All controller tests; `SubmitTest` covers `submitCount`. |
| `FormikInitialState(...)` data class + getters | Initial snapshot exposed by `initialState` flow. | `ResetTest`, `EdgeCasesTest` |
| `FormikErrors`, `FormikErrors.Empty`, `with`, `overlay`, `contains`, `get`, `size`, `isEmpty/isNotEmpty`, `byPath` | Immutable error map with path-keyed lookup. | `FormSchemaTest`, `ValidationTest`, `FormikControllerTest`, `UtilsTest` |
| `FormikErrors.Companion.build { put(...) }` / top-level `buildErrors { ... }` | Builder DSL for constructing FormikErrors. | All tests that assemble errors — `FormSchemaTest`, `ValidationTest`. |
| `FormikTouched`, `Empty`, `with`, `overlay`, `contains`, `get`, `size`, `isEmpty`, `byPath` | Immutable touched-flag map. | `FormikControllerTest`, `ValidationTest` |
| `FormSchema` (`SchemaValidator` impl) | Container of per-field rules + cross-field rules. | `FormSchemaTest`, `FormSchemaV14Test` |
| `FormSchema.fields()`, `requiredFields()`, `hasField(path)`, `isRequired(path)`, `fieldInfo(path)` | Schema introspection. | `FormSchemaTest`, `FormSchemaV14Test` |
| `FormSchema.validate(values)` (suspend) | Returns FormikErrors for all fields + cross rules. | `FormSchemaTest`, `ValidationTest` |
| `FormSchema.validateAll(values)` / `validateAllField(values, path)` | Field-level helpers respecting fail-fast vs. collect-all. | `FormSchemaTest`, `FormSchemaV14Test` |
| `FormSchema.validateField(values, path)` / `validateFieldIncludingCross(values, path)` | Single-path validation, with optional cross-rule inclusion. | `FormSchemaTest`, `FormSchemaV14Test` |
| `FormSchema.configureValuesUpdater(updater)` | Attaches a `ValuesUpdater` so rules can read non-Map values. | `FormSchemaTest`, `ValuesUpdaterHardeningTest` |
| `FormSchemaBuilder` + top-level `formSchema { ... }` | DSL entry point — `field("name", required) { ... }` and `cross { ... }`. | `FormSchemaTest`, `FormSchemaV14Test`, `kformik-forms/FormSchemaBuilderTest` |
| `FormSchemaBuilder.schemaFailFast` getter / ctor flag | Controls fail-fast vs. collect-all rule evaluation. | `FormSchemaTest`, `FormSchemaV14Test` |
| `FieldRulesBuilder` — `required`, `email`, `min`, `max`, `minLength`, `maxLength`, `pattern`, `custom`, `customValue` | Per-field rule DSL. | `FormSchemaTest`, `ValidationTest`, `kformik-forms/FormSchemaBuilderTest` |
| `FieldRule` (`name`, `check`) | Internal value type exposed as public for inspection. | `FormSchemaTest` (asserts schema-built rules), `FieldRegistrationTest` |
| `FormFieldInfo(path, rules)` + `isRequired` | Schema introspection per field. | `FormSchemaTest`, `FormSchemaV14Test` |
| `FieldBinding(...)` data class | Snapshot used by Compose layer (`name`, `value`, `initialValue`, `error`, `displayError`, `touched`, `initialTouched`, `initialError`, `onValueChange`, `onFocusChange`, `setError`). | `FieldOfTest`, `FieldFlowTest`, `FieldRegistrationTest` |
| `FieldArrayController` — `current()`, `size()`, `push`, `pop`, `insert`, `remove`, `replace`, `move`, `swap`, `unshift` | Array-shaped collection manipulation at a path. | `FieldArrayTest`, `FieldArrayHardeningTest` |
| Top-level `FormikController.array(path)` extension | Returns a `FieldArrayController` for a path. | `FieldArrayTest`, `FieldArrayHardeningTest` |
| `MapValuesUpdater` (object) | Default `ValuesUpdater` for `Map<String, Any?>` values. | `UtilsTest`, `FormikControllerTest`, `ValuesUpdaterHardeningTest` |
| `FlatTopLevelUpdater` | `ValuesUpdater` for flat top-level paths (no nesting). | `ValuesUpdaterHardeningTest` |
| `ValuesUpdater` interface (`getAt`, `setAt`, `leafPaths`) | Pluggable strategy for non-Map value containers (used with KSP-generated updaters). | `ValuesUpdaterHardeningTest`, `kformik-ksp/FormValuesUpdaterGenerationTest` |
| `SchemaValidator` interface (`validate`) | Strategy interface — implemented by `FormSchema`. | `FormSchemaTest`, `ValidationTest` |
| `FormikUtilsKt.deepEquals`, `getIn`, `path(Map, String)`, `setIn` | Path-walking helpers. | `UtilsTest` |
| `@InternalKformikApi` opt-in annotation | Marker for non-stable surface. | Documentary only — no dedicated test. |

Gaps in `:kformik` mapping: `FormikConfig.copy(...)` and component functions are standard data-class machinery and have no dedicated test. `@InternalKformikApi` is a marker annotation with no testable behaviour. Every other public symbol has at least one named test above.

## 4. Public-API to test-file mapping — `:kformik-compose`

Source: [`kformik-compose/api/jvm/kformik-compose.api`](../kformik-compose/api/jvm/kformik-compose.api).

| API symbol | Documented behaviour | Tests |
| --- | --- | --- |
| `rememberFormik(initialValues, validate, schemaValidator, onSubmit, onReset, enableReinitialize, validateOnChange, validateOnBlur, validateOnMount, valuesUpdater, onError, status, validateDebounceMs, validateAsync, initialErrors, initialTouched, initialStatus)` | Composable that constructs (and remembers) a `ComposeFormik` keyed on stable inputs; reconfigures when `enableReinitialize` and inputs change. | `RememberFormikUiTest`, `RememberFormikRegressionTest.fieldState_resetsWhenControllerKeyChanges`, `RememberFormikRegressionTest.valuesUpdater_isReadThroughUpdatedState`, `RememberFormikRegressionTest.enableReinitialize_replacesUserEdits_andResetsTouched_andClearsErrors`, `ComposeFormikLifecycleTest` |
| `ComposeFormik.controller: FormikController` | Underlying controller exposed for advanced use. | `ComposeFormikTest`, `RememberFormikUiTest` |
| `ComposeFormik.state` (composable getter) | Re-composing accessor to the live `FormikState`. | `RememberFormikUiTest`, `ComposeFormikTest` |
| `ComposeFormik.dirty` (composable getter) | Re-composing accessor to dirty. | `RememberFormikUiTest`, `ComposeFormikTest` |
| `ComposeFormik.isValid` (composable getter) | Re-composing accessor to isValid. | `RememberFormikUiTest`, `ComposeFormikTest` |
| `ComposeFormik.fieldState(name)` (composable) | Returns a `State<FieldBinding>` keyed on `(name, controller)`; re-syncs when the controller is rebuilt. | `RememberFormikUiTest` (per-field recomposition), `RememberFormikRegressionTest.fieldState_resetsWhenControllerKeyChanges`, `RememberFormikRegressionTest.recomposition_count_perField_doesNotLeak_across_threeFields`, `kformik-forms/StaleStateRegressionTest.fieldState_resyncsAfterControllerRebuild_throughKformikForm` |
| `ComposeFormik.value(name)` | Snapshot read of a path's value. | `ComposeFormikTest` |
| `ComposeFormik.error(name)` / `displayError(name)` | Snapshot reads of error / displayed error. | `ComposeFormikTest` |
| `ComposeFormik.isTouched(name)` | Snapshot read of touched flag. | `ComposeFormikTest` |
| `ComposeFormik.setFieldValue(name, value, shouldValidate)` | Delegates to controller. | `ComposeFormikTest`, `RememberFormikUiTest` |
| `ComposeFormik.setFieldValue(name, updater, shouldValidate)` | Functional setter. | `ComposeFormikTest` |
| `ComposeFormik.setFieldTouched(name, isTouched, shouldValidate)` | Delegates to controller. | `ComposeFormikTest` |
| `ComposeFormik.setFieldError(name, error)` | Delegates to controller. | `ComposeFormikTest` |
| `ComposeFormik.setErrors(errors)` | Delegates to controller. | `ComposeFormikTest` |
| `ComposeFormik.setStatus(status)` / `setSubmitting(value)` | Delegates to controller. | `ComposeFormikTest` |
| `ComposeFormik.submit()` / `resetForm()` | Fire-and-forget bridges, route exceptions to `onError`. | `ComposeFormikTest`, `ComposeFormikLifecycleTest` |
| `ComposeFormik.launch(block)` | Coroutine-launch helper on the controller scope; routes throws (other than `CancellationException`) to `onError`. | `ComposeFormikTest` (launch exception routing), `ComposeFormikLifecycleTest` |

Gaps in `:kformik-compose` mapping: every public symbol from the .api has at least one named test above.

## 5. Public-API to test-file mapping — `:kformik-forms`

Source: [`kformik-forms/api/jvm/kformik-forms.api`](../kformik-forms/api/jvm/kformik-forms.api).

| API symbol | Documented behaviour | Tests |
| --- | --- | --- |
| `KformikForm(fields, onSubmit, modifier, verticalSpacing, renderOverride, submitButton, enableReinitialize, validateOnChange, validateOnBlur, validateOnMount, validateDebounceMs, extraValidate, validateAsync, onError, initialErrors, initialTouched, initialStatus, footerSlot)` | Composable that wires a `KformikFields` block, a default/overridden submit button, and an optional footer slot. Routes every cross-cutting parameter to a `rememberFormik` call. | `KformikFormUiTest` (parameter-by-parameter coverage), `EdgeCasesUiTest` (rapid input / large form / lifecycle), `StaleStateRegressionTest` (controller-rebuild semantics) |
| `KformikFields(fields, form, modifier, verticalSpacing, renderOverride)` | Composable that renders a declarative `Map<String, Field>` against a `ComposeFormik`. | All renderer suites (`TextRendererUiTest`, `NumberRendererUiTest`, `CheckboxRendererUiTest`, `SwitchRendererUiTest`, `SelectRendererUiTest`, `RadioRendererUiTest`, `DateRendererUiTest`), `KformikFormUiTest`, `StaleStateRegressionTest` |
| `DefaultSubmitButton(onClick, isValid, isSubmitting)` | Default submit button used when caller does not supply a custom `submitButton` slot. | `KformikFormUiTest.submitButton_defaultLabel_says_Submit_andSubmittingDuringSubmit` |
| `Field(type, label, placeholder, helperText, initialValue, required, disabled, rules)` data class + getters / `copy` / component fns | Declarative description of a single form field. | All renderer suites; `FormSchemaBuilderTest`, `DefaultValuesTest` |
| `FieldDefaultValue` (singleton sentinel) | Sentinel value meaning "use the type's default initial value". | `DefaultValuesTest`, `TextRendererUiTest`, `NumberRendererUiTest`, `EdgeCasesUiTest.nonStringValue_inTextRenderer_via_FieldDefaultValue_renders_toString` |
| `FieldType.Text` | Single-line text input; default value `""`. | `TextRendererUiTest`, `DefaultValuesTest` |
| `FieldType.Email` | Text input with email keyboard + `email()` rule injection. | `TextRendererUiTest`, `FormSchemaBuilderTest` |
| `FieldType.Password` | Text input with masking via `PasswordVisualTransformation`. | `TextRendererUiTest` |
| `FieldType.Multiline` | Multi-line text input. | `TextRendererUiTest` |
| `FieldType.Number(asInt)` | Numeric input; commits `Int` when `asInt=true`, `Double` otherwise; preserves `displayBuffer` for non-numeric typing. | `NumberRendererUiTest` (all 12 tests), `DefaultValuesTest`, `StaleStateRegressionTest.numberRenderer_displayBuffer_resetsAfterControllerRebuild`, `StaleStateRegressionTest.numberRenderer_hadFocus_doesNotMarkRebuiltFormTouched` |
| `FieldType.Checkbox` | Boolean input rendered as `Modifier.toggleable` row with checkbox; default `false`. | `CheckboxRendererUiTest`, `DefaultValuesTest` |
| `FieldType.Switch` | Boolean input rendered with `Role.Switch`; default `false`. | `SwitchRendererUiTest`, `DefaultValuesTest` |
| `FieldType.Select(options)` + `SelectOption(value, label)` | Dropdown; default value is the first option's value. | `SelectRendererUiTest` (all 8 tests), `DefaultValuesTest`, `StaleStateRegressionTest.selectRenderer_expanded_closesAfterControllerRebuild` |
| `FieldType.Radio(options)` | Radio group; selects one option's value. | `RadioRendererUiTest`, `DefaultValuesTest` |
| `FieldType.Date` | M3 DatePicker dialog launcher; stores ISO `yyyy-MM-dd` string. | `DateRendererUiTest` (all 10 tests except internal day-picking), `StaleStateRegressionTest.dateRenderer_picker_closesAfterFormSwap` |
| `ComposableSingletons$DefaultRenderersKt` lambda fields (`lambda-1/2/3`) | Compose-compiler-generated singletons for default lambdas; not consumer surface. | Documentary only — exercised transitively through every renderer test. |
| `ComposableSingletons$KformikFormKt` lambda fields | Same — Compose-compiler-generated singletons used inside `KformikForm`. | Documentary only — exercised transitively through `KformikFormUiTest`. |

Gaps in `:kformik-forms` mapping: every public consumer symbol has at least one named test above. The `ComposableSingletons$*` classes are compiler-generated lambda holders and are not intended to be called by consumers; they are exercised transitively by every renderer test.

## 6. Public-API to test-file mapping — `:kformik-ksp`

Source: [`kformik-ksp/api/kformik-ksp.api`](../kformik-ksp/api/kformik-ksp.api).

| API symbol | Documented behaviour | Tests |
| --- | --- | --- |
| `@FormValues` annotation | Marks a `data class` for processor-generated `ValuesUpdater`. | `FormValuesProcessorTest`, `FormValuesProcessorCompileTest`, `FormValuesProcessorHardeningTest`, `FormValuesUpdaterGenerationTest`, `ProcessorErrorMessagesTest` |
| `FormValuesProcessor(env)` | KSP `SymbolProcessor` that generates a per-class `ValuesUpdater` implementation. | `FormValuesProcessorTest`, `FormValuesProcessorCompileTest`, `FormValuesProcessorHardeningTest`, `FormValuesUpdaterGenerationTest` |
| `FormValuesProcessor.process(resolver)` | Single processing pass; emits a generated file per annotated class. | Same as above — invoked by every kctfork compile test. |
| `FormValuesProcessorProvider()` / `create(env)` | KSP entrypoint registered via `META-INF/services`. | Same as above. |
| Generated `ValuesUpdater<T>` for a `@FormValues` data class (`getAt`/`setAt`/`leafPaths`) | Path-keyed read + immutable-`copy` write over the class's properties. | `FormValuesUpdaterGenerationTest`, `FormValuesProcessorHardeningTest`, `ProcessorErrorMessagesTest.generated_ValuesUpdater_handles_nullProperty_setAt_value_null` |
| Processor error messages — non-data class / abstract class / no primary constructor / unsupported property type | Diagnostic messages emitted via the KSP logger. | `ProcessorErrorMessagesTest`, `FormValuesProcessorHardeningTest` |

Gaps in `:kformik-ksp` mapping: every public symbol from the .api has at least one named test above.

## 7. v1.9.2 regression-test pins

These six fixes shipped in v1.9.2 (commits `de2003a..80157b6`). Each is pinned by at least one regression test; the load-bearing column lists which test would fail if the fix were reverted, and which are documentary only because the JVM headless harness cannot drive the underlying signal.

| # | Fix | Module | Pinning test(s) | Status |
| --- | --- | --- | --- | --- |
| 1 | `ComposeFormik.fieldState` keyed on `(name, controller)` so a rebuilt controller re-syncs every field. | `:kformik-compose` (with cross-module test in `:kformik-forms`) | `RememberFormikRegressionTest.fieldState_resetsWhenControllerKeyChanges`; `StaleStateRegressionTest.fieldState_resyncsAfterControllerRebuild_throughKformikForm` | LOAD-BEARING — reverting the key narrowing causes both tests to fail. |
| 2 | `rememberFormik` reads `valuesUpdater` through `rememberUpdatedState` so a swapped closure takes effect on the next mutation. | `:kformik-compose` | `RememberFormikRegressionTest.valuesUpdater_isReadThroughUpdatedState` | LOAD-BEARING — reverting the `rememberUpdatedState` causes this test to fail. |
| 3 | `KformikForm` blur-touches do not leak across a form rebuild (the post-rebuild form starts un-touched). | `:kformik-forms` | `StaleStateRegressionTest.blurTouches_doesNotLeakAcrossFormRebuild` | Documentary only — focus events on the JVM headless dispatcher are not deterministic; the test asserts on the observable post-rebuild touched-map state, but it cannot drive a real native focus loss. The behaviour is enforced by code review of `KformikForm`, not by this test. |
| 4 | `NumberRenderer.displayBuffer` keyed on `(name, controller)` so the display-buffer string resets when the controller is rebuilt. | `:kformik-forms` | `NumberRendererUiTest.displayBuffer_resetsAfter_controllerRebuild`; `StaleStateRegressionTest.numberRenderer_displayBuffer_resetsAfterControllerRebuild` | LOAD-BEARING — reverting the key narrowing causes both tests to fail. |
| 4-sub | `NumberRenderer.hadFocus` (companion flag) keyed on `(name, controller)`. | `:kformik-forms` | `NumberRendererUiTest.hadFocus_doesNotLeakAcross_controllerRebuild`; `StaleStateRegressionTest.numberRenderer_hadFocus_doesNotMarkRebuiltFormTouched` | Documentary only — `hadFocus` is read on a focus-loss callback that the JVM harness does not deterministically trigger; the tests assert on the visible post-rebuild touched-map state but cannot reproduce a real iOS-side focus race. |
| 5 | `SelectRenderer.expanded` keyed on `(name, controller)` so the dropdown closes on controller rebuild. | `:kformik-forms` | `SelectRendererUiTest.expanded_closesAfterControllerRebuild`; `StaleStateRegressionTest.selectRenderer_expanded_closesAfterControllerRebuild` | LOAD-BEARING — reverting the key narrowing causes both tests to fail. |
| 6 | `DateRenderer` uses `LaunchedEffect(form)` (not `LaunchedEffect(Unit)`) to dismiss its picker when the form is swapped. | `:kformik-forms` | `DateRendererUiTest.picker_closesAfterControllerRebuild`; `StaleStateRegressionTest.dateRenderer_picker_closesAfterFormSwap` | LOAD-BEARING — reverting `LaunchedEffect(form)` to `LaunchedEffect(Unit)` causes both tests to fail. |

If you fix a future stale-state bug, add a row here and a named test under the relevant module's regression suite.

## 8. Known coverage gaps

These gaps are intentional or tracked. Adding tests for any of them is welcome; the rationale here is why they were left open in the current pass.

- **iOS UI tests for `:kformik-compose` and `:kformik-forms`.** Compose-UI testing on iOS-Native is still experimental; setting up a harness in `iosTest` is out of scope. Every regression we know about is logic-level and reproduces identically on JVM. The JVM `iosSimulatorArm64Test` for `:kformik` core stays for cross-platform-logic confidence.
- **Android instrumented tests.** Emulator dependency, slow CI, low marginal value over JVM headless. Compose-side logic is platform-shared; Android-only failures would be in the AGP packaging path, which CI's `:assembleRelease` already catches.
- **M3 DatePicker dialog day-picking.** `DateRendererUiTest` covers open/close, programmatic value commit, and ISO-string display. M3's `DatePicker` internals shift between Compose versions and have no stable test selectors; coupling tests to JetBrains internals would break the suite on every Compose bump. Programmatic `setFieldValue("yyyy-MM-dd")` is the supported way to test the date-value round-trip.
- **`validateAsync` throwing without a debounce.** `EdgeCasesUiTest.asyncValidator_throwing_routes_to_onError` documents (in its KDoc) that when `validateDebounceMs == null` and the async validator throws synchronously, the exception is currently swallowed rather than routed to `onError`. Tracked as a follow-up library bug — the test sets a debounce to make the behaviour observable, but the no-debounce path is a known gap.
- **Focus-state regressions on headless JVM.** `blurTouches_doesNotLeakAcrossFormRebuild` (v1.9.2 fix #3) and the `NumberRenderer.hadFocus` companion (v1.9.2 fix #4-sub) are documentary only — the JVM Compose harness does not deterministically dispatch real native focus events, so these tests assert on observable touched-map state rather than driving a true focus loss. Code review of `KformikForm` / `NumberRenderer` is the load-bearing gate for those paths.
- **`FormikConfig.copy(...)` and component functions.** Standard data-class machinery. Exercised indirectly through controller construction in every test; no dedicated assertion.
- **`@InternalKformikApi` opt-in marker.** Marker-only annotation; no testable behaviour.
- **End-to-end "build sample-forms-cmp-app and walk every screen".** Manual smoke test against the showcase app, not part of the library suite.
- **Property-based testing, memory-leak / GC tests, snapshot-image rendering, real network async tests, mutation testing.** Out of scope for the current pass.

## 9. How to extend this matrix

When **adding a public API** (any new declaration in one of the `api/<module>.api` files):
1. Add a row to the relevant module's table in Section 3 / 4 / 5 / 6 with the symbol, its documented behaviour, and the test file that pins it.
2. If you cannot point to a test, the row should still appear with `(no test yet)` in the Tests column — silent gaps are worse than visible ones.
3. `./gradlew apiCheck` is the binary-compat gate; do not rely on this doc to catch API drift.

When **fixing a bug**:
1. Add a regression test in the module's existing regression suite (e.g. `StaleStateRegressionTest` for UI-layer rebuild bugs, `FormikIssueRegressionTest` for core-layer issues).
2. If the fix shape resembles a v1.9.2 stale-state bug, append a row to Section 7 with the original symptom, the test name, and the load-bearing status (LOAD-BEARING if reverting the fix makes the test fail, Documentary if the test cannot deterministically drive the underlying signal).

When **refreshing counts** in Section 1: rerun `./gradlew test` and either count `tests=` attributes in the JUnit XML or grep for `@Test` annotations across the test source tree. Update the per-module rows in one commit alongside any new test files.
