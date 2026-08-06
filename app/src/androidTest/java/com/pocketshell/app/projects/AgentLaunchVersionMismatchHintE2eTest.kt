package com.pocketshell.app.projects

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.FakeOldHostSshSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device (instrumented) proof for issue #759: when the host's `pocketshell`
 * is OUTDATED (it predates the `agent` subcommand, the maintainer's v0.3.34
 * dogfood failure), an agent launch surfaces the friendly "update pocketshell"
 * hint instead of the cryptic Click `No such command 'agent'` error — and never
 * types the doomed agent line into the new pane.
 *
 * This runs the REAL production wiring
 * ([SshFolderListGateway.createSessionOnSession], the same code the picker
 * confirm path calls) on the Android runtime, against an on-device fake
 * [com.pocketshell.core.ssh.SshSession] that injects the outdated-host
 * `agent --help` probe output via a test seam. The fake stands in for an
 * outdated remote so the proof is deterministic and needs no separate Docker
 * fixture (the deterministic `agents` fixture ships a CURRENT `pocketshell`
 * that DOES have `agent`) — it is the non-happy-host fixture G10 asks for.
 *
 * ## Issue #1928: the same hint, correctly ACCOUNTED FOR
 *
 * The version pre-flight runs AFTER the tmux session has been created, and it
 * used to THROW — so this exact scenario reported "Couldn't create session:
 * <hint>" while an empty session sat on the host, unmentioned. It is now the
 * reason of a [SessionCreateOutcome.LaunchFailed]: same words, but the outcome
 * says the session EXISTS, and the surfaces render it as "Session “…” was
 * created, but the agent didn't start: <hint>". This class asserts the whole
 * shape — the outcome type, the session it names, the hint, the doomed line NOT
 * being typed, and the created session NOT being cleaned up.
 *
 * The captured before/after + recorded commands are written under the app's
 * external files dir (`agent-version-mismatch/`) per the artifact rules.
 */
@RunWith(AndroidJUnit4::class)
class AgentLaunchVersionMismatchHintE2eTest {

    @Test
    fun outdatedHostAgentLaunchSurfacesFriendlyHintOnDevice(): Unit { runBlocking {
        val gateway = SshFolderListGateway()
        // Reusable extracted seam (issue #853): an old host that rejects the
        // new-in-this-release subcommands (here: `agent`, surfaced by the
        // launch pre-flight). The default installed version (0.3.33) predates
        // the `agent` subcommand — the #759 maintainer dogfood host.
        val session = FakeOldHostSshSession()

        val outcome = runCatching {
            gateway.createSessionOnSession(
                session = session,
                sessionName = "issue759-outdated",
                cwd = "/home/alexey/tmp/test",
                // The exact short wrapper line the picker builds for a Claude
                // agent launch (issue #703).
                startCommand = "pocketshell agent claude --dir '/home/alexey/tmp/test'",
                namePolicy = SessionNamePolicy.UniqueOnHost,
            )
        }

        // Issue #1928: an outdated host is a LAUNCH failure, not a create
        // failure — the tmux session was created one step earlier and is the
        // user's to keep. Reporting it as a create failure left an orphan
        // session the user was never told about.
        val value = outcome.getOrNull()
        assertTrue(
            "an outdated host must not fail the CREATE — the session exists; got $outcome",
            value is SessionCreateOutcome.LaunchFailed,
        )
        val launchFailed = value as SessionCreateOutcome.LaunchFailed
        assertEquals(
            "the outcome must name the session that exists on the host",
            "issue759-outdated",
            launchFailed.sessionName,
        )
        val hint = launchFailed.detail

        // The friendly, actionable hint — names the installed version, the
        // required minimum, and a copyable update command.
        assertTrue(
            "hint must name installed version: $hint",
            hint.contains(FakeOldHostSshSession.DEFAULT_OLD_VERSION),
        )
        assertTrue(
            "hint must name required minimum: $hint",
            hint.contains(AgentLaunchVersionCheck.MIN_AGENT_POCKETSHELL_VERSION),
        )
        assertTrue(
            "hint must give a copyable update command: $hint",
            hint.contains(AgentLaunchVersionCheck.UPDATE_COMMAND),
        )
        // The raw Click jargon must NOT leak to the user.
        assertFalse("raw Click error must not leak: $hint", hint.contains("No such command"))
        // Regression (chronic emulator red): the #976 launch-collision guard
        // (`tmux has-session`) runs BEFORE the version pre-flight. For a
        // fresh-name launch the session is ABSENT, so the guard must NOT fire
        // and must NOT short-circuit the version hint with its "already open"
        // collision message. This is exactly the failure that kept this E2E red
        // for days (the fake reported a never-created session as already open).
        assertFalse(
            "launch-collision guard must not short-circuit the version pre-flight: $hint",
            hint.contains("already open"),
        )
        // The doomed agent line must NOT be typed into the pane.
        assertFalse(
            "must not send-keys a launch that will fail: ${session.execCommands}",
            session.execCommands.any { it.contains("send-keys") },
        )
        // The pre-flight probe DID run (this is what caught the mismatch).
        assertTrue(
            "must have pre-flighted `pocketshell agent --help`: ${session.execCommands}",
            session.execCommands.any { it.contains("pocketshell agent --help") },
        )
        // Issue #1928: the session really was created, and the failed launch
        // must not have taken it away again.
        assertTrue(
            "the tmux session must have been created before the launch: ${session.execCommands}",
            session.execCommands.any { it.contains("create-detached") || it.contains("new-session") },
        )
        assertFalse(
            "a failed launch must never kill the created session: ${session.execCommands}",
            session.execCommands.any { it.contains("kill-session") },
        )

        // And the sentence the user actually reads names the session AND the hint.
        val userMessage = sessionLaunchFailedMessage(launchFailed.sessionName, hint)
        assertTrue(userMessage, userMessage.contains("issue759-outdated"))
        assertTrue(userMessage, userMessage.contains(AgentLaunchVersionCheck.UPDATE_COMMAND))

        writeArtifact(hint, userMessage, session.execCommands)
    } }

    private fun writeArtifact(hint: String, userMessage: String, commands: List<String>) {
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir(null),
            "agent-version-mismatch",
        ).apply { mkdirs() }
        File(dir, "outdated-host-hint.txt").writeText(
            buildString {
                appendLine("=== issue #759 outdated-host agent-launch hint (on device) ===")
                appendLine("--- raw Click error the OLD behaviour would have shown ---")
                appendLine("Error: No such command 'agent'. (Did you mean one of: 'agent-log', 'usage'?)")
                appendLine()
                appendLine("--- launch-failure reason carried by SessionCreateOutcome.LaunchFailed ---")
                appendLine(hint)
                appendLine()
                appendLine("--- issue #1928 sentence the user reads (session kept, agent absent) ---")
                appendLine(userMessage)
                appendLine()
                appendLine("--- commands the gateway issued over the lease (no send-keys, no kill) ---")
                commands.forEach { appendLine(it) }
            },
        )
    }
}
