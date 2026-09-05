package com.pocketshell.core.hostapi

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte assertions on every command line [HostCliClient] emits.
 *
 * These strings are the contract with the host: they are handed to a remote
 * shell verbatim, so a stray space, a dropped `--`, or a re-ordered flag is a
 * production break that no type checks. Each expected string here was also run
 * against the real `pocketshell` CLI on the dev box (schema-2 build) — e.g.
 *
 *     sh -c "exec pocketshell sessions attach --hide-status -- 'it'\''s a test'"
 *     -> no session named "it's a test"   (exit 3)
 *
 * i.e. the CLI received the name back intact, including the apostrophe, and
 * accepted the `--` option terminator.
 */
class HostCliCommandStringTest {

    private fun client(exec: RemoteExec = RecordingExec.ok("{}"), binary: String? = null) =
        if (binary == null) HostCliClient(exec) else HostCliClient(exec, binary)

    // --- attach -----------------------------------------------------------

    @Test
    fun `attach hides the status bar by default and terminates the options`() {
        assertEquals(
            "exec pocketshell sessions attach --hide-status -- 'work'",
            client().attachCommand("work"),
        )
    }

    @Test
    fun `attach without hideStatus drops only that flag`() {
        assertEquals(
            "exec pocketshell sessions attach -- 'work'",
            client().attachCommand("work", hideStatus = false),
        )
    }

    @Test
    fun `attach quotes an apostrophe in the session name`() {
        assertEquals(
            "exec pocketshell sessions attach --hide-status -- 'it'\\''s a test'",
            client().attachCommand("it's a test"),
        )
    }

    @Test
    fun `attach keeps a unicode session name intact`() {
        assertEquals(
            "exec pocketshell sessions attach --hide-status -- 'ünïcødé пример'",
            client().attachCommand("ünïcødé пример"),
        )
    }

    @Test
    fun `a session name that looks like a flag is still a name`() {
        // Without the `--` terminator this would be parsed as an option and
        // the user would get the CLI's help text instead of their session.
        assertEquals(
            "exec pocketshell sessions attach --hide-status -- '--help'",
            client().attachCommand("--help"),
        )
    }

    @Test
    fun `a custom binary path replaces the command word only`() {
        assertEquals(
            "exec ~/.local/bin/pocketshell sessions attach --hide-status -- 'work'",
            client(binary = "~/.local/bin/pocketshell").attachCommand("work"),
        )
    }

    // --- commands that are actually run ------------------------------------

    @Test
    fun `listSessions runs the json list verb on the list budget`() {
        val exec = RecordingExec.ok(fixture("sessions-list-real.json"))

        runSuspending { HostCliClient(exec).listSessions() }

        assertEquals("pocketshell sessions list --json", exec.command)
        assertEquals(listOf(HostCliClient.LIST_TIMEOUT_MS), exec.timeouts)
    }

    @Test
    fun `listEngines runs the json engines verb`() {
        val exec = RecordingExec.ok(fixture("engines-list-real.json"))

        runSuspending { HostCliClient(exec).listEngines() }

        assertEquals("pocketshell engines list --json", exec.command)
        assertEquals(listOf(HostCliClient.LIST_TIMEOUT_MS), exec.timeouts)
    }

    @Test
    fun `listProfiles runs the json profiles verb`() {
        val exec = RecordingExec.ok(fixture("profiles-list-real.json"))

        runSuspending { HostCliClient(exec).listProfiles() }

        assertEquals("pocketshell profiles list --json", exec.command)
        assertEquals(listOf(HostCliClient.LIST_TIMEOUT_MS), exec.timeouts)
    }

    @Test
    fun `createSession with only a name omits every optional flag`() {
        val exec = RecordingExec.ok(fixture("create-tmux-real.json"))

        runSuspending { HostCliClient(exec).createSession(name = "work") }

        assertEquals("pocketshell sessions create --json -- 'work'", exec.command)
        assertEquals(listOf(HostCliClient.CREATE_TIMEOUT_MS), exec.timeouts)
    }

    @Test
    fun `createSession quotes every argument it forwards`() {
        val exec = RecordingExec.ok(fixture("create-tmux-real.json"))

        runSuspending {
            HostCliClient(exec).createSession(
                name = "alexey's work",
                cwd = "/home/alexey/git/pocket shell",
                engine = "claude",
                profile = "Claude (Z.AI)",
            )
        }

        assertEquals(
            "pocketshell sessions create --json " +
                "--cwd '/home/alexey/git/pocket shell' " +
                "--engine 'claude' " +
                "--profile 'Claude (Z.AI)' " +
                "-- 'alexey'\\''s work'",
            exec.command,
        )
    }

    @Test
    fun `createSession keeps cwd when engine and profile are absent`() {
        val exec = RecordingExec.ok(fixture("create-tmux-real.json"))

        runSuspending {
            HostCliClient(exec).createSession(name = "work", cwd = "/tmp")
        }

        assertEquals("pocketshell sessions create --json --cwd '/tmp' -- 'work'", exec.command)
    }

    @Test
    fun `createSession forwards backend when set`() {
        val exec = RecordingExec.ok(fixture("create-tmux-real.json"))

        runSuspending {
            HostCliClient(exec).createSession(name = "work", backend = "aplexer")
        }

        assertEquals(
            "pocketshell sessions create --json --backend 'aplexer' -- 'work'",
            exec.command,
        )
    }

    @Test
    fun `createSession forwards engine and backend together`() {
        val exec = RecordingExec.ok(fixture("create-tmux-real.json"))

        runSuspending {
            HostCliClient(exec).createSession(
                name = "work",
                engine = "claude",
                backend = "tmux",
            )
        }

        assertEquals(
            "pocketshell sessions create --json --engine 'claude' --backend 'tmux' -- 'work'",
            exec.command,
        )
    }

    @Test
    fun `a custom binary is used by the run verbs too`() {
        val exec = RecordingExec.ok(fixture("sessions-list-real.json"))

        runSuspending { HostCliClient(exec, "/opt/ps/bin/pocketshell").listSessions() }

        assertEquals("/opt/ps/bin/pocketshell sessions list --json", exec.command)
    }
}
