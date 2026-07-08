package com.pocketshell.app.projects

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.FakeOldHostSshSession
import kotlinx.coroutines.runBlocking
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
 * [SshSession] that injects the outdated-host `agent --help` probe output via a
 * test seam. The fake stands in for an outdated remote so the proof is
 * deterministic and needs no separate Docker fixture (the deterministic
 * `agents` fixture ships a CURRENT `pocketshell` that DOES have `agent`).
 *
 * The friendly hint is exactly the `RuntimeException` message that
 * `createSession` surfaces as `Result.failure`, which the folder-list view
 * model renders to the user as "Couldn't create session: <hint>"
 * (FolderListViewModel#createSession). Asserting the thrown message therefore
 * asserts the on-screen text.
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

        val error = runCatching {
            gateway.createSessionOnSession(
                session = session,
                sessionName = "issue759-outdated",
                cwd = "/home/alexey/tmp/test",
                // The exact short wrapper line the picker builds for a Claude
                // agent launch (issue #703).
                startCommand = "pocketshell agent claude --dir '/home/alexey/tmp/test'",
            )
        }.exceptionOrNull()

        val hint = error?.message.orEmpty()

        // The friendly, actionable hint — names the installed version, the
        // required minimum, and a copyable update command.
        assertTrue("expected a surfaced failure, got $error", error is RuntimeException)
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

        writeArtifact(hint, session.execCommands)
    } }

    private fun writeArtifact(hint: String, commands: List<String>) {
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
                appendLine("--- friendly hint surfaced to the user (createSession failure) ---")
                appendLine(hint)
                appendLine()
                appendLine("--- commands the gateway issued over the lease (no send-keys) ---")
                commands.forEach { appendLine(it) }
            },
        )
    }
}
