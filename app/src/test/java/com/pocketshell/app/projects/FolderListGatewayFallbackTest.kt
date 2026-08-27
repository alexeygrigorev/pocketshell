package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.TmuxRead
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

class FolderListGatewayFallbackTest {

    @Test
    fun nativeTmuxNoServerFallsBackToPocketshellSessions() = runTest {
        val session = FakeSshSession(
            pocketshellResult = ExecResult(
                stdout = """
                    IDX  SESSION               CREATED
                      1  claude-main           2026-05-30 00:20:01
                      2  codex                 2026-05-30 00:19:58
                """.trimIndent(),
                stderr = "",
                exitCode = 0,
            ),
        )
        val gateway = SshFolderListGateway()

        val result = gateway.listSessionsFromNativeOrPocketshell(
            session = session,
            listSessions = ExecResult(
                stdout = "",
                stderr = "error connecting to /tmp/tmux-1000/default (No such file or directory)",
                exitCode = 1,
            ),
            probes = gateway.serialSideProbes(session, HOST, emptyList()),
        )

        assertTrue(result is FolderListResult.Sessions)
        val rows = (result as FolderListResult.Sessions).rows
        assertEquals(listOf("claude-main", "codex"), rows.map { it.sessionName })
        assertEquals(listOf(null, null), rows.map { it.cwd })
        assertEquals(listOf(false, false), rows.map { it.attached })
        assertTrue(session.execCommands.any { it.contains(SshFolderListGateway.POCKETSHELL_SESSIONS_COMMAND) })
    }

    @Test
    fun nativeTmuxFallbackEnrichesHumanRowsWithCustomRawKind() = runTest {
        val structured =
            "${'$'}7::custom-session::100::200::1::custom-codex::/srv/custom\n"
        val session = FakeSshSession(
            pocketshellResult = ExecResult(
                stdout = "IDX  SESSION               CREATED\n" +
                    "1    custom-session         2026-05-30 00:20:01\n",
                stderr = "",
                exitCode = 0,
            ),
            structuredResult = ExecResult(structured, "", 0),
        )
        val engines = FamilyGateway("custom-codex")
        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = SshLeaseManager(
                connector = SshLeaseConnector { Result.success(session) },
            ),
            sessionListParser = HostTmuxSessionListParser(),
            execReadTimeoutMs = SshFolderListGateway.EXEC_READ_TIMEOUT_MS,
            enginesGateway = engines,
        )

        val result = gateway.listSessionsFromNativeOrPocketshell(
            session = session,
            listSessions = ExecResult(
                stdout = "",
                stderr = "error connecting to /tmp/tmux-1000/default (No such file or directory)",
                exitCode = 1,
            ),
            familyForRawId = { rawId -> engines.familyForRawId(HOST.id, rawId) },
            probes = gateway.serialSideProbes(session, HOST, emptyList()),
        )

        assertTrue(result is FolderListResult.Sessions)
        val row = (result as FolderListResult.Sessions).rows.single()
        assertEquals("custom-codex", row.recordedKindId)
        assertEquals(SessionAgentKind.Codex, row.recordedKind)
        assertEquals(SessionAgentKind.Codex, row.agentKind)
        assertEquals("\$7", row.tmuxSessionId)
        assertEquals(listOf("custom-codex"), engines.resolvedRawIds)
        assertEquals(
            1,
            session.execCommands.count {
                it.contains(STRUCTURED_PROBE_HEAD)
            },
        )
        assertTrue(
            session.execCommands.any {
                it.contains(STRUCTURED_PROBE_HEAD)
            },
        )
    }

    @Test
    fun malformedTmuxEnrichmentKeepsLegacyHumanRows() = runTest {
        val session = FakeSshSession(
            pocketshellResult = ExecResult(
                stdout = "IDX  SESSION               CREATED\n" +
                    "1    legacy-session         2026-05-30 00:20:01\n",
                stderr = "",
                exitCode = 0,
            ),
            structuredResult = ExecResult("not-a-tmux-row", "", 0),
        )
        val gateway = SshFolderListGateway()

        val result = gateway.listSessionsFromNativeOrPocketshell(
            session = session,
            listSessions = ExecResult(
                stdout = "",
                stderr = "tmux: not found",
                exitCode = 127,
            ),
            probes = gateway.serialSideProbes(session, HOST, emptyList()),
        )

        assertTrue(result is FolderListResult.Sessions)
        val row = (result as FolderListResult.Sessions).rows.single()
        assertEquals("legacy-session", row.sessionName)
        assertEquals(null, row.recordedKindId)
        assertEquals(SessionAgentKind.Shell, row.agentKind)
    }

    @Test
    fun unavailableTmuxEnrichmentKeepsLegacyHumanRows() = runTest {
        val session = FakeSshSession(
            pocketshellResult = ExecResult(
                stdout = "IDX  SESSION               CREATED\n" +
                    "1    legacy-session         2026-05-30 00:20:01\n",
                stderr = "",
                exitCode = 0,
            ),
            structuredResult = ExecResult(
                stdout = "",
                stderr = "tmux: command not found",
                exitCode = 127,
            ),
        )

        val gateway = SshFolderListGateway()
        val result = gateway.listSessionsFromNativeOrPocketshell(
            session = session,
            listSessions = ExecResult(stdout = "", stderr = "tmux: not found", exitCode = 127),
            probes = gateway.serialSideProbes(session, HOST, emptyList()),
        )

        assertTrue(result is FolderListResult.Sessions)
        val row = (result as FolderListResult.Sessions).rows.single()
        assertEquals("legacy-session", row.sessionName)
        assertTrue(session.execCommands.any {
            it.contains(STRUCTURED_PROBE_HEAD)
        })
        assertEquals(null, row.recordedKindId)
    }

    @Test
    fun wedgedSupportedTmuxEnrichmentIsBoundedAndKeepsHumanRows() = runTest {
        val session = FakeSshSession(
            pocketshellResult = ExecResult(
                stdout = "IDX  SESSION               CREATED\n" +
                    "1    legacy-session         2026-05-30 00:20:01\n",
                stderr = "",
                exitCode = 0,
            ),
            blockStructuredProbe = true,
        )
        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = SshLeaseManager(
                connector = SshLeaseConnector { Result.success(session) },
            ),
            sessionListParser = HostTmuxSessionListParser(),
            execReadTimeoutMs = 40L,
            enginesGateway = null,
        )

        val result = gateway.listSessionsFromNativeOrPocketshell(
            session = session,
            listSessions = ExecResult(stdout = "", stderr = "tmux: not found", exitCode = 127),
            probes = gateway.serialSideProbes(session, HOST, emptyList()),
        )

        assertTrue(result is FolderListResult.Sessions)
        assertEquals(
            listOf("legacy-session"),
            (result as FolderListResult.Sessions).rows.map { it.sessionName },
        )
        val structuredCommands = session.execCommands.filter {
            it.contains(STRUCTURED_PROBE_HEAD)
        }
        assertEquals(1, structuredCommands.size)
        assertTrue(
            structuredCommands.single().contains("${TmuxRead.CLIENT} list-sessions -F"),
        )
        assertFalse(structuredCommands.single().contains("--json"))
    }

    @Test
    fun nativeAndPocketshellTmuxSocketMissingReturnsEmptySessions() = runTest {
        val socketMissing = ExecResult(
            stdout = "",
            stderr = "error connecting to /tmp/tmux-1000/default (No such file or directory)",
            exitCode = 1,
        )
        val session = FakeSshSession(pocketshellResult = socketMissing)
        val gateway = SshFolderListGateway()

        val result = gateway.listSessionsFromNativeOrPocketshell(
            session = session,
            listSessions = socketMissing,
            probes = gateway.serialSideProbes(session, HOST, emptyList()),
        )

        assertTrue(result is FolderListResult.Sessions)
        assertEquals(emptyList<FolderSessionRow>(), (result as FolderListResult.Sessions).rows)
        assertTrue(session.execCommands.any { it.contains(SshFolderListGateway.POCKETSHELL_SESSIONS_COMMAND) })
    }

    // --- Issue #726: memory-capped create-detached + two-layer fallback ---

    @Test
    fun cappedCreatePathEmitsTmuxctlCreateDetachedWithQuoting() = runTest {
        // tmuxctl present + supports create-detached -> the capped verb runs and
        // exits 0; no raw-tmux fallback should be issued.
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(match = "create-detached", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        val name = gateway.createSessionOnSession(
            session = session,
            sessionName = SESSION_NAME,
            cwd = CWD,
            startCommand = null,
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertEquals(SessionCreateOutcome.Created(SESSION_NAME), name)
        val capped = session.execCommands.single { it.contains("create-detached") }
        // The recorded command is wrapped by pathAware (`/bin/sh -lc '<PATH=…;
        // body>'`), which re-escapes every inner single quote to `'"'"'`. Build
        // the EXACT expected wrapped string from the production companion
        // builder so the assertion can't drift from the real quoting.
        val expectedCapped = pathAware(
            SshFolderListGateway.cappedCreateSessionCommand(
                shellQuote(SESSION_NAME),
                shellQuote(CWD),
            ),
        )
        assertEquals(
            "capped command must invoke create-detached on the quoted name",
            expectedCapped,
            capped,
        )
        // Sanity: the inner verb + escaped name survive in the wrapped string,
        // and the capability probe precedes the real verb so an absent/old
        // tmuxctl is detected before we try to run it.
        assertTrue(
            "capped command must route create-detached on the escaped name: $capped",
            capped.contains("tmuxctl create-detached ${escapedInWrapper(SESSION_NAME)} -c ${escapedInWrapper(CWD)}"),
        )
        assertTrue(
            "capped command must probe tmuxctl support first: $capped",
            capped.contains("command -v tmuxctl") && capped.contains("create-detached --help"),
        )
        // No raw-tmux fallback when the capped path succeeded.
        assertFalse(
            "must NOT fall back to raw tmux when create-detached succeeded",
            session.execCommands.any { it.contains("new-session -A -d") },
        )
    }

    @Test
    fun binaryAbsentFallsBackToRawNewSession() = runTest {
        // The capped probe finds no tmuxctl on PATH and exits the sentinel
        // (TMUXCTL_UNSUPPORTED_EXIT_CODE) -> client falls back to raw tmux.
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(
                    match = "create-detached",
                    result = ExecResult(
                        stdout = "",
                        stderr = "",
                        exitCode = SshFolderListGateway.TMUXCTL_UNSUPPORTED_EXIT_CODE,
                    ),
                ),
                CreateSessionFake.Rule(match = "new-session -A -d", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        val name = gateway.createSessionOnSession(
            session = session,
            sessionName = SESSION_NAME,
            cwd = CWD,
            startCommand = null,
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertEquals(SessionCreateOutcome.Created(SESSION_NAME), name)
        val fallback = session.execCommands.single { it.contains("new-session -A -d") }
        // Same pathAware wrapping applies to the raw fallback create; assert the
        // EXACT wrapped string from the production builder.
        val expectedFallback = pathAware(
            SshFolderListGateway.fallbackCreateSessionCommand(
                shellQuote(SESSION_NAME),
                shellQuote(CWD),
            ),
        )
        assertEquals(
            "fallback must be the raw uncapped create with the quoted name + cwd",
            expectedFallback,
            fallback,
        )
    }

    @Test
    fun verbAbsentOlderTmuxctlAlsoFallsBackToRawNewSession() = runTest {
        // Binary present but an OLDER tmuxctl lacks the verb. The capability
        // probe (`create-detached --help`) fails, so the wrapper exits the same
        // sentinel and the client falls back exactly like binary-absent — it is
        // NOT mistaken for a hard error (the pre-fix exit-127-only check threw).
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(
                    match = "create-detached",
                    result = ExecResult(
                        stdout = "",
                        stderr = "",
                        exitCode = SshFolderListGateway.TMUXCTL_UNSUPPORTED_EXIT_CODE,
                    ),
                ),
                CreateSessionFake.Rule(match = "new-session -A -d", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        val name = gateway.createSessionOnSession(
            session = session,
            sessionName = SESSION_NAME,
            cwd = CWD,
            startCommand = null,
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertEquals(SessionCreateOutcome.Created(SESSION_NAME), name)
        assertTrue(
            "verb-absent host must fall back to raw new-session, not error",
            session.execCommands.any {
                it.contains("tmux new-session -A -d -s ${escapedInWrapper(SESSION_NAME)}")
            },
        )
    }

    @Test
    fun genuineCreateDetachedErrorIsSurfacedNotSwallowed() = runTest {
        // tmuxctl ran the real verb and it genuinely failed (e.g. systemd-run /
        // tmux error). The error must surface, NOT be downgraded to an uncapped
        // raw-tmux session behind the user's back.
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(
                    match = "create-detached",
                    result = ExecResult(
                        stdout = "",
                        stderr = "systemd-run: Failed to start transient scope unit",
                        exitCode = 1,
                    ),
                ),
            ),
        )
        val gateway = SshFolderListGateway()

        val ex = runCatching {
            gateway.createSessionOnSession(
                session = session,
                sessionName = SESSION_NAME,
                cwd = CWD,
                startCommand = null,
                namePolicy = SessionNamePolicy.ExactName,
            )
        }.exceptionOrNull()
        assertTrue("expected a surfaced RuntimeException, got $ex", ex is RuntimeException)
        assertEquals("systemd-run: Failed to start transient scope unit", ex?.message)
        assertFalse(
            "a genuine create-detached failure must NOT fall back to raw tmux",
            session.execCommands.any { it.contains("new-session -A -d") },
        )
    }

    @Test
    fun cappedCreateBlankStderrNonzeroExitIsStillFailure() = runTest {
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(
                    match = "create-detached",
                    result = ExecResult(stdout = "", stderr = "", exitCode = 23),
                ),
            ),
        )

        val failure = runCatching {
            SshFolderListGateway().createSessionOnSession(
                session = session,
                sessionName = SESSION_NAME,
                cwd = CWD,
                startCommand = null,
                namePolicy = SessionNamePolicy.ExactName,
            )
        }.exceptionOrNull()

        assertTrue("non-zero capped create must fail even when stderr is blank", failure is RuntimeException)
        assertTrue("fallback error must name exit code 23: ${failure?.message}", failure?.message.orEmpty().contains("23"))
    }

    @Test
    fun rawFallbackBlankStderrNonzeroExitIsStillFailure() = runTest {
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(
                    match = "create-detached",
                    result = ExecResult(
                        stdout = "",
                        stderr = "",
                        exitCode = SshFolderListGateway.TMUXCTL_UNSUPPORTED_EXIT_CODE,
                    ),
                ),
                CreateSessionFake.Rule(
                    match = "new-session -A -d",
                    result = ExecResult(stdout = "", stderr = "", exitCode = 24),
                ),
            ),
        )

        val failure = runCatching {
            SshFolderListGateway().createSessionOnSession(
                session = session,
                sessionName = SESSION_NAME,
                cwd = CWD,
                startCommand = null,
                namePolicy = SessionNamePolicy.ExactName,
            )
        }.exceptionOrNull()

        assertTrue("non-zero raw fallback must fail even when stderr is blank", failure is RuntimeException)
        assertTrue("fallback error must name exit code 24: ${failure?.message}", failure?.message.orEmpty().contains("24"))
    }

    @Test
    fun startCommandSendKeysStillFiresAfterCappedCreate() = runTest {
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(match = "create-detached", result = ok()),
                // Issue #759: agent launches first pre-flight `pocketshell agent
                // --help`. A current host answers 0 (help text), so the launch
                // proceeds to send-keys.
                CreateSessionFake.Rule(match = "pocketshell agent --help", result = ok()),
                CreateSessionFake.Rule(match = "send-keys", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        gateway.createSessionOnSession(
            session = session,
            sessionName = SESSION_NAME,
            cwd = CWD,
            startCommand = "pocketshell agent claude --dir '/home/me/proj dir'",
            namePolicy = SessionNamePolicy.ExactName,
        )

        val sendKeys = session.execCommands.single { it.contains("send-keys") }
        // pathAware wraps the send-keys command too, re-escaping the inner single
        // quotes: assert the escaped session name target and the trailing
        // `Enter'` of the wrapper's closing quote.
        assertTrue(
            "send-keys must target the quoted session name and end with Enter: $sendKeys",
            sendKeys.contains("tmux send-keys -t ${escapedInWrapper("=$SESSION_NAME:")}") &&
                sendKeys.endsWith("Enter'"),
        )
        // The capped create still ran before send-keys.
        assertTrue(session.execCommands.any { it.contains("create-detached") })
    }

    // --- Issue #759: graceful agent-launch version-mismatch guard ---

    @Test
    fun outdatedHostAgentLaunchSurfacesUpdateHintNotRawClickError() = runTest {
        // Simulate the maintainer's v0.3.34 dogfood: the host's pocketshell is
        // OUTDATED (0.3.33), so the `agent` subcommand probe answers with the
        // raw Click "No such command 'agent'" error. The launch must abort with
        // the actionable update hint, and must NOT type the doomed agent line
        // into the pane (no send-keys).
        //
        // Issue #1928: the pre-flight runs AFTER the session was created, so the
        // hint is now the reason of a LAUNCH failure rather than a thrown CREATE
        // failure — same words, and it no longer claims nothing was created.
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(match = "create-detached", result = ok()),
                CreateSessionFake.Rule(
                    match = "pocketshell agent --help",
                    result = ExecResult(
                        stdout = "",
                        stderr = "Error: No such command 'agent'. " +
                            "(Did you mean one of: 'agent-log', 'usage'?)",
                        exitCode = 2,
                    ),
                ),
                CreateSessionFake.Rule(
                    match = "pocketshell --version",
                    result = ExecResult(
                        stdout = "pocketshell, version 0.3.33",
                        stderr = "",
                        exitCode = 0,
                    ),
                ),
                CreateSessionFake.Rule(match = "send-keys", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        val outcome = gateway.createSessionOnSession(
            session = session,
            sessionName = SESSION_NAME,
            cwd = CWD,
            startCommand = "pocketshell agent claude --dir '/home/me/proj dir'",
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertTrue(
            "an outdated host is a LAUNCH failure — the session exists; got $outcome",
            outcome is SessionCreateOutcome.LaunchFailed,
        )
        val message = (outcome as SessionCreateOutcome.LaunchFailed).detail
        // The friendly hint: names the concrete installed version, the required
        // minimum, and a copyable update command.
        assertTrue("hint must name installed version: $message", message.contains("0.3.33"))
        assertTrue(
            "hint must name required minimum: $message",
            message.contains(AgentLaunchVersionCheck.MIN_AGENT_POCKETSHELL_VERSION),
        )
        assertTrue(
            "hint must give a copyable update command: $message",
            message.contains(AgentLaunchVersionCheck.UPDATE_COMMAND),
        )
        // The raw Click jargon must NOT leak to the user.
        assertFalse("raw Click error must not leak: $message", message.contains("No such command"))
        // The doomed agent line must NOT be typed into the pane.
        assertFalse(
            "must not send-keys a launch that will fail",
            session.execCommands.any { it.contains("send-keys") },
        )
    }

    @Test
    fun freshNameAgentLaunchOnOutdatedHostReachesVersionHintNotCollisionGuard() = runTest {
        // Regression lock for the chronic-red #759 emulator journey. The #976
        // launch-collision guard probes `tmux has-session` and REFUSES the
        // launch when the target already exists — and it runs BEFORE the #759
        // version pre-flight. For a FRESH target name the session is ABSENT
        // (has-session exits non-zero), so the guard must NOT fire and the
        // launch MUST reach the version-mismatch pre-flight and surface the
        // update hint — NOT the "already open" collision message.
        //
        // The on-device fake (FakeOldHostSshSession) had regressed to answer
        // has-session with exit 0 (a never-created session reported as already
        // open), which short-circuited the version hint and kept
        // AgentLaunchVersionMismatchHintE2eTest red for days. This per-push JVM
        // test locks the coexistence so the ordering can't silently rot again.
        val session = CreateSessionFake(
            results = listOf(
                // Fresh name → has-session reports the session absent.
                CreateSessionFake.Rule(
                    match = "has-session",
                    result = ExecResult(stdout = "", stderr = "can't find session", exitCode = 1),
                ),
                CreateSessionFake.Rule(match = "create-detached", result = ok()),
                CreateSessionFake.Rule(
                    match = "pocketshell agent --help",
                    result = ExecResult(
                        stdout = "",
                        stderr = "Error: No such command 'agent'. " +
                            "(Did you mean one of: 'agent-log', 'usage'?)",
                        exitCode = 2,
                    ),
                ),
                CreateSessionFake.Rule(
                    match = "pocketshell --version",
                    result = ExecResult(stdout = "pocketshell, version 0.3.33", stderr = "", exitCode = 0),
                ),
                CreateSessionFake.Rule(match = "send-keys", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        val outcome = gateway.createSessionOnSession(
            session = session,
            sessionName = SESSION_NAME,
            cwd = CWD,
            startCommand = "pocketshell agent claude --dir '/home/me/proj dir'",
            namePolicy = SessionNamePolicy.ExactName,
        )

        // Issue #1928: the hint arrives as a LAUNCH failure on a session that
        // was created, not as a create failure.
        assertTrue("expected a launch failure, got $outcome", outcome is SessionCreateOutcome.LaunchFailed)
        val message = (outcome as SessionCreateOutcome.LaunchFailed).detail
        // Reached the version pre-flight: the hint names the installed version.
        assertTrue("must reach the version hint (installed version): $message", message.contains("0.3.33"))
        // The collision guard must NOT have short-circuited the launch.
        assertFalse(
            "collision guard must not short-circuit the version pre-flight: $message",
            message.contains("already open"),
        )
        // The pre-flight probe genuinely ran (this is what caught the mismatch).
        assertTrue(
            "must have pre-flighted `pocketshell agent --help`: ${session.execCommands}",
            session.execCommands.any { it.contains("pocketshell agent --help") },
        )
        // A doomed launch must never leak keystrokes.
        assertFalse(
            "must not send-keys a launch that will fail",
            session.execCommands.any { it.contains("send-keys") },
        )
    }

    @Test
    fun outdatedHostHintStaysGenericWhenVersionProbeFails() = runTest {
        // The `--help` probe shows the mismatch but the version probe itself
        // errors out: the hint still fires, just with generic "too old"
        // phrasing instead of a concrete version, and never blocks on the
        // failed version fetch.
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(match = "create-detached", result = ok()),
                CreateSessionFake.Rule(
                    match = "pocketshell agent --help",
                    result = ExecResult(
                        stdout = "",
                        stderr = "Error: No such command 'agent'.",
                        exitCode = 2,
                    ),
                ),
                CreateSessionFake.Rule(
                    match = "pocketshell --version",
                    result = ExecResult(stdout = "", stderr = "boom", exitCode = 1),
                ),
                CreateSessionFake.Rule(match = "send-keys", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        val outcome = gateway.createSessionOnSession(
            session = session,
            sessionName = SESSION_NAME,
            cwd = CWD,
            startCommand = "pocketshell agent codex --dir '/home/me/proj dir'",
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertTrue("expected a launch failure, got $outcome", outcome is SessionCreateOutcome.LaunchFailed)
        val message = (outcome as SessionCreateOutcome.LaunchFailed).detail
        assertTrue("hint must fall back to generic phrasing: $message", message.contains("too old"))
        assertTrue(
            "hint must still name required minimum: $message",
            message.contains(AgentLaunchVersionCheck.MIN_AGENT_POCKETSHELL_VERSION),
        )
        assertTrue(
            "hint must still give a copyable command: $message",
            message.contains(AgentLaunchVersionCheck.UPDATE_COMMAND),
        )
        assertFalse(
            "must not send-keys a launch that will fail",
            session.execCommands.any { it.contains("send-keys") },
        )
    }

    @Test
    fun shellSessionSkipsTheAgentVersionPreflight() = runTest {
        // A plain SHELL session (startCommand is not a `pocketshell agent` line)
        // must never run the agent --help pre-flight probe.
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(match = "create-detached", result = ok()),
                CreateSessionFake.Rule(match = "send-keys", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        gateway.createSessionOnSession(
            session = session,
            sessionName = SESSION_NAME,
            cwd = CWD,
            startCommand = "htop",
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertFalse(
            "a shell session must not probe `pocketshell agent --help`",
            session.execCommands.any { it.contains("pocketshell agent --help") },
        )
        assertTrue(session.execCommands.any { it.contains("send-keys") })
    }

    // --- Issue #976: a LAUNCH must never type into an already-open session ---

    @Test
    fun agentLaunchIntoAnAlreadyOpenSameNameSessionRefusesAndDoesNotSendKeys() = runTest {
        // The #976 misroute: a new Codex launch in a directory that ALREADY has
        // an open session derives the SAME path-prefix name. With an empty
        // de-dupe list (a #974 drop / still-loading picker collapsed
        // `existingNames` to ∅), the suffix is skipped and the idempotent create
        // would REUSE the live session — `send-keys -t '<name>'` then types the
        // launch line into the currently-attached pane. The gateway must probe
        // `tmux has-session` first, see the name is taken, and REFUSE: no create,
        // no send-keys, a surfaced error. (Red on base: base has no has-session
        // guard, so the create no-ops and send-keys fires into the existing pane.)
        val session = CreateSessionFake(
            results = listOf(
                // The session already exists on the host → has-session exits 0.
                CreateSessionFake.Rule(match = "has-session", result = ok()),
                CreateSessionFake.Rule(match = "create-detached", result = ok()),
                CreateSessionFake.Rule(match = "pocketshell agent --help", result = ok()),
                CreateSessionFake.Rule(match = "send-keys", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        val ex = runCatching {
            gateway.createSessionOnSession(
                session = session,
                sessionName = SESSION_NAME,
                cwd = CWD,
                startCommand = "pocketshell agent codex --dir '/home/me/proj dir'",
                namePolicy = SessionNamePolicy.ExactName,
            )
        }.exceptionOrNull()

        assertTrue("a colliding launch must surface an error, got $ex", ex is RuntimeException)
        // The load-bearing assertion: the launch line was NEVER typed into the
        // existing (current) pane.
        assertFalse(
            "must NOT send-keys the launch into an already-open session",
            session.execCommands.any { it.contains("send-keys") },
        )
        // It must not have created/attached either — the create is skipped once
        // the collision is detected.
        assertFalse(
            "must NOT run the idempotent create once a collision is detected",
            session.execCommands.any { it.contains("create-detached") },
        )
        // It DID probe for the collision.
        assertTrue(
            "must probe has-session before launching",
            session.execCommands.any { it.contains("has-session") },
        )
    }

    @Test
    fun agentLaunchWithSuffixedFreshNameCreatesAndSendsKeys() = runTest {
        // Class-cover (b): a legitimate same-dir second session has already been
        // given a `-2` suffix by the deriver (picker was Ready), so the name is
        // genuinely FREE on the host. has-session exits non-zero, the create runs,
        // and send-keys fires into the NEW session — the happy path still works.
        val freshName = "$SESSION_NAME-2"
        val session = CreateSessionFake(
            results = listOf(
                // -2 name is free → has-session exits non-zero.
                CreateSessionFake.Rule(
                    match = "has-session",
                    result = ExecResult(stdout = "", stderr = "can't find session", exitCode = 1),
                ),
                CreateSessionFake.Rule(match = "create-detached", result = ok()),
                CreateSessionFake.Rule(match = "pocketshell agent --help", result = ok()),
                CreateSessionFake.Rule(match = "send-keys", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        gateway.createSessionOnSession(
            session = session,
            sessionName = freshName,
            cwd = CWD,
            startCommand = "pocketshell agent codex --dir '/home/me/proj dir'",
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertTrue(
            "a fresh suffixed name must create the session",
            session.execCommands.any { it.contains("create-detached") },
        )
        val sendKeys = session.execCommands.single { it.contains("send-keys") }
        assertTrue(
            "send-keys must target the fresh suffixed name: $sendKeys",
            sendKeys.contains("tmux send-keys -t ${escapedInWrapper("=$freshName:")}"),
        )
    }

    @Test
    fun agentLaunchIntoAFreshDistinctDirNameCreatesAndSendsKeys() = runTest {
        // Class-cover (c): a distinct directory derives a distinct name that does
        // not collide with any open session — has-session exits non-zero, create
        // runs, send-keys fires. No regression for the no-collision common case.
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(
                    match = "has-session",
                    result = ExecResult(stdout = "", stderr = "no session", exitCode = 1),
                ),
                CreateSessionFake.Rule(match = "create-detached", result = ok()),
                CreateSessionFake.Rule(match = "pocketshell agent --help", result = ok()),
                CreateSessionFake.Rule(match = "send-keys", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        gateway.createSessionOnSession(
            session = session,
            sessionName = "var-log",
            cwd = "/var/log",
            startCommand = "pocketshell agent claude --dir '/var/log'",
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertTrue(session.execCommands.any { it.contains("create-detached") })
        assertTrue(session.execCommands.any { it.contains("send-keys") })
    }

    @Test
    fun shellLaunchIntoAnAlreadyOpenSameNameSessionRefusesAndDoesNotSendKeys() = runTest {
        // Class-cover: the same collision guard applies to a SHELL launch (the
        // path-prefix name is shared by agent AND shell — #642). A shell start
        // command into an already-open same-name session must also refuse rather
        // than type into the existing pane.
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(match = "has-session", result = ok()),
                CreateSessionFake.Rule(match = "create-detached", result = ok()),
                CreateSessionFake.Rule(match = "send-keys", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        val ex = runCatching {
            gateway.createSessionOnSession(
                session = session,
                sessionName = SESSION_NAME,
                cwd = CWD,
                startCommand = "htop",
                namePolicy = SessionNamePolicy.ExactName,
            )
        }.exceptionOrNull()

        assertTrue("a colliding shell launch must surface an error, got $ex", ex is RuntimeException)
        assertFalse(
            "must NOT send-keys a shell launch into an already-open session",
            session.execCommands.any { it.contains("send-keys") },
        )
    }

    @Test
    fun plainRepickWithNoLaunchKeepsIdempotentAttachOrCreate() = runTest {
        // A plain re-pick (no startCommand) must KEEP the idempotent
        // attach-or-create semantics (#642/#429) — it does NOT probe has-session
        // and does NOT refuse on an existing name (the collision guard is scoped
        // to the LAUNCH case only, so the re-pick UX is unchanged).
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(match = "create-detached", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        gateway.createSessionOnSession(
            session = session,
            sessionName = SESSION_NAME,
            cwd = CWD,
            startCommand = null,
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertFalse(
            "a no-launch re-pick must not probe has-session",
            session.execCommands.any { it.contains("has-session") },
        )
        assertTrue(
            "a no-launch re-pick must still run the idempotent create",
            session.execCommands.any { it.contains("create-detached") },
        )
    }

    // --- Issue #1820: session-name uniqueness is resolved on the HOST -------
    //
    // The reported defect: the in-session "+ New session" picker published
    // `Ready` with a session list that OMITTED the very session the app was
    // attached to over `-CC`, so the screen derived the colliding base name and
    // the create either silently attached to the existing session or failed
    // (`open terminal failed: not a terminal`). No new session for the user.
    //
    // The client can no longer make that decision at all — these pin that the
    // gateway asks the host, at create time, and threads the ANSWER through the
    // create, the launch and the return value. (The remote script itself runs
    // against a real tmux in the connected
    // TmuxInSessionNewSessionCollisionDockerTest; a fake session can only prove
    // the wiring around it.)

    @Test
    fun uniqueOnHostCreatesTheNameTheHostSaysIsFreeNotTheRequestedBase() = runTest {
        // The host answers the free-name probe with the `-2` variant, i.e. the
        // requested base is already live there. Every downstream step must use
        // THAT name — this is the load-bearing assertion for #1820.
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(
                    match = "__ps_n=",
                    result = ExecResult(stdout = "$SESSION_NAME-2\n", stderr = "", exitCode = 0),
                ),
                CreateSessionFake.Rule(match = "create-detached", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        val resolved = gateway.createSessionOnSession(
            session = session,
            sessionName = SESSION_NAME,
            cwd = CWD,
            startCommand = null,
            namePolicy = SessionNamePolicy.UniqueOnHost,
        )

        assertEquals(SessionCreateOutcome.Created("$SESSION_NAME-2"), resolved)
        val create = session.execCommands.single { it.contains("create-detached") }
        assertTrue(
            "the create must target the host-resolved name, got: $create",
            create.contains("'$SESSION_NAME-2'"),
        )
    }

    @Test
    fun uniqueOnHostLaunchSendsKeysToTheResolvedNameNotTheCollidingBase() = runTest {
        // Class coverage: the AGENT/shell LAUNCH path. Before #1820 a colliding
        // base here was the #976 misroute — `send-keys` typing the launch line
        // into the already-attached pane — and latterly a hard refusal. With the
        // host resolving the name, the launch proceeds into the NEW session.
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(
                    match = "__ps_n=",
                    result = ExecResult(stdout = "$SESSION_NAME-3\n", stderr = "", exitCode = 0),
                ),
                // has-session on the RESOLVED name: free, so the #976 guard
                // passes and the launch is allowed to proceed.
                CreateSessionFake.Rule(match = "has-session", result = ExecResult("", "", 1)),
                CreateSessionFake.Rule(match = "agent --help", result = ok()),
                CreateSessionFake.Rule(match = "create-detached", result = ok()),
                CreateSessionFake.Rule(match = "send-keys", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        val resolved = gateway.createSessionOnSession(
            session = session,
            sessionName = SESSION_NAME,
            cwd = CWD,
            startCommand = "htop",
            namePolicy = SessionNamePolicy.UniqueOnHost,
        )

        assertEquals(SessionCreateOutcome.Created("$SESSION_NAME-3"), resolved)
        val sendKeys = session.execCommands.single { it.contains("send-keys") }
        // The command is wrapped in `/bin/sh -lc '...'`, so the inner single
        // quotes are shell-escaped; match the quoted NAME token rather than the
        // `-t '<name>'` pair. Since #1820 round 2 the pane target is also EXACT
        // (`=<name>:`), so the token to look for is `'=work session-3:'`. The
        // negative is the one that carries the bug: `=work session:` (separator
        // straight after the base) can only appear if the launch went to the
        // colliding base instead.
        assertTrue(
            "the launch must be typed into the RESOLVED session, got: $sendKeys",
            sendKeys.contains("'=$SESSION_NAME-3:'"),
        )
        assertFalse(
            "the launch must NOT target the colliding base, got: $sendKeys",
            sendKeys.contains("'=$SESSION_NAME:'"),
        )
        val guard = session.execCommands.single { it.contains("has-session") && !it.contains("__ps_n=") }
        assertTrue(
            "the #976 guard must probe the resolved name by EXACT match, got: $guard",
            guard.contains("'=$SESSION_NAME-3'"),
        )
    }

    @Test
    fun exactNameSkipsTheHostProbeEntirely() = runTest {
        // The stale-session recovery path names the session the user is getting
        // back. It must NOT be handed a `-2`, and it must not pay for a probe.
        val session = CreateSessionFake(
            results = listOf(CreateSessionFake.Rule(match = "create-detached", result = ok())),
        )
        val gateway = SshFolderListGateway()

        val resolved = gateway.createSessionOnSession(
            session = session,
            sessionName = SESSION_NAME,
            cwd = CWD,
            startCommand = null,
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertEquals(SessionCreateOutcome.Created(SESSION_NAME), resolved)
        assertFalse(
            "ExactName must not run the free-name walk",
            session.execCommands.any { it.contains("__ps_n=") },
        )
    }

    @Test
    fun uniqueOnHostFallsBackToTheRequestedBaseWhenTheProbeFails() = runTest {
        // Fail-safe: a create must never be BLOCKED by the uniqueness probe. On
        // a probe error we degrade to exactly the pre-#1820 behaviour (request
        // the base, let the idempotent create attach) rather than erroring out.
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(
                    match = "__ps_n=",
                    result = ExecResult(stdout = "", stderr = "tmux: not found", exitCode = 127),
                ),
                CreateSessionFake.Rule(match = "create-detached", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        val resolved = gateway.createSessionOnSession(
            session = session,
            sessionName = SESSION_NAME,
            cwd = CWD,
            startCommand = null,
            namePolicy = SessionNamePolicy.UniqueOnHost,
        )

        assertEquals(SessionCreateOutcome.Created(SESSION_NAME), resolved)
        assertTrue(
            "a failed probe must still create",
            session.execCommands.any { it.contains("create-detached") },
        )
    }

    @Test
    fun freeSessionNameCommandUsesExactMatchAndABoundedWalk() {
        // `-t "=$n"` is load-bearing: without the `=`, tmux falls back to prefix
        // matching, so probing `foo` while only `foo-2` exists answers "taken"
        // and the walk skips a genuinely free name. The bound stops a
        // pathological host spinning the remote shell forever.
        val command = SshFolderListGateway.freeSessionNameCommand("'work session'")

        assertTrue("must exact-match: $command", command.contains("has-session -t \"=\$__ps_n\""))
        assertTrue("must seed from the quoted base: $command", command.contains("__ps_n='work session'"))
        assertTrue("must build the suffix from the quoted base: $command", command.contains("__ps_n='work session'-\$__ps_i"))
        assertTrue("must start at -2: $command", command.contains("__ps_i=2"))
        assertTrue(
            "must bound the walk: $command",
            command.contains("-gt ${SshFolderListGateway.FREE_SESSION_NAME_MAX_SUFFIX}"),
        )
        assertTrue("must emit the chosen name: $command", command.contains("printf '%s\\n' \"\$__ps_n\""))
    }

    /**
     * A configurable fake [SshSession] for the create-session path: it records
     * every command, returns the start-directory-exists probe as success, and
     * resolves create/fallback/send-keys commands against ordered [Rule]s
     * (first matching substring wins). Anything unmatched is a failure so a
     * mis-routed command surfaces in the test rather than passing silently.
     */
    private class CreateSessionFake(
        private val results: List<Rule>,
    ) : SshSession {
        data class Rule(val match: String, val result: ExecResult)

        val execCommands = mutableListOf<String>()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            // The start-directory existence probe must pass so creation proceeds.
            if (command.contains("test -d")) {
                return ok()
            }
            val rule = results.firstOrNull { command.contains(it.match) }
            return rule?.result
                ?: ExecResult(stdout = "", stderr = "unexpected command: $command", exitCode = 1)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() = Unit
    }

    private class FamilyGateway(private val customId: String) : EnginesGateway {
        val resolvedRawIds = mutableListOf<String?>()

        override suspend fun listEngines(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
        ) = error("not used")

        override fun familyForRawId(hostId: Long, rawId: String?): SessionAgentKind? {
            resolvedRawIds += rawId
            return SessionAgentKind.Codex.takeIf { rawId == customId }
        }
    }

    private class FakeSshSession(
        private val pocketshellResult: ExecResult,
        private val structuredResult: ExecResult? = null,
        private val blockStructuredProbe: Boolean = false,
    ) : SshSession {
        val execCommands = mutableListOf<String>()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return when {
                command.contains(STRUCTURED_PROBE_HEAD) -> {
                    if (blockStructuredProbe) awaitCancellation()
                    structuredResult ?: ExecResult(
                        stdout = "",
                        stderr = "tmux: no server running",
                        exitCode = 1,
                    )
                }
                command.contains(SshFolderListGateway.POCKETSHELL_SESSIONS_JSON_COMMAND) ->
                    ExecResult(stdout = "", stderr = "", exitCode = 1)
                command.contains(SshFolderListGateway.POCKETSHELL_SESSIONS_COMMAND) -> pocketshellResult
                else -> ExecResult(stdout = "", stderr = "unexpected command: $command", exitCode = 1)
            }
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() = Unit
    }

    private companion object {
        const val SESSION_NAME = "work session"
        const val CWD = "/home/me/proj dir"

        // `pathAwareCommand` shell-quotes the format string, so the complete
        // production constant is escaped in the command observed by this fake.
        // The stable command head still identifies exactly the supported raw
        // tmux enrichment probe without matching the unsupported `--json` path.
        val STRUCTURED_PROBE_HEAD =
            SshFolderListGateway.POCKETSHELL_SESSIONS_TMUX_COMMAND.substringBefore(" -F")

        val HOST = HostEntity(
            id = 1L,
            name = "Walkthrough Docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 10L,
        )
    }
}

private fun ok(): ExecResult = ExecResult(stdout = "", stderr = "", exitCode = 0)

// --- Issue #726 test helpers: reproduce the production pathAware wrapping ---
//
// `SshFolderListGateway.createSessionOnSession` routes its commands through
// `pathAware(...)` -> `ReposRemoteSource.pathAwareCommand`, which wraps the body
// in `/bin/sh -lc '<PATH=…; body>'`. Because the whole body is single-quoted by
// the wrapper, every inner single quote is re-escaped to `'"'"'`. These helpers
// mirror that exact transform (ReposRemoteSource.pathAwareCommand / shellQuote)
// so assertions check the real recorded string instead of the pre-wrap form.

/** Single-quote a value, escaping inner quotes — mirrors `shellQuoteValue`. */
private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

/** Wrap a command exactly as production `pathAware` does. */
private fun pathAware(command: String): String =
    "/bin/sh -lc " + shellQuote("PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:\$PATH\"; $command")

/**
 * The form a single-quoted value takes once it is INSIDE the pathAware wrapper:
 * the inner `'<value>'` has each `'` re-escaped by the outer wrapper's quoting.
 * e.g. `work session` -> `'"'"'work session'"'"'`.
 */
private fun escapedInWrapper(value: String): String =
    shellQuote(value).replace("'", "'\"'\"'")
