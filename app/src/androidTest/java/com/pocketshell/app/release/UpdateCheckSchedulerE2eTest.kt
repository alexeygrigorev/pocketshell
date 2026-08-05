package com.pocketshell.app.release

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.notifications.UpdateNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Emulator evidence for issue #698: the foreground update check fires on
 * the triggers the maintainer actually hits — **process foreground
 * resume** (driven through the real [ProcessLifecycleOwner]) and **opening
 * a host** ([UpdateCheckScheduler.onHostOpened]) — and the throttle keeps
 * the GitHub call rate sane.
 *
 * This is the on-device proof that the lifecycle wiring actually delivers
 * `ON_START` to the scheduler observer (the JVM unit test
 * `UpdateCheckSchedulerTest` drives a fake [androidx.lifecycle.LifecycleOwner];
 * here we drive the *real* process lifecycle the production wiring uses in
 * [com.pocketshell.app.App.onCreate]). We use a fake [ReleaseChecker] so
 * the test never hits the network, and the scheduler's coroutine scope is
 * the default IO scope (production shape); we poll briefly for the async
 * check to land.
 */
@RunWith(AndroidJUnit4::class)
class UpdateCheckSchedulerE2eTest {

    private companion object {
        const val TEST_PREFS_NAME = "update_check_throttle_issue_698_e2e"
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    // The instrumentation process runs the real App, whose singleton scheduler observes the
    // same ProcessLifecycleOwner. Give this test scheduler a separate ledger so the App's
    // earlier ON_START observer cannot consume this scheduler's throttle window.
    private val isolatedStoreContext: Context = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            context.getSharedPreferences(TEST_PREFS_NAME, mode)
    }
    private var activeScheduler: UpdateCheckScheduler? = null

    private val sampleInfo = ReleaseInfo(
        tagName = "v9.9.9",
        htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v9.9.9",
        apkUrl = "https://example.com/pocketshell-9.9.9-debug.apk",
    )

    private class FakeChecker(private val result: ReleaseCheckResult) : ReleaseChecker() {
        val calls = AtomicInteger(0)
        override suspend fun checkForUpdate(currentVersion: String): ReleaseCheckResult {
            calls.incrementAndGet()
            return result
        }
    }

    private class RecordingNotifier : UpdateNotifier {
        val notified = mutableListOf<String>()
        override fun notifyUpdateAvailable(info: ReleaseInfo) {
            notified += info.tagName
        }
    }

    @Before
    fun resetThrottleStore() {
        restoreProcessLifecycleStarted()
        context.getSharedPreferences(TEST_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun cleanup() {
        val scheduler = activeScheduler
        try {
            scheduler?.let {
                instrumentation.runOnMainSync {
                    it.stopObservingProcessLifecycleForTest(ProcessLifecycleOwner.get())
                }
            }
        } finally {
            try {
                // The tests below mutate the process-global owner. Restore it even when the
                // body or an early hard assertion throws, before any sibling test can start.
                restoreProcessLifecycleStarted()
            } finally {
                scheduler?.scope?.cancel()
                activeScheduler = null
                context.getSharedPreferences(TEST_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().clear().commit()
            }
        }
    }

    private fun restoreProcessLifecycleStarted() {
        instrumentation.runOnMainSync {
            val registry = ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry
            if (!registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            }
        }
    }

    private inline fun <T> withStoppedProcessLifecycle(body: () -> T): T {
        return try {
            instrumentation.runOnMainSync {
                (ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry)
                    .handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
            body()
        } finally {
            restoreProcessLifecycleStarted()
        }
    }

    private fun newScheduler(checker: ReleaseChecker, notifier: UpdateNotifier): UpdateCheckScheduler {
        val s = UpdateCheckScheduler(
            applicationContext = context,
            releaseChecker = checker,
            store = UpdateCheckStore(isolatedStoreContext),
            updateNotifier = notifier,
        )
        s.scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        s.currentVersionProvider = { "0.3.0" }
        activeScheduler = s
        return s
    }

    private fun pollUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    @Test
    fun openingHost_firesCheck_andSurfacesUpdate_onDevice() {
        val checker = FakeChecker(ReleaseCheckResult.UpdateAvailable(sampleInfo))
        val notifier = RecordingNotifier()
        val scheduler = newScheduler(checker, notifier)

        // Simulate the maintainer's journey: they deep-link straight into a
        // host (skipping home). Opening the host fires the check.
        scheduler.onHostOpened()

        assertTrue(
            "opening a host should fire the update check",
            pollUntil { scheduler.checkCount >= 1L },
        )
        assertTrue(
            "an available update should surface on the global flow",
            pollUntil { scheduler.updateAvailable.value == sampleInfo },
        )
        assertEquals(listOf("v9.9.9"), notifier.notified)
        assertEquals(1, checker.calls.get())
    }

    @Test
    fun foregroundResume_firesThrottledCheck_viaRealProcessLifecycle() {
        val checker = FakeChecker(ReleaseCheckResult.UpToDate)
        val notifier = RecordingNotifier()
        val scheduler = newScheduler(checker, notifier)

        withStoppedProcessLifecycle {
            // Attach while stopped so the attach-time cold-start seed cannot satisfy the
            // load-bearing resume assertion.
            instrumentation.runOnMainSync {
                scheduler.observeProcessLifecycle(ProcessLifecycleOwner.get())
            }
            assertTrue(
                "the lifecycle observer should attach within the bounded pump",
                pollUntil { scheduler.lifecycleObserverAttached },
            )

            // Foreground the process so the attached observer receives the real ON_START event.
            instrumentation.runOnMainSync {
                (ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry)
                    .handleLifecycleEvent(Lifecycle.Event.ON_START)
            }

            assertTrue(
                "foreground resume (ON_START) should fire the update check",
                pollUntil { scheduler.checkCount >= 1L },
            )
            assertTrue(
                "the first lifecycle request should settle within the bounded pump",
                pollUntil { scheduler.settledRequestCount >= 1L },
            )

            val firstCount = scheduler.checkCount
            val firstSettledRequestCount = scheduler.settledRequestCount

            // A second immediate resume within the throttle window must NOT fire another call.
            instrumentation.runOnMainSync {
                val registry =
                    ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry
                registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            }
            assertTrue(
                "the second lifecycle request should settle within the bounded pump",
                pollUntil { scheduler.settledRequestCount > firstSettledRequestCount },
            )
            assertEquals(
                "a resume within the throttle window must not re-check",
                firstCount,
                scheduler.checkCount,
            )
        }
    }

    @Test
    fun stoppedProcessLifecycle_isRestoredWhenTestBodyThrows() {
        val sentinel = IllegalStateException("issue-698-sentinel")

        val thrown = runCatching {
            withStoppedProcessLifecycle {
                assertEquals(
                    Lifecycle.State.CREATED,
                    ProcessLifecycleOwner.get().lifecycle.currentState,
                )
                throw sentinel
            }
        }.exceptionOrNull()

        assertSame("the sentinel body failure must propagate", sentinel, thrown)
        assertTrue(
            "a thrown test body must leave the shared process owner STARTED",
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
    }
}
