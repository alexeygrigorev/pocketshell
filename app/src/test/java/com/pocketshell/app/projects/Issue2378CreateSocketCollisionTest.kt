package com.pocketshell.app.projects

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Issue #2378 — creating a session whose name is already taken by a
 * **tmuxctl-managed** session on its own dedicated socket.
 *
 * ## The reported state, reproduced
 *
 * The maintainer created `git-pocketshell` from the folder tree's New Session
 * sheet while a live tmuxctl-managed `git-pocketshell` already existed on
 * `/tmp/tmux-1000/tmuxctl-git-pocketshell`. Everything the create path asked
 * the host went to the **default** socket, which knew nothing about it:
 *
 *  1. the free-name walk called the name free, so nothing disambiguated it;
 *  2. `tmuxctl create-detached` — which IS cross-socket aware — saw the live
 *     session and no-oped, so no new session was made;
 *  3. the launch's `tmux send-keys` (default socket again) failed with
 *     `no server running on /tmp/tmux-1000/default`, reported to the user as
 *     "the agent didn't start", while several tmux servers were running.
 *
 * ## Why the fixture is the point (G10)
 *
 * No existing fixture had a session anywhere but the default socket — the
 * Docker `agents` stub's `create-detached` is a plain `tmux new-session -A -d`
 * — so every green create test was run against a host that cannot produce this
 * bug. [MultiSocketHost] below models the real thing: a socket directory with
 * several servers, a `create-detached` that is idempotent ACROSS sockets and
 * creates on its own dedicated one, a raw `new-session -A -d` that only ever
 * sees the default socket, and a `send-keys` that answers exactly what tmux
 * answers when it is pointed at a server that is not holding the session.
 *
 * Class coverage (G2): tmuxctl-managed AND aplexer-managed collisions, the
 * capped create AND the raw fallback-layer create, launch AND no-launch
 * creates, plus the fail-safe host where the socket sweep cannot run at all.
 */
class Issue2378CreateSocketCollisionTest {

    @Test
    fun uniqueOnHostDisambiguatesAgainstATmuxctlSessionOnItsOwnSocket() = runTest {
        // The maintainer's exact scenario: a live tmuxctl-managed session of the
        // requested name, on its own dedicated socket, and an agent launch.
        val host = MultiSocketHost().apply { seedDedicated(BASE) }

        val outcome = SshFolderListGateway().createSessionOnSession(
            session = host,
            sessionName = BASE,
            cwd = CWD,
            startCommand = AGENT_LAUNCH,
            namePolicy = SessionNamePolicy.UniqueOnHost,
        )

        // PRE-FIX: LaunchFailed(git-pocketshell, "no server running on
        // /tmp/tmux-1000/default") — the name was never disambiguated and the
        // launch was typed at the wrong server.
        assertEquals(
            "a name taken by a tmuxctl-managed session must disambiguate to -2",
            SessionCreateOutcome.Created("$BASE-2"),
            outcome,
        )
        assertEquals(
            "the pre-existing session must be left alone and NOT duplicated",
            listOf(host.dedicatedSocket(BASE)),
            host.socketsHolding(BASE),
        )
        assertEquals(
            "the new session must exist exactly once, on its own dedicated socket",
            listOf(host.dedicatedSocket("$BASE-2")),
            host.socketsHolding("$BASE-2"),
        )
        assertTrue(
            "no same-named orphan may appear on the default socket",
            host.sessionsOn(MultiSocketHost.DEFAULT_SOCKET).isEmpty(),
        )
    }

    @Test
    fun agentLaunchIsTypedIntoTheSocketTheSessionWasActuallyCreatedOn() = runTest {
        // The second half of the report, isolated: even with NO collision, a
        // session created through `tmuxctl create-detached` lives on its own
        // socket, so a default-socket `send-keys` cannot reach it.
        val host = MultiSocketHost()

        val outcome = SshFolderListGateway().createSessionOnSession(
            session = host,
            sessionName = BASE,
            cwd = CWD,
            startCommand = AGENT_LAUNCH,
            namePolicy = SessionNamePolicy.UniqueOnHost,
        )

        // PRE-FIX: LaunchFailed(git-pocketshell, "no server running on
        // /tmp/tmux-1000/default") on a host where the session was created fine.
        assertEquals(SessionCreateOutcome.Created(BASE), outcome)
        assertEquals(
            "the launch must be delivered into the session's own server",
            listOf(host.dedicatedSocket(BASE) to AGENT_LAUNCH),
            host.deliveredKeys,
        )
        val sendKeys = host.execCommands.single { it.contains("send-keys") }
        assertTrue(
            "send-keys must target the dedicated socket explicitly: $sendKeys",
            sendKeys.contains("tmux -S ${escapedInWrapper(host.dedicatedSocket(BASE))} send-keys"),
        )
    }

    @Test
    fun rawFallbackCreateNeverOrphansASameNamedSessionOnTheDefaultSocket() = runTest {
        // Class coverage: the layer-1 fallback host (no tmuxctl / too old). Its
        // raw `tmux new-session -A -d` only ever sees the DEFAULT socket, so
        // before the fix it happily made a SECOND `git-pocketshell` there — the
        // orphan the issue reports, shadowing the real session in every listing.
        val host = MultiSocketHost(tmuxctlAvailable = false).apply { seedDedicated(BASE) }

        val outcome = SshFolderListGateway().createSessionOnSession(
            session = host,
            sessionName = BASE,
            cwd = CWD,
            startCommand = null,
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertEquals(
            "an ExactName re-pick of a live session is the idempotent attach case",
            SessionCreateOutcome.Created(BASE),
            outcome,
        )
        assertEquals(
            "the session must still exist exactly once, on its original socket",
            listOf(host.dedicatedSocket(BASE)),
            host.socketsHolding(BASE),
        )
        assertFalse(
            "the raw default-socket create must not run against an already-live name",
            host.execCommands.any { it.contains("new-session -A -d") },
        )
    }

    @Test
    fun aplexerManagedSessionOfTheSameNameAlsoDisambiguates() = runTest {
        // Class coverage (G2): aplexer sessions are not tmux servers at all, so
        // no socket sweep can see them. They still own their name — the union
        // with the `pocketshell sessions list` enumerator is what covers them.
        val host = MultiSocketHost().apply { aplexerSessions = listOf(BASE) }

        val outcome = SshFolderListGateway().createSessionOnSession(
            session = host,
            sessionName = BASE,
            cwd = CWD,
            startCommand = null,
            namePolicy = SessionNamePolicy.UniqueOnHost,
        )

        assertEquals(
            "a name owned by an aplexer-managed session must disambiguate too",
            SessionCreateOutcome.Created("$BASE-2"),
            outcome,
        )
    }

    @Test
    fun launchCreateOntoALiveTmuxctlSessionIsRefusedNotShadowed() = runTest {
        // Class coverage: the #976 routing guard, now asked of every socket. An
        // ExactName LAUNCH at a name a live tmuxctl session owns must be refused
        // outright — typing the launch line anywhere else is the #968 misroute,
        // and creating a same-named shadow is the #2378 orphan.
        val host = MultiSocketHost().apply { seedDedicated(BASE) }

        val failure = runCatching {
            SshFolderListGateway().createSessionOnSession(
                session = host,
                sessionName = BASE,
                cwd = CWD,
                startCommand = AGENT_LAUNCH,
                namePolicy = SessionNamePolicy.ExactName,
            )
        }.exceptionOrNull()

        assertTrue("expected a refused launch, got $failure", failure is RuntimeException)
        assertTrue(
            "the refusal must name the already-open session: ${failure?.message}",
            failure?.message.orEmpty().contains(BASE),
        )
        assertEquals(
            "nothing may have been created or typed",
            emptyList<Pair<String, String>>(),
            host.deliveredKeys,
        )
        assertEquals(listOf(host.dedicatedSocket(BASE)), host.socketsHolding(BASE))
    }

    @Test
    fun launchFailureDetailIsAccurateWhenNoServerHoldsTheSession() = runTest {
        // Issue #2378 acceptance 3: the reason must describe the REAL cause.
        // Here the create reports success but leaves nothing behind (a create
        // that raced a server teardown), so the sweep proves the session is on
        // no socket at all. tmux's own `no server running on <one socket>` is
        // the misleading sentence and must not be what the user is shown.
        val host = MultiSocketHost(createSilentlyDoesNothing = true)

        val outcome = SshFolderListGateway().createSessionOnSession(
            session = host,
            sessionName = BASE,
            cwd = CWD,
            startCommand = AGENT_LAUNCH,
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertTrue("expected a partial success, got $outcome", outcome is SessionCreateOutcome.LaunchFailed)
        val detail = (outcome as SessionCreateOutcome.LaunchFailed).detail
        assertFalse(
            "the misleading default-socket wording must not be surfaced: $detail",
            detail.contains("no server running", ignoreCase = true),
        )
        assertTrue(
            "the reason must say the session is on no tmux server: $detail",
            detail.contains("isn't on any tmux server"),
        )
    }

    @Test
    fun aHostThatCannotRunTheSweepStillCreatesAndLaunches() = runTest {
        // Fail-safe (the sweep must never BLOCK a create): a host whose shell
        // rejects the probes degrades to exactly the pre-#2378 default-socket
        // behaviour rather than refusing, guessing, or reporting a failure.
        val host = MultiSocketHost(sweepUnavailable = true, tmuxctlAvailable = false)

        val outcome = SshFolderListGateway().createSessionOnSession(
            session = host,
            sessionName = BASE,
            cwd = CWD,
            startCommand = AGENT_LAUNCH,
            namePolicy = SessionNamePolicy.UniqueOnHost,
        )

        assertEquals(SessionCreateOutcome.Created(BASE), outcome)
        assertEquals(
            "the launch must still be delivered over the plain default socket",
            listOf(MultiSocketHost.DEFAULT_SOCKET to AGENT_LAUNCH),
            host.deliveredKeys,
        )
    }

    /**
     * A fake [SshSession] modelling a host whose tmux sessions are spread over
     * SEVERAL sockets, the way tmuxctl actually runs them (one server per
     * session, `…/tmux-<uid>/tmuxctl-<name>`), plus the default socket.
     *
     * Behaviour is copied from the real components, including the parts that
     * make the bug possible:
     *  - `tmuxctl create-detached` is idempotent ACROSS sockets (it locates the
     *    session first) and otherwise creates on the session's OWN socket;
     *  - the raw `tmux new-session -A -d` fallback only ever sees the default
     *    socket, so it will happily create a same-named twin there;
     *  - a `tmux` client aimed at a socket with no server answers
     *    `no server running on <socket>`, and one aimed at a live server that
     *    lacks the session answers `can't find pane`.
     */
    private class MultiSocketHost(
        private val tmuxctlAvailable: Boolean = true,
        private val createSilentlyDoesNothing: Boolean = false,
        private val sweepUnavailable: Boolean = false,
    ) : SshSession {

        /** socket path -> session names living on that socket's server. */
        private val servers = linkedMapOf<String, MutableSet<String>>()

        /** Names reported by `pocketshell sessions list --json` as aplexer rows. */
        var aplexerSessions: List<String> = emptyList()

        val execCommands = mutableListOf<String>()

        /** (socket, keys) actually delivered by a successful `send-keys`. */
        val deliveredKeys = mutableListOf<Pair<String, String>>()

        fun dedicatedSocket(name: String): String = "$SOCKET_DIR/tmuxctl-$name"

        fun seedDedicated(name: String) {
            servers.getOrPut(dedicatedSocket(name)) { linkedSetOf() } += name
        }

        fun sessionsOn(socket: String): Set<String> = servers[socket].orEmpty()

        fun socketsHolding(name: String): List<String> =
            servers.filterValues { name in it }.keys.toList()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return when {
                command.contains("test -d") -> ok()
                // --- issue #2378 probes -------------------------------------
                command.contains("__ps_sock") && command.contains("list-sessions") ->
                    if (sweepUnavailable) shellUnavailable() else namesSweep()
                command.contains("__ps_want=") ->
                    if (sweepUnavailable) shellUnavailable() else locateSweep(command)
                // --- pre-#2378 command shapes, so a base (unfixed) run of this
                //     test measures BEHAVIOUR rather than an unanswered fake ---
                command.contains("__ps_n=") -> legacyDefaultSocketNameWalk(command)
                command.contains("has-session") -> legacyDefaultSocketHasSession(command)
                // --- create -------------------------------------------------
                command.contains("create-detached") && command.contains(" -c ") ->
                    createDetached(command)
                command.contains("new-session -A -d") -> rawFallbackCreate(command)
                // --- launch -------------------------------------------------
                command.contains("pocketshell agent --help") -> ok()
                command.contains("send-keys") -> sendKeys(command)
                // --- enumeration --------------------------------------------
                command.contains(SshFolderListGateway.POCKETSHELL_SESSIONS_JSON_COMMAND) ->
                    aplexerJson()
                command.contains(SshFolderListGateway.POCKETSHELL_SESSIONS_COMMAND) ->
                    ExecResult("", "pocketshell: not found", 127)
                else -> ExecResult("", "unexpected command: $command", 1)
            }
        }

        private fun namesSweep(): ExecResult {
            // Dedicated servers first, the default socket last — the order the
            // production glob + trailing bare client produce.
            val names = servers.entries
                .sortedBy { it.key == DEFAULT_SOCKET }
                .flatMap { it.value }
            return ExecResult(names.joinToString("") { "$it\n" }, "", 0)
        }

        private fun locateSweep(command: String): ExecResult {
            val want = quotedArg(command, "__ps_want=") ?: return shellUnavailable()
            val dedicated = servers.entries
                .firstOrNull { it.key != DEFAULT_SOCKET && want in it.value }
            return when {
                dedicated != null -> ExecResult("${dedicated.key}\n", "", 0)
                want in sessionsOn(DEFAULT_SOCKET) ->
                    ExecResult("${TmuxSocketSweep.DEFAULT_SOCKET_TOKEN}\n", "", 0)
                else -> ExecResult("${TmuxSocketSweep.NO_SOCKET_SENTINEL}\n", "", 0)
            }
        }

        private fun legacyDefaultSocketNameWalk(command: String): ExecResult {
            val base = quotedArg(command, "__ps_n=") ?: return shellUnavailable()
            var candidate = base
            var suffix = 2
            while (candidate in sessionsOn(DEFAULT_SOCKET) && suffix <= 200) {
                candidate = "$base-$suffix"
                suffix++
            }
            return ExecResult("$candidate\n", "", 0)
        }

        private fun legacyDefaultSocketHasSession(command: String): ExecResult {
            val want = quotedArg(command, "has-session -t ")?.removePrefix("=")
            if (sessionsOn(DEFAULT_SOCKET).isEmpty()) {
                return ExecResult("", "no server running on $DEFAULT_SOCKET", 1)
            }
            return if (want in sessionsOn(DEFAULT_SOCKET)) ok() else ExecResult("", "", 1)
        }

        private fun createDetached(command: String): ExecResult {
            if (!tmuxctlAvailable) {
                return ExecResult("", "", SshFolderListGateway.TMUXCTL_UNSUPPORTED_EXIT_CODE)
            }
            // The command carries the capability probe (`create-detached
            // --help`) BEFORE the real invocation, so match the invocation that
            // actually carries a quoted name and `-c <cwd>`.
            val name = Regex("""create-detached '"'"'(.*?)'"'"' -c""")
                .find(command)?.groupValues?.get(1)
                ?: return ExecResult("", "no name", 64)
            if (createSilentlyDoesNothing) return ok()
            // tmuxctl locates the session across every socket first, so an
            // existing session anywhere makes this an idempotent no-op.
            if (socketsHolding(name).isNotEmpty()) return ok()
            servers.getOrPut(dedicatedSocket(name)) { linkedSetOf() } += name
            return ok()
        }

        private fun rawFallbackCreate(command: String): ExecResult {
            val name = quotedArg(command, "new-session -A -d -s ")
                ?: return ExecResult("", "no name", 1)
            if (name in sessionsOn(DEFAULT_SOCKET)) {
                // `-A` turns this into an attach, and with no tty the attach
                // dies — the real behaviour recorded in #1820.
                return ExecResult("", "open terminal failed: not a terminal", 1)
            }
            // Blind to every other socket: this is how the orphan is born.
            servers.getOrPut(DEFAULT_SOCKET) { linkedSetOf() } += name
            return ok()
        }

        private fun sendKeys(command: String): ExecResult {
            val socket = quotedArg(command, "tmux -S ") ?: DEFAULT_SOCKET
            // `send-keys -t '<target>' '<keys>' Enter`, each value single-quoted
            // by the gateway and re-escaped by the pathAware wrapper.
            val quoted = quotedArgs(command.substringAfter("send-keys -t "))
            val target = quoted.getOrNull(0)?.removePrefix("=")?.removeSuffix(":")
            val keys = quoted.getOrNull(1)
            val live = servers[socket]
            return when {
                live.isNullOrEmpty() -> ExecResult("", "no server running on $socket", 1)
                target !in live -> ExecResult("", "can't find pane: $target", 1)
                else -> {
                    deliveredKeys += socket to keys.orEmpty()
                    ok()
                }
            }
        }

        private fun aplexerJson(): ExecResult {
            if (aplexerSessions.isEmpty()) return ExecResult("", "", 1)
            val rows = aplexerSessions.joinToString(",") {
                """{"name":"$it","manager":"aplexer"}"""
            }
            return ExecResult("""{"sessions":[$rows],"managers":["aplexer"]}""", "", 0)
        }

        private fun shellUnavailable(): ExecResult =
            ExecResult("", "/bin/sh: bad substitution", 2)

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

        companion object {
            const val SOCKET_DIR = "/tmp/tmux-1000"
            const val DEFAULT_SOCKET = "$SOCKET_DIR/default"
        }
    }

    private companion object {
        const val BASE = "git-pocketshell"
        const val CWD = "/home/alexey/git/pocketshell"
        const val AGENT_LAUNCH = "pocketshell agent claude --dir /home/alexey/git/pocketshell"

        private fun ok(): ExecResult = ExecResult("", "", 0)

        /**
         * Read the single-quoted value that follows [marker] in a command as it
         * looks INSIDE the `pathAware` wrapper: the gateway single-quotes the
         * value and the wrapper then re-escapes every `'` to `'"'"'`.
         */
        fun quotedArg(command: String, marker: String): String? {
            val idx = command.indexOf(marker)
            if (idx < 0) return null
            val rest = command.substring(idx + marker.length)
            return Regex("""^'"'"'(.*?)'"'"'""").find(rest)?.groupValues?.get(1)
        }

        /** Every wrapper-escaped single-quoted value in [fragment], in order. */
        fun quotedArgs(fragment: String): List<String> =
            Regex("""'"'"'(.*?)'"'"'""")
                .findAll(fragment)
                .map { it.groupValues[1] }
                .toList()

        /** A single-quoted value as it appears inside the pathAware wrapper. */
        fun escapedInWrapper(value: String): String =
            ("'" + value.replace("'", "'\"'\"'") + "'").replace("'", "'\"'\"'")
    }
}
