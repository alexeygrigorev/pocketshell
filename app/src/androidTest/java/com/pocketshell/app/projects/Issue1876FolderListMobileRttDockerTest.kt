package com.pocketshell.app.projects

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #1876 device-level journey through the production FolderListViewModel
 * and SshFolderListGateway over the repository's real tc-netem proxy.
 *
 * CI starts packet-loss-proxy on host port 2229 with 200 ms one-way delay,
 * 40 ms jitter, and 5% loss (~400 ms RTT). The fixture carries 18 real tmux
 * sessions and three watched roots, matching the maintainer's failing host
 * shape. The load-bearing assertion is that the production view model leaves
 * Loading without ConnectError and projects the complete live tree while its
 * unchanged 12-second reconcile bound is active.
 */
@RunWith(AndroidJUnit4::class)
class Issue1876FolderListMobileRttDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private lateinit var db: AppDatabase
    private val viewModelStore = ViewModelStore()
    private val createdSessions = mutableListOf<String>()

    @Before
    fun setUp(): Unit { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val keyText = instrumentation.context.assets.open("test_key")
            .bufferedReader()
            .use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        keyFile = File(instrumentation.targetContext.cacheDir, "issue1876-mobile-rtt-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        db = Room.inMemoryDatabaseBuilder(
            instrumentation.targetContext,
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        // Hard preconditions, never self-skips: both the direct seed route and
        // shaped app route must be alive or this gated journey fails.
        waitForSshFixtureReady(sshKey, port = DEFAULT_PORT)
        waitForSshFixtureReady(sshKey, port = MOBILE_PORT)
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        viewModelStore.clear()
        if (createdSessions.isNotEmpty()) {
            runCatching {
                withTimeout(20_000L) {
                    SshConnection.connect(
                        host = DEFAULT_HOST,
                        port = DEFAULT_PORT,
                        user = DEFAULT_USER,
                        key = sshKey,
                        knownHosts = KnownHostsPolicy.AcceptAll,
                        timeoutMs = 10_000,
                    ).getOrNull()?.use { session ->
                        createdSessions.forEach { name ->
                            runCatching {
                                session.exec("tmux kill-session -t '$name' 2>/dev/null || true")
                            }
                        }
                    }
                }
            }
        }
        runCatching { db.close() }
        runCatching { keyFile.delete() }
    } }

    @Test
    fun fullFolderTreeLeavesLoadingUnderMeasuredMobileRtt(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val sessionNames = (1..SESSION_COUNT).map { "issue1876-$suffix-s$it" }
        val roots = listOf(
            "~/issue1876-$suffix-a",
            "~/issue1876-$suffix-b",
            "/home/testuser/issue1876-$suffix-c",
        )

        // Seed through the direct route so setup time is not part of the app
        // measurement; the production bind below uses only the shaped route.
        withTimeout(30_000L) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 10_000,
            ).getOrThrow().use { session ->
                session.exec(
                    "mkdir -p /home/testuser/issue1876-$suffix-a/p1 " +
                        "/home/testuser/issue1876-$suffix-b/p2 " +
                        "/home/testuser/issue1876-$suffix-c/p3",
                )
                sessionNames.forEach { name ->
                    session.exec("tmux new-session -d -s '$name' -c /home/testuser")
                    createdSessions += name
                }
            }
        }

        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue1876-key", privateKeyPath = keyFile.absolutePath),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "issue1876-mobile",
                hostname = DEFAULT_HOST,
                port = MOBILE_PORT,
                username = DEFAULT_USER,
                keyId = keyId,
            ),
        )
        roots.forEachIndexed { index, path ->
            db.projectRootDao().insert(
                ProjectRootEntity(
                    hostId = hostId,
                    label = "mobile-root-$index",
                    path = path,
                ),
            )
        }
        val host = db.hostDao().getById(hostId)!!

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vm = FolderListViewModel(
            gateway = SshFolderListGateway(),
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            forwardingController = ForwardingController(context),
            treeRemoteSource = TreeRemoteSource(),
            attachLifecycle = false,
        ).also { viewModelStore.put("issue1876-folder-list", it) }
        vm.setProcessStartedForTest(true)
        assertEquals(
            "the journey must exercise the fixed product bound, not a widened test override",
            12_000L,
            vm.reconcileTimeoutMs,
        )

        val startedAt = System.nanoTime()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.bind(
                hostId = host.id,
                hostName = host.name,
                hostname = host.hostname,
                port = host.port,
                username = host.username,
                keyPath = keyFile.absolutePath,
                passphrase = null,
            )
        }

        val ready: FolderListUiState.Ready = withTimeout(JOURNEY_BOUND_MS) {
            var settled: FolderListUiState.Ready? = null
            while (settled == null) {
                when (val state = vm.state.value) {
                    is FolderListUiState.ConnectError ->
                        error("mobile reconcile surfaced ConnectError: ${state.message}")
                    is FolderListUiState.Failed ->
                        error("mobile reconcile failed: ${state.message}")
                    is FolderListUiState.Ready -> {
                        if (state.flatSessions.map { it.sessionName }.containsAll(sessionNames)) {
                            settled = state
                        }
                    }
                    else -> Unit
                }
                delay(100L)
            }
            requireNotNull(settled)
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        println(
            "ISSUE1876_CONNECTED_MOBILE_RTT elapsedMs=$elapsedMs " +
                "sessions=${ready.flatSessions.size} roots=${ready.treeRoots.map { it.path }} " +
                "productBound=${vm.reconcileTimeoutMs}",
        )
        assertTrue(
            "all 18 real tmux sessions must reach the projected folder tree",
            ready.flatSessions.map { it.sessionName }.containsAll(sessionNames),
        )
        assertTrue(
            "all configured watched roots must remain represented",
            roots.all { configured ->
                val resolved = configured.replace("~", "/home/testuser")
                ready.treeRoots.any { it.path == configured || it.path == resolved }
            },
        )
    } }

    private companion object {
        const val MOBILE_PORT: Int = 2229
        const val SESSION_COUNT: Int = 18
        const val JOURNEY_BOUND_MS: Long = 25_000L
    }
}
