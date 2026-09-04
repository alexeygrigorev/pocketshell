package com.pocketshell.next.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [ForegroundSignal] a test drives by hand.
 *
 * The whole reason [ForegroundSignal] is an interface: "no reconnect attempt
 * fires while the app is backgrounded" (D21, task U-7) cannot be asserted
 * against `ProcessLifecycleOwner` on the host JVM, and asserting it on a device
 * would mean driving the launcher mid-journey. Here it is two method calls.
 */
class FakeForegroundSignal(initiallyForeground: Boolean = true) : ForegroundSignal {

    private val _isForeground = MutableStateFlow(initiallyForeground)

    override val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    /** The user left the app (`ProcessLifecycleOwner.onStop`). */
    fun background() {
        _isForeground.value = false
    }

    /** The user came back (`ProcessLifecycleOwner.onStart`). */
    fun foreground() {
        _isForeground.value = true
    }
}
