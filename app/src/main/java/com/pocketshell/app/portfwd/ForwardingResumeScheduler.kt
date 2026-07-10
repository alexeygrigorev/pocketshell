package com.pocketshell.app.portfwd

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.startup.StartupTiming
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.PortRemappingDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-singleton that **re-establishes port forwarding for every
 * host the user previously enabled** when the app comes to the foreground
 * (issue #752, REOPENED).
 *
 * ## Why this exists
 *
 * All three port-forward indicator surfaces — the ⇄ ongoing notification
 * ([com.pocketshell.app.portfwd.service.ForwardingService]), the host-list
 * pill ([ForwardingIndicatorViewModel]) and the in-session chip
 * ([SessionForwardingIndicatorViewModel]) — gate on
 * [ForwardingController.flowOfActiveHostCount] `> 0`. That count only
 * becomes non-zero when [PortForwardPanelViewModel.setAutoForwardEnabled]
 * runs **inside the current app process**.
 *
 * The persisted enabled state ([HostEntity.enabled], written by the panel
 * on a successful enable) was **never read back to auto-start forwarding**:
 * [HostDao.getEnabled] had zero callers, so a host the user enabled in a
 * previous session forwarded nothing and every indicator stayed hidden.
 * This scheduler is the missing read-back: it makes
 * [ForwardingController.activeHostCount] reflect the persisted intent so
 * the already-built indicators appear.
 *
 * ## Foreground-only (D21 / no-background-work)
 *
 * The resume is hooked to [ProcessLifecycleOwner]'s `ON_START` — a
 * **foreground** trigger, mirroring [com.pocketshell.app.usage.UsageScheduler]
 * and [com.pocketshell.app.release.UpdateCheckScheduler]. There is no
 * `WorkManager`, no `AlarmManager`, no boot receiver, no repeating timer.
 * Nothing runs while the process is `STOPPED`; the resume only fires when
 * the app is foregrounded. Active forwarding itself is the sanctioned D21
 * carve-out ("active **or auto-forward-enabled** host"), so resuming an
 * enabled host on foreground is exactly inside that carve-out.
 *
 * ## Idempotency + reconnect interaction (#439)
 *
 * - A host already active in this process (per
 *   [ForwardingController.isHostActive]) is **never** re-started — the
 *   existing [com.pocketshell.core.portfwd.AutoForwarderSupervisor] owns
 *   its own reconnect/restore loop, so a foreground while a forward is
 *   merely down-and-reconnecting must not fight that in-flight attempt.
 * - An in-flight resume for a host id is tracked in [resuming] so two
 *   `ON_START`s in quick succession (a rapid app-switch) coalesce into one
 *   connect attempt per host.
 * - A connect failure (key missing on disk, SSH refused) does **not**
 *   consume any state — the next foreground simply retries.
 *
 * ## Passphrase-protected keys
 *
 * A launch-time resume cannot prompt for a passphrase (no UI surface), so
 * a host whose key [com.pocketshell.core.storage.entity.SshKeyEntity.hasPassphrase]
 * is true is skipped here (same degrade as [com.pocketshell.app.usage.UsageScheduler]).
 * The user can re-establish it by opening the panel, which can prompt.
 *
 * ## POST_NOTIFICATIONS
 *
 * The in-app pill/chip do not depend on the OS notification permission —
 * they read controller flows directly. Resuming forwarding here lights up
 * the in-app indicators regardless of whether POST_NOTIFICATIONS is
 * granted; a denial only hides the OS ⇄ notification, not the in-app
 * surfaces (MainActivity already re-requests the permission on the 0→1
 * active-host edge this resume produces).
 */
@Singleton
public class ForwardingResumeScheduler @Inject constructor(
    private val hostDao: HostDao,
    private val sshKeyDao: SshKeyDao,
    private val connector: PortForwardConnector,
    private val portRemappingDao: PortRemappingDao,
    private val forwardingController: ForwardingController,
) {

    /**
     * Coroutine scope each resume runs on. Default is an
     * application-singleton [Dispatchers.IO] scope; tests swap in a scope
     * built on the `runTest` dispatcher so the resume completes
     * deterministically without a real thread hop.
     */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Guards a single resume sweep so concurrent `ON_START`s serialise
     * rather than racing the [resuming] set / DB read.
     */
    private val mutex = Mutex()

    /**
     * Host ids whose resume connect is currently in flight. Prevents a
     * second `ON_START` (rapid app-switch) from opening a duplicate SSH
     * session for a host whose first connect has not finished registering
     * with [ForwardingController] yet.
     */
    private val resuming: MutableSet<Long> = mutableSetOf()

    /**
     * Monotonically-increasing counter incremented every time a host's
     * forwarding is actually (re-)started by this scheduler (i.e. an
     * enabled, not-already-active host with a usable key connected and was
     * adopted). Test seam.
     */
    private val _resumedHostCount = AtomicLong(0L)
    public val resumedHostCount: Long
        get() = _resumedHostCount.get()

    private val processLifecycleObserver = LifecycleEventObserver { _: LifecycleOwner, event ->
        if (event == Lifecycle.Event.ON_START) {
            requestResume(TRIGGER_FOREGROUND)
        }
    }

    private var lifecycleAttached: Boolean = false

    /**
     * Attach a [ProcessLifecycleOwner] (or any [LifecycleOwner]) so a
     * resume sweep fires on every `ON_START` (foreground resume). Called
     * once from [com.pocketshell.app.App.onCreate]; subsequent calls are
     * no-ops so it is safe to invoke from tests. Seeds an immediate sweep
     * when the owner is already `STARTED` at attach time (cold launch) so
     * the first foreground does not wait for a later resume.
     */
    public fun observeProcessLifecycle(
        owner: LifecycleOwner = ProcessLifecycleOwner.get(),
    ) {
        synchronized(this) {
            if (lifecycleAttached) return
            lifecycleAttached = true
        }
        scope.launch {
            val alreadyStarted = withContext(Dispatchers.Main) {
                owner.lifecycle.addObserver(processLifecycleObserver)
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
            if (alreadyStarted) requestResume(TRIGGER_FOREGROUND)
        }
    }

    private fun requestResume(trigger: String) {
        scope.launch { resumeEnabledHosts(trigger) }
    }

    /**
     * Read every persisted enabled host and re-establish forwarding for
     * each one that is not already active in this process. Exposed for
     * tests; production drives it through [observeProcessLifecycle].
     */
    internal suspend fun resumeEnabledHosts(trigger: String = TRIGGER_MANUAL) {
        mutex.withLock {
            val enabled = runCatching { hostDao.getEnabled().first() }.getOrElse { emptyList() }
            StartupTiming.markOnce(
                "forwarding-resume-sweep",
                "trigger" to trigger,
                "enabledHostCount" to enabled.size,
            )
            val candidates = enabled.filter { host ->
                !forwardingController.isHostActive(host.id) && !resuming.contains(host.id)
            }
            // Mark every candidate in-flight before connecting so the
            // re-check inside [resumeHost] (and the [resuming] guard on the
            // next sweep) skips an already-claimed host. The whole sweep is
            // serialised under [mutex] — resume is a rare foreground action,
            // so the simpler "one sweep at a time" invariant is preferred
            // over interleaving connects; a host that finishes adopting is
            // skipped by the isHostActive check on the next sweep.
            candidates.forEach { host -> resuming.add(host.id) }
            candidates.forEach { host -> resumeHost(host) }
        }
    }

    private suspend fun resumeHost(host: HostEntity) {
        try {
            val key = sshKeyDao.getById(host.keyId)
            if (key == null) {
                Log.w(TAG, "resume skipped: no key for host ${host.id}")
                return
            }
            if (!File(key.privateKeyPath).exists()) {
                Log.w(TAG, "resume skipped: key file missing for host ${host.id}")
                return
            }
            if (key.hasPassphrase) {
                // Cannot prompt at launch time — degrade silently. The user
                // re-establishes by opening the panel.
                Log.i(TAG, "resume skipped: passphrase-protected key for host ${host.id}")
                return
            }
            // Re-check active under the connect path: a panel toggle (or a
            // sibling resume) may have raced us between the candidate filter
            // and here.
            if (forwardingController.isHostActive(host.id)) return

            val connected = connector.connect(host, key.privateKeyPath, null)
            val session = connected.getOrElse { failure ->
                DiagnosticEvents.record(
                    "action",
                    "port_forward_resume_result",
                    "status" to "failure",
                    "hostId" to host.id,
                    "cause" to failure.javaClass.simpleName,
                )
                Log.w(TAG, "resume connect failed for host ${host.id}", failure)
                return
            }
            // Lost the race after the (slow) connect — don't double-adopt.
            if (forwardingController.isHostActive(host.id)) {
                session.close()
                return
            }
            val remappings = runCatching {
                portRemappingDao.getByHostId(host.id).first()
                    .associate { it.remotePort to it.localPort }
            }.getOrElse { emptyMap() }
            forwardingController.adoptForwardingSession(
                host = host,
                keyPath = key.privateKeyPath,
                passphrase = null,
                firstSession = session,
                initialRemappings = remappings,
            )
            _resumedHostCount.incrementAndGet()
            DiagnosticEvents.record(
                "action",
                "port_forward_resume_result",
                "status" to "success",
                "hostId" to host.id,
            )
            StartupTiming.markOnce("forwarding-resume-host", "hostId" to host.id)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "resume failed for host ${host.id}", t)
        } finally {
            resuming.remove(host.id)
        }
    }

    public companion object {
        private const val TRIGGER_FOREGROUND = "foreground_resume"
        private const val TRIGGER_MANUAL = "manual"
        private const val TAG = "PsForwardingResume"
    }
}
