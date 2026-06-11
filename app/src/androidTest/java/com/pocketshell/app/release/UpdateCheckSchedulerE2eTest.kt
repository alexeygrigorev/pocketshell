package com.pocketshell.app.release

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.notifications.UpdateNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
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

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()

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
        context.getSharedPreferences("update_check_throttle", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun cleanup() {
        context.getSharedPreferences("update_check_throttle", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun newScheduler(checker: ReleaseChecker, notifier: UpdateNotifier): UpdateCheckScheduler {
        val s = UpdateCheckScheduler(
            applicationContext = context,
            releaseChecker = checker,
            store = UpdateCheckStore(context),
            updateNotifier = notifier,
        )
        s.scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        s.currentVersionProvider = { "0.3.0" }
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

        // Attach to the REAL ProcessLifecycleOwner, exactly as App.onCreate
        // does. Drive the lifecycle from CREATED → STARTED on the main thread
        // so the observer receives ON_START (the foreground-resume trigger).
        instrumentation.runOnMainSync {
            scheduler.observeProcessLifecycle(ProcessLifecycleOwner.get())
        }

        // Background then foreground the process so an ON_START is delivered.
        instrumentation.runOnMainSync {
            val registry = ProcessLifecycleOwner.get().lifecycle
            // currentState mutation is restricted to LifecycleRegistry; the
            // process owner is a LifecycleRegistry under the hood. Toggle via
            // the standard handleLifecycleEvent path used by tests.
            (registry as androidx.lifecycle.LifecycleRegistry)
                .handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        instrumentation.runOnMainSync {
            (ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry)
                .handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        assertTrue(
            "foreground resume (ON_START) should fire the update check",
            pollUntil { scheduler.checkCount >= 1L },
        )

        val firstCount = scheduler.checkCount

        // A second immediate resume within the throttle window must NOT fire
        // another GitHub call.
        instrumentation.runOnMainSync {
            val registry = ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        // Give any (incorrect) extra check a chance to run, then assert it did not.
        Thread.sleep(500)
        assertEquals(
            "a resume within the throttle window must not re-check",
            firstCount,
            scheduler.checkCount,
        )

        // Restore the process owner to STARTED so the shared AVD lifecycle is
        // left in a sane state for sibling tests.
        instrumentation.runOnMainSync {
            (ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry)
                .handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
    }
}
