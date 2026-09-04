package com.pocketshell.next.terminal

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.connect.awaitIdle
import com.pocketshell.next.hosts.hostRowTag
import com.pocketshell.next.tree.SESSION_TREE_TAG
import com.pocketshell.next.tree.sessionRowTag
import com.termux.view.TerminalView
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Journey J06 — leaving the app and coming right back must not cost the
 * session anything (rewrite task U-8, decision D21, plan §C.4).
 *
 * ## Why this has to be a device journey
 *
 * [GraceCoordinatorTest] proves the POLICY on the JVM against a scripted
 * transport and a fake service control: it cannot see whether Android actually
 * accepts the `startForegroundService()` call this late in the background
 * transition (issue #1595 — a rejected start on a real device is invisible to
 * any fake), whether the posted notification really carries a live
 * system-rendered chronometer, whether backgrounding a REAL Activity by way of
 * [androidx.test.core.app.ActivityScenario] fires the same
 * [GraceCoordinator.enterBackground] the production activity-lifecycle path
 * does, or — the whole point of the D21/#1123 contract — whether the terminal
 * screen a real user is looking at stays exactly as it was, with no reconnect
 * banner EVER rendering, across a real app-switch over a real SSH session.
 *
 * ## Backgrounding without a launcher
 *
 * The instrumentation drives [androidx.test.core.app.ActivityScenario] down to
 * [Lifecycle.State.CREATED] and back to [Lifecycle.State.RESUMED] rather than
 * `UiDevice.pressHome()`: it is the same Activity instance the compose rule
 * already owns (so the SAME `TerminalView`/`TerminalSession` this journey reads
 * from survives the trip, exactly as it would for a user who switched apps and
 * came straight back), and it drives the exact `onStop`/`onStart` +
 * `Application.ActivityLifecycleCallbacks` boundary [GraceCoordinator]'s class
 * doc documents (issue #1595) without depending on the launcher being present
 * on a minimal CI image.
 *
 * ## The oracle for "no reconnect ever happened" is the rendered screen AND the host
 *
 * A reconnect that never happened is a negative — [SESSION_RECONNECT_BANNER_TAG]
 * must never exist in the semantics tree across the whole cycle, not merely at
 * the end — and the recovered session must still be the SAME one: it renders
 * the SAME transcript it had before backgrounding (no re-attach, no `clear`)
 * and, after typing, the command shows up in the host's OWN `capture-pane`,
 * over an independent connection.
 *
 * Bring the fixture up before running:
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents`
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J06BackgroundGraceReturnJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(SeedBeforeLaunchRule { description -> seed(description) })
        .around(compose)

    private var hostId: Long = 0

    /**
     * Restores `RESUMED` before the compose rule's own `after()` closes the
     * scenario — a scenario left at `CREATED` crashes `ActivityScenario.close()`
     * (the same issue J05's sibling grace test documents for the old app,
     * #788). Best-effort: a failed assertion may have already torn things down.
     */
    @After
    fun restoreResumed() {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
    }

    private suspend fun seed(description: Description) {
        grantNotificationPermission()
        val graph = appGraph()
        // GraceCoordinator is a process-wide Hilt singleton, so it survives
        // across every @Test method in this class (each gets its own
        // MainActivity via a fresh compose rule, but the SAME
        // ActivityLifecycleCallbacks-registered coordinator). Destroying the
        // PREVIOUS test's Activity is itself a background transition, and if
        // that test left a live connection behind it arms a REAL window whose
        // notification only clears once THIS test's Activity starts and calls
        // enterForeground() — asynchronously, since Context.stopService() does
        // not block for the service's onDestroy(). Closing the connection
        // table AND forcing that foreground edge here, before this test's own
        // seeding proceeds, is what makes each test start from a genuinely
        // clean slate rather than racing the previous test's teardown.
        graph.connectionsRegistry().closeAll()
        graph.graceCoordinator().enterForeground()
        graph.hostDao().getAll().first().forEach { graph.hostDao().deleteById(it.id) }
        graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

        val fingerprint = AgentsFixture.probeHostKeyFingerprint()
        println("J06_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $fingerprint")

        seedTmuxSession()

        val keyPath = AgentsFixture.installPrivateKey(fileName = "j06_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j06-${description.methodName}", privateKeyPath = keyPath),
        )
        hostId = HOST_IDS.getValue(description.methodName)
        graph.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "docker-fixture",
                hostname = AgentsFixture.host,
                port = AgentsFixture.port,
                username = AgentsFixture.USER,
                keyId = keyId,
                trustedHostKeyAlgorithm = "SHA256",
                trustedHostKeySha256 = fingerprint,
            ),
        )
    }

    /**
     * On API 33+ `POST_NOTIFICATIONS` is a runtime permission the
     * `connectedAndroidTest` install does NOT grant on its own — without it
     * [GraceService]'s `notify()` silently no-ops even though the foreground
     * service itself starts fine (a REAL device gap this journey exists to
     * catch: it is exactly the class of thing [GraceCoordinatorTest]'s fake
     * service control cannot see). Granted the same way `pm grant` would, via
     * the instrumentation's own [android.app.UiAutomation] — no extra test
     * dependency needed. The production first-run prompt for this permission
     * is out of scope here (task U-8 is the grace mechanism, not permission
     * onboarding).
     */
    private fun grantNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    private fun seedTmuxSession() {
        AgentsFixture.exec("tmux -S $SOCKET kill-session -t '=$SESSION' 2>/dev/null || true")
        AgentsFixture.exec("mkdir -p $SOCKET_DIR && chmod 700 $SOCKET_DIR")
        AgentsFixture.exec(
            "tmux -S $SOCKET new-session -d -s $SESSION -c /home/testuser -x 80 -y 24",
        )
        AgentsFixture.exec("tmux -S $SOCKET send-keys -t '=$SESSION:' 'PS1=\"$PROMPT \"' Enter")
        AgentsFixture.exec("tmux -S $SOCKET send-keys -t '=$SESSION:' 'clear; echo $BANNER' Enter")
        SystemClock.sleep(500)
        val pane = capturePane()
        check(squashed(pane).contains(BANNER)) {
            "the fixture tmux session did not come up: capture-pane says\n$pane"
        }
    }

    /**
     * Attach, leave the app, watch the hold come up, come straight back,
     * and prove the session paid nothing for the trip.
     */
    @Test
    fun returningWithinGraceKeepsTheSessionAliveWithNoReconnectBanner() {
        openSession()
        val before = awaitTranscript("the fixture's banner line") { it.contains(BANNER) }
        JourneyScreenshots.capture("01-attached", JOURNEY)
        assertTrue(
            "the live shell prompt must be on screen, got:\n$before",
            squashed(before).contains(PROMPT),
        )
        assertNoReconnectBanner("before backgrounding")

        // 1. Leave the app. This is the real onStop/ActivityLifecycleCallbacks
        //    boundary GraceCoordinator listens on.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)

        // 2. A background window is open, and it has a real, visible,
        //    counting-down notification behind it — not just a coordinator
        //    flag.
        awaitGraceHolding(true, "after backgrounding")
        val notification = awaitGraceNotification("after backgrounding")
        JourneyScreenshots.capture("02-backgrounded-notification-posted", JOURNEY)
        assertTrue(
            "the grace notification must be ongoing (not swipe-dismissable), got flags=" +
                notification.notification.flags,
            notification.notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
        assertTrue(
            "the grace notification must show a system count-down chronometer",
            notification.notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
        )
        assertTrue(
            "the chronometer must count DOWN to the deadline, not up",
            notification.notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
        )

        // 3. Come straight back — well inside the 90s window.
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        // 4. The hold is released and the notification is gone...
        awaitGraceHolding(false, "after returning")
        awaitNoGraceNotification("after returning")

        // 5. ...and the D21/#1123 contract: the terminal never showed a
        //    reconnect banner, at any point in the cycle, and it is showing
        //    the SAME session — not a freshly re-attached, cleared one.
        assertNoReconnectBanner("immediately after returning")
        compose.onNodeWithTag(SESSION_TERMINAL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_ERROR_BANNER_TAG).assertDoesNotExist()
        val afterReturn = renderedTranscript()
        assertTrue(
            "the SAME pane must still be on screen after a within-grace return, got:\n$afterReturn",
            squashed(afterReturn).contains(BANNER),
        )
        JourneyScreenshots.capture("03-returned-no-banner", JOURNEY)

        // A settle window: nothing this class owns runs on a timer once the
        // window closes, so if a banner were going to appear late it would
        // show up here.
        SystemClock.sleep(SETTLE_MS)
        assertNoReconnectBanner("after a settle window")

        // 6. And the session is genuinely still usable: type into it, watch
        //    the output come back, and cross-check the host's own pane.
        typeLine("echo $MARKER")
        val afterTyping = awaitTranscript("the echoed marker twice") {
            squashed(it).split(MARKER).size >= 3
        }
        JourneyScreenshots.capture("04-typed-after-return", JOURNEY)
        assertTrue(
            "the recovered viewport must show the command's output, got:\n$afterTyping",
            squashed(afterTyping).contains(MARKER),
        )
        val pane = capturePane()
        assertTrue(
            "the host's pane must show the command typed after the within-grace return, got:\n$pane",
            squashed(pane).contains("echo$MARKER"),
        )
        assertNoReconnectBanner("after typing")

        // Close the connection this test dialled before the Activity tears
        // down. Without this, tearing down the Activity at the end of the
        // test is ITSELF a real background transition over a still-live
        // connection, arming a SECOND, genuine grace window whose
        // `startForegroundService()` call can be delivered by the OS several
        // seconds late on a loaded box — long after this class's own
        // `enterForeground()` cancel/stop calls already ran, leaving a
        // notification with nothing left to take it down and contaminating
        // whichever test runs next in this class. Closing here removes the
        // live connection BEFORE teardown, so the destroy is a true no-op
        // background transition.
        runBlocking { appGraph().connectionsRegistry().closeAll() }
    }

    /**
     * Regression for issue #2483, on the real device path.
     *
     * ## What broke
     *
     * A process can end up with more than one [GraceCoordinator] — Hilt's
     * Android test harness rebuilds the whole `SingletonComponent` per
     * `@HiltAndroidTest` method on top of ONE real `Application`, which is why
     * [GraceCoordinator.register] hands over between instances at all (#2477).
     * That hand-over used to only UNREGISTER the outgoing instance, leaving its
     * already-armed window — and its expiry timer — running. Up to
     * [GraceCoordinator.DEFAULT_GRACE_MS] later that zombie timer called
     * `stop()` on the ONE process-global [GraceService], taking down whichever
     * hold the CURRENT coordinator had open by then. The observable result is
     * the exact failure the `app2` journey lane hit (CI run 33888824496):
     * `isHolding == true` with NO notification, no foreground service and no
     * wake lock — a session held with nothing keeping it alive and nothing to
     * tell the user about it.
     *
     * ## Why it is driven this way
     *
     * The landmine's fuse is a full 90 s in production, and it is planted by a
     * SUPERSEDED coordinator, so the only way to observe it inside a journey is
     * to build the retired instance explicitly with a short window. Everything
     * else here is real: the real [ConnectionsRegistry] with a real SSH session
     * on the Docker fixture, the real [AndroidGraceServiceControl], the real
     * foreground service, the real notification tray, and the real Activity
     * stop/start boundary that arms the surviving coordinator's window.
     */
    @Test
    fun aRetiredCoordinatorsExpiryCannotTakeDownTheLiveHold() {
        openSession()
        awaitTranscript("the fixture's banner line") { it.contains(BANNER) }
        assertNoReconnectBanner("before the retired-coordinator scenario")

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application =
            instrumentation.targetContext.applicationContext as android.app.Application
        val registry = appGraph().connectionsRegistry()

        // The coordinator that is about to be retired, with a window short
        // enough to observe. It opens a REAL hold over the REAL live
        // connection this journey just dialled.
        val retired = GraceCoordinator(
            connections = registry,
            service = AndroidGraceServiceControl(application),
            graceMs = RETIRED_GRACE_MS,
        )
        instrumentation.runOnMainSync {
            retired.register(application)
            retired.enterBackground()
        }
        val retiredArmedAt = SystemClock.elapsedRealtime()
        assertTrue(
            "the retired-to-be coordinator must really open a window",
            retired.isHolding,
        )
        awaitGraceNotification("for the soon-to-be-retired coordinator's own hold")

        // A later Hilt component's coordinator takes over — the hand-over that
        // has to leave NOTHING of the previous instance pending.
        val current = GraceCoordinator(
            connections = registry,
            service = AndroidGraceServiceControl(application),
        )
        instrumentation.runOnMainSync { current.register(application) }

        // The surviving coordinator opens its own window off the REAL activity
        // stop boundary, exactly as a user switching apps would.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        awaitHolding(current, "after backgrounding under the current coordinator")
        awaitGraceNotification("after backgrounding under the current coordinator")
        JourneyScreenshots.capture("06-current-hold-armed", JOURNEY)

        // Anti-vacuous guard: if the retired coordinator's window had already
        // elapsed by now there would be no zombie left to fire and this test
        // would pass without testing anything.
        val elapsed = SystemClock.elapsedRealtime() - retiredArmedAt
        assertTrue(
            "the current hold must be open BEFORE the retired window elapses, " +
                "or this test proves nothing (took ${elapsed}ms of ${RETIRED_GRACE_MS}ms)",
            elapsed < RETIRED_GRACE_MS,
        )

        // Sleep past the retired coordinator's deadline — the instant its
        // zombie expiry used to stop the shared service out from under the
        // current hold.
        val wakeAt = retiredArmedAt + RETIRED_GRACE_MS + ZOMBIE_MARGIN_MS
        while (SystemClock.elapsedRealtime() < wakeAt) {
            SystemClock.sleep(POLL_MS)
        }

        assertTrue(
            "the current coordinator's window must still be open",
            current.isHolding,
        )
        assertNotNull(
            "issue #2483: a retired coordinator's expiry must never take down the " +
                "grace notification the CURRENT coordinator is holding — that leaves " +
                "the session held with nothing user-visible and nothing keeping it alive",
            graceNotification(),
        )
        assertTrue(
            "and the retired coordinator's armed close must have been cancelled when it " +
                "was retired, not left to close the live session minutes later",
            registry.liveConnections().isNotEmpty(),
        )
        JourneyScreenshots.capture("07-hold-survived-retired-expiry", JOURNEY)

        // Coming back must still be free: same session, no reconnect, no
        // leftover notification.
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitHolding(current, "after returning", expected = false)
        awaitNoGraceNotification("after returning")
        assertNoReconnectBanner("after returning from the retired-coordinator scenario")
        compose.onNodeWithTag(SESSION_ERROR_BANNER_TAG).assertDoesNotExist()

        typeLine("echo $RETIRED_MARKER")
        awaitTranscript("the echoed marker twice") {
            squashed(it).split(RETIRED_MARKER).size >= 3
        }
        val pane = capturePane()
        assertTrue(
            "the host's pane must show the command typed after the retired window elapsed, got:\n$pane",
            squashed(pane).contains("echo$RETIRED_MARKER"),
        )
        JourneyScreenshots.capture("08-retired-scenario-session-still-usable", JOURNEY)

        // Same reason as the sibling test: leave no live connection behind for
        // the Activity teardown to arm a real window over.
        runBlocking { registry.closeAll() }
    }

    /**
     * A user who never opened a host and only backgrounds the app must not
     * find PocketShell holding a notification in their tray.
     */
    @Test
    fun backgroundingWithNoOpenSessionShowsNoHoldAndNoNotification() {
        awaitTag(hostRowTag(hostId))
        JourneyScreenshots.capture("05-hosts-only", JOURNEY)
        // Drains any notification left mid-teardown by a PREVIOUS test method
        // in this class (see the comment in `seed`) before this test makes its
        // own "nothing was posted" claim.
        awaitNoGraceNotification("before this test's own background check")

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        // No live connection was ever dialled, so there is nothing to hold.
        SystemClock.sleep(SETTLE_MS)
        assertFalse(
            "a user who never opened a host must not get a background hold",
            appGraph().graceCoordinator().isHolding,
        )
        assertNoGraceNotificationImmediately()

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitTag(hostRowTag(hostId))
    }

    // --- helpers ------------------------------------------------------------

    private fun openTree() {
        awaitTag(hostRowTag(hostId))
        compose.onNodeWithTag(hostRowTag(hostId)).performClick()
        awaitTag(SESSION_TREE_TAG)
    }

    private fun openSession() {
        openTree()
        awaitTag(sessionRowTag(SESSION))
        compose.onNodeWithTag(sessionRowTag(SESSION)).performClick()
        awaitTag(SESSION_SCREEN_TAG)
    }

    /** [GraceCoordinator.isHolding], read from the app's real Hilt singleton. */
    private fun graceHolding(): Boolean = appGraph().graceCoordinator().isHolding

    /**
     * [awaitGraceHolding] against a coordinator the test built itself, rather
     * than the app's Hilt singleton (issue #2483's scenario retires that one).
     */
    private fun awaitHolding(
        coordinator: GraceCoordinator,
        what: String,
        expected: Boolean = true,
    ) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (coordinator.isHolding == expected) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("the coordinator's isHolding never became $expected $what")
    }

    private fun awaitGraceHolding(expected: Boolean, what: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var last = !expected
        while (SystemClock.elapsedRealtime() < deadline) {
            last = graceHolding()
            if (last == expected) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "GraceCoordinator.isHolding never became $expected $what (last=$last)",
        )
    }

    /** The app's own posted grace notification, or null. */
    private fun graceNotification(): android.service.notification.StatusBarNotification? {
        val manager = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(NotificationManager::class.java)
        return manager?.activeNotifications?.firstOrNull { it.id == GraceService.NOTIFICATION_ID }
    }

    private fun awaitGraceNotification(what: String): android.service.notification.StatusBarNotification {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            graceNotification()?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("no grace notification was posted $what")
    }

    private fun awaitNoGraceNotification(what: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (graceNotification() == null) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("the grace notification was still posted $what")
    }

    private fun assertNoGraceNotificationImmediately() {
        assertEquals(
            "no grace notification may be posted with no live connection",
            null,
            graceNotification(),
        )
    }

    /**
     * The D21/#1123 assertion: the reconnect banner tag must not exist in the
     * semantics tree AT ALL, not merely "not currently displayed".
     */
    private fun assertNoReconnectBanner(`when`: String) {
        compose.awaitIdle("before asserting the reconnect banner is absent")
        assertTrue(
            "the reconnect banner must never render $`when`",
            compose.onAllNodesWithTag(SESSION_RECONNECT_BANNER_TAG).fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun awaitTranscript(what: String, predicate: (String) -> Boolean): String {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("transcript poll: $what")
            last = renderedTranscript()
            if (predicate(squashed(last))) return last
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-${what.replace(' ', '-')}", JOURNEY)
        throw AssertionError(
            "the terminal never rendered $what within ${TIMEOUT_MS}ms.\n" +
                "Rendered viewport was:\n$last\n" +
                "The host's own capture-pane says:\n" + capturePane() + "\n" +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    private fun renderedTranscript(): String {
        var text = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            text = terminalView()?.mEmulator?.screen?.transcriptText.orEmpty()
        }
        return text
    }

    private fun terminalView(): TerminalView? =
        findTerminalView(compose.activity.window.decorView)

    private fun findTerminalView(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findTerminalView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun typeLine(line: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.awaitIdle("before typing a line")
        instrumentation.runOnMainSync {
            val view = terminalView()
            checkNotNull(view) { "no TerminalView on screen to type into" }
            view.requestFocus()
        }
        instrumentation.waitForIdleSync()
        instrumentation.sendStringSync(line)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
        instrumentation.waitForIdleSync()
    }

    private fun capturePane(): String =
        AgentsFixture.exec("tmux -S $SOCKET capture-pane -p -t '=$SESSION:' 2>/dev/null || true")

    private fun squashed(text: String): String = text.filterNot { it.isWhitespace() }

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 250L
        const val SETTLE_MS = 2_000L

        const val JOURNEY = "j06-background-grace-return"

        const val SESSION = "j06-shell"

        const val SOCKET_DIR = "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)\""
        const val SOCKET = "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)/tmuxctl-$SESSION\""

        const val PROMPT = "J06READY\$"
        const val BANNER = "J06-FIXTURE-PANE"
        const val MARKER = "j06-still-alive"
        const val RETIRED_MARKER = "j06-retired-coordinator-ok"

        /**
         * Issue #2483's retired coordinator gets a SHORT window so its zombie
         * expiry lands inside the test rather than 90 s later. Long enough that
         * the hand-over and the current coordinator's own backgrounding
         * comfortably finish first — and the test asserts they did, rather than
         * assuming it.
         */
        const val RETIRED_GRACE_MS = 12_000L

        /** Head-room past the retired window, so the zombie has provably had its chance. */
        const val ZOMBIE_MARGIN_MS = 5_000L

        val HOST_IDS: Map<String, Long> = mapOf(
            "returningWithinGraceKeepsTheSessionAliveWithNoReconnectBanner" to 9_601L,
            "backgroundingWithNoOpenSessionShowsNoHoldAndNoNotification" to 9_602L,
            "aRetiredCoordinatorsExpiryCannotTakeDownTheLiveHold" to 9_603L,
        )
    }
}
