package com.pocketshell.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Issue #2378: run [TmuxSocketSweep]'s POSIX-sh probes for real.
 *
 * The gateway tests drive fakes that ANSWER these commands; nothing there
 * proves the scripts are valid shell, that the socket glob finds tmuxctl's
 * per-session sockets, or that the dedicated-before-default precedence is what
 * the code actually emits. So this test runs them through `/bin/sh` against a
 * throwaway socket directory holding REAL unix sockets and a stub `tmux` on
 * `PATH` that answers per socket — the same shape the maintainer's host has
 * (`default` plus `tmuxctl-<session>` servers).
 *
 * No tmux and no SSH involved, so it belongs in the JVM gate.
 */
class TmuxSocketSweepShellTest {

    @Test
    fun theNameSweepUnionsEverySocketNotJustTheDefaultOne() {
        val host = shellHost(
            "default" to listOf("legacy-leftover"),
            "tmuxctl-git-pocketshell" to listOf("git-pocketshell"),
            "tmuxctl-notes" to listOf("notes"),
        )

        val names = TmuxSocketSweep.parseLiveSessionNames(
            stdout = host.run(TmuxSocketSweep.liveSessionNamesCommand()).stdout,
            exitCode = 0,
        )

        assertEquals(
            "the sweep must see every socket's sessions",
            setOf("legacy-leftover", "git-pocketshell", "notes"),
            names,
        )
        assertEquals(
            "and the disambiguation walk must then skip the taken name",
            "git-pocketshell-2",
            TmuxSocketSweep.nextFreeSessionName("git-pocketshell", names),
        )
    }

    @Test
    fun locateFindsTheDedicatedSocketAheadOfTheDefaultOne() {
        // The exact orphan state #2378 reports: the SAME name on a dedicated
        // tmuxctl socket and on the default socket. tmuxctl treats the dedicated
        // server as authoritative, so a launch must be typed there.
        val host = shellHost(
            "default" to listOf("git-pocketshell"),
            "tmuxctl-git-pocketshell" to listOf("git-pocketshell"),
        )

        val located = TmuxSocketSweep.parseSessionSocket(
            stdout = host.run(TmuxSocketSweep.sessionSocketCommand("'git-pocketshell'")).stdout,
            exitCode = 0,
        )

        assertEquals(
            SessionSocket.Located("${host.socketDir}/tmuxctl-git-pocketshell"),
            located,
        )
        assertTrue(
            "the located client must target that socket explicitly",
            (located as SessionSocket.Located).tmuxClient
                .contains("tmux -S '${host.socketDir}/tmuxctl-git-pocketshell'"),
        )
    }

    @Test
    fun locateFallsBackToTheDefaultSocketTokenAndTheAbsentSentinel() {
        val host = shellHost(
            "default" to listOf("legacy-leftover"),
            "tmuxctl-notes" to listOf("notes"),
        )

        assertEquals(
            "a default-socket session answers the bare-client token",
            SessionSocket.Located(socket = null),
            TmuxSocketSweep.parseSessionSocket(
                host.run(TmuxSocketSweep.sessionSocketCommand("'legacy-leftover'")).stdout,
                0,
            ),
        )
        assertEquals(
            "a session on no socket answers Absent, never Unknown",
            SessionSocket.Absent,
            TmuxSocketSweep.parseSessionSocket(
                host.run(TmuxSocketSweep.sessionSocketCommand("'git-pocketshell'")).stdout,
                0,
            ),
        )
    }

    @Test
    fun locateUsesTmuxsExactSessionMatchNotAPrefixMatch() {
        // #1820: without the `=`, `has-session -t foo` matches a live `foo-2`,
        // which would make the walk skip a free name and point a launch at the
        // neighbour's pane.
        val host = shellHost("tmuxctl-git-pocketshell-2" to listOf("git-pocketshell-2"))

        assertEquals(
            SessionSocket.Absent,
            TmuxSocketSweep.parseSessionSocket(
                host.run(TmuxSocketSweep.sessionSocketCommand("'git-pocketshell'")).stdout,
                0,
            ),
        )
    }

    @Test
    fun bothProbesSurviveASocketDirectoryThatDoesNotExist() {
        // A host that has never started tmux: the glob matches nothing, the
        // trailing bare client finds no server, and both probes must still
        // answer cleanly (exit 0, empty listing / Absent) rather than erroring
        // the create out.
        val host = shellHost(sessionsBySocket = emptyArray(), createSocketDir = false)

        val names = host.run(TmuxSocketSweep.liveSessionNamesCommand())
        assertEquals("the sweep must exit 0 on a tmux-less host", 0, names.exitCode)
        assertEquals(emptySet<String>(), TmuxSocketSweep.parseLiveSessionNames(names.stdout, 0))

        val located = host.run(TmuxSocketSweep.sessionSocketCommand("'git-pocketshell'"))
        assertEquals(0, located.exitCode)
        assertEquals(
            SessionSocket.Absent,
            TmuxSocketSweep.parseSessionSocket(located.stdout, located.exitCode),
        )
    }

    // --- harness --------------------------------------------------------

    private class ShellHost(val root: File, val socketDir: String) {
        fun run(command: String): Answer {
            val process = ProcessBuilder("/bin/sh", "-c", command)
                .directory(root)
                .apply {
                    environment()["TMUX_TMPDIR"] = root.absolutePath
                    environment()["PATH"] = "${root.absolutePath}/bin:${System.getenv("PATH")}"
                }
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            check(process.waitFor(30, TimeUnit.SECONDS)) { "probe did not finish: $command" }
            return Answer(stdout, stderr, process.exitValue())
        }

        data class Answer(val stdout: String, val stderr: String, val exitCode: Int)
    }

    /**
     * A throwaway `$TMUX_TMPDIR/tmux-<uid>` holding one real unix socket per
     * entry, plus a stub `tmux` that answers `list-sessions` / `has-session`
     * from the mapping. The sockets have to be REAL: the production probes skip
     * anything that is not one (`[ -S … ]`), which is what keeps a stray
     * regular file in that directory from being handed to a tmux client.
     */
    private fun shellHost(
        vararg sessionsBySocket: Pair<String, List<String>>,
        createSocketDir: Boolean = true,
    ): ShellHost {
        val root = createTempDir()
        val uid = ProcessBuilder("id", "-u").start().let {
            it.inputStream.bufferedReader().readText().trim()
        }
        val socketDir = File(root, "tmux-$uid")
        if (createSocketDir) {
            socketDir.mkdirs()
            sessionsBySocket.forEach { (socket, _) ->
                createUnixSocket(File(socketDir, socket))
            }
        }
        val bin = File(root, "bin").apply { mkdirs() }
        File(bin, "tmux").apply {
            writeText(stubTmux(sessionsBySocket.toMap()))
            setExecutable(true)
        }
        return ShellHost(root, socketDir.absolutePath)
    }

    /**
     * A short-pathed scratch directory. AF_UNIX paths are capped at 108 bytes,
     * so the socket files must not sit under a deep `java.io.tmpdir` (a Gradle
     * worker's can be arbitrarily deep).
     */
    private fun createTempDir(): File {
        val parent = File("/tmp").takeIf { it.isDirectory && it.canWrite() }
            ?: File(System.getProperty("java.io.tmpdir"))
        val dir = File(parent, "i2378-${System.nanoTime()}")
        check(dir.mkdirs()) { "could not create $dir" }
        dir.deleteOnExit()
        return dir
    }

    /**
     * Real AF_UNIX socket files — the probes skip anything that is not one.
     * `nc -lU`/python would drag an external dependency into the JVM gate, and
     * POSIX sh cannot create a socket at all, so the harness binds them itself
     * and closes immediately, leaving the socket inode behind exactly like a
     * tmux server that has since exited.
     *
     * Reflection, not a direct call: unit tests compile against `android.jar`,
     * whose `java.nio` stubs predate `ServerSocketChannel.open(ProtocolFamily)`
     * even though the JDK 17 these tests RUN on has it.
     */
    private fun createUnixSocket(path: File) {
        val protocolFamily = Class.forName("java.net.ProtocolFamily")
        @Suppress("UNCHECKED_CAST")
        val unix = java.lang.Enum.valueOf(
            Class.forName("java.net.StandardProtocolFamily") as Class<out Enum<*>>,
            "UNIX",
        )
        val channel = java.nio.channels.ServerSocketChannel::class.java
            .getMethod("open", protocolFamily)
            .invoke(null, unix) as java.nio.channels.ServerSocketChannel
        val address = Class.forName("java.net.UnixDomainSocketAddress")
            .getMethod("of", java.nio.file.Path::class.java)
            .invoke(null, path.toPath()) as java.net.SocketAddress
        channel.use { it.bind(address) }
        path.deleteOnExit()
    }

    /**
     * A `tmux` stand-in that understands exactly the two invocation shapes the
     * production probes emit: an optional `-u`, an optional `-S <socket>`, then
     * `list-sessions -F …` or `has-session -t =<name>`. Unknown sockets answer
     * tmux's own `no server running on <socket>` on stderr with a non-zero exit.
     */
    private fun stubTmux(sessionsBySocket: Map<String, List<String>>): String {
        val cases = sessionsBySocket.entries.joinToString("\n") { (socket, names) ->
            "  $socket) names=\"${names.joinToString(" ")}\" ;;"
        }
        return """
            #!/bin/sh
            sock=default
            [ "${'$'}1" = "-u" ] && shift
            if [ "${'$'}1" = "-S" ]; then sock=${'$'}(basename "${'$'}2"); shift 2; fi
            [ "${'$'}1" = "-u" ] && shift
            verb=${'$'}1; shift
            names=""
            case "${'$'}sock" in
            $cases
              *) echo "no server running on ${'$'}sock" >&2; exit 1 ;;
            esac
            case "${'$'}verb" in
              list-sessions) for n in ${'$'}names; do echo "${'$'}n"; done ;;
              has-session)
                want=${'$'}2
                want=${'$'}{want#=}
                for n in ${'$'}names; do [ "${'$'}n" = "${'$'}want" ] && exit 0; done
                exit 1 ;;
              *) exit 1 ;;
            esac
        """.trimIndent()
    }
}
