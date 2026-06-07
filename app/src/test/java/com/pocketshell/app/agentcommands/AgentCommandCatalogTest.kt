package com.pocketshell.app.agentcommands

import com.pocketshell.core.agents.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AgentCommandCatalog] (issue #436, Slice A). Verify the
 * per-agent filtering, the curated-first ordering, and the agent-specific
 * availability nuances called out in the spike (notably: OpenCode has no
 * `/goal`; Claude has both new/clear and goal; alias pairs collapse to one
 * row).
 */
class AgentCommandCatalogTest {

    @Test
    fun `every agent kind has a non-empty catalog`() {
        AgentKind.entries.forEach { agent ->
            assertTrue(
                "expected commands for $agent",
                AgentCommandCatalog.commandsFor(agent).isNotEmpty(),
            )
        }
    }

    @Test
    fun `command text is unique within each agent catalog`() {
        AgentKind.entries.forEach { agent ->
            val commands = AgentCommandCatalog.commandsFor(agent).map { it.command }
            assertEquals(
                "duplicate command rows for $agent",
                commands.size,
                commands.toSet().size,
            )
        }
    }

    @Test
    fun `every command starts with a slash`() {
        AgentKind.entries.forEach { agent ->
            AgentCommandCatalog.commandsFor(agent).forEach { command ->
                assertTrue(
                    "command ${command.command} for $agent must start with /",
                    command.command.startsWith("/"),
                )
            }
        }
    }

    @Test
    fun `openCode has no goal command`() {
        val hasGoal = AgentCommandCatalog.commandsFor(AgentKind.OpenCode)
            .any { it.command == "/goal" }
        assertFalse("OpenCode must not offer /goal", hasGoal)
    }

    @Test
    fun `claude and codex both have goal command`() {
        listOf(AgentKind.ClaudeCode, AgentKind.Codex).forEach { agent ->
            val hasGoal = AgentCommandCatalog.commandsFor(agent)
                .any { it.command == "/goal" }
            assertTrue("$agent must offer /goal", hasGoal)
        }
    }

    @Test
    fun `claude curated set leads with new-clear compact goal rewind`() {
        val curated = AgentCommandCatalog.commandsFor(AgentKind.ClaudeCode)
            .take(4)
            .map { it.command }
        assertEquals(listOf("/clear", "/compact", "/goal", "/rewind"), curated)
    }

    @Test
    fun `codex curated set leads with new compact goal diff`() {
        val curated = AgentCommandCatalog.commandsFor(AgentKind.Codex)
            .take(4)
            .map { it.command }
        assertEquals(listOf("/new", "/compact", "/goal", "/diff"), curated)
    }

    @Test
    fun `openCode curated set leads with new compact sessions undo`() {
        val curated = AgentCommandCatalog.commandsFor(AgentKind.OpenCode)
            .take(4)
            .map { it.command }
        assertEquals(listOf("/new", "/compact", "/sessions", "/undo"), curated)
    }

    @Test
    fun `claude collapses new and clear into a single row`() {
        // Claude's /clear aliases /new and /reset — surfacing each as its own
        // row would confuse, so the catalog carries only /clear (not /new).
        val commands = AgentCommandCatalog.commandsFor(AgentKind.ClaudeCode)
            .map { it.command }
        assertTrue("Claude must have /clear", commands.contains("/clear"))
        assertFalse("Claude must not also have /new", commands.contains("/new"))
    }

    @Test
    fun `openCode collapses new and clear into a single row`() {
        // OpenCode's /new and /clear are aliases of each other.
        val commands = AgentCommandCatalog.commandsFor(AgentKind.OpenCode)
            .map { it.command }
        assertTrue("OpenCode must have /new", commands.contains("/new"))
        assertFalse("OpenCode must not also have /clear", commands.contains("/clear"))
    }

    @Test
    fun `destructive flag is set for context-clearing commands`() {
        val claudeClear = AgentCommandCatalog.commandsFor(AgentKind.ClaudeCode)
            .first { it.command == "/clear" }
        assertTrue("/clear must be destructive", claudeClear.destructive)

        val codexNew = AgentCommandCatalog.commandsFor(AgentKind.Codex)
            .first { it.command == "/new" }
        assertTrue("/new must be destructive", codexNew.destructive)

        val claudeCompact = AgentCommandCatalog.commandsFor(AgentKind.ClaudeCode)
            .first { it.command == "/compact" }
        assertFalse("/compact must not be destructive", claudeCompact.destructive)
    }

    @Test
    fun `goal requires an argument where available`() {
        listOf(AgentKind.ClaudeCode, AgentKind.Codex).forEach { agent ->
            val goal = AgentCommandCatalog.commandsFor(agent)
                .first { it.command == "/goal" }

            assertTrue("$agent /goal should require text", goal.argument?.required == true)
            assertFalse(goal.canDispatch(""))
            assertTrue(goal.canDispatch("ship command templates"))
            assertEquals("/goal ship command templates", goal.dispatchText("  ship command templates  "))
        }
    }

    @Test
    fun `compact accepts optional argument where available`() {
        AgentKind.entries.forEach { agent ->
            val compact = AgentCommandCatalog.commandsFor(agent)
                .first { it.command == "/compact" }

            assertFalse("$agent /compact argument is optional", compact.argument?.required == true)
            assertTrue(compact.canDispatch(""))
            assertEquals("/compact", compact.dispatchText(""))
            assertEquals("/compact keep git context", compact.dispatchText("keep git context"))
        }
    }

    @Test
    fun `plain commands dispatch as their command text`() {
        val diff = AgentCommandCatalog.commandsFor(AgentKind.Codex)
            .first { it.command == "/diff" }

        assertEquals(null, diff.argument)
        assertTrue(diff.canDispatch(""))
        assertEquals("/diff", diff.dispatchText("ignored"))
    }

    @Test
    fun `blank query returns the full ordered catalog`() {
        AgentKind.entries.forEach { agent ->
            assertEquals(
                AgentCommandCatalog.commandsFor(agent),
                AgentCommandCatalog.filter(agent, "   "),
            )
        }
    }

    @Test
    fun `filter matches command text label and description case-insensitively`() {
        val byCommand = AgentCommandCatalog.filter(AgentKind.ClaudeCode, "COMPACT")
        assertTrue(byCommand.any { it.command == "/compact" })

        val byLabel = AgentCommandCatalog.filter(AgentKind.ClaudeCode, "rewind")
        assertTrue(byLabel.any { it.command == "/rewind" })

        // "context" appears in the /compact description and the /context label.
        val byDescription = AgentCommandCatalog.filter(AgentKind.ClaudeCode, "context")
        assertTrue(byDescription.any { it.command == "/compact" })
    }

    @Test
    fun `filter with no match returns empty list`() {
        val result = AgentCommandCatalog.filter(AgentKind.OpenCode, "zzzznotacommand")
        assertTrue(result.isEmpty())
    }
}
