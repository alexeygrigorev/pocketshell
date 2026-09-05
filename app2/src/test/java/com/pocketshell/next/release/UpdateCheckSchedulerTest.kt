package com.pocketshell.next.release

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UpdateCheckSchedulerTest {

    private lateinit var context: Context
    private val dispatcher = StandardTestDispatcher(TestCoroutineScheduler())

    private fun release(tag: String, date: String = "5 Sep 2026") = ReleaseInfo(
        tagName = tag,
        htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/$tag",
        apkUrl = "https://example.com/${tag}.apk",
        publishedDateLabel = date,
    )

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

    private fun scheduler(
        checker: ReleaseChecker,
        now: () -> Long = { 1_000_000L },
    ): UpdateCheckScheduler {
        val s = UpdateCheckScheduler(
            applicationContext = context,
            releaseChecker = checker,
            store = UpdateCheckStore(context),
        )
        s.scope = CoroutineScope(SupervisorJob() + dispatcher)
        s.nowMillis = now
        s.currentVersionProvider = { "0.5.0" }
        return s
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("update_check", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun foregroundResume_firesCheck_viaLifecycleObserver() = runTest(dispatcher) {
        val info = release("v0.5.1")
        val checker = ScriptedReleaseChecker(mutableListOf(ReleaseCheckResult.UpdateAvailable(info)))
        val s = scheduler(checker)
        val owner = FakeLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.CREATED

        s.observeProcessLifecycle(owner)
        advanceUntilIdle()
        assertEquals(0, checker.calls)

        owner.registry.currentState = Lifecycle.State.STARTED
        advanceUntilIdle()

        assertEquals(1, checker.calls)
        assertEquals(info, s.updateAvailable.value)
    }

    @Test
    fun onStartInsideThrottle_doesNotFetch() = runTest(dispatcher) {
        val checker = ScriptedReleaseChecker(
            mutableListOf(ReleaseCheckResult.UpToDate, ReleaseCheckResult.UpToDate),
        )
        var clock = 1_000_000L
        val s = scheduler(checker, now = { clock })
        val owner = FakeLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.STARTED

        s.observeProcessLifecycle(owner)
        advanceUntilIdle()
        assertEquals(1, checker.calls)

        owner.registry.currentState = Lifecycle.State.CREATED
        advanceUntilIdle()
        owner.registry.currentState = Lifecycle.State.STARTED
        advanceUntilIdle()
        assertEquals(1, checker.calls)

        clock += UpdateCheckScheduler.DEFAULT_THROTTLE_WINDOW_MILLIS + 1
        owner.registry.currentState = Lifecycle.State.CREATED
        advanceUntilIdle()
        owner.registry.currentState = Lifecycle.State.STARTED
        advanceUntilIdle()
        assertEquals(2, checker.calls)
    }

    @Test
    fun refreshNow_bypassesThrottle() = runTest(dispatcher) {
        val checker = ScriptedReleaseChecker(
            mutableListOf(ReleaseCheckResult.UpToDate, ReleaseCheckResult.UpToDate),
        )
        val s = scheduler(checker)
        val owner = FakeLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.STARTED
        s.observeProcessLifecycle(owner)
        advanceUntilIdle()
        assertEquals(1, checker.calls)

        s.refreshNow()
        advanceUntilIdle()
        assertEquals(2, checker.calls)
    }

    @Test
    fun failedCheck_doesNotLookLikeUpToDate_andDoesNotBurnThrottle() = runTest(dispatcher) {
        var clock = 1_000_000L
        val checker = ScriptedReleaseChecker(
            mutableListOf(
                ReleaseCheckResult.Failed("rate-limited, try again later"),
                ReleaseCheckResult.UpToDate,
            ),
        )
        val s = scheduler(checker, now = { clock })
        s.refreshNow()
        advanceUntilIdle()
        assertEquals("rate-limited, try again later", s.updateCheckFailed.value)
        assertTrue(s.lastResult.value is ReleaseCheckResult.Failed)
        assertNull(s.updateAvailable.value)

        clock += 1
        s.refreshNow()
        advanceUntilIdle()
        assertEquals(ReleaseCheckResult.UpToDate, s.lastResult.value)
        assertNull(s.updateCheckFailed.value)
    }

    @Test
    fun dismissHidesTag_untilANewerTagAppears() = runTest(dispatcher) {
        val v51 = release("v0.5.1")
        val v52 = release("v0.5.2")
        val checker = ScriptedReleaseChecker(
            mutableListOf(
                ReleaseCheckResult.UpdateAvailable(v51),
                ReleaseCheckResult.UpdateAvailable(v51),
                ReleaseCheckResult.UpdateAvailable(v52),
            ),
        )
        val s = scheduler(checker)

        s.refreshNow()
        advanceUntilIdle()
        assertEquals(v51, s.updateAvailable.value)

        s.dismissCurrentUpdate()
        assertNull(s.updateAvailable.value)
        assertTrue(s.lastResult.value is ReleaseCheckResult.UpdateAvailable)

        s.refreshNow()
        advanceUntilIdle()
        assertNull("dismissed tag must not re-nag", s.updateAvailable.value)

        s.refreshNow()
        advanceUntilIdle()
        assertEquals(v52, s.updateAvailable.value)
    }

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
