package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.portfwd.PortForwardingTestIsolationRule
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.testsupport.GRACE_HELD_BY_PORT_FORWARD_PIN_SIGNATURE
import com.pocketshell.testsupport.OwnedClientDetachVerdict
import com.pocketshell.testsupport.TMUX_CLIENT_OWNERSHIP_FORMAT
import com.pocketshell.testsupport.TmuxClientRecord
import com.pocketshell.testsupport.describeOwnedDetachFailure
import com.pocketshell.testsupport.evaluateOwnedClientDetach
import com.pocketshell.testsupport.parseTmuxClients
import com.pocketshell.testsupport.resolveOwnedClientNames
import com.termux.view.TerminalView
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import com.pocketshell.app.proof.signals.captureViewToBitmap

/**
 * Issue #215 — regression test for the v0.2.8 maintainer feedback:
 *
 *  > "right now I am entered the shell from my phone from a pocket shell
 *  >  right so then I dictate and then I close it right so so I did it I
 *  >  close it and then I open it on so the app I I stop using right and
 *  >  then I open it in my other computer and then I cannot type anything"
 *
 * The original bug left an orphan control client registered server-side after
 * PocketShell stopped owning it. Since #1123/#1159, an Activity close while
 * the instrumented process remains alive deliberately parks that runtime only
 * for bounded background grace. The actual regression signal is therefore a
 * client that survives the grace deadline, not a still-owned client inside it.
 *
 * The fix is in [com.pocketshell.core.tmux.TmuxClient.detachCleanly] and
 * its wire-up inside the elapsed-grace teardown path.
 *
 * This test runs against the deterministic `agents:2222` Docker fixture
 * (already required by the rest of the connected suite — no new fixture
 * needed). It exercises both halves of the acceptance criteria in
 * sequence:
 *
 *  1. Attach to `claude-main` via the normal app journey
 *     (host picker -> session picker -> Attach).
 *  2. Identify the app's OWN client by identity — the client that appeared
 *     between the pre-attach baseline and the live attach (issue #1994).
 *  3. Force the activity to DESTROYED while the instrumented process stays
 *     alive, then let a short injected background grace elapse.
 *  4. Poll `tmux list-clients -t claude-main` until every APP-OWNED client is
 *     gone and nothing new appeared (acceptance: inside
 *     [ORPHAN_CLIENT_CLEANUP_TIMEOUT_MS]). A client that was already on the
 *     shared fixture session before this journey started is reported and
 *     ignored: it is not PocketShell's orphan, and counting it as one is the
 *     nightly red #1994 was reopened for.
 *  5. Open a fresh non-CC interactive `tmux attach -t claude-main`
 *     from a sidecar SSH session and type a unique marker. Verify the
 *     marker reaches the running pane — proving that input flows
 *     through the new client unimpeded.
 *
 * Artifact contract (see process.md "Terminal Artifact Review"):
 *
 *  - `issue215-01-attached-viewport.png` + `-visible-terminal.txt` —
 *    proof the app attached cleanly before the test exercised the
 *    teardown.
 *  - `issue215-02-after-destroy-clients.txt` — output of
 *    `tmux list-clients -t claude-main` after activity destruction + grace,
 *    captured for the orphan-count assertion.
 *  - `issue215-03-second-client-pane.txt` — text the second
 *    (non-CC) client typed-and-read, proving input round-trips.
 *  - `timings.txt` — attach time, destroy-to-orphan-cleared latency,
 *    second-client attach + input round-trip timing.
 */
@RunWith(AndroidJUnit4::class)
class TmuxOrphanClientCleanupE2eTest {

    val compose = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found").
    val grantPermissions = PreGrantPermissionsRule()

    /**
     * Issue #1994 (round 2) — the port-forward pin owner.
     *
     * As an OUTER rule it guarantees this class never leaks an always-on pin
     * into a later class. Its [PortForwardingTestIsolationRule.hardStopAndAssertZero]
     * is also called MID-journey, right before the app close, so a pin leaked
     * INTO this class by an earlier one cannot suppress the bounded teardown and
     * masquerade as a PocketShell orphan — the mechanism that produced the
     * hosted red on run 30961154855 shard 2.
     */
    private val forwardingIsolation = PortForwardingTestIsolationRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(forwardingIsolation)
        .around(grantPermissions)
        .around(compose)

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var pinnedForwardHostId: Long = -1L
    private val timings = mutableListOf<String>()

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        if (pinnedForwardHostId >= 0L) {
            runCatching { forwardingController().unregisterActiveHost(pinnedForwardHostId) }
            pinnedForwardHostId = -1L
        }
        BackgroundGraceTestOverride.setForTest(null)
        runBlocking {
            runCatching { cleanupRemoteTmuxSession(readFixtureKey()) }
        }
    }

    @Test
    fun closingTheAppDoesNotLeaveAnOrphanCcClient() { runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        // ---- Seed the session with a long-running tail so the session
        // outlives the test body (a `sleep` is enough — the actual pane
        // payload is irrelevant; the test is about CLIENT lifecycle, not
        // pane content).
        seedTmuxSession(key)

        // Issue #1994: snapshot the clients that already sit on this session
        // BEFORE the app attaches. tmux does not kill a client whose session is
        // killed — it MOVES it to another session — so on the nightly's ~300-test
        // single-process shard a stray client from an earlier class can land on
        // the shared fixture session by itself. Everything after this baseline is
        // attributed by client IDENTITY so a foreign client can never be read as
        // PocketShell's orphan (and, just as importantly, so PocketShell's own
        // orphan can never hide behind one).
        val foreignBaseline = listClientNames(key, SEEDED_SESSION)
        Log.i(LOG_TAG, "foreign client baseline on $SEEDED_SESSION = $foreignBaseline")

        val hostRowTag = seedDockerHost(key)
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // ---- (1) Attach to claude-main from the picker.
        val attachStart = SystemClock.elapsedRealtime()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SEEDED_SESSION, timeoutMs = 20_000)
        compose.onNodeWithText(SEEDED_SESSION).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        recordTiming("attach_ms", SystemClock.elapsedRealtime() - attachStart)
        captureViewport("issue215-01-attached")

        // ---- (2) Identify the app's OWN client(s): whatever appeared on the
        // session between the pre-attach baseline and now. tmux registers the
        // `-CC` client one control-mode round-trip after the local TerminalView
        // binds, so this is polled rather than sampled once.
        val ownedNames = awaitOwnedClientNames(key, SEEDED_SESSION, foreignBaseline)
        Log.i(LOG_TAG, "app-owned clients on $SEEDED_SESSION = $ownedNames")
        assertTrue(
            "expected the app's own tmux -CC client to register on $SEEDED_SESSION within " +
                "${OWNED_CLIENT_REGISTRATION_TIMEOUT_MS}ms; foreignBaseline=$foreignBaseline " +
                "current=${listClientRecords(key, SEEDED_SESSION)}",
            ownedNames.isNotEmpty(),
        )

        // ---- (3a) Issue #1994 round 2 — HARD-ISOLATE any always-on port-forward
        // pin before the close. `App.dispatchGraceElapsedIfNeeded` SUPPRESSES the
        // bounded teardown while `forwardingController.flowOfActiveHostCount() > 0`
        // (#1159 Part 3, the D21 carve-out), so a pin leaked into this process by
        // an earlier class makes the app hold its `-CC` client BY DESIGN — and the
        // old total-count oracle reported that as `expected:<0> but was:<1>`,
        // pointing straight at the product. This is exactly what happened on
        // nightly run 30961154855 shard 2 (`PsAppBgGrace:
        // grace-window-held-by-port-forward (always-on)` at 00:30:42.881, 11
        // classes after the forwarding tests). Isolating here removes the
        // precondition instead of tolerating it.
        val pinBeforePrelude = portForwardPinActive()
        forwardingIsolation.hardStopAndAssertZero("#1994 pre-close forwarding-pin isolation")
        val pinAfterPrelude = portForwardPinActive()
        recordTiming("forwarding_pin_active_before_prelude", if (pinBeforePrelude) 1L else 0L)
        recordTiming("forwarding_pin_active_after_prelude", if (pinAfterPrelude) 1L else 0L)
        assertFalse(
            "the pre-close isolation must leave NO port-forward pin active, otherwise the " +
                "bounded teardown stays suppressed and any orphan verdict below is meaningless",
            pinAfterPrelude,
        )

        // ---- (3) Force the activity to DESTROYED. ActivityScenario keeps the
        // instrumented app process alive, so this is a background transition,
        // not an OS process kill. Since #1123/#1159 the ViewModel parks its
        // runtime during bounded grace and the App-level grace owner performs
        // the clean detach after the deadline. Inject a short deadline rather
        // than asserting the superseded synchronous-onCleared contract.
        BackgroundGraceTestOverride.setForTest(POST_CLOSE_GRACE_MS)
        // ActivityScenario's
        // `close()` walks the lifecycle through stopped + destroyed,
        // which invokes [TmuxSessionViewModel.onCleared], parks the runtime,
        // and leaves the process-scoped grace owner responsible for teardown.
        // This is the exact code path the maintainer's "close the
        // app" sequence exercises on a real phone (back-out of the
        // session screen -> back-out of the app).
        val destroyAt = SystemClock.elapsedRealtime()
        launchedActivity?.close()
        launchedActivity = null

        // ---- (4) Poll until the clients THIS journey created are gone. A client
        // before the deadline is owned, not orphaned; an APP-OWNED client
        // surviving the deadline is the actual orphan class this proof guards,
        // and so is a client that appeared afterwards (a post-teardown re-dial
        // is a D21 violation). A foreign client that was already there is
        // reported and ignored — see [evaluateOwnedClientDetach].
        val convergence = awaitOwnedClientsDetached(
            key = key,
            sessionName = SEEDED_SESSION,
            foreignBaseline = foreignBaseline,
            ownedNames = ownedNames,
        )
        recordTiming(
            "destroy_to_orphan_cleared_ms",
            SystemClock.elapsedRealtime() - destroyAt,
        )
        recordTiming("orphan_convergence_polls", convergence.polls.toLong())
        val pinAtVerdict = portForwardPinActive()
        writeText(
            "issue215-02-after-destroy-clients.txt",
            buildString {
                appendLine("foreign_baseline=$foreignBaseline")
                appendLine("app_owned=$ownedNames")
                appendLine("polls=${convergence.polls}")
                appendLine("forwarding_pin_active_before_prelude=$pinBeforePrelude")
                appendLine("forwarding_pin_active_at_verdict=$pinAtVerdict")
                appendLine("verdict=${convergence.verdict.diagnosis()}")
                appendLine("--- raw tmux list-clients ---")
                append(convergence.raw)
            },
        )
        assertTrue(
            describeOwnedDetachFailure(
                verdict = convergence.verdict,
                portForwardPinActive = pinAtVerdict,
                sessionName = SEEDED_SESSION,
                rawClients = convergence.raw,
            ).orEmpty() + " foreign_baseline=$foreignBaseline app_owned=$ownedNames",
            convergence.verdict.detached,
        )

        // ---- (5) The session itself must still be alive on the server.
        // The maintainer's reported workflow is "close the phone app,
        // attach from laptop" — so the session must persist, only the
        // -CC client should be gone. assertion mirrors the issue's
        // explicit non-goal "killing the session itself on PocketShell
        // close is NOT the desired behaviour".
        val survives = listSessions(key).any { it.startsWith("$SEEDED_SESSION:") }
        assertTrue(
            "expected $SEEDED_SESSION to survive PocketShell close — the maintainer's workflow " +
                "requires reattaching from a laptop; sessions=${listSessions(key)}",
            survives,
        )

        // ---- (6) Now act as the laptop: open a plain (non-CC) ssh
        // shell, run `tmux attach -t claude-main`, type a marker, and
        // assert the marker reaches the running pane. We use
        // [openShell] from AndroidSshTestFixtures (already used by
        // other proof tests) and capture the pane output via
        // `tmux capture-pane -p`.
        val secondClientStart = SystemClock.elapsedRealtime()
        val marker = "POCKETSHELL215_${System.nanoTime()}"
        val capture = attachAsPlainClientAndType(key, SEEDED_SESSION, marker)
        recordTiming(
            "second_client_attach_to_marker_ms",
            SystemClock.elapsedRealtime() - secondClientStart,
        )
        writeText("issue215-03-second-client-pane.txt", capture)
        assertTrue(
            "expected `$marker` in second-client pane capture, got:\n$capture",
            capture.contains(marker),
        )

        writeTimings()
        Unit
    } }

    /**
     * Issue #1994 — a foreign client on the SAME session must not be reported as
     * PocketShell's orphan.
     *
     * Nightly run 30961154855 (shard 2, exact main `1ddeadb3`) failed this class
     * with:
     *
     * ```
     * expected zero tmux clients on claude-main after app close grace elapsed;
     * raw=`/dev/pts/24: claude-main [62x xterm-256color] (attached,focused,control-mode,UTF-8)`
     * expected:<0> but was:<1>
     * ```
     *
     * A TOTAL count cannot tell whose client that is, and the nightly runs ~300
     * connected classes in ONE process against ONE tmux server on the SHARED
     * fixture session — several of them attach their own sidecar clients, and
     * tmux MOVES (never kills) a client whose session is killed, so a stray
     * client migrates onto this session on its own. The issue asks for exactly
     * this discrimination: "distinguish a product detach regression from a
     * leaked prior-test client".
     *
     * This test injects the contaminating state deterministically — a real
     * second client on the very session under test, alive across the whole
     * journey — and requires that:
     *
     *  * the proof still concludes PocketShell's own client detached; AND
     *  * the naive total count at that same moment is NON-zero, i.e. the state
     *    that produced the nightly red is genuinely present (without this the
     *    test could pass vacuously against no contamination at all).
     *
     * The complementary direction — PocketShell's own client surviving is STILL
     * a hard failure, and a client appearing after teardown is too — is pinned
     * per-push on the JVM by `TmuxClientOwnershipTest`.
     */
    @Test
    fun aForeignClientOnTheSameSessionIsNotReportedAsPocketShellsOrphan() { runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        seedTmuxSession(key)

        val foreign = attachForeignPlainClient(key, SEEDED_SESSION)
        try {
            val foreignBaseline = listClientNames(key, SEEDED_SESSION)
            assertTrue(
                "the injected foreign client must be registered on $SEEDED_SESSION before " +
                    "the app attaches, otherwise this test proves nothing; baseline=$foreignBaseline",
                foreignBaseline.isNotEmpty(),
            )

            val hostRowTag = seedDockerHost(key)
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
            waitForText(SEEDED_SESSION, timeoutMs = 20_000)
            compose.onNodeWithText(SEEDED_SESSION).performClick()
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
            waitForTerminalViewAttached()

            val ownedNames = awaitOwnedClientNames(key, SEEDED_SESSION, foreignBaseline)
            assertTrue(
                "the app's own -CC client must be distinguishable from the injected " +
                    "foreign client; foreignBaseline=$foreignBaseline " +
                    "current=${listClientRecords(key, SEEDED_SESSION)}",
                ownedNames.isNotEmpty(),
            )
            assertTrue(
                "app-owned and foreign client sets must be disjoint; owned=$ownedNames " +
                    "foreign=$foreignBaseline",
                ownedNames.intersect(foreignBaseline).isEmpty(),
            )

            forwardingIsolation.hardStopAndAssertZero("#1994 foreign-client arm pin isolation")
            BackgroundGraceTestOverride.setForTest(POST_CLOSE_GRACE_MS)
            launchedActivity?.close()
            launchedActivity = null

            val convergence = awaitOwnedClientsDetached(
                key = key,
                sessionName = SEEDED_SESSION,
                foreignBaseline = foreignBaseline,
                ownedNames = ownedNames,
            )
            val pinAtVerdict = portForwardPinActive()
            writeText(
                "issue1994-foreign-client-contamination.txt",
                buildString {
                    appendLine("foreign_baseline=$foreignBaseline")
                    appendLine("app_owned=$ownedNames")
                    appendLine("polls=${convergence.polls}")
                    appendLine("forwarding_pin_active_at_verdict=$pinAtVerdict")
                    appendLine("verdict=${convergence.verdict.diagnosis()}")
                    appendLine("naive_total_client_count=${convergence.records.size}")
                    appendLine("--- raw tmux list-clients ---")
                    append(convergence.raw)
                },
            )

            assertTrue(
                "PocketShell's own client must be gone even while a foreign PLAIN client " +
                    "shares the session; " +
                    describeOwnedDetachFailure(
                        verdict = convergence.verdict,
                        portForwardPinActive = pinAtVerdict,
                        sessionName = SEEDED_SESSION,
                        rawClients = convergence.raw,
                    ).orEmpty(),
                convergence.verdict.detached,
            )
            assertTrue(
                "the injected sidecar must be a PLAIN client — a control-mode client is " +
                    "never foreign on this fixture and must never be excused; " +
                    "foreign_remaining=${convergence.verdict.foreignRemaining}",
                convergence.verdict.foreignRemaining.none { it.controlMode },
            )
            // Hard proof the contaminating state was really present: the naive
            // total-count oracle that produced the nightly red would have read a
            // non-zero count at this exact moment.
            assertTrue(
                "the injected foreign client must still be attached at verdict time — " +
                    "otherwise the contaminating state this test exists to reproduce was " +
                    "never present; records=${convergence.records}",
                convergence.verdict.foreignRemaining.isNotEmpty(),
            )
            assertTrue(
                "a naive total tmux client count must be NON-zero here; that non-zero count " +
                    "is exactly what the nightly reported as `expected:<0> but was:<1>`; " +
                    "records=${convergence.records}",
                convergence.records.isNotEmpty(),
            )
        } finally {
            runCatching { foreign.shell.close() }
            runCatching { foreign.client.disconnect() }
        }
        Unit
    } }

    /**
     * Issue #1994 round 2 — the mechanism that ACTUALLY produced the hosted red.
     *
     * The nightly's own per-class logcat
     * (`logcat-…TmuxOrphanClientCleanupE2eTest-closingTheAppDoesNotLeaveAnOrphanCcClient.txt`
     * in run 30961154855 shard 2) shows:
     *
     * ```
     * 00:30:40.441 Issue215OrphanClient: attached-state clients on claude-main = 1
     * 00:30:41.381 PsAppBgGrace: grace-window-start millis=1500
     * 00:30:41.812 LifecycleMonitor: MainActivity … DESTROYED
     * 00:30:42.881 PsAppBgGrace: grace-window-held-by-port-forward (always-on)
     * 00:30:58.331 failed: raw=`/dev/pts/24: claude-main [62x xterm-256color]
     *                            (attached,focused,control-mode,UTF-8)`
     * ```
     *
     * The TOTAL count at attach time was 1, so there was **no foreign client** to
     * ignore, and the survivor is control-mode at 62 columns — exactly the app's
     * own client geometry (`tmux-client-size-known … cols=62`). The cause is
     * `App.kt`'s `dispatchGraceElapsedIfNeeded`: while a port-forward pins the
     * connection always-on it SUPPRESSES the bounded teardown (#1159 Part 3), and
     * on that shard the pin had leaked out of an earlier forwarding class in the
     * shared instrumentation process. So the app was behaving exactly as
     * specified; the ORACLE was reporting the wrong thing.
     *
     * This test drives that state deterministically on the real journey and
     * proves all three halves of the fix:
     *
     *  1. **The leak is isolated.** A pin registered before the journey (standing
     *     in for the earlier class) is cleared by the pre-close prelude, and the
     *     unchanged journey converges — no misleading orphan red.
     *  2. **A pin that is still active at verdict time is NAMED, not blamed on
     *     the product** — and it is STILL a hard failure. Attribution is never
     *     amnesty.
     *  3. **The product itself is correct**: once the pin is released,
     *     `BackgroundGraceController.onPinReleased` resumes the suppressed
     *     teardown and the app's `-CC` client goes away.
     *
     * The pin is injected synthetically (`ForwardingController.registerActiveHost`
     * on the singleton the app reads) because a leaked cross-class pin cannot be
     * commanded on demand — the #780 model, hard-failing, never self-skipping. It
     * is the SAME injection the reviewed `NotificationTapLivePinnedForegroundReseedJourneyE2eTest`
     * uses to reach the #1159 always-on state.
     */
    @Test
    fun aLeakedAlwaysOnForwardingPinIsIsolatedNamedAndReleasedInsteadOfBlamedOnTheProduct() {
        runBlocking {
            val key = readFixtureKey()
            waitForSshFixtureReady(SshKey.Pem(key))
            seedTmuxSession(key)
            val foreignBaseline = listClientNames(key, SEEDED_SESSION)

            val hostRowTag = seedDockerHost(key)
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
            waitForText(SEEDED_SESSION, timeoutMs = 20_000)
            compose.onNodeWithText(SEEDED_SESSION).performClick()
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
            waitForTerminalViewAttached()

            val ownedNames = awaitOwnedClientNames(key, SEEDED_SESSION, foreignBaseline)
            assertTrue(
                "the app's own -CC client must register before the pin arms; " +
                    "foreignBaseline=$foreignBaseline " +
                    "current=${listClientRecords(key, SEEDED_SESSION)}",
                ownedNames.isNotEmpty(),
            )

            // ---- (A) THE LEAK: a pin left behind by an EARLIER class. The
            // controller is a process singleton, so who registered it is
            // irrelevant — this is byte-for-byte the state shard 2 was in.
            pinnedForwardHostId = LEAKED_PIN_HOST_ID
            forwardingController().registerActiveHost(
                hostId = LEAKED_PIN_HOST_ID,
                hostName = "Issue1994 Leaked Pin",
            )
            assertTrue(
                "the injected pin must make holdWhilePinned() true, otherwise this test " +
                    "reproduces nothing",
                portForwardPinActive(),
            )

            // ---- (B) The pre-close prelude must CLEAR it. Without this line the
            // journey below reports the misleading `expected:<0> but was:<1>`.
            forwardingIsolation.hardStopAndAssertZero("#1994 leaked-pin isolation")
            pinnedForwardHostId = -1L
            assertFalse(
                "the prelude must clear the leaked pin so the bounded teardown can run",
                portForwardPinActive(),
            )

            BackgroundGraceTestOverride.setForTest(POST_CLOSE_GRACE_MS)
            launchedActivity?.close()
            launchedActivity = null

            val isolated = awaitOwnedClientsDetached(
                key = key,
                sessionName = SEEDED_SESSION,
                foreignBaseline = foreignBaseline,
                ownedNames = ownedNames,
            )
            assertTrue(
                "with the leaked pin isolated the bounded teardown must run and the app's " +
                    "own client must go; " +
                    describeOwnedDetachFailure(
                        verdict = isolated.verdict,
                        portForwardPinActive = portForwardPinActive(),
                        sessionName = SEEDED_SESSION,
                        rawClients = isolated.raw,
                    ).orEmpty(),
                isolated.verdict.detached,
            )

            // ---- (C) Now the un-isolatable case: re-attach, arm the pin AFTER
            // the prelude, and close. Production correctly HOLDS the client, so
            // the journey must NAME the pin rather than report a product orphan —
            // and the verdict must remain a failure.
            val hostRowTag2 = seedDockerHost(key)
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(hostRowTag2, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(hostRowTag2, useUnmergedTree = true).performClick()
            waitForText(SEEDED_SESSION, timeoutMs = 20_000)
            compose.onNodeWithText(SEEDED_SESSION).performClick()
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
            waitForTerminalViewAttached()

            val pinnedBaseline = isolated.records.map { it.name }.toSet()
            val pinnedOwned = awaitOwnedClientNames(key, SEEDED_SESSION, pinnedBaseline)
            assertTrue(
                "the app's own -CC client must re-register before the pinned arm; " +
                    "baseline=$pinnedBaseline current=${listClientRecords(key, SEEDED_SESSION)}",
                pinnedOwned.isNotEmpty(),
            )

            pinnedForwardHostId = LEAKED_PIN_HOST_ID
            forwardingController().registerActiveHost(
                hostId = LEAKED_PIN_HOST_ID,
                hostName = "Issue1994 Held Pin",
            )
            BackgroundGraceTestOverride.setForTest(POST_CLOSE_GRACE_MS)
            launchedActivity?.close()
            launchedActivity = null

            val held = awaitOwnedClientsDetached(
                key = key,
                sessionName = SEEDED_SESSION,
                foreignBaseline = pinnedBaseline,
                ownedNames = pinnedOwned,
                timeoutMs = PIN_HELD_OBSERVATION_MS,
            )
            val pinActiveAtHeldVerdict = portForwardPinActive()
            assertTrue(
                "the pin must still be active at verdict time for this arm to mean anything",
                pinActiveAtHeldVerdict,
            )
            assertFalse(
                "#1159 Part 3: an active port-forward SUPPRESSES the bounded teardown, so the " +
                    "app's -CC client is expected to survive here — if it detached, the " +
                    "always-on carve-out itself regressed; ${held.verdict.diagnosis()}",
                held.verdict.detached,
            )
            val heldFailure = describeOwnedDetachFailure(
                verdict = held.verdict,
                portForwardPinActive = pinActiveAtHeldVerdict,
                sessionName = SEEDED_SESSION,
                rawClients = held.raw,
            )
            assertNotNull("a non-detached verdict must produce a failure message", heldFailure)
            assertTrue(
                "the failure must NAME the port-forward pin as the cause instead of reporting " +
                    "a plain PocketShell orphan — that mis-attribution is the nightly red this " +
                    "issue was reopened for; got: $heldFailure",
                heldFailure!!.contains(GRACE_HELD_BY_PORT_FORWARD_PIN_SIGNATURE),
            )
            assertFalse(
                "with a pin active the message must not call this a genuine detach regression; " +
                    "got: $heldFailure",
                heldFailure.contains("genuine detach regression"),
            )
            // The naive total-count oracle would have read exactly the nightly's
            // `expected:<0> but was:<1>` at this moment.
            assertTrue(
                "the contaminating state must be genuinely present: a naive total client " +
                    "count here must be non-zero; records=${held.records}",
                held.records.isNotEmpty(),
            )

            // ---- (D) The product half: releasing the pin resumes the suppressed
            // teardown (BackgroundGraceController.onPinReleased) and the client
            // goes away. Without this the "held" arm above could be hiding a real
            // never-tears-down defect.
            val releaseAt = SystemClock.elapsedRealtime()
            forwardingController().unregisterActiveHost(LEAKED_PIN_HOST_ID)
            pinnedForwardHostId = -1L
            val released = awaitOwnedClientsDetached(
                key = key,
                sessionName = SEEDED_SESSION,
                foreignBaseline = pinnedBaseline,
                ownedNames = pinnedOwned,
            )
            recordTiming(
                "pin_release_to_orphan_cleared_ms",
                SystemClock.elapsedRealtime() - releaseAt,
            )
            writeText(
                "issue1994-forwarding-pin-mechanism.txt",
                buildString {
                    appendLine("--- (B) leaked pin isolated before close ---")
                    appendLine("owned=$ownedNames verdict=${isolated.verdict.diagnosis()}")
                    appendLine("--- (C) pin active at verdict time ---")
                    appendLine("owned=$pinnedOwned polls=${held.polls}")
                    appendLine("pin_active_at_verdict=$pinActiveAtHeldVerdict")
                    appendLine("naive_total_client_count=${held.records.size}")
                    appendLine("verdict=${held.verdict.diagnosis()}")
                    appendLine("failure=$heldFailure")
                    appendLine("raw=${held.raw}")
                    appendLine("--- (D) pin released, teardown resumes ---")
                    appendLine("polls=${released.polls} verdict=${released.verdict.diagnosis()}")
                    appendLine("raw=${released.raw}")
                },
            )
            assertTrue(
                "releasing the port-forward pin must resume the suppressed bounded teardown " +
                    "(BackgroundGraceController.onPinReleased) and clear the app's own client; " +
                    describeOwnedDetachFailure(
                        verdict = released.verdict,
                        portForwardPinActive = portForwardPinActive(),
                        sessionName = SEEDED_SESSION,
                        rawClients = released.raw,
                    ).orEmpty(),
                released.verdict.detached,
            )
            writeTimings("issue1994-forwarding-pin-timings.txt")
            Unit
        }
    }

    // ---------------------------------------------------------------- Helpers

    private fun forwardingController(): ForwardingController =
        EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            TestAccessEntryPoint::class.java,
        ).forwardingController()

    /**
     * The exact predicate `App.kt` uses for `holdWhilePinned` — true while a
     * port-forward pins the connection always-on and the bounded-grace teardown
     * is therefore SUPPRESSED (#1159 Part 3).
     */
    private fun portForwardPinActive(): Boolean =
        forwardingController().flowOfActiveHostCount().value > 0

    /** Result of the post-teardown convergence poll. */
    private data class OwnedDetachConvergence(
        val verdict: OwnedClientDetachVerdict,
        val records: List<TmuxClientRecord>,
        val raw: String,
        val polls: Int,
    )

    /**
     * Issue #1994: poll for the owned-client detach over ONE reused SSH session.
     *
     * The old loop called `SshConnection.connect(...)` on EVERY iteration, so a
     * 15 s ceiling bought only a handful of looks once a hosted runner's connect
     * cost rose — and the last look could land before the teardown had even been
     * dispatched. One session makes the poll cheap enough that the ceiling is a
     * real convergence budget rather than a handshake budget.
     */
    private suspend fun awaitOwnedClientsDetached(
        key: String,
        sessionName: String,
        foreignBaseline: Set<String>,
        ownedNames: Set<String>,
        timeoutMs: Long = ORPHAN_CLIENT_CLEANUP_TIMEOUT_MS,
    ): OwnedDetachConvergence {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var polls = 0
        var records: List<TmuxClientRecord> = emptyList()
        var raw = ""
        var verdict = evaluateOwnedClientDetach(foreignBaseline, ownedNames, records)
        val connection = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).getOrThrow()
        connection.use { session ->
            while (true) {
                polls++
                // ONE query per poll: the human-readable `raw` is rendered from
                // the SAME rows the verdict is computed on. Two separate execs
                // let the client list change between them, so a failure artifact
                // could contradict its own verdict (observed in the #1994 red
                // run: `raw` listed the app's client that the verdict had
                // already, correctly, seen detach).
                raw = session.exec(
                    "tmux list-clients -t ${shellQuote(sessionName)} " +
                        "-F ${shellQuote(TMUX_CLIENT_OWNERSHIP_FORMAT)} 2>/dev/null || true",
                ).stdout
                records = parseTmuxClients(raw)
                verdict = evaluateOwnedClientDetach(foreignBaseline, ownedNames, records)
                if (verdict.detached || SystemClock.elapsedRealtime() >= deadline) break
                SystemClock.sleep(100)
            }
        }
        return OwnedDetachConvergence(
            verdict = verdict,
            records = records,
            raw = raw,
            polls = polls,
        )
    }

    /** Poll until the app's own client(s) show up on the session. */
    private suspend fun awaitOwnedClientNames(
        key: String,
        sessionName: String,
        foreignBaseline: Set<String>,
    ): Set<String> {
        val deadline = SystemClock.elapsedRealtime() + OWNED_CLIENT_REGISTRATION_TIMEOUT_MS
        var owned = emptySet<String>()
        while (SystemClock.elapsedRealtime() < deadline) {
            owned = resolveOwnedClientNames(
                foreignBaseline = foreignBaseline,
                attachedSnapshot = listClientRecords(key, sessionName),
            )
            if (owned.isNotEmpty()) break
            SystemClock.sleep(200)
        }
        return owned
    }

    private suspend fun listClientRecords(
        key: String,
        sessionName: String,
    ): List<TmuxClientRecord> {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec(
                    "tmux list-clients -t ${shellQuote(sessionName)} " +
                        "-F ${shellQuote(TMUX_CLIENT_OWNERSHIP_FORMAT)} 2>/dev/null || true",
                )
            }
        }
        return parseTmuxClients(result.getOrNull()?.stdout.orEmpty())
    }

    private suspend fun listClientNames(key: String, sessionName: String): Set<String> =
        listClientRecords(key, sessionName).map { it.name }.toSet()

    /**
     * Attach a real, plain (non-`-CC`) tmux client over its own PTY and leave it
     * attached — the deterministic stand-in for the stray client an earlier
     * class leaves on the shared fixture session during a nightly shard. The
     * caller owns closing the returned handle.
     */
    private suspend fun attachForeignPlainClient(key: String, sessionName: String): ShellHandle {
        val handle = openShell(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
        )
        val stdin = handle.shell.outputStream
        stdin.write(
            "tmux attach-session -t ${shellQuote(sessionName)}\n".toByteArray(StandardCharsets.UTF_8),
        )
        stdin.flush()
        val deadline = SystemClock.elapsedRealtime() + OWNED_CLIENT_REGISTRATION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (listClientRecords(key, sessionName).isNotEmpty()) break
            SystemClock.sleep(200)
        }
        return handle
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedDockerHost(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        seedFlatHostDetailMode()
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue215-key-${System.currentTimeMillis()}",
                content = key,
            )
            val appVersion = targetAppVersionName()
            val now = System.currentTimeMillis()
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue215 OrphanClient",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = now,
                    pocketshellInstalled = true,
                    pocketshellLastDetectedAt = now,
                    pocketshellCliVersion = appVersion,
                    pocketshellExpectedCliVersion = appVersion,
                    pocketshellVersionCompatible = true,
                    pocketshellDaemonRunning = true,
                    pocketshellDaemonEnabled = true,
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private fun targetAppVersionName(): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        return appContext.packageManager
            .getPackageInfo(appContext.packageName, 0)
            .versionName ?: error("target app versionName is missing")
    }

    private fun seedFlatHostDetailMode() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        check(
            appContext
                .getSharedPreferences(APP_SETTINGS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(HOST_DETAIL_VIEW_MODE_PREF_KEY, HOST_DETAIL_VIEW_MODE_FLAT)
                .commit(),
        ) {
            "failed to seed flat host-detail mode"
        }
    }

    private suspend fun seedTmuxSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SEEDED_SESSION)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SEEDED_SESSION)} " +
                    "${shellQuote("printf 'ISSUE215-READY\\n'; exec sleep 600")}",
            )
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec(script) }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux seeding to succeed; exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session: ${exec?.stdout?.trim()}")
    }

    private suspend fun cleanupRemoteTmuxSession(key: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    it.exec("tmux kill-session -t ${shellQuote(SEEDED_SESSION)} 2>/dev/null || true")
                }
            }
        }
    }

    // Issue #1994 (D22 hard cut): the total-count helpers `listClientsCount` /
    // `listClientsRaw` are DELETED. Counting clients is the oracle that could
    // not tell PocketShell's orphan from a foreign sidecar or from a
    // deliberately pin-held client; leaving them here would invite the next
    // journey to reach for them again.

    private suspend fun listSessions(key: String): List<String> {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec("tmux list-sessions 2>/dev/null || true") }
        }
        return result.getOrNull()?.stdout?.lines()?.filter { it.isNotBlank() }.orEmpty()
    }

    /**
     * Issue #215: open a fresh interactive SSH session, run
     * `tmux attach-session -t <sessionName>` over a normal PTY (not
     * `-CC`), type [marker] followed by Enter, then read the resulting
     * pane content back via `tmux capture-pane -p`.
     *
     * The second client uses [openShell] (the same helper the existing
     * proof suite uses to drive raw interactive shells against the
     * fixture). We intentionally do NOT use [SshSession.exec] to fire
     * the `tmux attach` because that would not allocate a PTY and tmux
     * would refuse to attach (`not a terminal`). Going through a real
     * PTY mirrors what the maintainer's laptop does with
     * `ssh testuser@host -t -p 2222 tmux attach -t claude-main`.
     */
    private suspend fun attachAsPlainClientAndType(
        key: String,
        sessionName: String,
        marker: String,
    ): String {
        val handle = openShell(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
        )
        try {
            // Drain initial banner/prompt bytes so we can deterministically
            // detect when the next prompt is ready. We rely on the simple
            // "wait for any bytes" semantics — alpine's busybox `sh`
            // prints `~ $ ` within tens of ms of shell start.
            val stdin = handle.shell.outputStream
            stdin.write(
                ("tmux attach-session -t ${shellQuote(sessionName)}\n").toByteArray(StandardCharsets.UTF_8),
            )
            stdin.flush()
            // Give tmux a moment to attach + redraw the pane. tmux attach
            // is fast (sub-100ms locally), so 750ms is comfortable padding.
            SystemClock.sleep(750)

            // Type the marker via `echo <marker>` so the pane echoes a
            // self-contained line that's easy to grep for in capture-pane.
            // We deliberately don't drive `send-keys` over a side channel
            // — we want the keystrokes to flow through THIS client's
            // input pipe, which is the part the orphan -CC client used
            // to break.
            stdin.write(("echo $marker\n").toByteArray(StandardCharsets.UTF_8))
            stdin.flush()
            // Wait for the shell inside tmux to print the line. 1500ms is
            // generous on the CI emulator + Docker round-trip.
            SystemClock.sleep(1_500)

            // Capture the pane content from a separate exec channel — this
            // is the authoritative "what the second client sees" snapshot.
            // We use a sidecar session for capture-pane because the
            // interactive `tmux attach` session is still inside tmux's
            // event loop; running `tmux capture-pane` from it would race.
            val captureResult = SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    it.exec("tmux capture-pane -p -t ${shellQuote(sessionName)} 2>&1 || true")
                }
            }
            return captureResult.getOrNull()?.stdout.orEmpty()
        } finally {
            // Detach the second client by closing its shell channel.
            // Leaving the handle dangling would itself leave an orphan
            // client which is what the @After cleanup also guards
            // against, but explicit teardown is cheaper than relying on
            // the After path.
            runCatching { handle.shell.close() }
            runCatching { handle.sessionChannel.close() }
            runCatching { handle.client.disconnect() }
        }
    }

    private fun waitForText(text: String, timeoutMs: Long) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            launchedActivity?.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    private fun visibleTerminalText(): String {
        var text = ""
        launchedActivity?.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return text
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            bitmap = captureViewToBitmap(
                activity.window.decorView.findTerminalView(),
                name,
            )
        }
        val captured = checkNotNull(bitmap) {
            "activity was not available to capture viewport '$name' (#2135)"
        }
        writeBitmap("$name-viewport", captured)
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        captured.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE215_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE215_TEXT ${file.absolutePath}")
        return file
    }

    // Each @Test gets a fresh instance, but they all share one artifact
    // directory — so a per-test name is needed or the last writer silently wins
    // and the earlier journey's latency evidence is lost.
    private fun writeTimings(name: String = "timings.txt"): File {
        val file = artifactFile(name)
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE215_TIMINGS ${file.absolutePath}")
        return file
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE215_TIMING $line")
    }

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findTerminalView()
            if (match != null) return match
        }
        return null
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val APP_SETTINGS_PREFS_NAME: String = "app_settings"
        const val HOST_DETAIL_VIEW_MODE_PREF_KEY: String = "host_detail_view_mode"
        const val HOST_DETAIL_VIEW_MODE_FLAT: String = "Flat"
        const val LOG_TAG: String = "Issue215OrphanClient"
        const val DEVICE_DIR_NAME: String = "issue215-orphan-client-cleanup"
        // Same seeded-session name pattern as the other connected tmux
        // tests: the picker reads its session list from `tmuxctl list`,
        // which only knows the pre-baked names; `claude-main` is the
        // most exercised one across the existing suite.
        const val SEEDED_SESSION: String = "claude-main"

        /**
         * After we destroy the activity, the short injected bounded grace
         * expires and the App-level owner detaches the parked runtime. The
         * budget includes ProcessLifecycleOwner's ON_STOP debounce, grace,
         * detach-client, and swiftshader/Docker contention.
         *
         * Issue #1994: this was a FLAT 15 s while every poll iteration opened a
         * brand-new SSH connection (connect + auth + exec, ~1 s on an idle dev
         * box and several seconds on a loaded hosted runner), so the ceiling was
         * mostly a HANDSHAKE budget. Removing the per-iteration handshake (see
         * [awaitOwnedClientsDetached], which now reuses ONE session) is the real
         * fix and is what makes the ceiling a convergence budget.
         *
         * The ceiling itself is tied to the MEASURED convergence distribution
         * rather than to making a slow state eventually pass: on an idle dev box
         * `destroy_to_orphan_cleared_ms` measured 2786 ms and 6611 ms across the
         * two contiguous green runs of this class, so 15 s local is ~2.3x the
         * worst observation and 30 s CI is ~4.5x it, matching the CI scale every
         * other budget in this file uses. It is deliberately NOT larger: a pin-
         * held state (#1159 Part 3) never converges on its own, so a bigger
         * ceiling could only launder that state into a slow pass. The pin is
         * isolated before the close and named at verdict time instead.
         * `destroy_to_orphan_cleared_ms` stays in `timings.txt` so a latency
         * regression is visible rather than absorbed.
         */
        val ORPHAN_CLIENT_CLEANUP_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L

        /** Budget for tmux to register the app's own `-CC` client server-side. */
        val OWNED_CLIENT_REGISTRATION_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L

        /**
         * How long the pinned arm watches a DELIBERATELY non-converging state.
         * A port-forward pin suppresses the teardown indefinitely, so this is an
         * observation window, not a convergence budget — kept short so the arm
         * does not spend the full cleanup ceiling proving a negative. It is
         * comfortably longer than the worst measured healthy convergence
         * (6611 ms), so a client that WOULD have detached has had its chance.
         */
        val PIN_HELD_OBSERVATION_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 15_000L else 10_000L

        /** Synthetic host id for the injected always-on forwarding pin. */
        const val LEAKED_PIN_HOST_ID: Long = 199_400L

        const val POST_CLOSE_GRACE_MS: Long = 1_500L
    }
}
