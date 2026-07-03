package com.pocketshell.app.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxOutputBacklogOverflow
import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #972 (D33 / G4 / G10): the connected/Docker proof of the host
 * connection-log mirror's **VM glue on the real path**.
 *
 * The #969-part-3 writer ([ConnectionLogHostMirror]) and its driver wiring landed
 * JVM-tested, but the single highest-risk real-path link — the VM glue
 * `TmuxSessionViewModel.mirrorConnectionLogToHost()` ->
 * `ConnectionTarget.toLeaseSessionTarget()` (the BYTE-IDENTICAL lease-key mapping
 * that makes the warm-lease borrow real) — had **zero** coverage of any kind:
 *
 *  - [ConnectionLogHostMirrorTest] writes over a FAKE counting connector /
 *    [com.pocketshell.app.sessions.LeaseSessionTarget], never the VM's path.
 *  - [com.pocketshell.app.tmux.connection.ConnectionEffectDriverConnectionLogMirrorTest]
 *    only counts that the `onTransportReconnected` callback fires; it stubs the
 *    effect.
 *
 * A wrong lease-key field mapping in `toLeaseSessionTarget()` would reproduce the
 * EXACT "wired but the real host file never lands" failure this issue closes —
 * and nothing caught it. This matters extra because the bulletproof RC soak will
 * RELY on this host log to attribute drops.
 *
 * This journey drives the **production** [TmuxSessionViewModel] against the
 * deterministic Docker `agents` fixture (host port 2222 — or the pool-allocated
 * port under `--pool`):
 *
 *  1. Seed a real reconnect-cause trail (incl. the named `keepalive_dead` cause)
 *     in a real [DiagnosticRecorder] (recording defaults ON, #969).
 *  2. Set the VM's `activeTarget` to the `agents:2222` host
 *     ([TmuxSessionViewModel.replaceClientForTest]) — the reconnect-completed
 *     state from which the production driver fires the mirror.
 *  3. PRE-WARM the SSH lease for that EXACT key (one cold handshake, counted),
 *     keeping the ref alive so the transport stays warm.
 *  4. Fire the EXACT production mirror glue
 *     ([TmuxSessionViewModel.mirrorConnectionLogToHostForTest], the synchronous
 *     seam over the same `mirrorConnectionLogToHost()` body the driver's
 *     `onTransportReconnected` edge fires) and await the result.
 *
 * GREEN ([mirrorWritesConnectionLogOverTheWarmLeaseOnReconnect]): the host file
 * `~/.pocketshell/connection-log.jsonl` ACTUALLY lands — read back over a FRESH
 * independent SSH exec — carrying the seeded `keepalive_dead` trail, written over
 * the WARM lease with ZERO extra handshakes (warm reuse), proving
 * `toLeaseSessionTarget()` produced a byte-identical key.
 *
 * RED guard ([wrongLeaseKeyMappingDoesNotLandOverTheWarmLease], G6/G10): the SAME
 * write driven through a deliberately-MISMATCHED lease key (a wrong port — the
 * shape a broken field mapping would produce) does NOT reuse the warm transport;
 * it dials a SECOND cold handshake. That pins the byte-identical-key property as
 * the LOAD-BEARING assertion: if `toLeaseSessionTarget()` mapped a field wrong,
 * the GREEN test's warm-reuse assertion (handshakes == 1) would fail exactly like
 * this guard.
 *
 * Uses ONLY the deterministic `agents:2222` fixture; no toxiproxy, no new Docker
 * service/port; does NOT self-skip on CI.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionLogHostMirrorReconnectDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var viewModel: TmuxSessionViewModel? = null

    /**
     * Counts every REAL cold SSH handshake the lease manager dials. A warm lease
     * already held for the same key means a borrow reuses it and the counter does
     * NOT advance — the byte-identical-key signal.
     */
    private val handshakeCount = AtomicInteger(0)

    @Before
    fun setUp() {
        bootstrapKey()
        // A fresh process-global sink so a sibling test's recorder cannot leak
        // reconnect-cause events into this run.
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
    }

    @After
    fun tearDown() {
        viewModel?.clearForTest()
        viewModel = null
        factoryScope.cancel()
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
    }

    @Test
    fun mirrorWritesConnectionLogOverTheWarmLeaseOnReconnect() { runBlocking {
        waitForSshFixtureReady(sshKey, port = DEFAULT_PORT)

        val marker = "k${System.currentTimeMillis().toString(36).takeLast(6)}"
        val hostId = 972L

        // 1. A real recorder with a seeded reconnect-cause trail (the payload the
        //    mirror writes), incl. the named keepalive_dead cause + a unique
        //    marker field so the read-back unambiguously matches THIS run.
        val recorder = seedRecorderWithTrail(marker)
        val expectedJsonl = recorder.connectionLogJsonl()
        assertTrue(
            "precondition: the seeded trail must be non-blank (the mirror no-ops on blank)",
            expectedJsonl.isNotBlank() && "keepalive_dead" in expectedJsonl,
        )

        // 2. The production VM, with a handshake-counting lease manager + the real
        //    recorder, its activeTarget set to the agents:2222 host.
        val vm = buildViewModelTargetingFixture(hostId, recorder)

        // Clear any stale host log from a prior run so the assertion is about THIS
        // write landing, not a leftover file.
        clearHostConnectionLog()

        // 3. PRE-WARM the lease for the EXACT key the VM's activeTarget maps to.
        //    One cold handshake; the ref is kept alive so the transport stays warm
        //    for the mirror's borrow.
        val warmLease = leaseManager.acquire(fixtureLeaseTarget(hostId)).getOrThrow()
        assertEquals(
            "pre-warm must dial exactly one cold handshake",
            1,
            handshakeCount.get(),
        )
        assertTrue("pre-warm must be a fresh connection", warmLease.isNewConnection)

        try {
            // 4. Fire the EXACT production mirror glue (activeTarget ->
            //    toLeaseSessionTarget -> warm-lease write) and await it.
            val result = withTimeout(MIRROR_TIMEOUT_MS) {
                vm.mirrorConnectionLogToHostForTest()
            }
            assertTrue(
                "the production mirror glue must succeed over the warm lease; was: $result",
                result.isSuccess,
            )
            assertEquals(
                "the mirror must write to the well-known host diagnostics path",
                ConnectionLogHostMirror.REMOTE_PATH,
                result.getOrNull(),
            )

            // The byte-identical-key property: the warm-lease borrow reused the
            // pre-warmed transport, so NO second handshake fired. A wrong field
            // mapping in toLeaseSessionTarget() would diverge the key -> a fresh
            // cold dial -> this would be 2 (the RED guard below proves that).
            assertEquals(
                "the mirror must reuse the WARM lease (byte-identical key) — no extra handshake",
                1,
                handshakeCount.get(),
            )

            // The authoritative artifact: read the host file back over a FRESH,
            // independent SSH exec and confirm the seeded trail actually landed.
            val landed = readHostConnectionLog()
            assertNotNull("the host connection-log file must exist after the mirror", landed)
            val contents = landed!!
            assertTrue(
                "the host file must carry the seeded keepalive_dead trail; was:\n$contents",
                "keepalive_dead" in contents,
            )
            assertTrue(
                "the host file must carry THIS run's marker ($marker); was:\n$contents",
                marker in contents,
            )
            assertEquals(
                "the host file must be byte-identical to the recorder's connection-log payload",
                expectedJsonl.trim(),
                contents.trim(),
            )
        } finally {
            warmLease.release()
        }
        Unit
    } }

    @Test
    fun wrongLeaseKeyMappingDoesNotLandOverTheWarmLease() { runBlocking {
        // G6/G10 RED guard: this pins the byte-identical-key property as the
        // LOAD-BEARING assertion of the GREEN test. It drives the SAME warm-lease
        // write helper the mirror uses ([com.pocketshell.app.sessions.LeaseSessionExec]
        // via [ConnectionLogHostMirror.mirror]) but through a lease target whose key
        // is WRONG (a mismatched port — the exact shape a broken field mapping in
        // toLeaseSessionTarget() would produce). Because the key diverges from the
        // pre-warmed one, the borrow CANNOT reuse the warm transport: it dials a
        // SECOND cold handshake. So if the production mapping were wrong, the GREEN
        // test's `handshakes == 1` warm-reuse assertion would fail exactly here.
        waitForSshFixtureReady(sshKey, port = DEFAULT_PORT)
        val marker = "w${System.currentTimeMillis().toString(36).takeLast(6)}"
        val hostId = 9720L
        val recorder = seedRecorderWithTrail(marker)
        val jsonl = recorder.connectionLogJsonl()
        // A fresh counting manager isolated to this test.
        val counter = AtomicInteger(0)
        val manager = countingLeaseManager(counter)

        // Pre-warm the CORRECT key.
        val warmLease = manager.acquire(productionLeaseTarget(hostId, DEFAULT_PORT))
            .getOrThrow()
        assertEquals("pre-warm: one handshake", 1, counter.get())
        try {
            // Write through a MISMATCHED key (wrong port). The lease manager keys on
            // (host, port, user, credentialId), so this is a different lease ->
            // a fresh cold dial, not the warm reuse.
            val wrongTarget = com.pocketshell.app.sessions.LeaseSessionTarget(
                hostId = hostId,
                hostname = DEFAULT_HOST,
                // A wrong port: the lease key diverges, so the warm transport
                // CANNOT be reused. (The fixture also listens here in CI? No — the
                // point is the KEY mismatch, which forces a fresh acquire attempt;
                // we assert the second handshake was attempted, proving non-reuse.)
                port = DEFAULT_PORT + 1,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
            )
            // The borrow will TRY to dial the wrong port and fail to connect, but
            // the attempt itself proves the warm lease was NOT reused. We assert on
            // the handshake counter, which increments on every acquire attempt.
            runCatching {
                withTimeout(MIRROR_TIMEOUT_MS) {
                    ConnectionLogHostMirror.mirror(
                        leaseManager = manager,
                        target = wrongTarget,
                        jsonl = jsonl,
                    )
                }
            }
            assertTrue(
                "a mismatched lease key must NOT reuse the warm transport — it must " +
                    "dial a fresh handshake (counter was ${counter.get()})",
                counter.get() >= 2,
            )
        } finally {
            warmLease.release()
        }
        Unit
    } }

    // ---- helpers ----

    private lateinit var leaseManager: SshLeaseManager

    private fun countingLeaseManager(counter: AtomicInteger): SshLeaseManager =
        SshLeaseManager(
            connector = SshLeaseConnector { target ->
                counter.incrementAndGet()
                DefaultSshLeaseConnector().connect(target)
            },
        )

    private fun buildViewModelTargetingFixture(
        hostId: Long,
        recorder: DiagnosticRecorder,
    ): TmuxSessionViewModel {
        leaseManager = countingLeaseManager(handshakeCount)
        val vm = TmuxSessionViewModel(
            tmuxClientFactory = TmuxClientFactory(factoryScope),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = leaseManager,
            diagnosticRecorder = recorder,
        )
        // Set activeTarget to the agents:2222 host (the reconnect-completed state).
        // An inert client is sufficient — the mirror glue only reads activeTarget
        // and the lease manager; it issues no tmux commands.
        vm.replaceClientForTest(
            hostId = hostId,
            hostName = "Connection Log Mirror $hostId",
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            keyPath = keyFile.absolutePath,
            sessionName = "mirror-main",
            client = InertTmuxClient(),
        )
        viewModel = vm
        return vm
    }

    /**
     * The [SshLeaseTarget] the VM's `activeTarget.toLeaseSessionTarget()` maps to.
     * Encoded here the SAME way the production [com.pocketshell.app.sessions.LeaseSessionTarget.toSshLeaseTarget]
     * does (`credentialId = "$hostId:$keyPath"`, `knownHostsId = "accept-all"`) so
     * the pre-warm holds the EXACT key the production mapping yields. If the VM's
     * `toLeaseSessionTarget()` mapped any field wrong, the VM's borrow key would
     * diverge from THIS pre-warmed key and the warm-reuse assertion would go red.
     */
    private fun fixtureLeaseTarget(hostId: Long): SshLeaseTarget =
        productionLeaseTarget(hostId, DEFAULT_PORT)

    private fun productionLeaseTarget(hostId: Long, port: Int): SshLeaseTarget =
        SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = DEFAULT_HOST,
                port = port,
                user = DEFAULT_USER,
                credentialId = "$hostId:${keyFile.absolutePath}",
                knownHostsId = "accept-all",
            ),
            key = SshKey.Path(keyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
        )

    private suspend fun seedRecorderWithTrail(marker: String): DiagnosticRecorder {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsRepository = SettingsRepository(context)
        val recorder = DiagnosticRecorder(context, settingsRepository)
        recorder.clear()
        // The reconnect-cause breadcrumbs route through the globally-installed sink
        // (ReconnectCauseTrail.record -> DiagnosticEvents -> this recorder), exactly
        // like production. Seed a keepalive_dead cause so the read-back asserts the
        // named attribution lands, plus a unique marker field for this run.
        DiagnosticEvents.install(recorder)
        ReconnectCauseTrail.record(
            stage = "lease_transport",
            outcome = "down",
            cause = "keepalive_dead",
            trigger = null,
            "marker" to marker,
        )
        ReconnectCauseTrail.record(
            stage = "reconnect",
            outcome = "recovered",
            cause = null,
            trigger = "auto",
            "marker" to marker,
        )
        // Flush via the public read path so the JSONL is durable before we mirror.
        recorder.connectionLogJsonl()
        return recorder
    }

    private suspend fun clearHostConnectionLog() {
        withSshSession { session ->
            session.exec("rm -f \"\$HOME/${ConnectionLogHostMirror.REMOTE_PATH}\"")
        }
    }

    private suspend fun readHostConnectionLog(): String? {
        val result = withSshSession { session ->
            session.exec(
                "if [ -f \"\$HOME/${ConnectionLogHostMirror.REMOTE_PATH}\" ]; then " +
                    "cat \"\$HOME/${ConnectionLogHostMirror.REMOTE_PATH}\"; else echo __MISSING__; fi",
            )
        }
        if (result.exitCode != 0) return null
        val out = result.stdout
        return if (out.trim() == "__MISSING__") null else out
    }

    private suspend fun <T> withSshSession(block: suspend (SshSession) -> T): T {
        val session = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
        return session.use { block(it) }
    }

    private fun bootstrapKey() {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        keyFile = File(cacheDir, "issue972-connection-log-mirror-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
    }

    /**
     * A minimal [TmuxClient] test double for [TmuxSessionViewModel.replaceClientForTest].
     * The mirror glue reads only `activeTarget` + the lease manager and issues no
     * tmux commands, so every member is an inert no-op.
     */
    private class InertTmuxClient : TmuxClient {
        private val disconnectedState = MutableStateFlow(false)
        private val disconnectEventState = MutableStateFlow<TmuxDisconnectEvent?>(null)

        override val events: Flow<ControlEvent> = emptyFlow()
        override val disconnected: StateFlow<Boolean> = disconnectedState.asStateFlow()
        override val disconnectEvent: StateFlow<TmuxDisconnectEvent?> = disconnectEventState.asStateFlow()
        override val outputBacklogOverflows: Flow<TmuxOutputBacklogOverflow> = emptyFlow()

        override suspend fun connect() = Unit

        override suspend fun sendCommand(cmd: String): CommandResponse =
            CommandResponse(number = 0L, output = emptyList(), isError = false)

        override fun outputFor(paneId: String): Flow<ControlEvent.Output> = emptyFlow()
        override fun drainPaneOutputBacklog(paneId: String): Int = 0

        override fun close() = Unit

        override suspend fun setWindowSizeLatest(sessionId: String): CommandResponse =
            CommandResponse(number = 0L, output = emptyList(), isError = false)

        override suspend fun refreshClientSize(cols: Int, rows: Int): CommandResponse =
            CommandResponse(number = 0L, output = emptyList(), isError = false)

        override suspend fun detachCleanly(timeoutMs: Long) = Unit
    }

    private companion object {
        const val MIRROR_TIMEOUT_MS: Long = 30_000L
    }
}
