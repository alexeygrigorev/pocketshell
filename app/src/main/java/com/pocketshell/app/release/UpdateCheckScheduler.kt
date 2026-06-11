package com.pocketshell.app.release

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.pocketshell.app.notifications.DefaultUpdateNotifier
import com.pocketshell.app.notifications.UpdateNotifier
import com.pocketshell.app.startup.StartupTiming
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-singleton that fires the GitHub-Releases update check on
 * the triggers the maintainer actually hits — process foreground resume
 * and opening a host — rather than only the host-list/home screen, which
 * they almost never open (issue #698).
 *
 * The maintainer reported missing v0.3.33 because the only update-check
 * trigger lived in `HostListViewModel.init {}`; they deep-link straight
 * into a host and skip the home screen, so the check basically never ran.
 * This scheduler centralises the check and adds the missing triggers:
 *
 *  - **Foreground resume** — [observeProcessLifecycle] hooks
 *    [ProcessLifecycleOwner] and fires a (throttled) check on every
 *    `ON_START`, mirroring the [com.pocketshell.app.usage.UsageScheduler]
 *    lifecycle pattern. This satisfies the no-background-work principle
 *    (D21 / issue #161): nothing runs while the process is `STOPPED`; the
 *    check only fires when the app comes back to the foreground.
 *  - **Opening a host** — [onHostOpened] is called from the navigation
 *    chokepoint when the user routes into a host (folder list / session),
 *    also throttled.
 *
 * **Throttle.** Both triggers can fire many times per minute (quick
 * app-switches, tapping between hosts). [UpdateCheckStore] persists the
 * wall-clock millis of the last completed poll; a check is skipped while
 * it is within [throttleWindowMillis] of the previous one. Persistence
 * means a cold relaunch within the window also skips the redundant call,
 * so GitHub is never hammered.
 *
 * **Surfacing.** The result is exposed via [updateAvailable] — a global
 * [StateFlow] any screen can observe to render an "update available"
 * affordance wherever the user currently is. The same [UpdateNotifier]
 * the host-list check used (#502) posts the local notification, and it
 * de-dupes per release tag via
 * [com.pocketshell.app.notifications.UpdateNotificationStore] so a
 * version the user already saw/dismissed is not re-nagged (#619).
 *
 * **No background work** (D21 / issue #161): there is no `WorkManager`,
 * no `AlarmManager`, no repeating timer, no wakelock. Every check is
 * foreground-triggered (lifecycle resume or an in-app navigation), and
 * the lone coroutine each trigger launches completes after one HTTP
 * round-trip.
 */
@Singleton
public class UpdateCheckScheduler @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val releaseChecker: ReleaseChecker,
    private val store: UpdateCheckStore = UpdateCheckStore(applicationContext),
    private val updateNotifier: UpdateNotifier = DefaultUpdateNotifier(applicationContext),
) {

    /**
     * Throttle window: at most one GitHub poll per this interval across
     * all triggers (resume + host-open). Six hours keeps the user current
     * within a normal day of use without polling on every app-switch.
     */
    internal var throttleWindowMillis: Long = DEFAULT_THROTTLE_WINDOW_MILLIS

    /**
     * Clock seam so tests can drive the throttle deterministically.
     * Production reads the wall clock.
     */
    internal var nowMillis: () -> Long = { System.currentTimeMillis() }

    /**
     * Version-name seam so tests can pin the installed version without a
     * real `PackageManager`. Production reads the installed `versionName`.
     */
    internal var currentVersionProvider: () -> String? = ::readInstalledVersionName

    /**
     * Coroutine scope each trigger launches its one-shot check on. Default
     * is an application-singleton [Dispatchers.IO] scope; tests swap in a
     * scope built on the `runTest` dispatcher so the check completes
     * deterministically without a real thread hop.
     */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutex = Mutex()

    private val _updateAvailable = MutableStateFlow<ReleaseInfo?>(null)

    /**
     * Non-null when a newer GitHub Release than the installed APK is
     * available. Global so any screen — host list, folder list, session —
     * can observe it and surface the update wherever the user is.
     */
    public val updateAvailable: StateFlow<ReleaseInfo?> = _updateAvailable.asStateFlow()

    private val _updateCheckFailed = MutableStateFlow<String?>(null)

    /**
     * Non-null with the concrete failure reason when the most recent poll
     * did not complete (non-200 / rate-limit / network error / unparseable
     * body), so a surface can offer a visible "couldn't check — Retry"
     * affordance (issue #515 semantics, now centralised). Cleared on the
     * next successful check or by [dismissUpdateCheckFailure].
     */
    public val updateCheckFailed: StateFlow<String?> = _updateCheckFailed.asStateFlow()

    /** Dismiss the "couldn't check for updates" surface for now. */
    public fun dismissUpdateCheckFailure() {
        _updateCheckFailed.value = null
    }

    /**
     * Monotonically-increasing counter incremented every time a check
     * actually fires (i.e. passes the throttle). Exposed purely as a test
     * seam so a connected test can prove the resume/open-host triggers ran
     * and that the throttle suppressed the redundant ones.
     */
    private val _checkCount = AtomicLong(0L)
    public val checkCount: Long
        get() = _checkCount.get()

    private val processLifecycleObserver = LifecycleEventObserver { _: LifecycleOwner, event ->
        if (event == Lifecycle.Event.ON_START) {
            // Foreground resume — the maintainer's most common entry point
            // (deep-link into a host, then app-switch in and out). Throttled.
            requestCheck(TRIGGER_FOREGROUND)
        }
    }

    private var lifecycleAttached: Boolean = false

    /**
     * Attach a [ProcessLifecycleOwner] (or any [LifecycleOwner]) so a
     * throttled update check fires on every `ON_START` (foreground
     * resume). Called once from
     * [com.pocketshell.app.App.onCreate]; subsequent calls are no-ops so
     * it is safe to invoke from tests. Seeds an immediate check when the
     * owner is already `STARTED` at attach time (cold launch) so the
     * first foreground does not wait for a later resume.
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
            if (alreadyStarted) requestCheck(TRIGGER_FOREGROUND)
        }
    }

    /**
     * Called from the navigation chokepoint when the user opens a host
     * (folder list / session). Fires a throttled update check so the user
     * who never visits the home screen still gets the prompt the first
     * time they reach a host. No-op beyond the throttle window's first
     * call.
     */
    public fun onHostOpened() {
        requestCheck(TRIGGER_HOST_OPEN)
    }

    /**
     * Request a throttled check from a surface that came to the foreground
     * (e.g. the host-list screen mounting). Same throttle as the resume /
     * host-open triggers — a no-op within the throttle window.
     */
    public fun checkNow() {
        requestCheck(TRIGGER_SCREEN)
    }

    /**
     * Run a check immediately, bypassing the throttle (e.g. an explicit
     * pull-to-refresh / "Retry"). Still updates the persisted last-checked
     * time so the next throttled trigger respects it.
     */
    public fun refreshNow() {
        scope.launch { runCheck(TRIGGER_MANUAL, force = true) }
    }

    private fun requestCheck(trigger: String) {
        scope.launch { runCheck(trigger, force = false) }
    }

    private suspend fun runCheck(trigger: String, force: Boolean) {
        mutex.withLock {
            val now = nowMillis()
            val priorCheckedAt = store.lastCheckedAtMillis()
            if (!force) {
                if (priorCheckedAt != 0L && now - priorCheckedAt < throttleWindowMillis) {
                    StartupTiming.markOnce(
                        "update-check-throttled",
                        "trigger" to trigger,
                        "sinceLastMs" to (now - priorCheckedAt),
                    )
                    return
                }
            }
            val currentVersion = currentVersionProvider()
            if (currentVersion == null) {
                // Unknown installed version → never surface a misleading
                // banner. Do not consume the throttle so a later trigger
                // can retry once the version resolves.
                return
            }
            _checkCount.incrementAndGet()
            // Stamp the throttle BEFORE the network call so two near-simultaneous
            // triggers (resume + host-open within the same instant) coalesce — the
            // mutex serialises them and the second sees a fresh timestamp.
            store.markCheckedAt(now)
            StartupTiming.markOnce("update-check-fired", "trigger" to trigger)
            when (val result = releaseChecker.checkForUpdate(currentVersion)) {
                is ReleaseCheckResult.UpdateAvailable -> {
                    _updateAvailable.value = result.info
                    _updateCheckFailed.value = null
                    StartupTiming.mark(
                        "update-check-available",
                        "trigger" to trigger,
                        "tag" to result.info.tagName,
                    )
                    // De-dupes per release tag, so a version the user
                    // already saw / dismissed is not re-notified (#619).
                    updateNotifier.notifyUpdateAvailable(result.info)
                }

                ReleaseCheckResult.UpToDate -> {
                    _updateAvailable.value = null
                    _updateCheckFailed.value = null
                    StartupTiming.mark("update-check-uptodate", "trigger" to trigger)
                }

                is ReleaseCheckResult.Failed -> {
                    // Keep any previously-found update visible; surface the
                    // failure so a screen can offer Retry. The reason is also
                    // logged inside the checker. A failed poll should not burn
                    // the throttle window into the future, so restore the prior
                    // last-checked time so the next trigger retries promptly.
                    _updateCheckFailed.value = result.reason
                    store.markCheckedAt(priorCheckedAt)
                    Log.w(TAG, "update check failed (trigger=$trigger): ${result.reason}")
                }
            }
        }
    }

    private fun readInstalledVersionName(): String? = try {
        applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0)
            .versionName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: Exception) {
        null
    }

    public companion object {
        /** Default throttle: at most one GitHub poll per 6 hours. */
        public const val DEFAULT_THROTTLE_WINDOW_MILLIS: Long = 6L * 60L * 60L * 1000L

        private const val TRIGGER_FOREGROUND = "foreground_resume"
        private const val TRIGGER_HOST_OPEN = "host_open"
        private const val TRIGGER_SCREEN = "screen_shown"
        private const val TRIGGER_MANUAL = "manual"
        private const val TAG = "PsUpdateCheckSched"
    }
}
