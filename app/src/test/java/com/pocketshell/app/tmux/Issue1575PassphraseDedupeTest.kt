package com.pocketshell.app.tmux

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #1575: the same-session no-op dedupe in [TmuxSessionViewModel.connect]
 * compared the incoming [TmuxSessionViewModel.ConnectionTarget] to the active
 * one with data-class `==`, which INCLUDES `passphrase: CharArray?`. `CharArray`
 * uses REFERENCE equality, so two targets built from the SAME passphrase-protected
 * host + session (distinct `CharArray` instances holding the same chars) never
 * compared equal — the dedupe could NEVER match, and re-entering the SAME session
 * on a passphrase-protected host always fell through to a full (spurious) reconnect.
 * Key-only / no-passphrase hosts deduped fine (both passphrases `null`), which is
 * why the maintainer's "it started reconnecting, which shouldn't have happened"
 * (#1574) was host-dependent.
 *
 * The fix makes [ConnectionTarget] equality identity-based: `passphrase` is
 * EXCLUDED from `equals`/`hashCode`, so the secret bytes never participate in the
 * dedupe (they are never content-compared or logged either). Re-entering the same
 * session identity now dedupes as a no-op on passphrase hosts exactly as it did on
 * no-passphrase hosts.
 *
 * Load-bearing signal: [TMUX_CONNECT_ATTEMPTS] — the #145 deterministic
 * accepted-connect counter. Every ACCEPTED connect increments it; the two early
 * no-op dedupe guards in `connect()` explicitly do NOT (see the comment at the
 * `TMUX_CONNECT_ATTEMPTS.incrementAndGet()` call). So the delta across the second
 * `connect()` is exactly "did a spurious reconnect get accepted": non-zero on the
 * base bug (dedupe missed), zero when correctly deduped.
 *
 * Reproduce-first (D33/G10): the connect journey is driven synthetically under
 * `runTest` virtual time; no `assumeTrue`/CI-skip guards the load-bearing assertion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1575PassphraseDedupeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fresh-client factory build count — corroborates the attempts signal per-instance. */
    private val builds = AtomicInteger(0)

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    private fun TestScope.newVm(): TmuxSessionViewModel = TmuxSessionViewModel(
        tmuxClientFactory = TmuxClientFactory(factoryScope),
        activeTmuxClients = ActiveTmuxClients(),
        runtimeCache = TmuxSessionRuntimeCache(),
        sshLeaseManager = testLeaseManager(
            connector = AlwaysConnectingConnector(),
            scope = this,
            idleTtlMillis = 60_000L,
        ),
        sessionLifecycleSignals = null,
    ).also {
        it.setSeedIoDispatcherForTest(Dispatchers.Main)
        it.setAutoReconnectDelaysForTest(emptyList())
        // Every factory build hands back a FRESH healthy single-pane client so a
        // second (spurious) attach can also complete; the build count doubles as a
        // corroborating rebuild signal.
        it.setTmuxClientFactoryForTest { _, _, _ ->
            builds.incrementAndGet()
            FakeTmuxClient().withSinglePaneRow("work", "%1")
        }
    }

    private fun TmuxSessionViewModel.connectTo(
        passphrase: CharArray?,
        sessionName: String,
    ) {
        connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = passphrase,
            sessionName = sessionName,
        )
    }

    @Test
    fun passphraseHost_reenterSameSession_isDedupedNoSpuriousReconnect() = runTest {
        val vm = newVm()
        // First open of a passphrase-protected host + session "work".
        vm.connectTo(passphrase = charArrayOf('s', 'e', 'c', 'r', 'e', 't'), sessionName = "work")
        advanceUntilIdle()
        assertTrue(
            "the first passphrase-host connect must reach Connected, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        val attemptsBefore = TMUX_CONNECT_ATTEMPTS.get()
        val buildsBefore = builds.get()

        // Re-enter the SAME session identity, but with a DISTINCT CharArray holding
        // the SAME passphrase chars — exactly what a fresh row-tap builds. On the
        // base bug the CharArray reference mismatch defeats the data-class dedupe.
        vm.connectTo(passphrase = charArrayOf('s', 'e', 'c', 'r', 'e', 't'), sessionName = "work")
        advanceUntilIdle()

        // LOAD-BEARING (red on base): no accepted reconnect fired — the re-entry was
        // deduped as a no-op, just as it always was for no-passphrase hosts.
        assertEquals(
            "re-entering the SAME passphrase-host session must dedupe as a no-op — no " +
                "accepted (spurious) reconnect. Base bug: CharArray reference equality " +
                "defeats the data-class dedupe so a full reconnect fires.",
            attemptsBefore,
            TMUX_CONNECT_ATTEMPTS.get(),
        )
        assertEquals(
            "the deduped re-entry must not rebuild the tmux client (no spurious reconnect)",
            buildsBefore,
            builds.get(),
        )
    }

    @Test
    fun noPassphraseHost_reenterSameSession_staysDeduped() = runTest {
        // Class coverage: the previously-working case must remain deduped (regression guard).
        val vm = newVm()
        vm.connectTo(passphrase = null, sessionName = "work")
        advanceUntilIdle()
        assertTrue(
            "the first no-passphrase connect must reach Connected, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        val attemptsBefore = TMUX_CONNECT_ATTEMPTS.get()
        val buildsBefore = builds.get()

        vm.connectTo(passphrase = null, sessionName = "work")
        advanceUntilIdle()

        assertEquals(
            "a no-passphrase host re-entering the same session must still dedupe as a no-op",
            attemptsBefore,
            TMUX_CONNECT_ATTEMPTS.get(),
        )
        assertEquals(
            "the deduped no-passphrase re-entry must not rebuild the tmux client",
            buildsBefore,
            builds.get(),
        )
    }

    @Test
    fun passphraseHost_differentSession_isNotFalselyDeduped() = runTest {
        // Class coverage: a GENUINELY different session (different name) on the same
        // passphrase host must NOT be deduped — the switch/reconnect must still fire.
        val vm = newVm()
        vm.connectTo(passphrase = charArrayOf('p', 'w'), sessionName = "work")
        advanceUntilIdle()
        assertTrue(
            "the first connect must reach Connected, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        val attemptsBefore = TMUX_CONNECT_ATTEMPTS.get()

        // Same host + credential, DIFFERENT session name → a real switch, never a no-op.
        vm.connectTo(passphrase = charArrayOf('p', 'w'), sessionName = "other")
        advanceUntilIdle()

        assertTrue(
            "switching to a genuinely different session on a passphrase host must be an " +
                "ACCEPTED connect (attempts increment), never falsely deduped — got no increment",
            TMUX_CONNECT_ATTEMPTS.get() > attemptsBefore,
        )
    }

    private fun FakeTmuxClient.withSinglePaneRow(
        sessionName: String,
        paneId: String,
    ): FakeTmuxClient = apply {
        responses.addLast(
            com.pocketshell.core.tmux.CommandResponse(
                number = 1L,
                output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                isError = false,
            ),
        )
        capturePaneResponses.addLast(
            com.pocketshell.core.tmux.CommandResponse(
                number = 2L,
                output = listOf("$sessionName ready"),
                isError = false,
            ),
        )
        cursorQueryResponses.addLast(
            com.pocketshell.core.tmux.CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    private class AlwaysConnectingConnector : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(AlwaysConnectedSession())
    }

    /** SSH session double that stays `isConnected` until explicitly closed. */
    private class AlwaysConnectedSession : SshSession {
        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): kotlinx.coroutines.Job =
            kotlinx.coroutines.Job().apply { complete() }

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

        override fun close() {
            closed = true
        }
    }
}
