package com.pocketshell.next.usage

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.model.PillKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [UsageGlanceViewModel] — the terminal top bar's pill (task P-5). Pins that
 * it starts absent (no fetch has run), and that a real fetch round turns into
 * the [UsageGlancePillState] the pill paints.
 *
 * Issue #2579 added the session-scoped half: given a host and a session name,
 * the round ALSO reads `pocketshell sessions list --json` over the connection
 * the screen already holds and focuses the pill on that session's
 * aplexer-detected agent. Every listing shape the host can answer with is
 * driven here through the REAL [com.pocketshell.core.hostapi.HostCliClient]
 * over a scripted connection — the parse is not stubbed, so a wire-format
 * mistake fails here rather than on a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class UsageGlanceViewModelTest {

    private var stack: TestUsageStack? = null

    @After
    fun tearDown() {
        stack?.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `before any refresh the pill is absent, not a placeholder`() = vmTest { _ ->
        val viewModel = viewModel(stack!!)

        assertNull(viewModel.state.value)
    }

    @Test
    fun `a near-limit reading surfaces as a Warn pill with the right percent`() = vmTest { stack ->
        val hostId = stack.seedHost("codex-box")
        stack.scriptUsage(CODEX_NEAR_LIMIT_NDJSON)
        stack.connect(hostId)
        val viewModel = viewModel(stack)

        viewModel.refresh()
        runCurrent()

        val pill = viewModel.state.value
        checkNotNull(pill) { "expected a pill state after a connected host's fetch" }
        assertEquals(91, pill.percent)
        assertEquals("Codex", pill.provider)
        assertEquals(PillKind.Warn, pill.kind)
    }

    @Test
    fun `no connected hosts leaves the pill absent`() = vmTest { stack ->
        stack.seedHost()
        val viewModel = viewModel(stack)

        viewModel.refresh()
        runCurrent()

        assertNull(viewModel.state.value)
    }

    // --- session-focused pill (issue #2579) -------------------------------

    /**
     * The maintainer's shape: an aplexer session whose workload is running
     * Claude Code, on a host whose worst provider is Grok. Before #2579 the
     * pill read "Grok 7d 83%" on that screen; it must now read "Claude 38%"
     * — Claude's LONGEST window, no window token.
     */
    @Test
    fun `an aplexer session with a detected agent focuses the pill on that agent`() =
        vmTest { stack ->
            val hostId = stack.seedHost("claude-box")
            stack.scriptUsage(CLAUDE_AND_GROK_NDJSON)
            stack.scriptSessions(sessionsListing(APLEXER_CLAUDE_ROW))
            stack.connect(hostId)
            val viewModel = viewModel(stack)

            viewModel.refresh(hostId = hostId, sessionName = SESSION)
            runCurrent()

            val pill = viewModel.state.value
            checkNotNull(pill) { "expected a pill state after a connected host's fetch" }
            assertEquals("Claude", pill.provider)
            assertEquals(38, pill.percent)
            assertNull(pill.window)
            assertEquals("Claude 38%", pill.label)
        }

    /**
     * Same host, same usage numbers, same session name — but the row is tmux.
     * tmux cannot see inside a session, so there is nothing to focus on and the
     * pill keeps its cross-provider meaning.
     */
    @Test
    fun `a tmux row yields the old worst-provider pill`() = vmTest { stack ->
        val hostId = stack.seedHost("claude-box")
        stack.scriptUsage(CLAUDE_AND_GROK_NDJSON)
        stack.scriptSessions(sessionsListing(TMUX_ROW))
        stack.connect(hostId)
        val viewModel = viewModel(stack)

        viewModel.refresh(hostId = hostId, sessionName = SESSION)
        runCurrent()

        assertEquals("Grok 7d 83%", viewModel.state.value?.label)
    }

    /** An aplexer session with no agent detected is likewise unfocused. */
    @Test
    fun `an aplexer row with a null agent yields the old worst-provider pill`() =
        vmTest { stack ->
            val hostId = stack.seedHost("claude-box")
            stack.scriptUsage(CLAUDE_AND_GROK_NDJSON)
            stack.scriptSessions(sessionsListing(APLEXER_NO_AGENT_ROW))
            stack.connect(hostId)
            val viewModel = viewModel(stack)

            viewModel.refresh(hostId = hostId, sessionName = SESSION)
            runCurrent()

            assertEquals("Grok 7d 83%", viewModel.state.value?.label)
        }

    /**
     * A host CLI old enough not to emit `agent` at all (the state of every
     * installed host until #2581 ships). It must be indistinguishable from
     * "no agent detected" — never a crash, never a missing pill.
     */
    @Test
    fun `a host CLI that never emits the agent key yields the old pill`() = vmTest { stack ->
        val hostId = stack.seedHost("claude-box")
        stack.scriptUsage(CLAUDE_AND_GROK_NDJSON)
        stack.scriptSessions(sessionsListing(APLEXER_OLD_CLI_ROW))
        stack.connect(hostId)
        val viewModel = viewModel(stack)

        viewModel.refresh(hostId = hostId, sessionName = SESSION)
        runCurrent()

        assertEquals("Grok 7d 83%", viewModel.state.value?.label)
    }

    /**
     * The listing is a decoration on top of numbers already in hand. A failed
     * listing therefore costs the FOCUS, not the pill — dropping the whole pill
     * because a second read failed would be a worse regression than showing the
     * cross-provider number (this is issue #2532's lesson, re-applied).
     */
    @Test
    fun `a failed sessions listing still leaves the old pill, not an absent one`() =
        vmTest { stack ->
            val hostId = stack.seedHost("claude-box")
            stack.scriptUsage(CLAUDE_AND_GROK_NDJSON)
            stack.scriptSessions(stdout = "", exitCode = 127, stderr = "sh: pocketshell: not found")
            stack.connect(hostId)
            val viewModel = viewModel(stack)

            viewModel.refresh(hostId = hostId, sessionName = SESSION)
            runCurrent()

            assertEquals("Grok 7d 83%", viewModel.state.value?.label)
        }

    /** A session name that is not in the listing at all is simply not focusable. */
    @Test
    fun `a session the host does not list yields the old pill`() = vmTest { stack ->
        val hostId = stack.seedHost("claude-box")
        stack.scriptUsage(CLAUDE_AND_GROK_NDJSON)
        stack.scriptSessions(sessionsListing(APLEXER_CLAUDE_ROW))
        stack.connect(hostId)
        val viewModel = viewModel(stack)

        viewModel.refresh(hostId = hostId, sessionName = "some-other-session")
        runCurrent()

        assertEquals("Grok 7d 83%", viewModel.state.value?.label)
    }

    /**
     * The tree's Usage affordance keeps calling the no-argument overload, and
     * must keep the cross-provider meaning even when the host WOULD have
     * reported a focusable agent.
     */
    @Test
    fun `the no-argument refresh never focuses, even when the host could`() = vmTest { stack ->
        val hostId = stack.seedHost("claude-box")
        stack.scriptUsage(CLAUDE_AND_GROK_NDJSON)
        stack.scriptSessions(sessionsListing(APLEXER_CLAUDE_ROW))
        stack.connect(hostId)
        val viewModel = viewModel(stack)

        viewModel.refresh()
        runCurrent()

        assertEquals("Grok 7d 83%", viewModel.state.value?.label)
    }

    /**
     * D21: the pill never dials. A host the registry holds no live connection
     * for is not asked for a listing, so the focus is absent — and, with no
     * connection, so is every reading, so the pill stays hidden.
     */
    @Test
    fun `a disconnected host is never dialed just to resolve a focus`() = vmTest { stack ->
        val hostId = stack.seedHost("claude-box")
        stack.scriptUsage(CLAUDE_AND_GROK_NDJSON)
        stack.scriptSessions(sessionsListing(APLEXER_CLAUDE_ROW))
        val viewModel = viewModel(stack)

        viewModel.refresh(hostId = hostId, sessionName = SESSION)
        runCurrent()

        assertNull(viewModel.state.value)
        assertEquals(0, stack.factory.dialCount)
    }

    // --- helpers -----------------------------------------------------------

    private fun viewModel(stack: TestUsageStack) = UsageGlanceViewModel(
        fetcher = stack.fetcher,
        connections = stack.registry,
        clients = stack.clients,
    )

    private fun vmTest(body: suspend TestScope.(TestUsageStack) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val stack = TestUsageStack()
        this@UsageGlanceViewModelTest.stack = stack
        body(stack)
    }

    /** A schema-2 `sessions list --json` document holding exactly [row]. */
    private fun sessionsListing(row: String): String =
        """{"schema": 2, "managers": ["tmux", "aplexer"], "sessions": [$row], "errors": []}"""

    private companion object {
        // percent_remaining 9 -> 91% used, above WARN_PERCENT(85) and below
        // CRITICAL_PERCENT(95) -> Approaching -> the pill's Warn kind.
        const val CODEX_NEAR_LIMIT_NDJSON =
            "{\"provider\":\"codex\",\"status\":\"ok\"," +
                "\"windows\":{\"7d\":{\"percent_remaining\":9.0,\"reset_at\":null}}," +
                "\"block_reason\":null,\"error\":null,\"details\":{}}"

        const val SESSION = "git-pocketshell"

        /**
         * The maintainer's numbers (#2579): Claude 5h at 40% used and 7d at
         * 38% used, on a box whose Grok 7d is the worst reading anywhere. The
         * unfocused pill is therefore "Grok 7d 83%" and the focused one is
         * "Claude 38%" — the two answers can never be confused.
         */
        const val CLAUDE_AND_GROK_NDJSON =
            "{\"provider\":\"claude\",\"status\":\"ok\",\"windows\":{" +
                "\"5h\":{\"percent_remaining\":60.0,\"reset_at\":null}," +
                "\"7d\":{\"percent_remaining\":62.0,\"reset_at\":null}}," +
                "\"block_reason\":null,\"error\":null,\"details\":{}}\n" +
                "{\"provider\":\"grok\",\"status\":\"ok\",\"windows\":{" +
                "\"7d\":{\"percent_remaining\":17.0,\"reset_at\":null}}," +
                "\"block_reason\":null,\"error\":null,\"details\":{}}"

        /**
         * `engine` says "shell" and `agent` says "claude" — the real shape of
         * every aplexer session on the maintainer's box, and the reason the
         * client cannot answer this question from `engine`.
         */
        const val APLEXER_CLAUDE_ROW =
            "{\"name\": \"$SESSION\", \"manager\": \"aplexer\", \"attached\": true, " +
                "\"engine\": \"shell\", \"agent\": \"claude\"}"

        const val APLEXER_NO_AGENT_ROW =
            "{\"name\": \"$SESSION\", \"manager\": \"aplexer\", \"attached\": true, " +
                "\"engine\": \"shell\", \"agent\": null}"

        /** No `agent` key at all: a host CLI predating #2581. */
        const val APLEXER_OLD_CLI_ROW =
            "{\"name\": \"$SESSION\", \"manager\": \"aplexer\", \"attached\": true, " +
                "\"engine\": \"shell\"}"

        /** tmux never reports an agent, so this row carries the key as null. */
        const val TMUX_ROW =
            "{\"name\": \"$SESSION\", \"manager\": \"tmux\", \"attached\": true, " +
                "\"agent\": null}"
    }
}
