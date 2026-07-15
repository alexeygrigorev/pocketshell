package com.pocketshell.app.tmux

import com.pocketshell.core.agents.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1584: the composer must classify slash-commands per-agent, not
 * grammar-only. `/goal resume` (Codex) and every other TEXT slash-command must
 * route as agent payload (echoed, transcript turn) — NOT be misclassified as a
 * TUI-only alt-screen picker that suppresses the echo and raises a bogus
 * "Open in Terminal" notice.
 *
 * Load-bearing decision under test: [tmuxAgentConversationSend]. On base (the
 * grammar-only path) EVERY `/word …` returned [TmuxAgentConversationSend.TuiCommandNoEcho];
 * the fix routes only the genuine per-agent pickers there and everything else
 * to [TmuxAgentConversationSend.Echo].
 */
class Issue1584TuiClassificationTest {

    // --- The reported defect: /goal resume on Codex must be TEXT, not TUI. ---

    @Test
    fun goalResumeRoutesAsTextNotTui_codex() {
        // The maintainer's exact reported scenario (#1577 §5): `/goal resume` to
        // a Codex conversation. Base misclassified it TuiCommandNoEcho (no echo +
        // bogus notice); the fix routes it as Echo (agent payload + transcript).
        assertEquals(
            TmuxAgentConversationSend.Echo,
            tmuxAgentConversationSend("/goal resume", AgentKind.Codex),
        )
    }

    @Test
    fun goalBareRoutesAsTextNotTui_codex() {
        assertEquals(
            TmuxAgentConversationSend.Echo,
            tmuxAgentConversationSend("/goal ship the release", AgentKind.Codex),
        )
    }

    // --- Genuine pickers STILL raise the notice (regression guard). ---

    @Test
    fun modelPickerStillTui_codex() {
        assertEquals(
            TmuxAgentConversationSend.TuiCommandNoEcho,
            tmuxAgentConversationSend("/model", AgentKind.Codex),
        )
    }

    // --- Class coverage (D32 G2): text commands vs pickers, per agent kind. ---

    @Test
    fun textCommandsRouteAsEcho_acrossAgents() {
        // TEXT slash-commands that reach the agent and produce transcript output.
        // `/goal`/`/goal resume` exist for Claude + Codex (not OpenCode); other
        // text commands are checked per agent's catalog.
        val cases = listOf(
            Triple(AgentKind.ClaudeCode, "/goal", "goal (Claude)"),
            Triple(AgentKind.ClaudeCode, "/goal resume", "goal resume (Claude)"),
            Triple(AgentKind.ClaudeCode, "/compact", "compact (Claude)"),
            Triple(AgentKind.ClaudeCode, "/review", "review (Claude)"),
            Triple(AgentKind.Codex, "/goal", "goal (Codex)"),
            Triple(AgentKind.Codex, "/goal resume", "goal resume (Codex)"),
            Triple(AgentKind.Codex, "/diff", "diff (Codex)"),
            Triple(AgentKind.Codex, "/compact", "compact (Codex)"),
            Triple(AgentKind.OpenCode, "/compact", "compact (OpenCode)"),
            Triple(AgentKind.OpenCode, "/export", "export (OpenCode)"),
            Triple(AgentKind.OpenCode, "/goal", "goal (OpenCode — not a command, text)"),
        )
        for ((agent, text, label) in cases) {
            assertEquals(
                "$label must route as Echo (agent text payload)",
                TmuxAgentConversationSend.Echo,
                tmuxAgentConversationSend(text, agent),
            )
        }
    }

    @Test
    fun genuinePickersRouteAsTuiNoEcho_acrossAgents() {
        val cases = listOf(
            Triple(AgentKind.ClaudeCode, "/model", "model (Claude)"),
            Triple(AgentKind.ClaudeCode, "/config", "config (Claude)"),
            Triple(AgentKind.ClaudeCode, "/permissions", "permissions (Claude)"),
            Triple(AgentKind.ClaudeCode, "/resume", "resume picker (Claude)"),
            Triple(AgentKind.Codex, "/model", "model (Codex)"),
            Triple(AgentKind.Codex, "/approvals", "approvals (Codex)"),
            Triple(AgentKind.OpenCode, "/models", "models (OpenCode)"),
            Triple(AgentKind.OpenCode, "/sessions", "sessions (OpenCode)"),
        )
        for ((agent, text, label) in cases) {
            assertEquals(
                "$label must route as TuiCommandNoEcho (picker)",
                TmuxAgentConversationSend.TuiCommandNoEcho,
                tmuxAgentConversationSend(text, agent),
            )
        }
    }

    @Test
    fun pickerAllowlistIsPerAgent() {
        // `/config` is a Claude picker but NOT a Codex/OpenCode command → for
        // those agents it is unknown text (Echo), never a bogus notice. This is
        // the per-agent-blindness the grammar-only path could not express.
        assertEquals(
            TmuxAgentConversationSend.TuiCommandNoEcho,
            tmuxAgentConversationSend("/config", AgentKind.ClaudeCode),
        )
        assertEquals(
            TmuxAgentConversationSend.Echo,
            tmuxAgentConversationSend("/config", AgentKind.Codex),
        )
        // `/sessions` is an OpenCode picker but not a Claude command.
        assertEquals(
            TmuxAgentConversationSend.TuiCommandNoEcho,
            tmuxAgentConversationSend("/sessions", AgentKind.OpenCode),
        )
        assertEquals(
            TmuxAgentConversationSend.Echo,
            tmuxAgentConversationSend("/sessions", AgentKind.ClaudeCode),
        )
    }

    @Test
    fun caseInsensitivePickerAndArgumentTrimmed() {
        // Roots are matched case-insensitively and args are ignored.
        assertEquals(
            TmuxAgentConversationSend.TuiCommandNoEcho,
            tmuxAgentConversationSend("/Model sonnet", AgentKind.ClaudeCode),
        )
        assertEquals(
            TmuxAgentConversationSend.TuiCommandNoEcho,
            tmuxAgentConversationSend("  /model  ", AgentKind.ClaudeCode),
        )
    }

    // --- Non-command / edge inputs stay Echo (never a bogus notice). ---

    @Test
    fun promptsPathsAndMultilineRouteAsEcho() {
        val agent = AgentKind.ClaudeCode
        assertEquals(TmuxAgentConversationSend.Echo, tmuxAgentConversationSend("explain this diff", agent))
        assertEquals(TmuxAgentConversationSend.Echo, tmuxAgentConversationSend("", agent))
        assertEquals(TmuxAgentConversationSend.Echo, tmuxAgentConversationSend("/", agent))
        assertEquals(TmuxAgentConversationSend.Echo, tmuxAgentConversationSend("/home/user/file.txt", agent))
        assertEquals(TmuxAgentConversationSend.Echo, tmuxAgentConversationSend("/2 + 2", agent))
        // A multi-line message is a prompt, even if line 1 looks like a picker.
        assertEquals(TmuxAgentConversationSend.Echo, tmuxAgentConversationSend("/model\nand also do this", agent))
    }

    @Test
    fun unknownAgentRoutesAsEcho() {
        // Without a resolved agent kind we cannot know if a `/word` is a picker,
        // so the safe default is Echo (text) — never a bogus notice.
        assertEquals(
            TmuxAgentConversationSend.Echo,
            tmuxAgentConversationSend("/model", null),
        )
    }
}
