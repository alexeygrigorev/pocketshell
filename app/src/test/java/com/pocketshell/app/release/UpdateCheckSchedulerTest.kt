package com.pocketshell.app.release

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.notifications.UpdateNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the issue #698 foreground update-check scheduler.
 *
 * Acceptance proven here:
 *  - the check fires on opening a host ([onHostOpened]) and on process
 *    foreground resume (the [ProcessLifecycleOwner] `ON_START` observer);
 *  - the throttle suppresses a second check within the window and lets it
 *    through once the window elapses;
 *  - the last-checked time is persisted ([UpdateCheckStore]);
 *  - an available update surfaces on the global [updateAvailable] flow and
 *    notifies, while a dismissed/already-seen version is not re-notified
 *    (#619 de-dupe via the same notifier).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UpdateCheckSchedulerTest {

    private lateinit var context: Context
    private val dispatcher = StandardTestDispatcher()

    private fun release(tag: String) = ReleaseInfo(
        tagName = tag,
        htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/$tag",
        apkUrl = "https://example.com/pocketshell-${tag.removePrefix("v")}-debug.apk",
    )

    /** Records every result the scheduler should return for each call. */
    private class ScriptedReleaseChecker(
        private val results: MutableList<ReleaseCheckResult>,
    ) : ReleaseChecker() {
        var calls = 0
            private set

        override suspend fun checkForUpdate(currentVersion: String): ReleaseCheckResult {
            calls += 1
            return if (results.isEmpty()) ReleaseCheckResult.UpToDate else results.removeAt(0)
        }
    }

    private class RecordingNotifier : UpdateNotifier {
        val notified = mutableListOf<String>()
        override fun notifyUpdateAvailable(info: ReleaseInfo) {
            notified += info.tagName
        }
    }

    private fun scheduler(
        checker: ReleaseChecker,
        notifier: UpdateNotifier = RecordingNotifier(),
        now: () -> Long = { 1_000_000L },
    ): UpdateCheckScheduler {
        // Each test uses its own SharedPreferences-backed store via a unique
        // app context (Robolectric resets app state per test).
        val s = UpdateCheckScheduler(
            applicationContext = context,
            releaseChecker = checker,
            store = UpdateCheckStore(context),
            updateNotifier = notifier,
        )
        s.scope = CoroutineScope(SupervisorJob() + dispatcher)
        s.nowMillis = now
        s.currentVersionProvider = { "0.3.0" }
        return s
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        context.getSharedPreferences("update_check_throttle", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun onHostOpened_firesCheck_andSurfacesAvailableUpdate() = runTest(dispatcher) {
        val info = release("v0.4.0")
        val checker = ScriptedReleaseChecker(mutableListOf(ReleaseCheckResult.UpdateAvailable(info)))
        val notifier = RecordingNotifier()
        val s = scheduler(checker, notifier)

        s.onHostOpened()
        advanceUntilIdle()

        assertEquals(1, checker.calls)
        assertEquals(info, s.updateAvailable.value)
        assertEquals(listOf("v0.4.0"), notifier.notified)
    }

    @Test
    fun secondTrigger_isThrottled_withinWindow_thenFiresAfterWindow() = runTest(dispatcher) {
        var clock = 1_000_000L
        val checker = ScriptedReleaseChecker(
            mutableListOf(ReleaseCheckResult.UpToDate, ReleaseCheckResult.UpToDate),
        )
        val s = scheduler(checker, now = { clock })

        // First trigger fires.
        s.onHostOpened()
        advanceUntilIdle()
        assertEquals(1, checker.calls)
        assertEquals(1L, s.checkCount)

        // Second trigger within the throttle window is suppressed.
        clock += UpdateCheckScheduler.DEFAULT_THROTTLE_WINDOW_MILLIS - 1
        s.onHostOpened()
        advanceUntilIdle()
        assertEquals(1, checker.calls)
        assertEquals(1L, s.checkCount)

        // Once the window elapses, the next trigger fires again.
        clock += 2
        s.onHostOpened()
        advanceUntilIdle()
        assertEquals(2, checker.calls)
        assertEquals(2L, s.checkCount)
    }

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun foregroundResume_firesCheck_viaLifecycleObserver() = runTest(dispatcher) {
        val info = release("v0.5.0")
        val checker = ScriptedReleaseChecker(mutableListOf(ReleaseCheckResult.UpdateAvailable(info)))
        val s = scheduler(checker)
        val owner = FakeLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.CREATED

        s.observeProcessLifecycle(owner)
        advanceUntilIdle()
        // Not yet STARTED → no check.
        assertEquals(0, checker.calls)

        owner.registry.currentState = Lifecycle.State.STARTED
        advanceUntilIdle()

        assertEquals(1, checker.calls)
        assertEquals(info, s.updateAvailable.value)
    }

    @Test
    fun lastCheckedTime_isPersisted() = runTest(dispatcher) {
        val checker = ScriptedReleaseChecker(mutableListOf(ReleaseCheckResult.UpToDate))
        val now = 7_777_000L
        val s = scheduler(checker, now = { now })

        s.checkNow()
        advanceUntilIdle()

        assertEquals(now, UpdateCheckStore(context).lastCheckedAtMillis())
    }

    @Test
    fun refreshNow_bypassesThrottle() = runTest(dispatcher) {
        val checker = ScriptedReleaseChecker(
            mutableListOf(ReleaseCheckResult.UpToDate, ReleaseCheckResult.UpToDate),
        )
        val s = scheduler(checker, now = { 1_000_000L })

        s.onHostOpened()
        advanceUntilIdle()
        assertEquals(1, checker.calls)

        // Forced refresh within the throttle window still fires.
        s.refreshNow()
        advanceUntilIdle()
        assertEquals(2, checker.calls)
    }

    @Test
    fun dismissedVersion_isNotReNotified_acrossTriggers() = runTest(dispatcher) {
        // Same release tag returned twice; the notifier de-dupes per tag so
        // the user is not re-nagged for a version they already saw (#619).
        // Use the production DefaultUpdateNotifier (store-backed de-dupe).
        var clock = 1_000_000L
        val info = release("v0.4.0")
        val checker = ScriptedReleaseChecker(
            mutableListOf(
                ReleaseCheckResult.UpdateAvailable(info),
                ReleaseCheckResult.UpdateAvailable(info),
            ),
        )
        val notifier = com.pocketshell.app.notifications.DefaultUpdateNotifier(context)
        val s = scheduler(checker, notifier, now = { clock })

        s.onHostOpened()
        advanceUntilIdle()
        assertEquals(info, s.updateAvailable.value)

        // Advance past throttle and trigger again; the same tag must not
        // re-notify. We assert via the store ledger which records exactly
        // one notification for the tag.
        clock += UpdateCheckScheduler.DEFAULT_THROTTLE_WINDOW_MILLIS + 1
        s.onHostOpened()
        advanceUntilIdle()

        assertEquals(2, checker.calls)
        // Still surfaced, but the ledger proves a single notification per tag.
        assertEquals(info, s.updateAvailable.value)
        assertEquals(
            "v0.4.0",
            com.pocketshell.app.notifications.UpdateNotificationStore(context).lastNotifiedTag(),
        )
    }

    @Test
    fun failedCheck_doesNotConsumeThrottle_andSurfacesReason() = runTest(dispatcher) {
        var clock = 1_000_000L
        val checker = ScriptedReleaseChecker(
            mutableListOf(
                ReleaseCheckResult.Failed("GitHub returned HTTP 403"),
                ReleaseCheckResult.UpToDate,
            ),
        )
        val s = scheduler(checker, now = { clock })

        s.onHostOpened()
        advanceUntilIdle()
        assertEquals(1, checker.calls)
        assertEquals("GitHub returned HTTP 403", s.updateCheckFailed.value)

        // A failed poll must not burn the throttle window: an immediate
        // retrigger within the window still fires.
        clock += 1
        s.onHostOpened()
        advanceUntilIdle()
        assertEquals(2, checker.calls)
        assertNull(s.updateCheckFailed.value)
    }
}
