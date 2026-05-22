package com.pocketshell.core.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentDetectorTest {
    private val detector = AgentDetector(recentWindowMillis = 5_000)

    @Test
    fun encodesClaudeCwdLikeClaudeProjectsDirectory() {
        assertEquals(
            "-home-alexey-git-pocketshell",
            detector.encodeClaudeCwd("/home/alexey/git/pocketshell"),
        )
    }

    @Test
    fun picksMostRecentClaudeCandidateAndConfirmsWithProcess() {
        val detection = detector.detect(
            cwd = "/home/alexey/git/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.ClaudeCode,
                    path = "/home/alexey/.claude/projects/-home-alexey-git-pocketshell/new.jsonl",
                    modifiedAtMillis = 9_500,
                    sessionId = "claude-1",
                ),
            ),
            processLines = listOf("1234 pts/0 00:00:01 claude"),
        )

        assertEquals(AgentKind.ClaudeCode, detection?.agent)
        assertEquals("claude-1", detection?.sessionId)
        assertEquals(AgentDetection.Confidence.ProcessConfirmed, detection?.confidence)
    }

    @Test
    fun returnsNullWhenNoRecentCandidateMatches() {
        val detection = detector.detect(
            cwd = "/home/alexey/git/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.ClaudeCode,
                    path = "/home/alexey/.claude/projects/-home-alexey-git-pocketshell/old.jsonl",
                    modifiedAtMillis = 1_000,
                ),
            ),
            processLines = listOf("claude"),
        )

        assertNull(detection)
    }

    @Test
    fun rejectsCodexCandidateFromDifferentCwd() {
        val detection = detector.detect(
            cwd = "/home/alexey/git/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = "/home/alexey/.codex/sessions/2026/05/22/private-other-project.jsonl",
                    modifiedAtMillis = 9_900,
                    sessionId = "codex-other",
                    cwd = "/home/alexey/git/other",
                ),
            ),
            processLines = listOf("codex"),
        )

        assertNull(detection)
    }

    @Test
    fun rejectsCodexCandidateEvenWhenCwdAndProcessMatch() {
        val detection = detector.detect(
            cwd = "/home/alexey/git/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = "/home/alexey/.codex/sessions/2026/05/22/matching-project.jsonl",
                    modifiedAtMillis = 9_900,
                    sessionId = "codex-matching",
                    cwd = "/home/alexey/git/pocketshell",
                ),
            ),
            processLines = listOf("123 codex"),
        )

        assertNull(detection)
    }

    @Test
    fun rejectsOpenCodeGlobalDatabaseCandidate() {
        val detection = detector.detect(
            cwd = "/home/alexey/git/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.OpenCode,
                    path = "/home/alexey/.local/share/opencode/opencode.db",
                    modifiedAtMillis = 9_900,
                    cwd = "/home/alexey/git/pocketshell",
                ),
            ),
            processLines = listOf("123 opencode"),
        )

        assertNull(detection)
    }
}
