package com.pocketshell.app.portfwd.service

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.portfwd.ForwardingController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Issue #2006 — the ongoing port-forward notification left describing nothing
 * real while a tunnel was up: either stranded on
 * `Running in the background · Connecting…`, or gone from the tray entirely.
 *
 * ## The defect
 *
 * [ForwardingService]'s teardown is driven by the notification OBSERVER: when
 * its combined snapshot says "no active hosts" it calls `stopForwarding`, which
 * invalidates the notification generation, removes the foreground notification
 * and stops the service. That teardown is NOT atomic with respect to a
 * concurrent `ACTION_START`, and there was one bug on each side of it:
 *
 *  - **Before the close.** The observer's "no hosts left" conclusion is computed
 *    on the observe dispatcher. A forward starting in that window makes it
 *    false — `ForwardingController.registerActiveHost` /
 *    `adoptForwardingSession` publish the new count and request the service
 *    start BEFORE the close reaches its monitor — yet the teardown ran anyway,
 *    removing the live forward's notification and stopping the service. There is
 *    no follow-up start to recover with: `ForwardingService.start()` fires only
 *    on the controller's empty→1 transition, which already happened. The fix
 *    re-reads the live controller count INSIDE the close monitor and rejects the
 *    teardown outright, so the still-current observer simply publishes the live
 *    state.
 *  - **After the close.** `clearObserver()` runs once the close has released its
 *    monitor, and it used to cancel "whatever observer is armed". A start
 *    landing in that gap opens the next generation, posts the bootstrap
 *    `Connecting…` body and arms an observer for it — which the old teardown's
 *    clear then cancelled. Nothing re-arms, so the tray stays on `Connecting…`
 *    forever. The same generation-blindness previously let `armObserver` REUSE a
 *    live-but-invalidated observer whose every publish is dropped as stale.
 *
 * ## How this reproduces both deterministically
 *
 * [ForwardingNotificationCloseBarrier] holds one side of the close open so the
 * interleaving is driven, not raced. Everything else is the production path: the
 * real [ForwardingService] lifecycle entry points, the real
 * [ForwardingController], the real [NotificationManager] tray — and the start
 * these tests deliver is the one production itself enqueued (asserted from the
 * shadow application's started-service queue), never an extra start invented by
 * the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ForwardingServiceObserverGenerationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private val executor = Executors.newSingleThreadExecutor()
    private val observeDispatcher = executor.asCoroutineDispatcher()

    private val releaseBarrier = CountDownLatch(1)

    private lateinit var controller: ForwardingController
    private lateinit var service: ForwardingService

    @After
    fun tearDown() {
        releaseBarrier.countDown()
        ForwardingNotificationCloseBarrier.setForTest(null)
        runCatching { service.beforeNotificationPublishForTest = null }
        runCatching { service.onDestroy() }
        observeDispatcher.close()
        executor.shutdownNow()
        manager.cancelAll()
    }

    /**
     * LOAD-BEARING (#2006), the reported connected sequence: the last forward
     * goes away and a new one starts immediately (exactly what
     * `ForwardingNotificationE2eTest` does — `@Before` unregisters every host,
     * the test then registers one). The new forward's start lands while the
     * observer's teardown is still in flight.
     *
     * The ongoing notification must end up describing the LIVE forward. On the
     * unfixed service the in-flight teardown ran anyway and removed it, leaving
     * an active tunnel with no ongoing notification at all — and, critically,
     * nothing in production sends another start to recover with.
     */
    @Test
    fun startInsideCloseWindowKeepsTheLiveForwardNotification_withNoFollowUpStart() {
        startServiceWithSettledForward()

        // 1. The last host goes away. The observer wakes on its own dispatcher
        // and begins tearing the generation down — held before the close.
        val reachedClose = holdFirstOffMainThreadClose(
            ForwardingNotificationCloseBarrier.Phase.BEFORE_CLOSE,
        )
        controller.unregisterActiveHost(HOST_A)
        assertTrue(
            "the observer must reach the notification-close boundary after the " +
                "last host is unregistered",
            reachedClose.await(BOUNDARY_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )

        // 2. A new forward starts inside that window. This is the ONLY start in
        // the journey and production is what asks for it: the controller's
        // empty→1 transition calls ForwardingService.start(), which we assert
        // from the shadow application's queue before delivering it.
        shadowOf(application).clearStartedServices()
        controller.registerActiveHost(HOST_B, HOST_NAME)
        controller.updateActiveTunnels(HOST_B, TUNNELS)
        deliverTheStartProductionRequested()

        // 3. The held teardown is released. It is now acting on a conclusion the
        // new forward has already falsified, and must not tear the live
        // forward's notification down.
        releaseBarrier.countDown()
        drainObserveDispatcher()

        assertEquals(
            "the ongoing notification must describe the LIVE forward after a start " +
                "raced the observer's teardown; production sends no second start, so a " +
                "teardown that runs anyway leaves an active tunnel with no ongoing " +
                "notification (issue #2006). active titles=" + activeTitles() +
                " last body=" + forwardingNotificationText(),
            SETTLED_TEXT,
            awaitForwardingNotificationText(SETTLED_TEXT),
        )
        assertNotNull(
            "the ongoing forwarding notification must still be posted while the " +
                "forward is live (issue #2006); active titles=" + activeTitles(),
            forwardingNotification(),
        )
    }

    /**
     * LOAD-BEARING (#2006), the reported BODY: `Running in the background ·
     * Connecting…` while a forward is live.
     *
     * `Connecting…` has exactly one producer — the bootstrap notification
     * `startForeground()` posts when the service (re)promotes. So reaching it
     * requires a completed teardown followed by a fresh start, which is exactly
     * the window between the close and the old teardown's `clearObserver()`. The
     * fresh generation must keep its own observer: neither reused-and-stale
     * (`armObserver` on `isActive`) nor cancelled by the old teardown's
     * generation-blind clear.
     */
    @Test
    fun startAfterCloseIsNotStrandedOnConnectingBody() {
        startServiceWithSettledForward()

        // 1. The last host goes away and the teardown COMPLETES: notification
        // removed, foreground dropped. It is then held before clearing its
        // observer.
        val reachedClearWindow = holdFirstOffMainThreadClose(
            ForwardingNotificationCloseBarrier.Phase.AFTER_CLOSE,
        )
        controller.unregisterActiveHost(HOST_A)
        assertTrue(
            "the observer's teardown must complete the close and reach the " +
                "clear-observer boundary",
            reachedClearWindow.await(BOUNDARY_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )

        // 2. A new forward starts in that window — again the only start in the
        // journey, and again the one production itself enqueued.
        shadowOf(application).clearStartedServices()
        controller.registerActiveHost(HOST_B, HOST_NAME)
        controller.updateActiveTunnels(HOST_B, TUNNELS)
        deliverTheStartProductionRequested()

        // The service has re-promoted, so the tray is showing the bootstrap
        // placeholder right now. This is the exact reported state; asserting it
        // proves the test really enters it rather than skipping past it.
        assertEquals(
            "the re-promoted service must post the bootstrap Connecting… body, " +
                "otherwise the reported state was never entered",
            BOOTSTRAP_TEXT,
            forwardingNotificationText(),
        )

        // 3. Release the old teardown's clear. It belongs to a generation that
        // has already been superseded, so it must not cancel the fresh
        // generation's observer.
        releaseBarrier.countDown()
        drainObserveDispatcher()

        assertEquals(
            "the ongoing notification must converge on the live host + forwarded-port " +
                "detail; a stale teardown must not strand it on the bootstrap " +
                "Connecting… body (issue #2006). active titles=" + activeTitles() +
                " last body=" + forwardingNotificationText(),
            SETTLED_TEXT,
            awaitForwardingNotificationText(SETTLED_TEXT),
        )

        // 4. And the fresh generation's observer must still be LIVE, not merely
        // to have got one publish in before being cancelled: a later real
        // topology change must still reach the tray. On a multi-threaded
        // dispatcher that is the only thing separating "the observer survived"
        // from "the observer published once and was then killed", and it is what
        // the user feels as a frozen notification.
        controller.updateActiveTunnels(HOST_B, MORE_TUNNELS)
        assertEquals(
            "a topology change after the race must still reach the tray; a fresh " +
                "generation whose observer was cancelled by a stale teardown freezes " +
                "the notification on whatever it last showed (issue #2006). last body=" +
                forwardingNotificationText(),
            GREW_TEXT,
            awaitForwardingNotificationText(GREW_TEXT),
        )
    }

    /**
     * Class coverage (G2) for the mirror of the same defect: an observer left
     * over from an invalidated generation must not remove the CURRENT
     * generation's notification or stop the running service.
     */
    @Test
    fun staleObserverCannotTearDownTheGenerationThatSupersededIt() {
        startServiceWithSettledForward()

        val reachedClose = holdFirstOffMainThreadClose(
            ForwardingNotificationCloseBarrier.Phase.BEFORE_CLOSE,
        )
        controller.unregisterActiveHost(HOST_A)
        assertTrue(
            "the observer must reach the notification-close boundary",
            reachedClose.await(BOUNDARY_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )

        // The service thread completes a full stop and then a fresh start with
        // a new forward while the observer's close is still held. The barrier
        // is one-shot + off-main, so these service-thread closes run through.
        service.onStartCommand(stopIntent(), 0, 2)
        shadowOf(application).clearStartedServices()
        controller.registerActiveHost(HOST_B, HOST_NAME)
        controller.updateActiveTunnels(HOST_B, TUNNELS)
        deliverTheStartProductionRequested()

        // Release the stale observer's teardown. It belongs to a generation that
        // has already been invalidated, so it must be rejected outright — it may
        // neither cancel the current generation's observer nor remove the
        // current generation's foreground notification.
        releaseBarrier.countDown()
        drainObserveDispatcher()

        assertEquals(
            "the current generation must still render the live forward after a stale " +
                "observer's teardown is released; a stale observer must not remove the " +
                "notification or cancel the current observer (issue #2006). " +
                "active titles=" + activeTitles() + " last body=" + forwardingNotificationText(),
            SETTLED_TEXT,
            awaitForwardingNotificationText(SETTLED_TEXT),
        )
        assertNotNull(
            "a stale observer must not remove the current generation's ongoing " +
                "notification (issue #2006); active titles=" + activeTitles(),
            forwardingNotification(),
        )
    }

    /**
     * Issue #2047 — Android rate-limits rapid updates to one notification. A
     * controller mutation updates several StateFlows synchronously, and the
     * old five-way `combine` rendered every intermediate tuple. Adding and
     * immediately removing a second forward could therefore spend the
     * platform's enqueue budget on duplicate/intermediate bodies; the final
     * one-forward body was then discarded and the tray stayed on two.
     *
     * The service must collapse each synchronous topology burst to one
     * platform publication. The user-visible final-body checks remain the
     * load-bearing connected oracle; this JVM count makes the rate-budget
     * mechanism deterministic instead of relying on emulator load.
     */
    @Test
    fun addThenRemoveSecondForwardPublishesOnlySettledTopologyBodies() {
        startServiceWithSettledForward()

        val publishAttempts = AtomicInteger(0)
        service.beforeNotificationPublishForTest = { publishAttempts.incrementAndGet() }

        controller.registerActiveHost(HOST_C, "Second host")
        controller.updateActiveTunnels(HOST_C, mapOf(4444 to 4444))
        assertEquals(
            "adding the second forward must reach the tray",
            "Running in the background · Race Host + 1 more · 3 ports forwarded",
            awaitForwardingNotificationText(
                "Running in the background · Race Host + 1 more · 3 ports forwarded",
            ),
        )

        controller.unregisterActiveHost(HOST_C)
        assertEquals(
            "removing the second forward must reach the tray instead of leaving " +
                "the stale multi-forward body (issue #2047)",
            SETTLED_TEXT,
            awaitForwardingNotificationText(SETTLED_TEXT),
        )
        drainObserveDispatcher()

        assertEquals(
            "one settled platform notification write is allowed for add and one for " +
                "remove; intermediate StateFlow tuples exhaust Android's update budget",
            2,
            publishAttempts.get(),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun startServiceWithSettledForward() {
        manager.cancelAll()
        controller = ForwardingController(context)
        controller.registerActiveHost(HOST_A, HOST_NAME)
        controller.updateActiveTunnels(HOST_A, TUNNELS)
        service = Robolectric.buildService(ForwardingService::class.java).get().apply {
            this.controller = this@ForwardingServiceObserverGenerationTest.controller
            this.observeDispatcher = this@ForwardingServiceObserverGenerationTest.observeDispatcher
            createNotificationChannel()
        }
        service.onStartCommand(startIntent(), 0, 1)
        assertEquals(
            "the production observer must render the live forward before the race " +
                "is driven; last body=" + forwardingNotificationText(),
            SETTLED_TEXT,
            awaitForwardingNotificationText(SETTLED_TEXT),
        )
    }

    /**
     * Deliver the `ACTION_START` that PRODUCTION enqueued.
     *
     * A Robolectric-built service is not wired to the ActivityManager, so
     * nothing delivers the intent `ForwardingController` fired through
     * `ForwardingService.start()`. This asserts that intent is actually sitting
     * in the shadow application's started-service queue — i.e. the controller's
     * empty→1 transition really did request a start — and then hands exactly
     * that intent to `onStartCommand`. Nothing here invents a start production
     * would not send.
     */
    private fun deliverTheStartProductionRequested() {
        val requested = shadowOf(application).nextStartedService
        assertNotNull(
            "ForwardingController must request a service start on its empty→1 " +
                "transition; without it there is nothing for the platform to deliver",
            requested,
        )
        assertEquals(
            "the start production requested must be ACTION_START",
            ForwardingService.ACTION_START,
            requested.action,
        )
        assertEquals(
            "the start production requested must target ForwardingService",
            ForwardingService::class.java.name,
            requested.component?.className,
        )
        service.onStartCommand(requested, 0, 2)
    }

    /**
     * Install a ONE-SHOT barrier that only holds [phase] when it arrives off the
     * main (service) thread — i.e. the observer's own teardown. Service-thread
     * lifecycle commands run through untouched.
     */
    private fun holdFirstOffMainThreadClose(
        phase: ForwardingNotificationCloseBarrier.Phase,
    ): CountDownLatch {
        val reached = CountDownLatch(1)
        val held = AtomicBoolean(false)
        ForwardingNotificationCloseBarrier.setForTest { reachedPhase ->
            if (
                reachedPhase == phase &&
                Looper.myLooper() != Looper.getMainLooper() &&
                held.compareAndSet(false, true)
            ) {
                reached.countDown()
                releaseBarrier.await(BOUNDARY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        }
        return reached
    }

    /** Single-thread executor barrier: every queued observer turn has run. */
    private fun drainObserveDispatcher() {
        executor.submit {}.get(BOUNDARY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun startIntent(): Intent =
        Intent(context, ForwardingService::class.java).setAction(ForwardingService.ACTION_START)

    private fun stopIntent(): Intent =
        Intent(context, ForwardingService::class.java).setAction(ForwardingService.ACTION_STOP)

    private fun forwardingNotification() =
        manager.activeNotifications.firstOrNull {
            it.notification.extras.getCharSequence("android.title")?.toString()
                ?.contains("Port forwarding running") == true
        }

    private fun forwardingNotificationText(): String? =
        forwardingNotification()?.notification?.extras
            ?.getCharSequence("android.text")?.toString()

    private fun activeTitles(): List<CharSequence?> =
        manager.activeNotifications.map { it.notification.extras.getCharSequence("android.title") }

    /**
     * Bounded wall-clock pump for the real observe thread (Shape B): the
     * observer runs on a REAL executor, so the load-bearing assertion is this
     * pump's exit condition, never the loop body.
     */
    private fun awaitForwardingNotificationText(expected: String): String? {
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        var text = forwardingNotificationText()
        while (text != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            text = forwardingNotificationText()
        }
        return text
    }

    private companion object {
        const val HOST_A = 2_006_001L
        const val HOST_B = 2_006_002L
        const val HOST_C = 2_047_003L
        const val HOST_NAME = "Race Host"
        val TUNNELS = mapOf(2222 to 2222, 3333 to 3333)
        val MORE_TUNNELS = mapOf(2222 to 2222, 3333 to 3333, 4444 to 4444)
        const val SETTLED_TEXT = "Running in the background · Race Host · 2 ports forwarded"
        const val GREW_TEXT = "Running in the background · Race Host · 3 ports forwarded"
        const val BOOTSTRAP_TEXT = "Running in the background · Connecting…"
        const val BOUNDARY_TIMEOUT_MS = 10_000L
        const val SETTLE_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 20L
    }
}
