package com.pocketshell.app.portfwd

import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.portfwd.service.ForwardingService
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class PortForwardPanelViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // #492: the "Show hidden/noisy ports" checkbox is a persisted global pref;
        // Robolectric shares the prefs file across test methods, so reset it
        // to the default (unchecked) before each test for isolation.
        ShowAllPortsStore(context).setShowAll(false)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun enablingAutoForward_connectsAndPublishesTunnelRows() = runTest {
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
        val session = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val viewModel = newViewModel(FakeConnector(Result.success(session)))

        try {
            viewModel.load(hostId, "/tmp/key")
            runCurrent()
            viewModel.setAutoForwardEnabled(true)
            runCurrent()

            val state = viewModel.state.value
            assertTrue(state.autoForwardEnabled)
            assertEquals(PortForwardConnectionState.Connected, state.connectionState)
            assertEquals(1, state.tunnels.size)
            val tunnel = state.tunnels.single()
            val forward = session.openedForwards.single()
            assertEquals(3000, tunnel.remotePort)
            assertEquals(forward.localPort, tunnel.localPort)
            assertEquals("node", tunnel.process)
            assertEquals(com.pocketshell.core.portfwd.TunnelInfo.Status.FORWARDING, tunnel.status)
            assertEquals(listOf(3000), session.openedForwards.map { it.remotePort })
        } finally {
            viewModel.setAutoForwardEnabled(false)
            viewModel.leavePanel()
            runCurrent()
        }
    }

    @Test
    fun disablingAutoForwardStopsForwarderAndClosesSession() = runTest {
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
        val session = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val viewModel = newViewModel(FakeConnector(Result.success(session)))

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()
        assertNotNull(session.openedForwards.singleOrNull())

        viewModel.setAutoForwardEnabled(false)
        runCurrent()

        assertFalse(viewModel.state.value.autoForwardEnabled)
        assertTrue(session.closed)
        assertFalse(session.openedForwards.single().isActive)
        assertEquals(emptyList<com.pocketshell.core.portfwd.TunnelInfo>(), viewModel.state.value.tunnels)
    }

    @Test
    fun diagnosticsRecordAutoForwardToggleStartAndStop() = runTest {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
            val session = FakeSshSession(
                ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
            )
            val viewModel = newViewModel(FakeConnector(Result.success(session)))

            viewModel.load(hostId, "/tmp/key")
            runCurrent()
            viewModel.setAutoForwardEnabled(true)
            runCurrent()
            viewModel.setAutoForwardEnabled(false)
            runCurrent()

            val events = diagnostics.events
            assertEquals(
                listOf(true, false),
                events.filter { it.name == "port_forward_auto_toggle" }
                    .map { it.fields["enabled"] },
            )
            assertTrue(
                events.any {
                    it.name == "port_forward_auto_start_result" &&
                        it.fields["status"] == "success" &&
                        it.fields["hostId"] == hostId
                },
            )
            assertTrue(
                events.any {
                    it.name == "port_forward_stop" &&
                        it.fields["hostId"] == hostId
                },
            )
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun failedConnectionSurfacesErrorAndLeavesToggleOff() = runTest {
        val hostId = insertHost()
        val viewModel = newViewModel(FakeConnector(Result.failure(RuntimeException("no route"))))

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        val state = viewModel.state.value
        assertFalse(state.autoForwardEnabled)
        assertEquals(PortForwardConnectionState.Error, state.connectionState)
        assertEquals("no route", state.error)
    }

    @Test
    fun loadWithDiscoveryShowsAvailablePortsWithoutStartingForwarding() = runTest {
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
        val session = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val viewModel = newViewModel(FakeConnector(Result.success(session)))

        viewModel.load(hostId, "/tmp/key", discoverPorts = true)
        runCurrent()

        val state = viewModel.state.value
        assertFalse("discovery must leave auto-forward off", state.autoForwardEnabled)
        assertEquals(PortForwardConnectionState.Connected, state.connectionState)
        assertEquals(1, state.tunnels.size)
        val tunnel = state.tunnels.single()
        assertEquals(3000, tunnel.remotePort)
        assertEquals(3000, tunnel.localPort)
        assertEquals("node", tunnel.process)
        assertEquals(com.pocketshell.core.portfwd.TunnelInfo.Status.AVAILABLE, tunnel.status)
        assertEquals(emptyList<FakePortForward>(), session.openedForwards)
        assertTrue("passive discovery SSH session should close after scan", session.closed)
    }

    @Test
    fun discoveryCollapsesDuplicateListenersToOneRowPerRemotePort() = runTest {
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1)
        // Issue #456/#602: the same app port shows up once per bound address
        // family (`0.0.0.0:3000` and `[::]:3000`). Discovery must collapse
        // them to a single row, and ports outside 1000..10000 must be hidden
        // unless explicitly requested.
        val session = FakeSshSession(
            ssOutput = """
                0.0.0.0:3000 users:(("node",pid=42,fd=3))
                :::3000 users:(("node",pid=42,fd=4))
                0.0.0.0:11434 users:(("ollama",pid=42,fd=3))
                127.0.0.1:49152 users:(("app",pid=99,fd=3))
            """.trimIndent(),
        )
        val viewModel = newViewModel(FakeConnector(Result.success(session)))

        viewModel.load(hostId, "/tmp/key", discoverPorts = true)
        runCurrent()

        val state = viewModel.state.value
        assertFalse("discovery must leave auto-forward off", state.autoForwardEnabled)
        assertEquals(PortForwardConnectionState.Connected, state.connectionState)
        assertEquals(listOf(3000), state.tunnels.map { it.remotePort })
        assertEquals(1, state.tunnels.size)
        assertEquals("node", state.tunnels.single().process)
        assertEquals(2, state.hiddenPortCount)
        assertEquals(emptyList<FakePortForward>(), session.openedForwards)
        assertTrue("passive discovery SSH session should close after scan", session.closed)
    }

    @Test
    fun discoveryShowsUsefulRangeByDefaultAndShowAllRevealsHiddenPorts() = runTest {
        // #602: ports in 1000..10000 are shown by default while low/system and
        // high/noisy ports stay hidden. Toggling "Show hidden/noisy ports"
        // reveals every discovered port without a new SSH scan.
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1)
        val session = FakeSshSession(
            ssOutput = """
                0.0.0.0:22 users:(("sshd",pid=1,fd=3))
                0.0.0.0:443 users:(("nginx",pid=2,fd=3))
                0.0.0.0:3000 users:(("node",pid=42,fd=3))
                0.0.0.0:8080 users:(("vite",pid=43,fd=3))
                0.0.0.0:10000 users:(("dev",pid=44,fd=3))
                0.0.0.0:11434 users:(("ollama",pid=44,fd=3))
                0.0.0.0:49152 users:(("app",pid=45,fd=3))
            """.trimIndent(),
        )
        // Single connector result: a second SSH connect (a re-scan) would
        // fail the missing-stub assertion, proving the toggle re-filters the
        // cached scan instead of rescanning.
        val connector = QueueConnector(listOf(Result.success(session)))
        val viewModel = newViewModel(connector)

        viewModel.load(hostId, "/tmp/key", discoverPorts = true)
        runCurrent()

        // Default: useful range only, with the hidden/noisy count surfaced.
        assertFalse(viewModel.state.value.showAllPorts)
        assertEquals(listOf(3000, 8080, 10000), viewModel.state.value.tunnels.map { it.remotePort })
        assertEquals(4, viewModel.state.value.hiddenPortCount)

        // Show all: every discovered port, default-visible first, no extra connect.
        viewModel.setShowAllPorts(true)
        runCurrent()
        assertTrue(viewModel.state.value.showAllPorts)
        assertEquals(
            listOf(3000, 8080, 10000, 22, 443, 11434, 49152),
            viewModel.state.value.tunnels.map { it.remotePort },
        )
        assertEquals(1, connector.hosts.size)

        // Toggling back restores the filtered view.
        viewModel.setShowAllPorts(false)
        runCurrent()
        assertFalse(viewModel.state.value.showAllPorts)
        assertEquals(listOf(3000, 8080, 10000), viewModel.state.value.tunnels.map { it.remotePort })

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun discoveryHidesRowsWithHiddenLocalRemappedPortsByDefault() = runTest {
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1)
        db.portRemappingDao().insert(
            com.pocketshell.core.storage.entity.PortRemappingEntity(
                hostId = hostId,
                remotePort = 3_000,
                localPort = 49_152,
            ),
        )
        val session = FakeSshSession(
            ssOutput = """
                0.0.0.0:3000 users:(("node",pid=42,fd=3))
                0.0.0.0:8080 users:(("vite",pid=43,fd=3))
            """.trimIndent(),
        )
        val viewModel = newViewModel(FakeConnector(Result.success(session)))

        viewModel.load(hostId, "/tmp/key", discoverPorts = true)
        runCurrent()

        assertEquals(listOf(8080), viewModel.state.value.tunnels.map { it.remotePort })
        assertEquals(1, viewModel.state.value.hiddenPortCount)

        viewModel.setShowAllPorts(true)
        runCurrent()

        val tunnels = viewModel.state.value.tunnels
        assertEquals(listOf(8080, 3_000), tunnels.map { it.remotePort })
        assertEquals(49_152, tunnels.single { it.remotePort == 3_000 }.localPort)

        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun showAllPortsChoicePersistsAcrossViewModelInstances() = runTest {
        // #492: the checkbox is a persisted global pref. A fresh ViewModel
        // sharing the same store comes up with the saved value, and a fresh
        // discovery honours it without an explicit re-toggle.
        val store = ShowAllPortsStore(context)
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1)

        val first = newViewModel(
            FakeConnector(Result.success(FakeSshSession(ssOutput = ""))),
            showAllPortsStore = store,
        )
        first.setShowAllPorts(true)
        runCurrent()
        assertTrue(store.isShowAll())

        val session = FakeSshSession(
            ssOutput = """
                0.0.0.0:3000 users:(("node",pid=42,fd=3))
                0.0.0.0:49152 users:(("app",pid=45,fd=3))
            """.trimIndent(),
        )
        val second = newViewModel(
            FakeConnector(Result.success(session)),
            showAllPortsStore = store,
        )
        // Fresh instance picks up the persisted choice immediately.
        assertTrue(second.state.value.showAllPorts)

        second.load(hostId, "/tmp/key", discoverPorts = true)
        runCurrent()
        // Discovery honours the persisted show-all flag: high 49152 is visible.
        assertTrue(second.state.value.showAllPorts)
        assertEquals(listOf(3000, 49152), second.state.value.tunnels.map { it.remotePort })

        second.leavePanel()
        runCurrent()
    }

    @Test
    fun startPortFromDiscoveredStateEnablesForwardingExplicitly() = runTest {
        val hostId = insertHost(maxAutoPort = 2000, skipPortsBelow = 1000)
        val discoverySession = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val forwardingSession = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val connector = QueueConnector(
            listOf(Result.success(discoverySession), Result.success(forwardingSession)),
        )
        val viewModel = newViewModel(connector)

        viewModel.load(hostId, "/tmp/key", discoverPorts = true)
        runCurrent()
        viewModel.startPort(3000)
        runCurrent()

        assertFalse(discoverySession.openedForwards.any())
        assertTrue(viewModel.state.value.autoForwardEnabled)
        assertEquals(listOf(3000), forwardingSession.openedForwards.map { it.remotePort })
        assertEquals(com.pocketshell.core.portfwd.TunnelInfo.Status.FORWARDING, viewModel.state.value.tunnels.single().status)

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun loadWithPrefillRemotePortForwardsThatPortInOneStep() = runTest {
        // Slice B (#447): opening the panel pre-filled with a remote port
        // must connect and forward that port without a manual toggle,
        // and without a separate discovery scan (one SSH session).
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
        val session = FakeSshSession(
            ssOutput = "127.0.0.1:11434 users:((\"ollama\",pid=42,fd=3))\n",
        )
        val connector = QueueConnector(listOf(Result.success(session)))
        val viewModel = newViewModel(connector)

        viewModel.load(hostId, "/tmp/key", prefillRemotePort = 3000)
        runCurrent()

        val state = viewModel.state.value
        assertTrue("prefill must enable auto-forward", state.autoForwardEnabled)
        assertEquals(PortForwardConnectionState.Connected, state.connectionState)
        assertEquals(listOf(3000), session.openedForwards.map { it.remotePort })
        assertEquals(
            com.pocketshell.core.portfwd.TunnelInfo.Status.FORWARDING,
            state.tunnels.single { it.remotePort == 3000 }.status,
        )
        // Only the auto-forward session was opened: prefill skips the
        // idle discovery scan, so a single SSH connect happened.
        assertEquals(1, connector.hosts.size)

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun prefillRemotePortOnRecompositionStartsPortWithoutReconnect() = runTest {
        // Slice B (#447): a re-composition with the same host/credentials
        // but a newly-supplied prefill port must start that port using
        // the existing supervisor, not tear down + reconnect.
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1)
        val session = FakeSshSession(
            ssOutput = """
                127.0.0.1:3000 users:(("node",pid=42,fd=3))
                127.0.0.1:8080 users:(("vite",pid=43,fd=3))
            """.trimIndent(),
        )
        val connector = QueueConnector(listOf(Result.success(session)))
        val viewModel = newViewModel(connector)

        viewModel.load(hostId, "/tmp/key", prefillRemotePort = 3000)
        runCurrent()
        assertEquals(listOf(3000), session.openedForwards.map { it.remotePort })

        // Same panel, same credentials, new prefill port -> starts the
        // additional port over the existing session (no second connect).
        viewModel.load(hostId, "/tmp/key", prefillRemotePort = 8080)
        runCurrent()

        assertEquals(1, connector.hosts.size)
        assertFalse(session.closed)
        assertEquals(
            listOf(3000, 8080),
            session.openedForwards.map { it.remotePort }.sorted(),
        )

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun loadWithPrefillRemotePortStartsMissingPortWhenHostAlreadyActive() = runTest {
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
        val session = FakeSshSession(
            ssOutput = """
                127.0.0.1:3000 users:(("node",pid=42,fd=3))
                127.0.0.1:8080 users:(("vite",pid=43,fd=3))
            """.trimIndent(),
        )
        val connector = QueueConnector(listOf(Result.success(session)))
        val forwardingController = newForwardingController(connector)
        val firstPanel = newViewModel(
            connector = connector,
            forwardingController = forwardingController,
        )

        firstPanel.load(hostId, "/tmp/key")
        runCurrent()
        firstPanel.setAutoForwardEnabled(true)
        runCurrent()
        firstPanel.leavePanel()
        runCurrent()

        val secondPanel = newViewModel(
            connector = connector,
            forwardingController = forwardingController,
        )
        secondPanel.load(hostId, "/tmp/key", prefillRemotePort = 8080)
        runCurrent()

        assertEquals("active host prefill must reuse the existing SSH session", 1, connector.hosts.size)
        assertEquals(
            listOf(3000, 8080),
            session.openedForwards.map { it.remotePort }.sorted(),
        )
        assertEquals(
            com.pocketshell.core.portfwd.TunnelInfo.Status.FORWARDING,
            secondPanel.state.value.tunnels.single { it.remotePort == 8080 }.status,
        )

        secondPanel.setAutoForwardEnabled(false)
        secondPanel.leavePanel()
        runCurrent()
    }

    @Test
    fun loadWithPrefillRemotePortDoesNotToggleAlreadyForwardedActivePort() = runTest {
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
        val session = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val connector = QueueConnector(listOf(Result.success(session)))
        val forwardingController = newForwardingController(connector)
        val firstPanel = newViewModel(
            connector = connector,
            forwardingController = forwardingController,
        )

        firstPanel.load(hostId, "/tmp/key")
        runCurrent()
        firstPanel.setAutoForwardEnabled(true)
        runCurrent()
        firstPanel.leavePanel()
        runCurrent()

        val secondPanel = newViewModel(
            connector = connector,
            forwardingController = forwardingController,
        )
        secondPanel.load(hostId, "/tmp/key", prefillRemotePort = 3000)
        runCurrent()

        assertEquals(1, connector.hosts.size)
        assertEquals(listOf(3000), session.openedForwards.map { it.remotePort })
        assertTrue(session.openedForwards.single().isActive)
        assertEquals(
            com.pocketshell.core.portfwd.TunnelInfo.Status.FORWARDING,
            secondPanel.state.value.tunnels.single { it.remotePort == 3000 }.status,
        )

        secondPanel.setAutoForwardEnabled(false)
        secondPanel.leavePanel()
        runCurrent()
    }

    @Test
    fun loadWithoutPrefillRunsDiscoveryAndLeavesForwardingOff() = runTest {
        // Guard: the manual add-forward flow is unaffected — a load
        // without a prefill port still discovers ports passively and
        // does not open any forward until the user acts.
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
        val session = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val viewModel = newViewModel(FakeConnector(Result.success(session)))

        viewModel.load(hostId, "/tmp/key", discoverPorts = true)
        runCurrent()

        val state = viewModel.state.value
        assertFalse(state.autoForwardEnabled)
        assertEquals(PortForwardConnectionState.Connected, state.connectionState)
        assertEquals(1, state.tunnels.size)
        assertEquals(
            com.pocketshell.core.portfwd.TunnelInfo.Status.AVAILABLE,
            state.tunnels.single().status,
        )
        assertEquals(emptyList<FakePortForward>(), session.openedForwards)

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun loadingDifferentHostKeepsExistingControllerOwnedForwarderAlive() = runTest {
        val hostA = insertHost(name = "a", keyPath = "/tmp/a", maxAutoPort = 4000, skipPortsBelow = 1000)
        val hostB = insertHost(name = "b", keyPath = "/tmp/b", maxAutoPort = 5000, skipPortsBelow = 1000)
        val sessionA = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val sessionB = FakeSshSession(
            ssOutput = "127.0.0.1:4000 users:((\"python\",pid=44,fd=3))\n",
        )
        val connector = QueueConnector(listOf(Result.success(sessionA), Result.success(sessionB)))
        val forwardingController = newForwardingController(connector)
        val viewModel = newViewModel(
            connector = connector,
            forwardingController = forwardingController,
        )

        viewModel.load(hostA, "/tmp/a")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()
        assertTrue(viewModel.state.value.autoForwardEnabled)

        viewModel.load(hostB, "/tmp/b")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        assertFalse("loading a different panel host must not kill host A forwarding", sessionA.closed)
        assertTrue(sessionA.openedForwards.single().isActive)
        assertTrue(viewModel.state.value.autoForwardEnabled)
        assertEquals("b", viewModel.state.value.host?.name)
        assertEquals(listOf("/tmp/a", "/tmp/b"), connector.keys)
        assertFalse(sessionB.closed)
        assertEquals(2, forwardingController.flowOfActiveHostCount().value)

        viewModel.leavePanel()
        forwardingController.stopAllForwarding(requestServiceStop = false)
        runCurrent()
        assertTrue(sessionA.closed)
        assertTrue(sessionB.closed)
    }

    @Test
    fun sameHostReloadWithNewKeyUsesNewKeyPath() = runTest {
        val hostId = insertHost(keyPath = "/tmp/db-key")
        val session = FakeSshSession(ssOutput = "")
        val connector = QueueConnector(listOf(Result.success(session)))
        val viewModel = newViewModel(connector)

        viewModel.load(hostId, "/tmp/old")
        runCurrent()
        viewModel.load(hostId, "/tmp/new")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        assertEquals(listOf("/tmp/new"), connector.keys)

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun sameHostReloadWithNewPassphraseUsesNewPassphrase() = runTest {
        val hostId = insertHost(keyPath = "/tmp/key")
        val session = FakeSshSession(ssOutput = "")
        val connector = QueueConnector(listOf(Result.success(session)))
        val viewModel = newViewModel(connector)

        viewModel.load(hostId, "/tmp/key", "old".toCharArray())
        runCurrent()
        viewModel.load(hostId, "/tmp/key", "new".toCharArray())
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        assertEquals(listOf("new"), connector.passphrases)

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun staleConnectCannotWinAfterPassphraseReload() = runTest {
        val hostId = insertHost(keyPath = "/tmp/key")
        val staleSession = FakeSshSession(ssOutput = "")
        val freshSession = FakeSshSession(ssOutput = "")
        val connector = NonCooperativeDeferredConnector()
        val viewModel = newViewModel(connector)

        viewModel.load(hostId, "/tmp/key", "old".toCharArray())
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        viewModel.load(hostId, "/tmp/key", "new".toCharArray())
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        connector.completeCall(1, Result.success(freshSession))
        runCurrent()
        connector.completeCall(0, Result.success(staleSession))
        runCurrent()

        assertEquals(listOf("old", "new"), connector.passphrases)
        assertEquals(PortForwardConnectionState.Connected, viewModel.state.value.connectionState)
        assertTrue(staleSession.closed)
        assertFalse(freshSession.closed)

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun leavePanelClearsStoredPassphrase() = runTest {
        val hostId = insertHost(keyPath = "/tmp/key")
        val session = FakeSshSession(ssOutput = "")
        val connector = QueueConnector(listOf(Result.success(session)))
        val viewModel = newViewModel(connector)

        viewModel.load(hostId, "/tmp/key", "secret".toCharArray())
        runCurrent()
        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        assertEquals(listOf(null), connector.passphrases)

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun reloadingSamePanelAfterLeaveDoesNotAutostartEnabledHost() = runTest {
        val hostId = insertHost(keyPath = "/tmp/key", enabled = true)
        val connector = QueueConnector(emptyList())
        val viewModel = newViewModel(connector)

        viewModel.load(hostId, "/tmp/key", "secret".toCharArray())
        runCurrent()
        assertEquals(PortForwardConnectionState.Idle, viewModel.state.value.connectionState)
        assertFalse(viewModel.state.value.autoForwardEnabled)

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
        viewModel.load(hostId, "/tmp/key")
        runCurrent()

        assertEquals(emptyList<String?>(), connector.passphrases)
        assertEquals(PortForwardConnectionState.Idle, viewModel.state.value.connectionState)
        assertFalse(viewModel.state.value.autoForwardEnabled)

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun staleLoadAfterLeaveDoesNotRepopulateOrAutostart() = runTest {
        val host = testHost(id = 1, enabled = true)
        val hostDao = NonCooperativeHostDao()
        val connector = QueueConnector(listOf(Result.success(FakeSshSession(ssOutput = ""))))
        val viewModel = newViewModel(connector, hostDao = hostDao)

        viewModel.load(host.id, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
        hostDao.completeCall(0, host)
        runCurrent()

        assertEquals(PortForwardPanelState(), viewModel.state.value)
        assertEquals(emptyList<String>(), connector.keys)
    }

    @Test
    fun staleLoadCannotOverwriteNewerLoad() = runTest {
        val hostA = testHost(id = 1, name = "a", enabled = true)
        val hostB = testHost(id = 2, name = "b")
        val hostDao = NonCooperativeHostDao()
        val viewModel = newViewModel(FakeConnector(Result.success(FakeSshSession(ssOutput = ""))), hostDao = hostDao)

        viewModel.load(hostA.id, "/tmp/a")
        runCurrent()
        viewModel.load(hostB.id, "/tmp/b")
        runCurrent()
        hostDao.completeCall(1, hostB)
        runCurrent()
        hostDao.completeCall(0, hostA)
        runCurrent()

        assertEquals("b", viewModel.state.value.host?.name)
        assertFalse(viewModel.state.value.autoForwardEnabled)
        assertEquals(PortForwardConnectionState.Idle, viewModel.state.value.connectionState)
    }

    @Test
    fun enablingDuringPendingReloadDoesNotConnectPreviousHostWithNewCredentials() = runTest {
        val hostA = testHost(id = 1, name = "a")
        val hostB = testHost(id = 2, name = "b")
        val hostDao = NonCooperativeHostDao()
        val session = FakeSshSession(ssOutput = "")
        val connector = QueueConnector(listOf(Result.success(session)))
        val viewModel = newViewModel(connector, hostDao = hostDao)

        viewModel.load(hostA.id, "/tmp/a", "old".toCharArray())
        runCurrent()
        hostDao.completeCall(0, hostA)
        runCurrent()

        viewModel.load(hostB.id, "/tmp/b", "new".toCharArray())
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        assertEquals(null, viewModel.state.value.host)
        assertEquals(emptyList<String>(), connector.hosts)
        assertEquals(emptyList<String>(), connector.keys)
        assertEquals(emptyList<String?>(), connector.passphrases)
        assertFalse(session.closed)

        hostDao.completeCall(1, hostB)
        runCurrent()

        assertEquals("b", viewModel.state.value.host?.name)
        assertFalse(viewModel.state.value.autoForwardEnabled)
        assertEquals(emptyList<String>(), connector.hosts)
    }

    @Test
    fun leavePanelDetachesUiButKeepsControllerOwnedForwardingAlive() = runTest {
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
        val session = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val connector = FakeConnector(Result.success(session))
        val forwardingController = newForwardingController(connector)
        val viewModel = newViewModel(
            connector = connector,
            forwardingController = forwardingController,
        )

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        viewModel.leavePanel()
        runCurrent()

        assertFalse(viewModel.state.value.autoForwardEnabled)
        assertEquals(PortForwardConnectionState.Idle, viewModel.state.value.connectionState)
        assertEquals(
            "panel disposal must not unregister the foreground-service-backed host",
            1,
            forwardingController.flowOfActiveHostCount().value,
        )
        assertEquals(
            "notification input must keep the dismissed panel's host name",
            "dev",
            forwardingController.flowOfPrimaryHostName().value,
        )
        assertEquals(
            "notification input must keep the dismissed panel's tunnel count",
            1,
            forwardingController.flowOfTotalTunnelCount().value,
        )
        val chipState = SessionForwardingIndicatorViewModel(forwardingController)
            .stateFor(hostId)
            .first { it.visible }
        assertEquals(
            "session indicator must remain visible after the panel is dismissed",
            1,
            chipState.tunnelCount,
        )
        assertFalse("active forwarding must outlive panel disposal", session.closed)
        assertTrue(session.openedForwards.single().isActive)

        forwardingController.stopForwarding(hostId)
        runCurrent()

        assertTrue(session.closed)
        assertFalse(session.openedForwards.single().isActive)
    }

    @Test
    fun dismissedPanelForwardingSurvivesServiceForcedReconnect() = runTest {
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
        val firstSession = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val recoveredSession = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val connector = QueueConnector(
            listOf(Result.success(firstSession), Result.success(recoveredSession)),
        )
        // Issue #980: the forced reconnect is now driven SOLELY by the
        // controller's hardened TerminalNetworkObserver.changes subscription
        // (the service's raw force-tear callback was deleted). Emit a real
        // validated-handoff change through that flow to trigger the rebuild.
        val networkChanges =
            kotlinx.coroutines.flow.MutableSharedFlow<Any?>(extraBufferCapacity = 16)
        val forwardingController = newForwardingController(
            connector = connector,
            validatedNetworkChanges = networkChanges,
        )
        val viewModel = newViewModel(
            connector = connector,
            forwardingController = forwardingController,
        )

        try {
            viewModel.load(hostId, "/tmp/key", "secret".toCharArray())
            runCurrent()
            viewModel.setAutoForwardEnabled(true)
            runCurrent()

            viewModel.leavePanel()
            runCurrent()

            assertEquals(1, forwardingController.flowOfActiveHostCount().value)
            assertEquals(1, forwardingController.flowOfTotalTunnelCount().value)
            assertFalse("panel dismissal must not close the controller-owned SSH session", firstSession.closed)

            firstSession.simulateDeadForwardButStillConnected()
            // A real validated default-network handoff reaches the controller's
            // hardened subscription, which forces a rebuild on every active host
            // even after the panel is gone.
            assertTrue(networkChanges.tryEmit(Any()))
            advanceTimeBy(1_100L)
            runCurrent()

            assertEquals(
                "a validated-handoff network change must reach the controller-owned supervisor after the panel is gone",
                listOf("dev", "dev"),
                connector.hosts,
            )
            assertEquals(listOf("secret", "secret"), connector.passphrases)
            assertTrue("forced reconnect must close the stale session", firstSession.closed)
            assertEquals(1, forwardingController.flowOfActiveHostCount().value)
            assertEquals(1, forwardingController.flowOfTotalTunnelCount().value)
            val restoredForward = recoveredSession.openedForwards.single()
            assertEquals(
                mapOf(3000 to restoredForward.localPort),
                forwardingController.flowOfHostSnapshots().value.getValue(hostId).forwardedPortMap,
            )
            assertTrue(restoredForward.isActive)

            val chipState = SessionForwardingIndicatorViewModel(forwardingController)
                .stateFor(hostId)
                .first { it.visible && it.tunnelCount == 1 }
            assertFalse("restored forwarding should not leave the in-session chip stuck restoring", chipState.restoring)
        } finally {
            forwardingController.stopForwarding(hostId)
            runCurrent()
        }

        assertTrue(recoveredSession.closed)
        assertFalse(recoveredSession.openedForwards.single().isActive)
    }

    @Test
    fun userToggleOnPersistsHostEnabledTrueOnSuccess() = runTest {
        val hostId = insertHost(enabled = false)
        val session = FakeSshSession(ssOutput = "")
        val viewModel = newViewModel(FakeConnector(Result.success(session)))

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        val stored = db.hostDao().getById(hostId)
        assertNotNull(stored)
        assertTrue(
            "user-driven toggle on must persist HostEntity.enabled=true",
            stored!!.enabled,
        )
        assertEquals(true, viewModel.state.value.host?.enabled)

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun userToggleOffPersistsHostEnabledFalse() = runTest {
        val hostId = insertHost(enabled = true)
        val session = FakeSshSession(ssOutput = "")
        val viewModel = newViewModel(FakeConnector(Result.success(session)))

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()
        assertTrue(viewModel.state.value.autoForwardEnabled)

        viewModel.setAutoForwardEnabled(false)
        runCurrent()

        val stored = db.hostDao().getById(hostId)
        assertNotNull(stored)
        assertFalse(
            "user-driven toggle off must persist HostEntity.enabled=false",
            stored!!.enabled,
        )
        assertEquals(false, viewModel.state.value.host?.enabled)

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun userToggleOnConnectionFailureLeavesHostEnabledUnchanged() = runTest {
        val hostId = insertHost(enabled = false)
        val viewModel = newViewModel(FakeConnector(Result.failure(RuntimeException("no route"))))

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        val stored = db.hostDao().getById(hostId)
        assertNotNull(stored)
        assertFalse(
            "failed connect on a never-enabled host must NOT persist enabled=true",
            stored!!.enabled,
        )
    }

    @Test
    fun lifecycleStopKeepsActiveTunnelsUnderForegroundServiceCarveOut() = runTest {
        val hostId = insertHost(enabled = false)
        val session = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val viewModel = newViewModel(FakeConnector(Result.success(session)))
        val owner = ManualLifecycleOwner().also { it.moveTo(Lifecycle.State.RESUMED) }
        viewModel.observeProcessLifecycle(owner)
        runCurrent()

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        assertTrue(viewModel.state.value.autoForwardEnabled)
        assertEquals(1, session.openedForwards.size)
        assertTrue(session.openedForwards.single().isActive)

        owner.moveTo(Lifecycle.State.CREATED) // dispatches ON_PAUSE then ON_STOP
        runCurrent()

        assertTrue(
            "ON_STOP must keep explicit auto-forward sessions active under the foreground-service carve-out",
            viewModel.state.value.autoForwardEnabled,
        )
        assertEquals(
            com.pocketshell.core.portfwd.TunnelInfo.Status.FORWARDING,
            viewModel.state.value.tunnels.single().status,
        )
        assertFalse("active tunnels must survive ON_STOP", session.closed)
        assertTrue(session.openedForwards.single().isActive)

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun lifecycleStopDoesNotPersistHostEnabledFalse() = runTest {
        val hostId = insertHost(enabled = false)
        val session = FakeSshSession(ssOutput = "")
        val viewModel = newViewModel(FakeConnector(Result.success(session)))
        val owner = ManualLifecycleOwner().also { it.moveTo(Lifecycle.State.RESUMED) }
        viewModel.observeProcessLifecycle(owner)
        runCurrent()

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        // Baseline: user toggle persisted enabled=true.
        assertEquals(true, db.hostDao().getById(hostId)?.enabled)

        owner.moveTo(Lifecycle.State.CREATED)
        runCurrent()

        val stored = db.hostDao().getById(hostId)
        assertNotNull(stored)
        assertTrue(
            "lifecycle ON_STOP must NOT clear HostEntity.enabled; user intent preserved",
            stored!!.enabled,
        )

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun lifecycleStartDoesNotReconnectAlreadySupervisedTunnels() = runTest {
        val hostId = insertHost(enabled = false)
        val firstSession = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val unusedResumedSession = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val connector = QueueConnector(
            listOf(Result.success(firstSession), Result.success(unusedResumedSession)),
        )
        val viewModel = newViewModel(connector)
        val owner = ManualLifecycleOwner().also { it.moveTo(Lifecycle.State.RESUMED) }
        viewModel.observeProcessLifecycle(owner)
        runCurrent()

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()
        assertTrue(viewModel.state.value.autoForwardEnabled)

        owner.moveTo(Lifecycle.State.CREATED)
        runCurrent()
        assertTrue(viewModel.state.value.autoForwardEnabled)
        assertFalse(firstSession.closed)

        owner.moveTo(Lifecycle.State.RESUMED) // dispatches ON_START then ON_RESUME
        runCurrent()

        assertTrue(
            "ON_START must leave already-supervised tunnels running without a second connect",
            viewModel.state.value.autoForwardEnabled,
        )
        assertEquals(PortForwardConnectionState.Connected, viewModel.state.value.connectionState)
        assertTrue(firstSession.openedForwards.single().isActive)
        assertEquals(1, connector.hosts.size)

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun lifecycleStartDoesNotResumeWhenWasIdleBeforeBackground() = runTest {
        val hostId = insertHost(enabled = false)
        val session = FakeSshSession(ssOutput = "")
        val connector = QueueConnector(listOf(Result.success(session)))
        val viewModel = newViewModel(connector)
        val owner = ManualLifecycleOwner().also { it.moveTo(Lifecycle.State.RESUMED) }
        viewModel.observeProcessLifecycle(owner)
        runCurrent()

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        // Never enabled.
        assertFalse(viewModel.state.value.autoForwardEnabled)

        owner.moveTo(Lifecycle.State.CREATED)
        runCurrent()
        owner.moveTo(Lifecycle.State.RESUMED)
        runCurrent()

        assertFalse(
            "ON_START on a host that was idle before background must not autostart",
            viewModel.state.value.autoForwardEnabled,
        )
        assertEquals(emptyList<String>(), connector.hosts)
    }

    @Test
    fun lifecycleStartDoesNotResumeAfterLeavePanel() = runTest {
        val hostId = insertHost(enabled = false)
        val firstSession = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val connector = QueueConnector(listOf(Result.success(firstSession)))
        val viewModel = newViewModel(connector)
        val owner = ManualLifecycleOwner().also { it.moveTo(Lifecycle.State.RESUMED) }
        viewModel.observeProcessLifecycle(owner)
        runCurrent()

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        owner.moveTo(Lifecycle.State.CREATED)
        runCurrent()
        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
        owner.moveTo(Lifecycle.State.RESUMED)
        runCurrent()

        assertFalse(viewModel.state.value.autoForwardEnabled)
        assertNull(viewModel.state.value.host)
        // No second connect was attempted on resume.
        assertEquals(1, connector.hosts.size)
    }

    @Test
    fun observeProcessLifecycleIsIdempotent() = runTest {
        val hostId = insertHost(enabled = false)
        val session = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val viewModel = newViewModel(FakeConnector(Result.success(session)))
        val owner = ManualLifecycleOwner().also { it.moveTo(Lifecycle.State.RESUMED) }
        viewModel.observeProcessLifecycle(owner)
        viewModel.observeProcessLifecycle(owner)
        runCurrent()

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()
        assertTrue(viewModel.state.value.autoForwardEnabled)
        // The fake session opened one forward for the discovered port.
        assertEquals(1, session.openedForwards.size)

        // Triggering ON_STOP must not stop forwarding even though the
        // owner was observed twice.
        owner.moveTo(Lifecycle.State.CREATED)
        runCurrent()
        assertTrue(viewModel.state.value.autoForwardEnabled)
        assertEquals(1, session.openedForwards.size)
        assertTrue(session.openedForwards.single().isActive)
        assertFalse(session.closed)

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun formatBytesUsesCompactUnits() {
        assertEquals("999 B", formatBytes(999))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("2.0 MB", formatBytes(2 * 1024 * 1024))
    }

    @Test
    fun persistedRemappingOverridesLocalPortMirroring() = runTest {
        // Issue #203 expanded scope: a persisted PortRemappingEntity on
        // the host must override the natural "mirror remote port onto
        // same local port" rule once auto-forward is enabled. The
        // ViewModel loads the remappings from the DAO before
        // constructing the AutoForwarder.
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
        db.portRemappingDao().insert(
            com.pocketshell.core.storage.entity.PortRemappingEntity(
                hostId = hostId,
                remotePort = 3000,
                localPort = 9000,
            ),
        )
        val session = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val viewModel = newViewModel(FakeConnector(Result.success(session)))

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        val tunnel = viewModel.state.value.tunnels.single()
        assertEquals(3000, tunnel.remotePort)
        // 3000 is inside the auto-forward window — without the remap
        // entry the local port would mirror to 3000. With the remap,
        // the local port must be 9000.
        assertEquals(9000, tunnel.localPort)
        assertEquals(
            "openLocalPortForward should have been called with the remapped local port",
            9000,
            session.openedForwards.single().localPort,
        )

        viewModel.setAutoForwardEnabled(false)
        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun networkRecoveryReconnectsSupervisorAndRestoresTunnels() = runTest {
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
        val firstSession = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val recoveredSession = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val connector = QueueConnector(
            listOf(Result.success(firstSession), Result.success(recoveredSession)),
        )
        val forwardingController = newForwardingController(connector)
        val viewModel = newViewModel(
            connector = connector,
            forwardingController = forwardingController,
        )

        viewModel.load(hostId, "/tmp/key", "secret".toCharArray())
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        assertEquals(PortForwardConnectionState.Connected, viewModel.state.value.connectionState)
        assertEquals(
            com.pocketshell.core.portfwd.TunnelInfo.Status.FORWARDING,
            viewModel.state.value.tunnels.single().status,
        )
        assertEquals(1, firstSession.openedForwards.size)

        firstSession.close()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            PortForwardConnectionState.Reconnecting,
            viewModel.state.value.connectionState,
        )
        assertEquals(
            com.pocketshell.core.portfwd.TunnelInfo.Status.STOPPED,
            viewModel.state.value.tunnels.single().status,
        )
        assertEquals(0, forwardingController.flowOfTotalTunnelCount().value)

        forwardingController.reconnectNow()
        runCurrent()

        assertEquals(listOf("dev", "dev"), connector.hosts)
        assertEquals(listOf("secret", "secret"), connector.passphrases)
        assertEquals(PortForwardConnectionState.Connected, viewModel.state.value.connectionState)
        assertEquals(
            com.pocketshell.core.portfwd.TunnelInfo.Status.FORWARDING,
            viewModel.state.value.tunnels.single().status,
        )
        assertEquals(1, recoveredSession.openedForwards.size)
        assertEquals(1, forwardingController.flowOfTotalTunnelCount().value)

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun backgroundForegroundNetworkRecoveryForceRestoresStaleActiveForward() = runTest {
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
        val firstSession = FakeSshSession(
            ssOutput = "127.0.0.1:22 users:((\"sshd\",pid=1,fd=3))\n",
        )
        val recoveredSession = FakeSshSession(
            ssOutput = "127.0.0.1:22 users:((\"sshd\",pid=1,fd=3))\n",
        )
        val connector = QueueConnector(
            listOf(Result.success(firstSession), Result.success(recoveredSession)),
        )
        val forwardingController = newForwardingController(connector)
        val viewModel = newViewModel(
            connector = connector,
            forwardingController = forwardingController,
        )
        val owner = ManualLifecycleOwner().also { it.moveTo(Lifecycle.State.RESUMED) }
        viewModel.observeProcessLifecycle(owner)
        runCurrent()

        viewModel.load(hostId, "/tmp/key", "secret".toCharArray())
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()
        viewModel.startPort(22)
        runCurrent()

        assertEquals(PortForwardConnectionState.Connected, viewModel.state.value.connectionState)
        assertEquals(1, forwardingController.flowOfActiveHostCount().value)
        assertEquals(1, forwardingController.flowOfTotalTunnelCount().value)
        assertEquals(
            com.pocketshell.core.portfwd.TunnelInfo.Status.FORWARDING,
            viewModel.state.value.tunnels.single { it.remotePort == 22 }.status,
        )
        assertEquals(listOf(22), firstSession.openedForwards.map { it.remotePort })

        owner.moveTo(Lifecycle.State.CREATED)
        runCurrent()
        firstSession.simulateDeadForwardButStillConnected()
        advanceTimeBy(5_000L)
        runCurrent()

        assertTrue(
            "the stale session models sshj still reporting connected after the forward died",
            firstSession.isConnected,
        )
        assertEquals(
            "plain backgrounding must keep the enabled host registered",
            1,
            forwardingController.flowOfActiveHostCount().value,
        )
        assertEquals(
            "without a forced network-loss recovery hint the stale connected session is not rebuilt",
            listOf("dev"),
            connector.hosts,
        )

        owner.moveTo(Lifecycle.State.RESUMED)
        runCurrent()
        forwardingController.forceReconnectNow()
        advanceTimeBy(1_100L)
        runCurrent()

        assertEquals(listOf("dev", "dev"), connector.hosts)
        assertEquals(listOf("secret", "secret"), connector.passphrases)
        assertTrue("force reconnect must close the stale session", firstSession.closed)
        assertEquals(PortForwardConnectionState.Connected, viewModel.state.value.connectionState)
        assertEquals(
            com.pocketshell.core.portfwd.TunnelInfo.Status.FORWARDING,
            viewModel.state.value.tunnels.single { it.remotePort == 22 }.status,
        )
        assertEquals(
            "manual out-of-window forward must be restored on the fresh SSH session",
            listOf(22),
            recoveredSession.openedForwards.map { it.remotePort },
        )
        assertEquals(1, forwardingController.flowOfTotalTunnelCount().value)

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun serviceStopActionStopsControllerOwnedForwarding() = runTest {
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
        val session = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val connector = FakeConnector(Result.success(session))
        val forwardingController = newForwardingController(connector)
        val viewModel = newViewModel(
            connector = connector,
            forwardingController = forwardingController,
        )

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        assertEquals(1, forwardingController.flowOfActiveHostCount().value)
        assertFalse(session.closed)
        assertTrue(session.openedForwards.single().isActive)

        val service = Robolectric.buildService(ForwardingService::class.java).get()
        service.controller = forwardingController
        service.onStartCommand(
            Intent(context, ForwardingService::class.java).apply {
                action = ForwardingService.ACTION_STOP
            },
            0,
            1,
        )
        runCurrent()

        assertEquals(0, forwardingController.flowOfActiveHostCount().value)
        assertTrue(session.closed)
        assertFalse(session.openedForwards.single().isActive)
    }

    @Test
    fun reconnectNowWhileAlreadyConnectedDoesNotChurnHealthyTunnel() = runTest {
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
        val session = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val unusedReconnectSession = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val connector = QueueConnector(
            listOf(Result.success(session), Result.success(unusedReconnectSession)),
        )
        val forwardingController = newForwardingController(connector)
        val viewModel = newViewModel(
            connector = connector,
            forwardingController = forwardingController,
        )

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        forwardingController.reconnectNow()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            "onAvailable for an already-active network must not force a second SSH connect",
            listOf("dev"),
            connector.hosts,
        )
        assertFalse(session.closed)
        assertTrue(session.openedForwards.single().isActive)
        assertEquals(PortForwardConnectionState.Connected, viewModel.state.value.connectionState)

        viewModel.setAutoForwardEnabled(false)
        viewModel.leavePanel()
        runCurrent()
    }

    /**
     * Manual [LifecycleOwner] backed by [LifecycleRegistry]. Tests drive
     * state transitions explicitly via [moveTo] so the panel's lifecycle
     * observer fires deterministically. `LifecycleRegistry.handleLifecycleEvent`
     * dispatches synchronously while the registry's state is advanced,
     * which combined with [UnconfinedTestDispatcher] makes the
     * subsequent assertions see the post-event state without scheduler
     * shenanigans.
     */
    private class ManualLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.INITIALIZED }
        override val lifecycle: Lifecycle = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    private suspend fun insertHost(
        name: String = "dev",
        keyPath: String = "/tmp/key",
        maxAutoPort: Int = 10_000,
        skipPortsBelow: Int = 1000,
        enabled: Boolean = false,
    ): Long {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k-$name", privateKeyPath = keyPath))
        return db.hostDao().insert(
            HostEntity(
                name = name,
                hostname = "$name.example",
                username = "alexey",
                keyId = keyId,
                maxAutoPort = maxAutoPort,
                skipPortsBelow = skipPortsBelow,
                scanIntervalSec = 5,
                enabled = enabled,
            ),
        )
    }

    private fun testHost(
        id: Long,
        name: String = "dev",
        keyId: Long = 1,
        maxAutoPort: Int = 10_000,
        skipPortsBelow: Int = 1000,
        enabled: Boolean = false,
    ): HostEntity =
        HostEntity(
            id = id,
            name = name,
            hostname = "$name.example",
            username = "alexey",
            keyId = keyId,
            maxAutoPort = maxAutoPort,
            skipPortsBelow = skipPortsBelow,
            scanIntervalSec = 5,
            enabled = enabled,
        )

    private fun newViewModel(
        connector: PortForwardConnector,
        hostDao: HostDao = db.hostDao(),
        sshKeyDao: SshKeyDao = db.sshKeyDao(),
        portRemappingDao: com.pocketshell.core.storage.dao.PortRemappingDao = db.portRemappingDao(),
        forwardingController: ForwardingController = ForwardingController(
            appContext = context,
            connector = connector,
            portRemappingDao = portRemappingDao,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        ),
        showAllPortsStore: ShowAllPortsStore = ShowAllPortsStore(context),
    ): PortForwardPanelViewModel =
        PortForwardPanelViewModel(
            hostDao = hostDao,
            sshKeyDao = sshKeyDao,
            connector = connector,
            portRemappingDao = portRemappingDao,
            forwardingController = forwardingController,
            showAllPortsStore = showAllPortsStore,
        )

    private fun newForwardingController(
        connector: PortForwardConnector,
        portRemappingDao: com.pocketshell.core.storage.dao.PortRemappingDao = db.portRemappingDao(),
        validatedNetworkChanges: kotlinx.coroutines.flow.Flow<*> =
            kotlinx.coroutines.flow.emptyFlow<Any?>(),
    ): ForwardingController =
        ForwardingController(
            appContext = context,
            connector = connector,
            portRemappingDao = portRemappingDao,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            validatedNetworkChanges = validatedNetworkChanges,
        )

    private class FakeConnector(
        private val result: Result<SshSession>,
    ) : PortForwardConnector {
        override suspend fun connect(host: HostEntity, keyPath: String, passphrase: CharArray?): Result<SshSession> = result
    }

    private class QueueConnector(
        results: List<Result<SshSession>>,
    ) : PortForwardConnector {
        private val queue = ArrayDeque(results)
        val hosts = mutableListOf<String>()
        val keys = mutableListOf<String>()
        val passphrases = mutableListOf<String?>()

        override suspend fun connect(host: HostEntity, keyPath: String, passphrase: CharArray?): Result<SshSession> {
            hosts += host.name
            keys += keyPath
            passphrases += passphrase?.concatToString()
            return queue.removeFirstOrNull() ?: Result.failure(AssertionError("missing connector stub"))
        }
    }

    private class NonCooperativeDeferredConnector : PortForwardConnector {
        private val calls = mutableListOf<CompletableDeferred<Result<SshSession>>>()
        val passphrases = mutableListOf<String?>()

        override suspend fun connect(host: HostEntity, keyPath: String, passphrase: CharArray?): Result<SshSession> {
            val deferred = CompletableDeferred<Result<SshSession>>()
            calls += deferred
            passphrases += passphrase?.concatToString()
            return try {
                deferred.await()
            } catch (_: CancellationException) {
                withContext(NonCancellable) { deferred.await() }
            }
        }

        fun completeCall(index: Int, result: Result<SshSession>) {
            calls[index].complete(result)
        }
    }

    private class NonCooperativeHostDao : HostDao {
        private val calls = mutableListOf<CompletableDeferred<HostEntity?>>()

        override fun getAll(): Flow<List<HostEntity>> = flowOf(emptyList())

        override suspend fun getById(id: Long): HostEntity? {
            val deferred = CompletableDeferred<HostEntity?>()
            calls += deferred
            return try {
                deferred.await()
            } catch (_: CancellationException) {
                withContext(NonCancellable) { deferred.await() }
            }
        }

        override fun getEnabled(): Flow<List<HostEntity>> = flowOf(emptyList())

        override suspend fun insert(host: HostEntity): Long = error("insert not used")

        override suspend fun update(host: HostEntity) {
            error("update not used")
        }

        override suspend fun delete(host: HostEntity) {
            error("delete not used")
        }

        override suspend fun deleteById(id: Long) {
            error("deleteById not used")
        }

        fun completeCall(index: Int, host: HostEntity?) {
            calls[index].complete(host)
        }
    }

    private class FakeSshSession(
        private val ssOutput: String,
    ) : SshSession {
        val openedForwards = mutableListOf<FakePortForward>()
        var closed = false
            private set
        private var staleConnectedFailure = false

        override val isConnected: Boolean
            get() = !closed

        fun simulateDeadForwardButStillConnected() {
            staleConnectedFailure = true
            openedForwards.forEach { it.close() }
        }

        override suspend fun exec(command: String): ExecResult {
            if (closed || staleConnectedFailure) throw RuntimeException("session transport is stale")
            return ExecResult(stdout = ssOutput, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward {
            if (closed || staleConnectedFailure) throw RuntimeException("session transport is stale")
            return FakePortForward(remoteHost, remotePort, localPort).also { openedForwards += it }
        }

        override fun startShell(): SshShell = error("shell not used")

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() {
            closed = true
            openedForwards.forEach { it.close() }
        }
    }

    private class FakePortForward(
        override val remoteHost: String,
        override val remotePort: Int,
        override val localPort: Int,
    ) : SshPortForward {
        override var isActive: Boolean = true
            private set
        override val bytesForwarded: Long = 0
        override val bytesReceived: Long = 0

        override fun close() {
            isActive = false
        }
    }
}
