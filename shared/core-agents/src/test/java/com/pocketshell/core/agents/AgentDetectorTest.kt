package com.pocketshell.core.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #183: the detector must light up the Conversation tab for every
 * supported engine — Claude Code, Codex, and OpenCode — when a recent
 * JSONL candidate for that engine matches the engine's expected path
 * prefix. The previous implementation hard-coded `false` for Codex and
 * OpenCode in the candidate filter, so attach-to-existing on those
 * engines never produced a detection regardless of how the candidate
 * was sourced. The refreshed filter uses [AgentDetector.expectedPathHints]
 * uniformly, and the tests below pin that contract per engine.
 */
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
    fun expectedPathHintsCoverAllThreeEngines() {
        val hints = detector.expectedPathHints("/home/alexey/git/pocketshell")

        assertEquals(
            "Claude Code hint must encode the active cwd under .claude/projects/",
            listOf(".claude/projects/-home-alexey-git-pocketshell"),
            hints[AgentKind.ClaudeCode],
        )
        assertEquals(
            "Codex hint must point at the .codex/sessions/ tree",
            listOf(".codex/sessions/"),
            hints[AgentKind.Codex],
        )
        assertEquals(
            "OpenCode hint must point at the .local/share/opencode/ tree",
            listOf(".local/share/opencode/"),
            hints[AgentKind.OpenCode],
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
    fun detectsCodexCandidateWhenPathMatchesExpectedPrefix() {
        val detection = detector.detect(
            cwd = "/workspace/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = "/home/testuser/.codex/sessions/2026/05/22/rollout-abc.jsonl",
                    modifiedAtMillis = 9_500,
                    sessionId = "codex-1",
                    cwd = "/workspace/pocketshell",
                ),
            ),
            processLines = listOf("1234 pts/0 00:00:01 codex"),
        )

        assertNotNull("expected Codex detection for matching .codex/sessions/ path", detection)
        assertEquals(AgentKind.Codex, detection?.agent)
        assertEquals("codex-1", detection?.sessionId)
        assertEquals(AgentDetection.Confidence.ProcessConfirmed, detection?.confidence)
    }

    @Test
    fun detectsCodexCandidateAsRecentFileWhenProcessScanMisses() {
        val detection = detector.detect(
            cwd = "/workspace/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = "/home/testuser/.codex/sessions/2026/05/22/rollout-abc.jsonl",
                    modifiedAtMillis = 9_500,
                    cwd = "/workspace/pocketshell",
                ),
            ),
            processLines = listOf("1234 pts/0 00:00:01 sshd"),
        )

        assertNotNull(detection)
        assertEquals(AgentKind.Codex, detection?.agent)
        assertEquals(AgentDetection.Confidence.RecentFile, detection?.confidence)
    }

    @Test
    fun rejectsCodexCandidateOutsideExpectedDirectoryTree() {
        // A Codex candidate emitted from an unrelated location (e.g. a
        // backup directory, a manually-saved copy) must not register.
        val detection = detector.detect(
            cwd = "/workspace/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = "/tmp/codex-backup/rollout.jsonl",
                    modifiedAtMillis = 9_500,
                    cwd = "/workspace/pocketshell",
                ),
            ),
            processLines = listOf("1234 codex"),
        )

        assertNull(detection)
    }

    @Test
    fun detectsOpenCodeCandidateWhenPathMatchesExpectedPrefix() {
        val detection = detector.detect(
            cwd = "/workspace/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.OpenCode,
                    path = "/home/testuser/.local/share/opencode/pocketshell-rows.jsonl",
                    modifiedAtMillis = 9_500,
                    sessionId = "opencode-1",
                    cwd = "/workspace/pocketshell",
                ),
            ),
            processLines = listOf("1234 pts/0 00:00:01 opencode"),
        )

        assertNotNull("expected OpenCode detection for matching .local/share/opencode/ path", detection)
        assertEquals(AgentKind.OpenCode, detection?.agent)
        assertEquals("opencode-1", detection?.sessionId)
        assertEquals(AgentDetection.Confidence.ProcessConfirmed, detection?.confidence)
    }

    @Test
    fun rejectsOpenCodeCandidateOutsideExpectedDirectoryTree() {
        val detection = detector.detect(
            cwd = "/workspace/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.OpenCode,
                    path = "/tmp/opencode-backup/messages.jsonl",
                    modifiedAtMillis = 9_500,
                    cwd = "/workspace/pocketshell",
                ),
            ),
            processLines = listOf("1234 opencode"),
        )

        assertNull(detection)
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
    fun rejectsClaudeCandidateFromDifferentCwd() {
        // The Claude path hint encodes the active cwd, so a candidate
        // pointing at another project's project-dir must not match.
        val detection = detector.detect(
            cwd = "/home/alexey/git/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.ClaudeCode,
                    path = "/home/alexey/.claude/projects/-home-alexey-git-other/new.jsonl",
                    modifiedAtMillis = 9_500,
                ),
            ),
            processLines = listOf("claude"),
        )

        assertNull(detection)
    }

    @Test
    fun requireProcessMatchReturnsNullWhenProcessScanMisses() {
        // Issue #186 (per-window detection): when the caller is a tmux
        // pane that scopes the process scan to its own TTY, a sibling
        // window's agent JSONL must NOT bleed through. The pane-scoped
        // `processLines` will be empty (no agent on this TTY), so the
        // detection must return null — otherwise the Conversation tab
        // lights up on non-agent windows just because they share a cwd
        // with the agent window.
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
            processLines = listOf("1234 pts/2 00:00:01 bash"),
            requireProcessMatch = true,
        )

        assertNull(
            "requireProcessMatch=true must suppress detection when no row in " +
                "processLines names the agent; sibling-window JSONLs must not light up.",
            detection,
        )
    }

    @Test
    fun requireProcessMatchReturnsDetectionWhenProcessScanHits() {
        // Sanity: the same call with a matching process row returns the
        // detection — the gate fires only on a miss, not on every call.
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
            requireProcessMatch = true,
        )

        assertEquals(AgentKind.ClaudeCode, detection?.agent)
        assertEquals(AgentDetection.Confidence.ProcessConfirmed, detection?.confidence)
    }

    @Test
    fun requireProcessMatchDefaultsToFalseAndPreservesPreviousBehaviour() {
        // The session-scoped call path
        // ([AgentConversationRepository.detect]) still wants
        // RecentFile-without-process-confirm to register, so the new
        // requireProcessMatch parameter MUST default to `false`. This
        // test pins that default against a regression that would force
        // the strict gate everywhere.
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
            processLines = emptyList(),
        )

        assertNotNull(
            "default call (no requireProcessMatch) must still return RecentFile " +
                "when the process scan misses; this preserves the pre-#186 contract.",
            detection,
        )
        assertEquals(AgentDetection.Confidence.RecentFile, detection?.confidence)
    }

    @Test
    fun picksMostRecentAcrossMultipleEnginesWhenAllPathsMatch() {
        // When more than one engine has a recent matching candidate
        // (e.g. user briefly switched agents in the same pane), the
        // most-recently-modified row wins regardless of engine.
        val detection = detector.detect(
            cwd = "/workspace/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.ClaudeCode,
                    path = "/home/testuser/.claude/projects/-workspace-pocketshell/older.jsonl",
                    modifiedAtMillis = 8_500,
                ),
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = "/home/testuser/.codex/sessions/2026/05/22/newer.jsonl",
                    modifiedAtMillis = 9_900,
                ),
                AgentLogCandidate(
                    agent = AgentKind.OpenCode,
                    path = "/home/testuser/.local/share/opencode/in-between.jsonl",
                    modifiedAtMillis = 9_200,
                ),
            ),
            processLines = emptyList(),
        )

        assertEquals(AgentKind.Codex, detection?.agent)
    }
}
