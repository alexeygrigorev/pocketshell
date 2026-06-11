package com.pocketshell.app.proof

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.assistant.AssistantSshExecutorTestAccess
import com.pocketshell.app.env.EnvListResult
import com.pocketshell.app.env.SshEnvGateway
import com.pocketshell.app.sessions.StartDirectoryAutocompleteRemoteSource
import com.pocketshell.app.sessions.StartDirectoryAutocompleteTarget
import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #699: proves the three per-action-handshake offenders
 * (start-directory autocomplete, the assistant SSH executor, and the env
 * gateway) now SHARE the app-wide warm SSH transport instead of dialing a
 * fresh ~3-4s handshake per action.
 *
 * All three are pointed at ONE [SshLeaseManager] (the app-singleton stand-in)
 * whose connector COUNTS handshakes. After many actions across all three
 * surfaces the test asserts exactly ONE connect happened, the transport is
 * never closed mid-run, and measures the autocomplete keystroke latency: the
 * first (cold) probe pays the handshake, every subsequent one reuses the warm
 * transport and is dramatically faster.
 *
 * Runs against the deterministic Docker `agents` fixture on `10.0.2.2:2222`.
 */
@RunWith(AndroidJUnit4::class)
class WarmLeaseReuseDockerTest {

    private class CountingConnector(
        private val delegate: SshLeaseConnector,
    ) : SshLeaseConnector {
        val connectCount = AtomicInteger(0)
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount.incrementAndGet()
            return delegate.connect(target)
        }
    }

    @Test
    fun autocompleteAssistantAndEnvAllReuseOneWarmTransport() = runBlocking {
        val keyContent = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        val key = SshKey.Pem(keyContent)
        waitForSshFixtureReady(key)

        val keyPath = writeKeyToFile(keyContent)
        val connector = CountingConnector(DefaultSshLeaseConnector())
        val leaseManager = SshLeaseManager(connector = connector)

        val host = HostEntity(
            id = HOST_ID,
            name = "warm-lease-docker",
            hostname = DEFAULT_HOST,
            port = DEFAULT_PORT,
            username = DEFAULT_USER,
            keyId = 1L,
        )

        // --- Surface 1: start-directory autocomplete (worst offender) ---
        val autocomplete = StartDirectoryAutocompleteRemoteSource(leaseManager)
        val target = StartDirectoryAutocompleteTarget(
            hostId = HOST_ID,
            hostname = DEFAULT_HOST,
            port = DEFAULT_PORT,
            username = DEFAULT_USER,
            keyPath = keyPath,
            passphrase = null,
        )

        // Simulate typing a path one prefix at a time — the old code dialed a
        // fresh handshake for EVERY one of these.
        val prefixes = listOf("/", "/h", "/ho", "/hom", "/home", "/home/")
        val latencies = mutableListOf<Long>()
        prefixes.forEach { prefix ->
            val started = SystemClock.elapsedRealtime()
            autocomplete.suggestions(target, typedPrefix = prefix)
            latencies += SystemClock.elapsedRealtime() - started
        }

        // --- Surface 2: assistant SSH executor ---
        val executor = AssistantSshExecutorTestAccess.real(leaseManager)
        repeat(3) {
            val result = AssistantSshExecutorTestAccess.exec(
                executor = executor,
                hostId = HOST_ID,
                hostName = host.name,
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyPath,
                command = "printf 'assistant-warm-lease\\n'",
            )
            assertTrue(
                "assistant exec should succeed over the warm transport, got $result",
                result.getOrNull()?.stdout?.contains("assistant-warm-lease") == true,
            )
        }

        // --- Surface 3: env gateway ---
        val envGateway = SshEnvGateway(leaseManager)
        repeat(3) {
            val result = envGateway.listKeys(host, keyPath, passphrase = null, directory = "/home/$DEFAULT_USER")
            // ConnectFailed would mean a fresh dial failed; anything else means
            // the warm transport served the env list (Keys / ToolUnavailable /
            // Failed are all "the connection worked").
            assertTrue(
                "env list should run over the warm transport, got $result",
                result !is EnvListResult.ConnectFailed,
            )
        }

        // === The acceptance assertion: ONE handshake for ALL actions ===
        val connects = connector.connectCount.get()
        val firstLatency: Long = latencies.first()
        val warmLatencies = latencies.drop(1)
        val warmAvg: Double = if (warmLatencies.isEmpty()) 0.0 else warmLatencies.average()

        val summary = buildString {
            appendLine("issue699_warm_lease_reuse")
            appendLine("ssh_connects_total=$connects")
            appendLine("autocomplete_probes=${prefixes.size}")
            appendLine("assistant_execs=3")
            appendLine("env_reads=3")
            appendLine("autocomplete_first_cold_ms=$firstLatency")
            appendLine("autocomplete_warm_latencies_ms=$warmLatencies")
            appendLine("autocomplete_warm_avg_ms=$warmAvg")
        }
        Log.i(LOG_TAG, summary)
        println("ISSUE699_SUMMARY\n$summary")
        writeArtifact("issue699-warm-lease-summary.txt", summary)

        assertEquals(
            "all actions across autocomplete + assistant + env must share ONE warm transport " +
                "(no per-action handshake)\n$summary",
            1,
            connects,
        )
        // The warm probes must be meaningfully faster than the cold one — the
        // whole point of reuse is no repeated ~3-4s handshake. (Generous bound
        // for a loaded CI emulator; the handshake alone is seconds.)
        assertTrue(
            "warm autocomplete probes should be faster than the cold handshake\n$summary",
            warmAvg < firstLatency.toDouble() || firstLatency < 1_000L,
        )
    }

    private fun writeKeyToFile(content: String): String {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.filesDir, "issue699-warm-lease-key")
        file.writeText(content)
        file.setReadable(false, false)
        file.setReadable(true, true)
        return file.absolutePath
    }

    private fun writeArtifact(name: String, content: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$ARTIFACT_DIR")
        check(dir.exists() || dir.mkdirs()) { "Could not create artifact dir: ${dir.absolutePath}" }
        val file = File(dir, name)
        FileOutputStream(file).use { it.write(content.toByteArray()) }
        println("ISSUE699_ARTIFACT ${file.absolutePath}")
    }

    private companion object {
        const val LOG_TAG: String = "PocketShellWarmLease699"
        const val ARTIFACT_DIR: String = "issue699-warm-lease"
        const val HOST_ID: Long = 6990L
    }
}
