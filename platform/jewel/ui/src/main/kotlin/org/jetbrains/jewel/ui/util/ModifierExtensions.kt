package org.jetbrains.jewel.ui.util

import androidx.compose.ui.Modifier

/**
 * Conditionally applies the [action] to the receiver [Modifier], if [precondition] is true. Returns the receiver as-is
 * otherwise.
 */
@Deprecated(
    message = "This modifier has been moved to org.jetbrains.jewel.foundation.util. Please update your imports.",
    ReplaceWith("thenIf(precondition, action)", "org.jetbrains.jewel.foundation.util"),
)
public inline fun Modifier.thenIf(precondition: Boolean, action: Modifier.() -> Modifier): Modifier =
    if (precondition) action() else this
