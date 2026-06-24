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

    /**
     * Issue #820: Claude Code replaces BOTH `/` and `.` with `-` when it
     * names the directory under `~/.claude/projects/`. The previous
     * implementation only replaced `/`, so any cwd containing a dot encoded
     * to the wrong directory name, the resolver found no transcript, and the
     * Conversation tab hard-failed. Empirically on a real box,
     * `/home/alexey/git/.claude` → `-home-alexey-git--claude` (the dot
     * becomes a dash, producing the double dash). This pins the contract.
     */
    @Test
    fun encodesDotInClaudeCwdAsDashLikeRealClaude() {
        // A dotdir in the path (the empirically-verified double-dash case).
        assertEquals(
            "-home-alexey-git--claude",
            detector.encodeClaudeCwd("/home/alexey/git/.claude"),
        )
        // A versioned dir with a dot in the leaf name.
        assertEquals(
            "-workspace-app-1-2-3",
            detector.encodeClaudeCwd("/workspace/app-1.2.3"),
        )
        // A worktree path with a dot in a middle segment.
        assertEquals(
            "-home-user-my-project-feature",
            detector.encodeClaudeCwd("/home/user/my.project/feature"),
        )
        // Letters, digits, underscores, and existing hyphens are preserved.
        assertEquals(
            "-home-user-Repo_Name-2026",
            detector.encodeClaudeCwd("/home/user/Repo_Name-2026"),
        )
    }

    /**
     * Issue #820: the path hint that gates Claude candidates is built from
     * [AgentDetector.encodeClaudeCwd]. With a dot in the cwd, the hint must
     * match Claude's real `-cwd-with-dots-as-dashes` directory or every
     * candidate is rejected and detection returns null.
     */
    @Test
    fun expectedPathHintEncodesDotCwdSoClaudeCandidateMatches() {
        val hints = detector.expectedPathHints("/home/alexey/git/.claude")
        assertEquals(
            listOf(".claude/projects/-home-alexey-git--claude"),
            hints[AgentKind.ClaudeCode],
        )
    }

    /**
     * Issue #820 end-to-end through [detect]: a real Claude transcript that
     * lives at Claude's dot-encoded directory must be detected when the pane
     * cwd contains a dot. Before the fix the candidate's path
     * (`.claude/projects/-home-alexey-git--claude/...`) failed the path-hint
     * filter (which produced `-home-alexey-git-.claude`), so `detect`
     * returned null and the Conversation tab hard-failed.
     */
    @Test
    fun detectsClaudeCandidateWhenCwdContainsADot() {
        val detection = detector.detect(
            cwd = "/home/alexey/git/.claude",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.ClaudeCode,
                    path = "/home/alexey/.claude/projects/-home-alexey-git--claude/sess.jsonl",
                    modifiedAtMillis = 9_500,
                    sessionId = "claude-dot",
                ),
            ),
            processLines = listOf("1234 pts/0 00:00:01 claude"),
            requireProcessMatch = true,
        )

        assertNotNull(
            "a Claude transcript under the dot-encoded projects dir must be " +
                "detected when the cwd contains a dot (#820)",
            detection,
        )
        assertEquals(AgentKind.ClaudeCode, detection?.agent)
        assertEquals("claude-dot", detection?.sessionId)
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
            "OpenCode hint must point at the opencode.db store",
            listOf(".local/share/opencode/opencode.db"),
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
    fun acceptsRecentCandidateWithSmallRemoteClockFutureSkew() {
        val detection = detector.detect(
            cwd = "/workspace/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = "/home/testuser/.codex/sessions/2026/05/22/rollout-abc.jsonl",
                    modifiedAtMillis = 10_500,
                    sessionId = "codex-future",
                    cwd = "/workspace/pocketshell",
                ),
            ),
            processLines = listOf("1234 pts/0 00:00:01 codex"),
        )

        assertEquals(AgentKind.Codex, detection?.agent)
        assertEquals("codex-future", detection?.sessionId)
    }

    @Test
    fun rejectsCandidateBeyondRemoteClockFutureSkewTolerance() {
        val detection = detector.detect(
            cwd = "/workspace/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = "/home/testuser/.codex/sessions/2026/05/22/rollout-abc.jsonl",
                    modifiedAtMillis = 310_001,
                    sessionId = "codex-too-future",
                    cwd = "/workspace/pocketshell",
                ),
            ),
            processLines = listOf("1234 pts/0 00:00:01 codex"),
        )

        assertNull(detection)
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
                    path = "/home/testuser/.local/share/opencode/opencode.db#opencode-1",
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
    fun requireProcessMatchPrefersConfirmedOpenCodeOverNewerUnconfirmedClaudeCandidate() {
        val detection = detector.detect(
            cwd = "/workspace/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.ClaudeCode,
                    path = "/home/testuser/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl",
                    modifiedAtMillis = 9_900,
                    sessionId = "pocketshell-claude",
                ),
                AgentLogCandidate(
                    agent = AgentKind.OpenCode,
                    path = "/home/testuser/.local/share/opencode/opencode.db#opencode-1",
                    modifiedAtMillis = 9_000,
                    sessionId = "opencode-1",
                    cwd = "/workspace/pocketshell",
                ),
            ),
            processLines = listOf("4242 pts/3 00:00:00 node /usr/local/bin/opencode"),
            requireProcessMatch = true,
        )

        assertEquals(AgentKind.OpenCode, detection?.agent)
        assertEquals("opencode-1", detection?.sessionId)
        assertEquals(AgentDetection.Confidence.ProcessConfirmed, detection?.confidence)
    }

    @Test
    fun nonStrictDetectionPrefersOlderConfirmedCandidateOverNewerRecentFile() {
        val detection = detector.detect(
            cwd = "/workspace/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.OpenCode,
                    path = "/home/testuser/.local/share/opencode/opencode.db#opencode-1",
                    modifiedAtMillis = 9_000,
                    sessionId = "opencode-1",
                    cwd = "/workspace/pocketshell",
                ),
                AgentLogCandidate(
                    agent = AgentKind.ClaudeCode,
                    path = "/home/testuser/.claude/projects/-workspace-pocketshell/claude-newer.jsonl",
                    modifiedAtMillis = 9_900,
                    sessionId = "claude-newer",
                ),
            ),
            processLines = listOf("4242 pts/3 00:00:00 node /usr/local/bin/opencode"),
            requireProcessMatch = false,
        )

        assertEquals(AgentKind.OpenCode, detection?.agent)
        assertEquals("opencode-1", detection?.sessionId)
        assertEquals(AgentDetection.Confidence.ProcessConfirmed, detection?.confidence)
    }

    @Test
    fun quotedExecArgvTokenConfirmsAgentProcess() {
        val detection = detector.detect(
            cwd = "/workspace/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = "/home/testuser/.codex/sessions/2026/05/22/rollout-abc.jsonl",
                    modifiedAtMillis = 9_500,
                    sessionId = "codex-1",
                ),
            ),
            processLines = listOf("123 sh -c exec -a 'codex' sleep 30"),
        )

        assertEquals(AgentKind.Codex, detection?.agent)
        assertEquals(AgentDetection.Confidence.ProcessConfirmed, detection?.confidence)
    }

    @Test
    fun claudeLogPathInOpenCodeProcessArgsDoesNotConfirmClaudeCandidate() {
        val detection = detector.detect(
            cwd = "/workspace/pocketshell",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.ClaudeCode,
                    path = "/home/testuser/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl",
                    modifiedAtMillis = 9_900,
                    sessionId = "pocketshell-claude",
                ),
                AgentLogCandidate(
                    agent = AgentKind.OpenCode,
                    path = "/home/testuser/.local/share/opencode/opencode.db#opencode-1",
                    modifiedAtMillis = 9_000,
                    sessionId = "opencode-1",
                    cwd = "/workspace/pocketshell",
                ),
            ),
            processLines = listOf(
                "4242 pts/3 00:00:00 node /usr/local/bin/opencode --fixture " +
                    "/home/testuser/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl",
            ),
            requireProcessMatch = true,
        )

        assertEquals(AgentKind.OpenCode, detection?.agent)
        assertEquals("opencode-1", detection?.sessionId)
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
    fun defaultRecencyWindowAcceptsCodexJsonlFlushedThirtyMinutesAgo() {
        // Issue #236: the previous 5-minute window dropped real-world
        // Codex sessions because Codex flushes its rollout JSONL only on
        // turn completion. A user reattaching to an idle Codex TUI 30
        // minutes after the last turn must still see the Conversation
        // tab. The default `recentWindowMillis` was bumped from 5 to
        // 120 minutes to fix this; the test pins the new contract
        // against a future regression that would tighten the window
        // again.
        val defaultDetector = AgentDetector()
        val now = 7_200_000L // arbitrary anchor
        val thirtyMinutesAgo = now - (30L * 60L * 1000L)

        val detection = defaultDetector.detect(
            cwd = "/workspace/pocketshell",
            nowMillis = now,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = "/home/testuser/.codex/sessions/2026/05/27/rollout-old.jsonl",
                    modifiedAtMillis = thirtyMinutesAgo,
                    sessionId = "codex-stale",
                ),
            ),
            processLines = listOf("1234 pts/0 00:00:01 codex"),
        )

        assertNotNull(
            "a 30-minute-old Codex JSONL must still register on the default detector " +
                "(post-#236 the freshness window is 120 min). If this fails the window " +
                "has regressed below the realistic Codex turn-flush cadence.",
            detection,
        )
        assertEquals(AgentKind.Codex, detection?.agent)
        assertEquals(AgentDetection.Confidence.ProcessConfirmed, detection?.confidence)
    }

    @Test
    fun defaultRecencyWindowRejectsCodexJsonlFlushedThreeHoursAgo() {
        // Counter-pin: the 120-minute window is not unbounded. A Codex
        // rollout last touched 3 hours ago is almost certainly from a
        // finished session and must NOT light up the Conversation tab
        // on a current pane.
        val defaultDetector = AgentDetector()
        val now = 14_400_000L
        val threeHoursAgo = now - (3L * 60L * 60L * 1000L)

        val detection = defaultDetector.detect(
            cwd = "/workspace/pocketshell",
            nowMillis = now,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = "/home/testuser/.codex/sessions/2026/05/27/rollout-ancient.jsonl",
                    modifiedAtMillis = threeHoursAgo,
                    sessionId = "codex-ancient",
                ),
            ),
            processLines = listOf("1234 pts/0 00:00:01 codex"),
        )

        assertNull(
            "a 3-hour-old Codex JSONL must be rejected by the default detector " +
                "(post-#236 the freshness window is bounded at 120 min). A null result " +
                "means the upper bound is still honoured.",
            detection,
        )
    }

    @Test
    fun codexSelectsProcessOwnedRolloutOverNewerSameCwdSibling() {
        // Issue #819: two Codex rollouts share the pane's cwd — the pane's
        // OWN session (older mtime) and a busier orchestrator/sibling Codex
        // (newer mtime, e.g. a poll loop flushing on every turn). Both pass
        // the same-cwd candidate enumeration and both are "process
        // confirmed" because *a* codex runs on the pane TTY. The pre-#819
        // selection collapsed to maxBy(mtime) and picked the WRONG (newer
        // sibling) rollout, so the Conversation tab rendered another
        // session's transcript under the correct header.
        //
        // With an identity signal — the rollout actually held open by the
        // pane's own Codex process (resolved from /proc/<pid>/fd) — the
        // pane's session must win even though it is OLDER.
        val paneRollout =
            "/home/alexey/.codex/sessions/2026/06/18/rollout-pane-own.jsonl"
        val siblingRollout =
            "/home/alexey/.codex/sessions/2026/06/18/rollout-orchestrator.jsonl"

        val detection = detector.detect(
            cwd = "/home/alexey/git/ai-shipping-labs",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = siblingRollout,
                    modifiedAtMillis = 9_900, // newer — would win the mtime race
                    sessionId = "codex-orchestrator",
                    cwd = "/home/alexey/git/ai-shipping-labs",
                ),
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = paneRollout,
                    modifiedAtMillis = 9_000, // older — but it's THIS pane's
                    sessionId = "codex-pane-own",
                    cwd = "/home/alexey/git/ai-shipping-labs",
                ),
            ),
            processLines = listOf("4242 pts/3 00:00:01 codex"),
            requireProcessMatch = true,
            processOwnedSourcePaths = setOf(paneRollout),
        )

        assertEquals(
            "the rollout held open by the pane's own Codex process must win, " +
                "not the most-recently-flushed same-cwd sibling (#819)",
            "codex-pane-own",
            detection?.sessionId,
        )
        assertEquals(paneRollout, detection?.sourcePath)
        assertEquals(AgentDetection.Confidence.ProcessConfirmed, detection?.confidence)
    }

    @Test
    fun codexFallsBackToMtimeWhenNoProcessOwnedRollout() {
        // When the identity signal is unavailable (no /proc/<pid>/fd match —
        // e.g. a foreign/exited session, or a Codex build that doesn't keep
        // the rollout fd open), the selection degrades to the previous
        // mtime-among-same-cwd behaviour so detection still lights up. The
        // newer same-cwd rollout wins in the absence of an owner signal.
        val detection = detector.detect(
            cwd = "/home/alexey/git/ai-shipping-labs",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = "/home/alexey/.codex/sessions/2026/06/18/rollout-a.jsonl",
                    modifiedAtMillis = 9_900,
                    sessionId = "codex-newer",
                    cwd = "/home/alexey/git/ai-shipping-labs",
                ),
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = "/home/alexey/.codex/sessions/2026/06/18/rollout-b.jsonl",
                    modifiedAtMillis = 9_000,
                    sessionId = "codex-older",
                    cwd = "/home/alexey/git/ai-shipping-labs",
                ),
            ),
            processLines = listOf("4242 pts/3 00:00:01 codex"),
            requireProcessMatch = true,
            processOwnedSourcePaths = emptySet(),
        )

        assertEquals("codex-newer", detection?.sessionId)
    }

    @Test
    fun codexStrictOwnershipReturnsNullInsteadOfGuessingNewestRollout() {
        // Issue #819: recorded-Codex resolution may have multiple same-cwd
        // rollout candidates and a live codex process, but no fd-owned source
        // path. In that ambiguous shape, "newest confirmed" is a sibling guess;
        // callers that require ownership must get null instead.
        val detection = detector.detect(
            cwd = "/home/alexey/git/ai-shipping-labs",
            nowMillis = 10_000,
            candidates = listOf(
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = "/home/alexey/.codex/sessions/2026/06/18/rollout-a.jsonl",
                    modifiedAtMillis = 9_900,
                    sessionId = "codex-newer",
                    cwd = "/home/alexey/git/ai-shipping-labs",
                ),
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = "/home/alexey/.codex/sessions/2026/06/18/rollout-b.jsonl",
                    modifiedAtMillis = 9_000,
                    sessionId = "codex-older",
                    cwd = "/home/alexey/git/ai-shipping-labs",
                ),
            ),
            processLines = listOf("4242 pts/3 00:00:01 codex"),
            requireProcessMatch = true,
            processOwnedSourcePaths = emptySet(),
            requireProcessOwnedSourcePath = true,
        )

        assertNull(detection)
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
