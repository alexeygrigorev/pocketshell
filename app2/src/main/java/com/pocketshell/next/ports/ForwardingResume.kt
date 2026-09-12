package com.pocketshell.next.ports

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Restores auto-forward when the app comes to the foreground (issue #2654).
 *
 * [ForwardingController.resumeEnabled] already remounts every host with
 * `hosts.enabled = 1`. This class is the missing process-lifecycle hook:
 * [ProcessLifecycleOwner] `ON_START` starts [ForwardService] when at least
 * one enabled host has a usable key. Passphrase-protected and missing keys
 * are skipped — launch cannot prompt.
 *
 * Foreground-only (D21): no WorkManager, no AlarmManager, no boot receiver.
 * The observer is attached once so [com.pocketshell.next.App] and
 * [com.pocketshell.next.MainActivity] can both call [observeProcessLifecycle].
 * `ON_START` covers app-from-background. [resumeNow] is the activity-foreground
 * sweep: the process can stay `STARTED` across activity launches (no new
 * `ON_START`) and `startForegroundService` is only legal once the activity is
 * in the foreground (API 31+).
 */
@Singleton
class ForwardingResume @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val hostDao: HostDao,
    private val sshKeyDao: SshKeyDao,
) {
    /** Testable start-service seam. Production starts [ForwardService]. */
    internal var startService: (Context) -> Unit = { ForwardService.resume(it) }

    // Last-ditch handler (#2659): this scope fires on every app foreground, so a
    // throw here must degrade to a log line, never an uncaught crash.
    internal var scope: CoroutineScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "forwarding resume task failed", t) },
    )

    private val processLifecycleObserver = LifecycleEventObserver { _: LifecycleOwner, event ->
        if (event == Lifecycle.Event.ON_START) {
            requestResume()
        }
    }

    private var lifecycleAttached: Boolean = false

    /** Completed [resumeIfNeeded] sweeps. Journey seed waits on this. */
    internal val resumeSweepCount = AtomicInteger(0)

    private val _lifecycleObserverAttached = AtomicBoolean(false)
    internal val lifecycleObserverAttached: Boolean
        get() = _lifecycleObserverAttached.get()

    /**
     * Attach [ProcessLifecycleOwner] (or any [LifecycleOwner]) so a resume
     * sweep fires on every `ON_START`. The observer is added once.
     *
     * A later call is attach-only. Instrumentation keeps the process
     * `STARTED` across activity launches, so there is no new `ON_START`;
     * [resumeNow] from [com.pocketshell.next.MainActivity.onStart] is the
     * foreground sweep (`startForegroundService` is legal then).
     */
    fun observeProcessLifecycle(owner: LifecycleOwner = ProcessLifecycleOwner.get()) {
        val attachObserver = synchronized(this) {
            if (lifecycleAttached) {
                false
            } else {
                lifecycleAttached = true
                true
            }
        }
        scope.launch {
            withContext(Dispatchers.Main) {
                if (attachObserver) {
                    owner.lifecycle.addObserver(processLifecycleObserver)
                }
                _lifecycleObserverAttached.set(true)
            }
        }
    }

    /**
     * Run the enabled-host sweep now. [com.pocketshell.next.MainActivity.onStart]
     * calls this so `startForegroundService` happens while the activity is
     * foreground. Idempotent with an already-mounted supervisor (`reconnectNow`).
     */
    fun resumeNow() {
        requestResume()
    }

    private fun requestResume() {
        scope.launch { resumeIfNeeded() }
    }

    private suspend fun resumeIfNeeded() {
        try {
            val enabled = hostDao.getEnabled().first()
            if (enabled.isEmpty()) return
            val usable = enabled.any { host -> hostHasUsableKey(host.id, host.keyId) }
            if (!usable) return
            withContext(Dispatchers.Main) {
                startService(applicationContext)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Exception) {
            // Never rethrow: a transient DB failure during the foreground sweep
            // must leave auto-forward as-is, not crash the app (#2659).
            Log.e(TAG, "resume sweep failed; auto-forward left as-is", t)
        } finally {
            resumeSweepCount.incrementAndGet()
        }
    }

    private suspend fun hostHasUsableKey(hostId: Long, keyId: Long): Boolean {
        val key = sshKeyDao.getById(keyId)
        if (key == null) {
            Log.w(TAG, "resume skipped: no key for host $hostId")
            return false
        }
        if (key.hasPassphrase) {
            Log.i(TAG, "resume skipped: passphrase-protected key for host $hostId")
            return false
        }
        return true
    }

    companion object {
        private const val TAG = "PsForwardingResume"
    }
}
