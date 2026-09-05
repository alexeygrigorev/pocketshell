package com.pocketshell.next.release

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
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

/**
 * Foreground-only GitHub-Releases poll (D21 / issue #698).
 *
 * Hooks [ProcessLifecycleOwner] `ON_START` and fires a throttled check so a
 * user who never opens the host list still hears about a newer APK. Settings
 * "Check for updates" calls [refreshNow] and bypasses the throttle.
 *
 * No WorkManager, no AlarmManager, no repeating timer. Each trigger launches
 * one HTTP round-trip and completes.
 */
@Singleton
class UpdateCheckScheduler @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val releaseChecker: ReleaseChecker,
    private val store: UpdateCheckStore,
) {

    internal var throttleWindowMillis: Long = DEFAULT_THROTTLE_WINDOW_MILLIS
    internal var nowMillis: () -> Long = { System.currentTimeMillis() }
    internal var currentVersionProvider: () -> String? = ::readInstalledVersionName
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutex = Mutex()

    private val _updateAvailable = MutableStateFlow<ReleaseInfo?>(null)
    val updateAvailable: StateFlow<ReleaseInfo?> = _updateAvailable.asStateFlow()

    private val _updateCheckFailed = MutableStateFlow<String?>(null)
    val updateCheckFailed: StateFlow<String?> = _updateCheckFailed.asStateFlow()

    private val _lastResult = MutableStateFlow<ReleaseCheckResult?>(null)
    val lastResult: StateFlow<ReleaseCheckResult?> = _lastResult.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    fun dismissUpdateCheckFailure() {
        _updateCheckFailed.value = null
        if (_lastResult.value is ReleaseCheckResult.Failed) {
            _lastResult.value = null
        }
    }

    /** Hide the host-list banner for this tag until a newer tag appears. */
    fun dismissCurrentUpdate() {
        val tag = _updateAvailable.value?.tagName ?: return
        store.markDismissed(tag)
        _updateAvailable.value = null
    }

    fun installedVersionLabel(): String {
        val raw = currentVersionProvider() ?: return "unknown"
        return releaseChecker.renderDottedVersionLabel(raw)
    }

    private val _checkCount = AtomicLong(0L)
    val checkCount: Long
        get() = _checkCount.get()

    private val processLifecycleObserver = LifecycleEventObserver { _: LifecycleOwner, event ->
        if (event == Lifecycle.Event.ON_START) {
            requestCheck(TRIGGER_FOREGROUND)
        }
    }

    private var lifecycleAttached: Boolean = false
    private val _lifecycleObserverAttached = AtomicBoolean(false)
    internal val lifecycleObserverAttached: Boolean
        get() = _lifecycleObserverAttached.get()

    /**
     * Attach [ProcessLifecycleOwner] (or any [LifecycleOwner]) so a throttled
     * check fires on every `ON_START`. Subsequent calls are no-ops. Seeds an
     * immediate check when the owner is already `STARTED` (cold launch).
     */
    fun observeProcessLifecycle(owner: LifecycleOwner = ProcessLifecycleOwner.get()) {
        synchronized(this) {
            if (lifecycleAttached) return
            lifecycleAttached = true
        }
        scope.launch {
            val alreadyStarted = withContext(Dispatchers.Main) {
                owner.lifecycle.addObserver(processLifecycleObserver)
                _lifecycleObserverAttached.set(true)
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
            if (alreadyStarted) requestCheck(TRIGGER_FOREGROUND)
        }
    }

    internal fun stopObservingProcessLifecycleForTest(owner: LifecycleOwner) {
        owner.lifecycle.removeObserver(processLifecycleObserver)
        _lifecycleObserverAttached.set(false)
        synchronized(this) {
            lifecycleAttached = false
        }
    }

    /** Settings "Check for updates": run immediately, ignore the throttle. */
    fun refreshNow() {
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
                    return
                }
            }
            val currentVersion = currentVersionProvider()
            if (currentVersion == null) return
            _checkCount.incrementAndGet()
            store.markCheckedAt(now)
            _checking.value = true
            try {
                when (val result = releaseChecker.checkForUpdate(currentVersion)) {
                    is ReleaseCheckResult.UpdateAvailable -> {
                        _lastResult.value = result
                        _updateCheckFailed.value = null
                        _updateAvailable.value =
                            if (store.dismissedTag() == result.info.tagName) null else result.info
                    }

                    ReleaseCheckResult.UpToDate -> {
                        _lastResult.value = result
                        _updateAvailable.value = null
                        _updateCheckFailed.value = null
                    }

                    is ReleaseCheckResult.Failed -> {
                        // Keep any previously-found update visible. Do not burn
                        // the throttle window, so the next resume retries.
                        _lastResult.value = result
                        _updateCheckFailed.value = result.reason
                        store.markCheckedAt(priorCheckedAt)
                        Log.w(TAG, "update check failed (trigger=$trigger): ${result.reason}")
                    }
                }
            } finally {
                _checking.value = false
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

    companion object {
        const val DEFAULT_THROTTLE_WINDOW_MILLIS: Long = 6L * 60L * 60L * 1000L
        private const val TRIGGER_FOREGROUND = "foreground_resume"
        private const val TRIGGER_MANUAL = "manual"
        private const val TAG = "PsUpdateCheckSched"
    }
}
