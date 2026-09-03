package com.pocketshell.next.terminal

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * "Is the app on screen right now?" — the one lifecycle fact the reconnect loop
 * needs (rewrite task U-7).
 *
 * D21 says the app runs NO background work beyond the single bounded grace
 * close, so a reconnect ladder that kept dialling from behind the launcher
 * would break that contract on the first subway ride. [awaitForeground] is the
 * gate the loop parks on; a backgrounded app therefore neither counts down nor
 * dials, and coming back resumes it.
 *
 * An interface (rather than reaching for [ProcessLifecycleOwner] inside the
 * ViewModel) because a unit test has to be able to say "the app went away" —
 * and because the ONLY way to prove "no attempt fires while backgrounded" is to
 * drive that state deterministically. Task U-8 owns the rest of the background
 * story (the grace close and its foreground service); this is deliberately just
 * the boolean.
 */
interface ForegroundSignal {

    /** True while the process is at least STARTED, i.e. a screen is visible. */
    val isForeground: StateFlow<Boolean>

    /** Suspends until the app is in the foreground; returns at once when it already is. */
    suspend fun awaitForeground() {
        isForeground.first { it }
    }
}

/**
 * [ForegroundSignal] over `androidx.lifecycle`'s process-wide lifecycle.
 *
 * `ProcessLifecycleOwner` is initialised by the `lifecycle-process` artifact's
 * own `androidx.startup` provider, so nothing has to bootstrap it here — but its
 * `LifecycleRegistry` is main-thread-only, hence the post: a `@Singleton` is
 * created on whichever thread first injects it, and a Dagger graph gives no
 * guarantee that is the main thread.
 *
 * The initial value is read from the registry at registration time rather than
 * assumed, so a signal created while the app is already resumed does not make
 * the reconnect loop wait for the next `onStart` that will never come.
 */
@Singleton
class ProcessForegroundSignal @Inject constructor() : ForegroundSignal, DefaultLifecycleObserver {

    private val _isForeground = MutableStateFlow(false)

    override val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    init {
        val register = Runnable {
            val owner = ProcessLifecycleOwner.get()
            _isForeground.value = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            owner.lifecycle.addObserver(this)
        }
        if (Looper.myLooper() === Looper.getMainLooper()) {
            register.run()
        } else {
            Handler(Looper.getMainLooper()).post(register)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        _isForeground.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        _isForeground.value = false
    }
}
