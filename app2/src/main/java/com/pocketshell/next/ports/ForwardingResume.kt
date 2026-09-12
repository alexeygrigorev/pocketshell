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
import javax.inject.Inject
import javax.inject.Singleton
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
 * [com.pocketshell.next.MainActivity] can both call [observeProcessLifecycle];
 * a later call still resumes when the owner is already `STARTED`.
 */
@Singleton
class ForwardingResume @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val hostDao: HostDao,
    private val sshKeyDao: SshKeyDao,
) {
    /** Testable start-service seam. Production starts [ForwardService]. */
    internal var startService: (Context) -> Unit = { ForwardService.resume(it) }

    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val processLifecycleObserver = LifecycleEventObserver { _: LifecycleOwner, event ->
        if (event == Lifecycle.Event.ON_START) {
            requestResume()
        }
    }

    private var lifecycleAttached: Boolean = false

    /**
     * Attach [ProcessLifecycleOwner] (or any [LifecycleOwner]) so a resume
     * sweep fires on every `ON_START`. The observer is added once.
     *
     * A later call still seeds an immediate resume when the owner is already
     * `STARTED`. Instrumentation keeps the process in `STARTED` across
     * activity launches ([App] is replaced by `HiltTestApplication`, so
     * [com.pocketshell.next.MainActivity] is the attach site) and would
     * otherwise never see another `ON_START`.
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
            val alreadyStarted = withContext(Dispatchers.Main) {
                if (attachObserver) {
                    owner.lifecycle.addObserver(processLifecycleObserver)
                }
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
            if (alreadyStarted) requestResume()
        }
    }

    private fun requestResume() {
        scope.launch { resumeIfNeeded() }
    }

    private suspend fun resumeIfNeeded() {
        val enabled = hostDao.getEnabled().first()
        if (enabled.isEmpty()) return
        val usable = enabled.any { host -> hostHasUsableKey(host.id, host.keyId) }
        if (!usable) return
        startService(applicationContext)
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
