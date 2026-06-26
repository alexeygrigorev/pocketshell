package com.pocketshell.app.portfwd.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.portfwd.ForwardingController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #994 regression — the `ForwardingService` notification-observe
 * coroutine must NOT escape the test that started it.
 *
 * ## The leak class this guards (D31/D32/G2 — the whole class, not one instance)
 *
 * Before #994 the service built `scope = CoroutineScope(SupervisorJob() +
 * Dispatchers.Default)` — a bare, un-injectable dispatcher. When a Robolectric
 * unit test drove `onStartCommand(ACTION_START)` (directly, or indirectly via
 * the panel ViewModel toggling auto-forward → `ForwardingService.start()`),
 * `startObserving()` launched a `Dispatchers.Default` coroutine that
 * immediately collected the controller's hot `StateFlow`s and called
 * `updateNotification()` → `getSystemService(NotificationManager)`. The test had
 * no handle to confine or cancel that real background coroutine, so it outlived
 * the test; when Robolectric tore the Application down, the still-running
 * Default-thread coroutine dereferenced the now-null Application in
 * `getSystemService` → an uncaught NPE surfaced as `UncaughtExceptionsBeforeTest`
 * on the NEXT test. Intermittent in the full suite because it is a cross-test
 * race vs teardown.
 *
 * ## What this test asserts (deterministic, no flake)
 *
 * The fix makes the observe dispatcher INJECTABLE (`observeDispatcher`, the
 * `@DefaultDispatcher`-qualified Hilt binding). This test injects a
 * `StandardTestDispatcher` and proves the whole leak class is closed:
 *
 *  1. The service scope is built off the INJECTED dispatcher, so the observe
 *     coroutine is confined to the test scheduler — NOT a free-running
 *     `Dispatchers.Default` background thread the test cannot reach. We prove
 *     this by recording, inside the production observe collect, the dispatcher
 *     the collector actually resumed on and asserting it is the injected one.
 *  2. The observe coroutine ([ForwardingService.observeJobForTest]) is alive
 *     while observing and is CANCELLED by `onDestroy()` — no coroutine started
 *     by the service survives the service lifecycle / the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardingServiceDispatcherLeakTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun observeCoroutineIsConfinedToInjectedDispatcherAndCancelledOnDestroy() = runTest {
        // A test dispatcher whose scheduler WE drive — the observe loop must run
        // on this, not on a real Dispatchers.Default background thread that
        // outlives the test (the #994 leak).
        val observeDispatcher = StandardTestDispatcher(testScheduler)

        // A minimal real ForwardingController. Seed an active host directly on the
        // backing StateFlow via reflection (NOT registerActiveHost, which would
        // also fire ForwardingService.start() and auto-start a second service)
        // so the observe collector sees activeHosts > 0 and reaches the
        // notification-update branch — the leak-prone updateNotification() site.
        val controller = ForwardingController(context)
        seedActiveHostCount(controller, 1)

        // Build a real service, inject the test dispatcher + controller, then
        // drive the production observe path. We call onStartCommand directly (not
        // serviceController.create()) because the generated
        // Hilt_ForwardingService.onCreate requires a @HiltAndroidApp Application,
        // which a plain Robolectric unit test does not have — this mirrors the
        // existing PortForwardPanelViewModelTest service-stop test. The scope is
        // built lazily off the injected dispatcher inside startObserving(), so
        // onCreate is not required.
        val serviceController = Robolectric.buildService(ForwardingService::class.java)
        val service = serviceController.get()
        service.observeDispatcher = observeDispatcher
        service.controller = controller
        service.createNotificationChannel()
        service.onStartCommand(
            Intent(context, ForwardingService::class.java).apply {
                action = ForwardingService.ACTION_START
            },
            0,
            1,
        )

        // The observe coroutine exists and is scheduled — on the INJECTED
        // dispatcher, so it has run NOTHING until we advance that dispatcher's
        // scheduler (a free-running Dispatchers.Default coroutine would already
        // have resumed on its own thread).
        val observeJob = service.observeJobForTest
        assertNotNull("startObserving() must have launched the observe coroutine", observeJob)
        assertTrue("the observe coroutine must be active while observing", observeJob!!.isActive)

        // Drive the injected dispatcher: the production startObserving() collect
        // resumes — on the injected dispatcher. Recorded by the service for the
        // test (see ForwardingService.lastObserveDispatcherForTest).
        advanceUntilIdle()
        assertSame(
            "the observe collector must resume on the INJECTED dispatcher, not a " +
                "free-running Dispatchers.Default (issue #994)",
            observeDispatcher,
            service.lastObserveDispatcherForTest,
        )

        // Tearing the service down cancels the scope → the observe coroutine is
        // cancelled and leaves nothing behind. With the old bare
        // Dispatchers.Default scope the coroutine could not be confined to the
        // test scheduler at all and outlived the test.
        service.onDestroy()
        advanceUntilIdle()
        assertFalse(
            "onDestroy() must cancel the observe coroutine so none survives the " +
                "service/test (issue #994: no Dispatchers.Default coroutine may leak)",
            observeJob.isActive,
        )
        assertTrue("the observe coroutine must be fully cancelled after onDestroy()", observeJob.isCancelled)
    }

    /**
     * Set the controller's private `activeHostCount` StateFlow to [count] without
     * triggering the `ForwardingService.start()` side effect that
     * `registerActiveHost` has. Reflection is acceptable here: the test owns the
     * controller instance and only needs it to report an active host so the
     * service's real observe path reaches the notification branch.
     */
    @Suppress("UNCHECKED_CAST")
    private fun seedActiveHostCount(controller: ForwardingController, count: Int) {
        val field = ForwardingController::class.java.getDeclaredField("activeHostCount")
        field.isAccessible = true
        val flow = field.get(controller) as kotlinx.coroutines.flow.MutableStateFlow<Int>
        flow.value = count
    }
}
