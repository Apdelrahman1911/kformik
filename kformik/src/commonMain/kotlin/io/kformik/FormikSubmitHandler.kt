package io.kformik

/** Type alias for the `onSubmit` callback. Always suspending. */
public typealias FormikSubmitHandler<V> = suspend (V, FormikActions<V>) -> Unit

/** Type alias for the `onReset` callback. Optional, always suspending. */
public typealias FormikResetHandler<V> = suspend (V, FormikActions<V>) -> Unit
