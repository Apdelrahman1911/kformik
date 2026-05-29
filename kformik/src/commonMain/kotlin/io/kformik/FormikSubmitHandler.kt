package io.kformik

/** Type alias for the `onSubmit` callback. Always suspending. */
typealias FormikSubmitHandler<V> = suspend (V, FormikActions<V>) -> Unit

/** Type alias for the `onReset` callback. Optional, always suspending. */
typealias FormikResetHandler<V> = suspend (V, FormikActions<V>) -> Unit
