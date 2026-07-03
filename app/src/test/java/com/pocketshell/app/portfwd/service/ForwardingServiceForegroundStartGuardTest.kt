package com.pocketshell.app.portfwd.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.portfwd.ForwardingController
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService

/**
 * Issue #1232 regression — `ForwardingService` must CONTAIN a
 * background-restricted foreground-start failure instead of crashing the
 * process, exactly as the sibling
 * [com.pocketshell.app.sessions.service.SessionConnectionService] already does.
 *
 * ## The bug (reproduce-first, D33/G10)
 *
 * `promoteToForegroundIfNeeded` called `startForeground(...)` with no
 * `runCatching`. On Android 12+ (API 31) `startForeground` from a
 * background-restricted state throws
 * [ForegroundServiceStartNotAllowedException]; Android 14+ adds further
 * specialUse throw conditions. A forwarding start that reaches `onStartCommand`
 * on such an edge (a network-restore-driven rebuild, or an enable landing
 * exactly on the app→background transition) therefore propagated an uncaught
 * throw out of `onStartCommand` → process crash.
 *
 * The sibling `SessionConnectionService.promoteToForegroundIfNeeded` wraps the
 * identical call in `runCatching` and returns `false` on failure, and its
 * `onStartCommand` stops the service cleanly when promotion is refused. This
 * test drives the SAME failing edge against `ForwardingService` and asserts the
 * mirrored guard: no throw escapes, and the service stops itself.
 *
 * ## Injecting the failing state (the #780 model)
 *
 * A plain Robolectric AVD cannot enter the real FGS-start-not-allowed state, so
 * the background-restricted throw is injected synthetically with a scoped
 * [ThrowingForegroundShadow] that makes `startForeground` throw the exact
 * platform exception — and it is applied ONLY to the throw test via a
 * method-level `@Config`, so the "normal promotion" test still exercises the
 * real ShadowService success path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ForwardingServiceForegroundStartGuardTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /**
     * Robolectric shadow that reproduces the background-restricted foreground
     * start: every `startForeground` overload throws the exact platform
     * exception the OS raises when an FGS start is disallowed. Applied only to
     * the throw test (method-level `@Config`) so it does not affect the
     * normal-promotion test.
     */
    @Implements(Service::class)
    class ThrowingForegroundShadow : ShadowService() {
        @Implementation
        override fun startForeground(id: Int, notification: Notification) {
            throw ForegroundServiceStartNotAllowedException(
                "test-injected: FGS start not allowed from a background-restricted state",
            )
        }

        @Implementation
        override fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {
            throw ForegroundServiceStartNotAllowedException(
                "test-injected: FGS start not allowed from a background-restricted state",
            )
        }
    }

    @Test
    @Config(shadows = [ThrowingForegroundShadow::class])
    fun `background-restricted startForeground throw is contained and the service stops itself`() {
        val service = buildStartedService()

        // RED on base (unguarded startForeground): this onStartCommand call would
        // propagate ForegroundServiceStartNotAllowedException and crash. GREEN with
        // the fix: the throw is contained, no exception escapes.
        val result = try {
            service.onStartCommand(startIntent(), 0, 1)
        } catch (t: Throwable) {
            throw AssertionError(
                "onStartCommand must CONTAIN the background-restricted foreground-start " +
                    "failure (issue #1232), but it propagated: $t",
                t,
            )
        }

        // The service must degrade cleanly: promote-or-stop, not promote-or-die.
        assertEquals(
            "a refused foreground promotion must not leave a STICKY service that will be " +
                "restarted straight back into the same failing start",
            Service.START_NOT_STICKY,
            result,
        )
        assertTrue(
            "the service must stop ITSELF when foreground promotion is refused",
            shadowOf(service).isStoppedBySelf,
        )
    }

    @Test
    fun `normal foreground promotion is unchanged`() {
        val service = buildStartedService()

        val result = service.onStartCommand(startIntent(), 0, 1)

        assertEquals(
            "a successful foreground promotion keeps the service sticky",
            Service.START_STICKY,
            result,
        )
        assertFalse(
            "a successfully promoted service must NOT stop itself",
            shadowOf(service).isStoppedBySelf,
        )

        service.onDestroy()
    }

    private fun buildStartedService(): ForwardingService {
        val controller = ForwardingController(context)
        // Seed one active host so the observe loop keeps the service alive on the
        // happy path (0 active hosts is a legitimate self-teardown, unrelated to
        // the promotion-refusal path under test). Reflection avoids the
        // registerActiveHost() side effect that auto-starts a second service —
        // same seam the #994 dispatcher-leak test uses.
        seedActiveHostCount(controller, 1)
        val service = Robolectric.buildService(ForwardingService::class.java).get()
        service.controller = controller
        // Confine the observe loop to a direct dispatcher so nothing leaks (#994);
        // the happy path launches it, the guarded path returns before it.
        service.observeDispatcher = Dispatchers.Unconfined
        service.createNotificationChannel()
        return service
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedActiveHostCount(controller: ForwardingController, count: Int) {
        val field = ForwardingController::class.java.getDeclaredField("activeHostCount")
        field.isAccessible = true
        val flow = field.get(controller) as kotlinx.coroutines.flow.MutableStateFlow<Int>
        flow.value = count
    }

    private fun startIntent(): Intent =
        Intent(context, ForwardingService::class.java).apply {
            action = ForwardingService.ACTION_START
        }
}
