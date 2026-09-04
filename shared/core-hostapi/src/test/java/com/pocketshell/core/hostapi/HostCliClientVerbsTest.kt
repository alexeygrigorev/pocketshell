package com.pocketshell.core.hostapi

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * End-to-end behaviour of each verb over a scripted [RemoteExec]: what comes
 * back for a real host answer, and — the half that matters more — what comes
 * back when the host does not answer well.
 *
 * The invariant every failure case here asserts is the same one: the client
 * returns `Result.failure(HostCliError)`. Nothing throws, so no caller has to
 * wrap a verb in `try`/`catch` to stay alive, and every failure arrives as a
 * typed value the UI can branch on.
 */
class HostCliClientVerbsTest {

    // --- listSessions -----------------------------------------------------

    @Test
    fun `listSessions parses a real host listing`() {
        val result = runSuspending {
            HostCliClient(RecordingExec.ok(fixture("sessions-list-real.json"))).listSessions()
        }

        val listing = result.getOrThrow()
        assertEquals(15, listing.sessions.size)
        assertEquals(emptyList<BackendError>(), listing.errors)
    }

    @Test
    fun `listSessions keeps a partial listing's backend errors`() {
        val result = runSuspending {
            HostCliClient(RecordingExec.ok(fixture("sessions-list-errors.json"))).listSessions()
        }

        val listing = result.getOrThrow()
        assertEquals(listOf("git-pocketshell"), listing.sessions.map { it.name })
        assertEquals(listOf("aplexer"), listing.errors.map { it.manager })
    }

    @Test
    fun `listSessions reports a missing host CLI as a command failure, not a parse failure`() {
        val exec = RecordingExec.exit(
            code = 127,
            stderr = "bash: line 1: pocketshell: command not found\n",
        )

        val error = runSuspending { HostCliClient(exec).listSessions() }.hostCliError()

        val failed = error as HostCliError.Failed
        assertEquals(127, failed.exitCode)
        assertEquals("pocketshell sessions list --json", failed.command)
        assertFalse(failed.timedOut)
        assertEquals(
            "`pocketshell sessions list --json` failed on the host (exit 127): " +
                "bash: line 1: pocketshell: command not found",
            failed.userMessage,
        )
        assertEquals("bash: line 1: pocketshell: command not found\n", failed.stderr)
    }

    @Test
    fun `listSessions reports a timeout with no exit code`() {
        val error = runSuspending { HostCliClient(RecordingExec.timedOut()).listSessions() }
            .hostCliError()

        val failed = error as HostCliError.Failed
        assertTrue(failed.timedOut)
        // The scripted outcome carries exitCode -999; surfacing it would be a
        // lie, because a timed-out command never exited at all.
        assertNull(failed.exitCode)
        assertEquals(
            "`pocketshell sessions list --json` did not finish within 20000ms on the host.",
            failed.userMessage,
        )
    }

    @Test
    fun `listSessions turns a broken transport into a failure instead of a throw`() {
        val boom = IOException("channel closed")

        val error = runSuspending { HostCliClient(RecordingExec.throwing(boom)).listSessions() }
            .hostCliError()

        val failed = error as HostCliError.Failed
        assertSame(boom, failed.cause)
        assertNull(failed.exitCode)
        assertEquals(
            "Could not run `pocketshell sessions list --json` on the host: channel closed",
            failed.userMessage,
        )
    }

    @Test
    fun `listSessions rethrows cancellation instead of reporting a host failure`() {
        // A cancelled screen must not surface as "the host failed".
        val cancel = CancellationException("screen left")
        try {
            runSuspending { HostCliClient(RecordingExec.throwing(cancel)).listSessions() }
            fail("expected the CancellationException to propagate")
        } catch (e: CancellationException) {
            assertSame(cancel, e)
        }
    }

    @Test
    fun `listSessions rejects an empty stdout instead of reporting zero sessions`() {
        val error = runSuspending { HostCliClient(RecordingExec.ok("   \n")).listSessions() }
            .hostCliError()

        assertTrue(error is HostCliError.Malformed)
        assertEquals(
            "Could not read the host's response: " +
                "`pocketshell sessions list --json` printed nothing on stdout",
            error.userMessage,
        )
    }

    @Test
    fun `listSessions rejects non-JSON stdout`() {
        val exec = RecordingExec.ok("Usage: pocketshell sessions list [OPTIONS]\n")

        val error = runSuspending { HostCliClient(exec).listSessions() }.hostCliError()

        assertTrue(error is HostCliError.Malformed)
        assertTrue(error.userMessage.contains("was not valid JSON"))
    }

    @Test
    fun `listSessions surfaces an outdated host CLI as TooOld`() {
        val exec = RecordingExec.ok(fixture("sessions-list-schema1.json"))

        val error = runSuspending { HostCliClient(exec).listSessions() }.hostCliError()

        val tooOld = error as HostCliError.TooOld
        assertEquals(1, tooOld.foundSchema)
        assertEquals(2, tooOld.requiredSchema)
    }

    // --- createSession ----------------------------------------------------

    @Test
    fun `createSession parses a real tmux create`() {
        val exec = RecordingExec.ok(fixture("create-tmux-real.json"))

        val created = runSuspending {
            HostCliClient(exec).createSession(name = "k2-fixture-tmp", cwd = "/tmp")
        }.getOrThrow()

        assertEquals(
            CreatedSession(
                name = "k2-fixture-tmp",
                manager = Backend.TMUX,
                id = null,
                created = true,
            ),
            created,
        )
    }

    @Test
    fun `createSession treats an already-existing session as success`() {
        // Real second-run capture: the host's create is idempotent, and
        // `created: false` is a SUCCESS the reconnect path depends on.
        val exec = RecordingExec.ok(fixture("create-existing-real.json"))

        val created = runSuspending {
            HostCliClient(exec).createSession(name = "k2-fixture-tmp", cwd = "/tmp")
        }.getOrThrow()

        assertFalse(created.created)
        assertEquals("k2-fixture-tmp", created.name)
        assertEquals(Backend.TMUX, created.manager)
    }

    @Test
    fun `createSession reads an aplexer create's id`() {
        val exec = RecordingExec.ok(fixture("create-aplexer.json"))

        val created = runSuspending {
            HostCliClient(exec).createSession(name = "work", engine = "claude")
        }.getOrThrow()

        assertEquals("pocketshell:work", created.name)
        assertEquals(Backend.APLEXER, created.manager)
        assertEquals("0d5a4d1e-6a5c-4a1e-9d2f-5b7a0c3e8f11", created.id)
    }

    @Test
    fun `createSession keeps a session created by an unknown manager`() {
        val exec = RecordingExec.ok(fixture("create-unknown-manager.json"))

        val created = runSuspending { HostCliClient(exec).createSession(name = "x") }.getOrThrow()

        // A newer host growing a third manager must not make the phone think
        // the create failed — the session really does exist now.
        assertEquals(Backend.UNKNOWN, created.manager)
        assertEquals("future-session", created.name)
    }

    @Test
    fun `createSession prefers the host's own error text over the exit code`() {
        // Real capture: `sessions create --json` with tmuxctl off PATH exits
        // 127 and prints its explanation as JSON on STDOUT.
        val exec = RecordingExec.exit(code = 127, stdout = fixture("create-error-real.json"))

        val error = runSuspending {
            HostCliClient(exec).createSession(name = "work", cwd = "/tmp")
        }.hostCliError()

        val failed = error as HostCliError.Failed
        assertEquals(
            "pocketshell: `tmuxctl` is not installed on this host. " +
                "Install it via `uv tool install tmuxctl` or `pipx install tmuxctl` and re-run.",
            failed.userMessage,
        )
        assertEquals(127, failed.exitCode)
    }

    @Test
    fun `createSession falls back to the exit code when the failure is not JSON`() {
        val exec = RecordingExec.exit(
            code = 2,
            stdout = "Usage: pocketshell sessions create [OPTIONS] NAME\n",
            stderr = "Error: no such option: --profile\n",
        )

        val error = runSuspending { HostCliClient(exec).createSession(name = "work") }
            .hostCliError()

        val failed = error as HostCliError.Failed
        assertEquals(2, failed.exitCode)
        assertEquals(
            "`pocketshell sessions create --json -- 'work'` failed on the host (exit 2): " +
                "Error: no such option: --profile",
            failed.userMessage,
        )
    }

    @Test
    fun `createSession surfaces an outdated host CLI as TooOld`() {
        val exec = RecordingExec.ok(fixture("create-schema1.json"))

        val error = runSuspending { HostCliClient(exec).createSession(name = "work") }
            .hostCliError()

        assertEquals(1, (error as HostCliError.TooOld).foundSchema)
    }

    @Test
    fun `createSession rejects a response with no session name`() {
        val exec = RecordingExec.ok(fixture("create-malformed.json"))

        val error = runSuspending { HostCliClient(exec).createSession(name = "work") }
            .hostCliError()

        assertTrue(error is HostCliError.Malformed)
        assertTrue(error.userMessage.contains("create response did not match the expected shape"))
    }

    @Test
    fun `createSession rejects a response with no schema`() {
        val exec = RecordingExec.ok("""{"name":"work","manager":"tmux","created":true}""")

        val error = runSuspending { HostCliClient(exec).createSession(name = "work") }
            .hostCliError()

        assertTrue(error is HostCliError.Malformed)
        assertTrue(error.userMessage.contains("missing the `schema` field"))
    }

    @Test
    fun `createSession reports the exit code when the host said nothing at all`() {
        val exec = RecordingExec.exit(code = 1, stderr = "boom\n")

        val error = runSuspending { HostCliClient(exec).createSession(name = "work") }
            .hostCliError()

        assertEquals(1, (error as HostCliError.Failed).exitCode)
    }

    @Test
    fun `createSession times out on the create budget, not the list budget`() {
        val exec = RecordingExec.timedOut()

        val error = runSuspending { HostCliClient(exec).createSession(name = "work") }
            .hostCliError()

        assertTrue((error as HostCliError.Failed).timedOut)
        assertTrue(error.userMessage.contains("within 60000ms"))
        assertEquals(listOf(HostCliClient.CREATE_TIMEOUT_MS), exec.timeouts)
    }

    // --- listEngines ------------------------------------------------------

    @Test
    fun `listEngines parses a real registry`() {
        val engines = runSuspending {
            HostCliClient(RecordingExec.ok(fixture("engines-list-real.json"))).listEngines()
        }.getOrThrow()

        assertEquals(
            listOf("claude", "codex", "opencode", "grok", "zcodex"),
            engines.map { it.id },
        )
    }

    @Test
    fun `listEngines fails on a non-zero exit`() {
        val exec = RecordingExec.exit(code = 1, stderr = "Traceback (most recent call last):\n  ...\n")

        val error = runSuspending { HostCliClient(exec).listEngines() }.hostCliError()

        val failed = error as HostCliError.Failed
        assertEquals(1, failed.exitCode)
        assertTrue(failed.userMessage.endsWith(": Traceback (most recent call last):"))
    }

    @Test
    fun `listEngines fails on a payload that is not the engines envelope`() {
        val exec = RecordingExec.ok(fixture("engines-list-missing-key.json"))

        val error = runSuspending { HostCliClient(exec).listEngines() }.hostCliError()

        assertTrue(error is HostCliError.Malformed)
        assertTrue(error.userMessage.contains("missing the `engines` field"))
    }

    // --- listProfiles -----------------------------------------------------

    @Test
    fun `listProfiles parses a real profile list`() {
        val profiles = runSuspending {
            HostCliClient(RecordingExec.ok(fixture("profiles-list-real.json"))).listProfiles()
        }.getOrThrow()

        assertEquals(6, profiles.size)
        assertEquals(
            listOf("Claude", "Codex"),
            profiles.filter { it.isDefault }.map { it.name },
        )
    }

    @Test
    fun `listProfiles fails on a malformed row rather than dropping it`() {
        val exec = RecordingExec.ok(fixture("profiles-list-malformed-row.json"))

        val error = runSuspending { HostCliClient(exec).listProfiles() }.hostCliError()

        assertTrue(error is HostCliError.Malformed)
    }

    @Test
    fun `listProfiles fails on a timeout`() {
        val error = runSuspending { HostCliClient(RecordingExec.timedOut()).listProfiles() }
            .hostCliError()

        assertTrue((error as HostCliError.Failed).timedOut)
        assertEquals("pocketshell profiles list --json", error.command)
    }

    // --- one exec per call ------------------------------------------------

    @Test
    fun `a verb runs exactly one command with no retry`() {
        // Retry is explicitly not this layer's job: the caller decides what a
        // failure means. A silent retry here would double every create.
        val exec = RecordingExec.exit(code = 1, stderr = "nope\n")

        runSuspending { HostCliClient(exec).listSessions() }
        runSuspending { HostCliClient(exec).createSession(name = "work") }
        runSuspending { HostCliClient(exec).listEngines() }
        runSuspending { HostCliClient(exec).listProfiles() }

        assertEquals(4, exec.commands.size)
    }
}
