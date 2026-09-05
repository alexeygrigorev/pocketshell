package com.pocketshell.next.usage

import android.os.SystemClock
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
 * fetch used. Everything from the pill tap to the rendered provider cards is
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
     * whose first paint is the compact strip only (issue #2534). Tapping a
     * compact row mounts that provider's existing card — windows, reset credits,
     * severity pill — and tapping again collapses it. Other providers stay
     * collapsed unless tapped. Severity is still DERIVED from the fixture's
     * numbers (task P-5 accept: "J12 green; glance pill renders in session
     * screen").
     */
    @Test
    fun theGlancePillOpensThePanelAndExpandsACardOnCompactRowTap() {
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
        awaitTag(USAGE_SUMMARY_STRIP_TAG, "the compact usage strip")
        awaitTag(usageSummaryRowTag("Codex"), "the Codex compact row")
        awaitTag(usageSummaryRowTag("Claude Code"), "the Claude compact row")
        awaitTag(usageSummaryRowTag("GitHub Copilot"), "the Copilot compact row")
        JourneyScreenshots.capture("02-panel-collapsed", JOURNEY)

        // First paint is the compact list. Full cards stay unmounted until the
        // matching row is tapped — the screenshot that filed #2534.
        compose.onNodeWithTag(usageProviderCardTag("codex")).assertDoesNotExist()
        compose.onNodeWithTag(usageProviderCardTag("claude")).assertDoesNotExist()
        compose.onNodeWithTag(usageProviderCardTag("copilot")).assertDoesNotExist()

        compose.onNodeWithTag(usageSummaryRowTag("Codex")).performClick()
        awaitTag(usageProviderCardTag("codex"), "the Codex provider card")
        compose.onNodeWithTag(usageProviderCardTag("codex")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(USAGE_RESET_CREDITS_SECTION_TAG).performScrollTo().assertIsDisplayed()
        assertCardHasDescendant(usageProviderCardTag("codex"), "OK")
        compose.onNodeWithTag(usageProviderCardTag("claude")).assertDoesNotExist()
        compose.onNodeWithTag(usageProviderCardTag("copilot")).assertDoesNotExist()
        JourneyScreenshots.capture("03-codex-expanded", JOURNEY)

        compose.onNodeWithTag(usageSummaryRowTag("Codex")).performScrollTo().performClick()
        compose.awaitIdle("after collapsing Codex")
        compose.onNodeWithTag(usageProviderCardTag("codex")).assertDoesNotExist()
        compose.onNodeWithTag(USAGE_RESET_CREDITS_SECTION_TAG).assertDoesNotExist()

        // Expand the other two to keep the P-5 severity proof: claude is
        // hard-blocked, copilot is approaching. Each tap is independent — Codex
        // stays collapsed unless tapped again.
        compose.onNodeWithTag(usageSummaryRowTag("Claude Code")).performScrollTo().performClick()
        awaitTag(usageProviderCardTag("claude"), "the Claude provider card")
        assertCardHasDescendant(usageProviderCardTag("claude"), "EXCEEDED")
        compose.onNodeWithTag(usageProviderCardTag("codex")).assertDoesNotExist()

        compose.onNodeWithTag(usageSummaryRowTag("GitHub Copilot")).performScrollTo().performClick()
        awaitTag(usageProviderCardTag("copilot"), "the Copilot provider card")
        assertCardHasDescendant(usageProviderCardTag("copilot"), "WARN")
    }

    /**
     * Issue #2532: Usage is a host-scoped action on the session tree, not only
     * a glance pill inside a session. Tapping Usage on the tree must open the
     * same panel.
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
        awaitTag(usageProviderCardTag("codex"), "the codex provider card")
        JourneyScreenshots.capture("04-panel-from-tree", JOURNEY)
    }

    // --- helpers ------------------------------------------------------------

    private fun assertCardHasDescendant(cardTag: String, text: String) {
        compose.onNode(hasTestTag(cardTag) and hasAnyDescendant(hasText(text)))
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
        const val JOURNEY = "j12-usage-panel"

        const val SESSION = "j12-shell"
        const val HOST_ID = 9_801L

        const val SOCKET_DIR = "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)\""
        const val SOCKET = "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)/tmuxctl-$SESSION\""
    }
}
