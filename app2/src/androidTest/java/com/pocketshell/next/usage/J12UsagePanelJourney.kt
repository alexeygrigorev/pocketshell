package com.pocketshell.next.usage

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.connect.awaitIdle
import com.pocketshell.next.hosts.hostRowTag
import com.pocketshell.next.terminal.SESSION_SCREEN_TAG
import com.pocketshell.next.tree.SESSION_TREE_TAG
import com.pocketshell.next.tree.SESSION_TREE_USAGE_TAG
import com.pocketshell.next.tree.sessionRowTag
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Journey J12 — the usage/quota panel, reached from the terminal top bar's
 * glance pill (rewrite task P-5).
 *
 * ## Why this has to be a device journey
 *
 * `UsageFetcherTest`/`UsageViewModelTest` (host JVM) drive the same parsing
 * and state-folding over a scripted connection and cannot see any of what
 * breaks here: [UsageGlanceViewModel] never firing its `ON_START` fetch on a
 * real session screen, the pill's tap target not actually landing on the
 * `usage` route, [com.pocketshell.next.MainActivity]'s Hilt-managed
 * `hiltViewModel()` graph failing to resolve [UsageFetcher]'s dependencies,
 * or the panel reading a DIFFERENT connection than the one the pill's own
 * fetch used. Everything from the pill tap to the rendered provider rows is
 * production code against a real sshd here.
 *
 * ## The canned response is the host's own answer, not a Kotlin fixture
 *
 * `pocketshell usage --json` on the `agents` Docker fixture is a small shell
 * shim (`tests/docker/agent-bin/pocketshell`) that `cat`s
 * `tests/docker/agent-fixtures/pocketshell-usage.ndjson` verbatim — the SAME
 * "canned host answer" idiom `engines list --json` and `sessions list --json`
 * already use on this fixture. The journey therefore exercises the REAL
 * `HostConnection.exec` → NDJSON parse → threshold-state → Compose pipeline;
 * only the provider CLIs a real host would additionally shell out to are
 * stubbed away, because this fixture has no live Claude/Codex/Copilot
 * credentials to begin with.
 *
 * Bring the fixture up before running (the ndjson lives in the image, so a
 * rebuild is required after editing it):
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents`
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J12UsagePanelJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(SeedBeforeLaunchRule { description -> seed(description) })
        .around(compose)

    private var hostId: Long = 0

    private suspend fun seed(description: Description) {
        val graph = appGraph()
        graph.connectionsRegistry().closeAll()
        graph.hostDao().getAll().first().forEach { graph.hostDao().deleteById(it.id) }
        graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

        val fingerprint = AgentsFixture.probeHostKeyFingerprint()
        seedTmuxSession()

        val keyPath = AgentsFixture.installPrivateKey(fileName = "j12_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j12-${description.methodName}", privateKeyPath = keyPath),
        )
        hostId = HOST_ID
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

    /** Same per-session `tmuxctl-<name>` socket convention `sessions attach` resolves. */
    private fun seedTmuxSession() {
        AgentsFixture.exec("tmux -S $SOCKET kill-session -t '=$SESSION' 2>/dev/null || true")
        AgentsFixture.exec("mkdir -p $SOCKET_DIR && chmod 700 $SOCKET_DIR")
        AgentsFixture.exec(
            "tmux -S $SOCKET new-session -d -s $SESSION -c /home/testuser -x 80 -y 24",
        )
    }

    /**
     * Opening a session shows a live glance pill, and tapping it opens a panel
     * whose first paint is quiet provider rows. Tapping a row reveals that
     * provider's windows, reset credits, and status details inline; tapping again
     * collapses it. Severity is still DERIVED from the fixture's
     * numbers (task P-5 accept: "J12 green; glance pill renders in session
     * screen").
     */
    @Test
    fun theGlancePillOpensThePanelAndExpandsAProviderRowOnCompactRowTap() {
        awaitTag(hostRowTag(hostId))
        compose.onNodeWithTag(hostRowTag(hostId)).performClick()
        awaitTag(SESSION_TREE_TAG)
        awaitTag(sessionRowTag(SESSION))
        compose.onNodeWithTag(sessionRowTag(SESSION)).performClick()
        awaitTag(SESSION_SCREEN_TAG)

        // The pill runs its OWN foreground fetch on session open (task P-5: no
        // scheduler, no cache) — waiting for it here is the assertion that the
        // pill is actually live, not a static placeholder that happens to be
        // absent.
        awaitTag(USAGE_GLANCE_PILL_TAG, "the usage glance pill")
        JourneyScreenshots.capture("01-pill", JOURNEY)

        compose.onNodeWithTag(USAGE_GLANCE_PILL_TAG).performClick()
        awaitTag(USAGE_SCREEN_TAG, "the usage panel")
        awaitTag(USAGE_PROVIDER_LIST_TAG, "the usage provider list")
        awaitTag(usageProviderRowTag("Codex"), "the Codex provider row")
        awaitTag(usageProviderRowTag("claude"), "the Claude provider row")
        awaitTag(usageProviderRowTag("copilot"), "the Copilot provider row")
        JourneyScreenshots.capture("02-panel-collapsed", JOURNEY)

        // First paint is the compact list. Provider details stay unmounted until
        // the matching row is tapped.
        compose.onNodeWithTag(usageProviderDetailsTag("codex")).assertDoesNotExist()
        compose.onNodeWithTag(usageProviderDetailsTag("claude")).assertDoesNotExist()
        compose.onNodeWithTag(usageProviderDetailsTag("copilot")).assertDoesNotExist()

        compose.onNodeWithTag(usageProviderToggleTag("codex")).performClick()
        awaitTag(usageProviderDetailsTag("codex"), "the Codex provider details")
        compose.onNodeWithTag(usageProviderDetailsTag("codex")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(USAGE_RESET_CREDITS_SECTION_TAG).performScrollTo().assertIsDisplayed()
        assertDetailsHasText(usageProviderDetailsTag("codex"), "OK")
        compose.onNodeWithTag(usageProviderDetailsTag("claude")).assertDoesNotExist()
        compose.onNodeWithTag(usageProviderDetailsTag("copilot")).assertDoesNotExist()
        JourneyScreenshots.capture("03-codex-expanded", JOURNEY)

        compose.onNodeWithTag(usageProviderToggleTag("codex")).performScrollTo().performClick()
        compose.awaitIdle("after collapsing Codex")
        compose.onNodeWithTag(usageProviderDetailsTag("codex")).assertDoesNotExist()
        compose.onNodeWithTag(USAGE_RESET_CREDITS_SECTION_TAG).assertDoesNotExist()

        // Expand the other two to keep the P-5 severity proof: claude is
        // hard-blocked, copilot is approaching. Each tap is independent — Codex
        // stays collapsed unless tapped again.
        compose.onNodeWithTag(usageProviderToggleTag("claude")).performScrollTo().performClick()
        awaitTag(usageProviderDetailsTag("claude"), "the Claude provider details")
        assertDetailsHasText(usageProviderDetailsTag("claude"), "EXCEEDED")
        compose.onNodeWithTag(usageProviderDetailsTag("codex")).assertDoesNotExist()

        compose.onNodeWithTag(usageProviderToggleTag("copilot")).performScrollTo().performClick()
        awaitTag(usageProviderDetailsTag("copilot"), "the Copilot provider details")
        assertDetailsHasText(usageProviderDetailsTag("copilot"), "WARN")
    }

    /**
     * Issue #2532: Usage is a host-scoped action on the session tree, not only
     * a glance pill inside a session. Tapping Usage on the tree must open the
     * same panel. First paint is the quiet provider list — details are unmounted
     * until the matching row is tapped.
     */
    @Test
    fun tappingUsageOnTheTreeOpensThePanel() {
        awaitTag(hostRowTag(hostId))
        compose.onNodeWithTag(hostRowTag(hostId)).performClick()
        awaitTag(SESSION_TREE_TAG)
        awaitTag(SESSION_TREE_USAGE_TAG, "the tree Usage header action")
        JourneyScreenshots.capture("03-tree-usage", JOURNEY)

        compose.onNodeWithTag(SESSION_TREE_USAGE_TAG).performClick()
        awaitTag(USAGE_SCREEN_TAG, "the usage panel from the tree")
        awaitTag(USAGE_PROVIDER_LIST_TAG, "the usage provider list")
        awaitTag(usageProviderRowTag("Codex"), "the Codex provider row")
        compose.onNodeWithTag(usageProviderDetailsTag("codex")).assertDoesNotExist()
        compose.onNodeWithTag(usageProviderToggleTag("codex")).performClick()
        awaitTag(usageProviderDetailsTag("codex"), "the Codex provider details")
        JourneyScreenshots.capture("04-panel-from-tree", JOURNEY)
    }

    /**
     * Issue #2579: the pill on the SESSION screen is about THAT session's
     * agent, not about the worst provider on the box.
     *
     * The fixture's `pocketshell usage --json` reports Claude blocked with its
     * 5h bucket at 98% used and its 7d one at 60%, and that 98% is also the
     * worst reading anywhere — so the pre-#2579 pill on this screen reads
     * "Claude 5h 98%". The focused pill must instead read "Claude 60%":
     * Claude's LONGEST window, no window token. The two strings differ in both
     * the number and the token, so a fallback cannot pass this test.
     *
     * ## The session is a REAL aplexer session running a REAL `claude`
     *
     * [startRealAplexerSessionRunningClaude] does, over the same SSH the app
     * uses, exactly what the maintainer's box does: it starts an aplexer
     * session (through the host CLI, `--backend aplexer`, which is a plain
     * shell workload — `engine` is NOT the answer to "which agent"), then
     * launches an agent INSIDE it. The agent is a `claude`-named executable
     * that stays alive, so the workload has a live descendant whose `comm` and
     * `argv[0]` are both `claude` — the state aplexer's process-tree walker
     * classifies. `a capture --screen --plain` is the independent oracle that
     * the launch line really ran in that session.
     *
     * The image's own `/usr/local/bin/claude` is deliberately NOT used: it
     * prints one line and exits, so it leaves no descendant to detect.
     *
     * ## This journey is expected RED until #2581 + #2586 land
     *
     * Two things must ship before it can pass, and it fails on the FIRST of
     * them by name rather than degrading into a silent fallback:
     *
     *  1. `a` must report the detected agent (aplexer 0.1.4, issue #2580) and
     *     the host CLI must pass it through as `agent` on the schema-2 row
     *     (issue #2581, which also bumps `tools/pocketshell/pyproject.toml`'s
     *     `aplexer==` pin — the same line `Dockerfile.agents` derives the
     *     fixture's binary download from, so the pin bump is what re-points
     *     this fixture at a detecting `a`). Today the row carries no `agent`
     *     key at all.
     *  2. The fixture's `sessions list --json` arm must ENUMERATE live aplexer
     *     sessions instead of reading `~/.pocketshell-fixture-aplexer.json`
     *     (issue #2586; the shim says so itself). Until then the app
     *     cannot see this session, so the UI half below cannot run either —
     *     which is why the host-contract assertion comes first and is the one
     *     that fails.
     *
     * Nothing here is stubbed to route around either gap: a journey that
     * seeded the `agent` value itself would prove only that the client can
     * read a value the journey wrote, which is what the JVM suite already
     * proves without an emulator.
     */
    @Test
    // Red by design today; the KDoc above names both blockers and what turns
    // each green. Quarantined per D36(4) so it cannot freeze `main`'s journey
    // lane while it waits — one line, because the reconciler parses it.
    @Ignore("quarantined: #2586, expires 2026-09-20 — fixture lists no live aplexer row")
    fun theSessionPillFocusesTheSessionsDetectedAgentAndDropsTheWindowToken() {
        val sessionName = startRealAplexerSessionRunningClaude()
        try {
            assertHostReportsTheDetectedAgent(sessionName)

            awaitTag(hostRowTag(hostId))
            compose.onNodeWithTag(hostRowTag(hostId)).performClick()
            awaitTag(SESSION_TREE_TAG)
            awaitTag(sessionRowTag(sessionName), "the live aplexer session's row")
            compose.onNodeWithTag(sessionRowTag(sessionName)).performClick()
            awaitTag(SESSION_SCREEN_TAG)

            awaitTag(USAGE_GLANCE_PILL_TAG, "the usage glance pill")
            JourneyScreenshots.capture("05-focused-pill", JOURNEY)

            val description = pillContentDescription()
            assertEquals(
                "the session's pill must be attributed to the agent the host " +
                    "detected for THIS session, at that provider's longest " +
                    "window, with no window token (issue #2579)",
                "Usage Claude $CLAUDE_LONGEST_WINDOW_PERCENT%",
                description,
            )
            // Spelled out separately so a failure says WHICH half broke: a pill
            // that fell back to the cross-provider answer carries "5h" here.
            for (token in listOf("5h", "7d", "weekly", "monthly")) {
                assertFalse(
                    "the focused pill must carry no window token, got: $description",
                    description.contains(token),
                )
            }
        } finally {
            killAgentSession(sessionName)
        }
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Starts a real aplexer session with a live `claude` process inside it and
     * returns the listing name the host gave it.
     *
     * The launch line is SENT into the running session rather than passed as
     * the session's own command, because that is the shape the detection has to
     * cope with: aplexer starts a shell, the agent appears later as one of its
     * descendants, and `engine` never mentions it.
     */
    private fun startRealAplexerSessionRunningClaude(): String {
        // A `claude`-NAMED executable, so both `comm` and `argv[0]` say
        // "claude" for the live process. A shell script would report its
        // interpreter (`sh`) as `comm`, which is not the state being detected.
        AgentsFixture.exec(
            "mkdir -p $AGENT_BIN_DIR $AGENT_WORKSPACE && " +
                "ln -sf /bin/sleep $AGENT_BIN",
        )
        killAgentSession(aplexerSessionName())

        val created = AgentsFixture.exec(
            "pocketshell sessions create $AGENT_TAG --backend aplexer " +
                "--cwd $AGENT_WORKSPACE --json",
        )
        val envelope = JSONObject(created)
        assertEquals(
            "the fixture must create this session on aplexer, not tmux: $created",
            "aplexer",
            envelope.optString("manager"),
        )
        val name = envelope.optString("name")
        assertTrue("the create envelope carried no name: $created", name.isNotEmpty())

        AgentsFixture.exec(
            "a send --workspace $AGENT_WORKSPACE --tag $AGENT_TAG --enter " +
                "'$AGENT_BIN $AGENT_LIFETIME_SECONDS'",
        )

        // Independent oracle, on aplexer's own rendering of the live screen:
        // the launch line really reached THIS session. Without it, a `send`
        // that silently went nowhere would look identical to "the agent is
        // running but was not detected".
        awaitCondition("the launch line to appear on the session's screen") {
            AgentsFixture.exec(
                "a capture --screen --plain --workspace $AGENT_WORKSPACE --tag $AGENT_TAG",
            ).contains(AGENT_BIN)
        }
        // ...and the process is actually alive, so there IS something to detect.
        awaitCondition("a live `claude` process under the session's workload") {
            AgentsFixture.exec("pgrep -f '$AGENT_BIN ' >/dev/null && echo live || echo none")
                .trim() == "live"
        }
        return name
    }

    /**
     * The load-bearing host-contract assertions, run BEFORE any UI, in the
     * order the two blockers have to fall.
     *
     * They are what keep this journey honest. Without them a host that reports
     * no `agent` would leave the pill on its cross-provider answer, and the
     * failure would read as an unrelated UI problem instead of naming the pin
     * that has to move.
     */
    private fun assertHostReportsTheDetectedAgent(sessionName: String) {
        // 1. The CLI CONTRACT, on the unshimmed host CLI — the thing #2581
        //    changes. This is the assertion that fails today: `a` cannot yet
        //    report what is running inside a session, so the row has no
        //    `agent` at all.
        val realRow = requireNotNull(
            aplexerRow(realCliListing(), sessionName),
        ) { "the real host CLI did not list its own live aplexer session $sessionName" }
        assertEquals(
            "the host CLI must report the agent detected inside this session. " +
                "The row carries `engine` but no usable `agent`, which means " +
                "the fixture's `a` predates aplexer's process-tree detection " +
                "(#2580), or the CLI does not pass it through (#2581 — whose " +
                "`aplexer==` pin bump in tools/pocketshell/pyproject.toml is " +
                "the same line Dockerfile.agents derives the fixture's binary " +
                "download from, so that bump is what re-points this fixture " +
                "at a detecting `a`). Until then the pill falls back to the " +
                "cross-provider answer and this journey proves nothing." +
                "\nRow:\n$realRow",
            "claude",
            realRow.optString("agent", ""),
        )

        // 2. The APP-FACING listing: the exact command the client runs. On the
        //    fixture this is the deterministic shim, whose list arm still reads
        //    aplexer rows from a seed file rather than enumerating live ones.
        val appListing = AgentsFixture.exec("pocketshell sessions list --json")
        val appRow = aplexerRow(appListing, sessionName)
        assertNotNull(
            "`pocketshell sessions list --json` — the command the app runs — " +
                "did not list the live aplexer session $sessionName. The " +
                "fixture's list arm still reads aplexer rows from " +
                "~/.pocketshell-fixture-aplexer.json instead of enumerating " +
                "them (issue #2586), so the app cannot see a real " +
                "aplexer session yet.\nHost listing:\n$appListing",
            appRow,
        )
        assertEquals(
            "the app-facing listing must carry the same detected agent the " +
                "host CLI reports.\nRow:\n$appRow",
            "claude",
            appRow!!.optString("agent", ""),
        )
    }

    /**
     * The REAL host CLI's own enumeration, run the way
     * `tests/docker/agents-aplexer-selfcheck.py` runs it — `python3 -m
     * pocketshell` off `/opt/pocketshell-real/src`, never the deterministic
     * `agent-bin` shim, which would be asserting a fixture against itself.
     */
    private fun realCliListing(): String = AgentsFixture.exec(
        "PYTHONPATH=$REAL_CLI_SRC python3 -m pocketshell sessions list --json",
    )

    private fun aplexerRow(listing: String, sessionName: String): JSONObject? {
        val rows = JSONObject(listing).getJSONArray("sessions")
        return (0 until rows.length())
            .map { rows.getJSONObject(it) }
            .firstOrNull { it.optString("name") == sessionName }
    }

    /** Best-effort teardown; a leaked aplexer record blocks the next create. */
    private fun killAgentSession(name: String) {
        AgentsFixture.exec("pocketshell sessions kill '$name' --json >/dev/null 2>&1 || true")
    }

    /** The listing name aplexer gives `AGENT_WORKSPACE` + `AGENT_TAG`. */
    private fun aplexerSessionName(): String =
        "${AGENT_WORKSPACE.substringAfterLast('/')}:$AGENT_TAG"

    /** The pill's own accessibility text — "Usage Claude 60%". */
    private fun pillContentDescription(): String {
        val node = compose.onNodeWithTag(USAGE_GLANCE_PILL_TAG).fetchSemanticsNode()
        return node.config[SemanticsProperties.ContentDescription].joinToString(" ")
    }

    /**
     * Polls a HOST-side [condition] to the journey's deadline, then fails
     * saying [what]. A slower cadence than [awaitTag]'s: each probe is its own
     * SSH connection, not a semantics-tree read.
     */
    private fun awaitCondition(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(SSH_POLL_MS)
        }
        throw AssertionError("$what never happened within ${TIMEOUT_MS}ms")
    }

    private fun assertDetailsHasText(detailsTag: String, text: String) {
        // Quiet details expose a single status line such as "Status · OK";
        // the contract is the status token, not a legacy standalone card
        // label. Match that token within the real line and keep the check
        // usable with the merged semantics exposed on device.
        compose.onNode(
            hasTestTag(detailsTag) and
                hasAnyDescendant(hasText(text, substring = true, ignoreCase = true)),
            useUnmergedTree = true,
        )
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * Waits for [tag], and on timeout says WHY rather than just "condition not
     * satisfied after 60000 ms" (same discipline as J04/J07).
     */
    private fun awaitTag(tag: String, what: String = tag) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("tag poll: $what")
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-${what.replace(' ', '-')}", JOURNEY)
        throw AssertionError(
            "$what never appeared within ${TIMEOUT_MS}ms.\n" +
                "The host's fixture usage answer is:\n" +
                AgentsFixture.exec("pocketshell usage --json 2>&1 || true") + "\n" +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 250L
        const val SSH_POLL_MS = 1_000L

        /** Where the image keeps the real, unshimmed host CLI. */
        const val REAL_CLI_SRC = "/opt/pocketshell-real/src"
        const val JOURNEY = "j12-usage-panel"

        const val SESSION = "j12-shell"
        const val HOST_ID = 9_801L

        /**
         * The real aplexer session the focused-pill test drives (#2579).
         *
         * Its own workspace, so it can never collide with the canned fixture
         * rows or with another journey's aplexer session — aplexer keys a
         * session by workspace + tag.
         */
        const val AGENT_TAG = "j12-agent"
        const val AGENT_WORKSPACE = "/home/testuser/j12-agent-ws"
        const val AGENT_BIN_DIR = "/home/testuser/j12-agent-bin"
        const val AGENT_BIN = "$AGENT_BIN_DIR/claude"

        /** Long enough to outlive the journey, short enough to self-reap. */
        const val AGENT_LIFETIME_SECONDS = 600

        /**
         * Claude's LONGEST window in `agent-fixtures/pocketshell-usage.ndjson`:
         * `7d` at `percent_remaining: 40.0`, i.e. 60% used. Its `5h` bucket is
         * at 98% and is the worst reading on the whole fixture, which is what
         * the pre-#2579 pill shows — the two answers cannot collide.
         */
        const val CLAUDE_LONGEST_WINDOW_PERCENT = 60

        const val SOCKET_DIR = "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)\""
        const val SOCKET = "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)/tmuxctl-$SESSION\""
    }
}
