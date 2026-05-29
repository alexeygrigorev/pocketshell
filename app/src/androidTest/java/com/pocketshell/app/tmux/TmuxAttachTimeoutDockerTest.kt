package com.pocketshell.app.tmux

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TmuxAttachTimeoutDockerTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timings = mutableListOf<String>()
    private val stamps = mutableListOf<String>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    @Test
    fun stalledControlModeAttachFailsAndReconnectRecovers() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val targetContext = instrumentation.targetContext
            val key = instrumentation.context.assets
                .open("test_key")
                .bufferedReader()
                .use { it.readText() }
            val keyPath = File(targetContext.filesDir, "issue273_test_key.pem").apply {
                writeText(key)
                setReadable(false, false)
                setWritable(false, false)
                setReadable(true, true)
                setWritable(true, true)
            }.absolutePath
            waitForSshFixtureReady(SshKey.Pem(key))

            val sessionName = "issue273-timeout"
            val registry = ActiveTmuxClients()
            val vm = TmuxSessionViewModel(
                tmuxClientFactory = TmuxClientFactory(factoryScope),
                activeTmuxClients = registry,
            )

            try {
                cleanupRemote(key, sessionName)
                seedTmuxSession(key, sessionName)
                setTmuxHangFlag(key, enabled = true)

                val attachStartedAt = SystemClock.elapsedRealtime()
                stamp("connect_with_tmux_cc_hang")
                vm.connect(
                    hostId = 273L,
                    hostName = "Issue273 Docker",
                    host = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    user = DEFAULT_USER,
                    keyPath = keyPath,
                    passphrase = null,
                    sessionName = sessionName,
                )

                val failed = waitForStatus<TmuxSessionViewModel.ConnectionStatus.Failed>(vm)
                timing("attach_to_failed_status_ms", SystemClock.elapsedRealtime() - attachStartedAt)
                assertTrue(
                    "failure should name tmux panes and retry, got `${failed.message}`",
                    "tmux panes" in failed.message && "Tap Reconnect" in failed.message,
                )
                assertTrue("Reconnect must be available after attach timeout", vm.canReconnect.value)
                assertTrue("stalled attach must not leave panes visible", vm.panes.value.isEmpty())

                setTmuxHangFlag(key, enabled = false)
                val reconnectStartedAt = SystemClock.elapsedRealtime()
                stamp("reconnect_after_clearing_hang_flag")
                assertTrue("reconnect() should start from the preserved target", vm.reconnect())

                waitForStatus<TmuxSessionViewModel.ConnectionStatus.Connected>(vm)
                val panes = waitForPanes(vm)
                timing("reconnect_to_panes_ready_ms", SystemClock.elapsedRealtime() - reconnectStartedAt)
                assertEquals(listOf("%0"), panes.map { it.paneId })
                assertTrue("registry should point at the recovered client", registry.clients.value[273L] != null)
                writeSummary(sessionName, failed.message, panes.map { it.paneId })
            } finally {
                setTmuxHangFlag(key, enabled = false)
                cleanupRemote(key, sessionName)
                vm.clearForTest()
                writeTimings()
            }
        }
    }

    private suspend inline fun <reified T : TmuxSessionViewModel.ConnectionStatus> waitForStatus(
        vm: TmuxSessionViewModel,
    ): T = withTimeout(30_000) {
        while (true) {
            val status = vm.connectionStatus.value
            if (status is T) return@withTimeout status
            delay(50)
        }
        error("unreachable")
    }

    private suspend fun waitForPanes(vm: TmuxSessionViewModel): List<TmuxPaneState> =
        withTimeout(15_000) {
            while (true) {
                val panes = vm.panes.value
                if (panes.isNotEmpty()) return@withTimeout panes
                delay(50)
            }
            error("unreachable")
        }

    private suspend fun seedTmuxSession(key: String, sessionName: String) {
        val command = buildString {
            appendLine("set -eu")
            appendLine("rm -f ~/.pocketshell-hang-tmux-control")
            appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -x 80 -y 24 -s ${shellQuote(sessionName)} " +
                    shellQuote("while true; do printf 'issue273-recovered-pane-ready\\n'; sleep 1; done"),
            )
            appendLine("sleep 1")
            appendLine("tmux list-panes -t ${shellQuote(sessionName)}")
        }
        execRemote(key, command)
    }

    private suspend fun setTmuxHangFlag(key: String, enabled: Boolean) {
        execRemote(
            key,
            if (enabled) {
                "touch ~/.pocketshell-hang-tmux-control"
            } else {
                "rm -f ~/.pocketshell-hang-tmux-control"
            },
        )
    }

    private suspend fun cleanupRemote(key: String, sessionName: String) {
        runCatching {
            execRemote(
                key,
                "rm -f ~/.pocketshell-hang-tmux-control; " +
                    "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true",
            )
        }
    }

    private suspend fun execRemote(key: String, command: String) {
        val result = withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use { it.exec(command) }
            }
        }
        val exec = result.getOrNull()
        assertTrue(
            "remote command failed: ${result.exceptionOrNull()} exit=${exec?.exitCode} stderr=${exec?.stderr}",
            exec?.exitCode == 0,
        )
    }

    private fun writeSummary(
        sessionName: String,
        failureMessage: String,
        recoveredPaneIds: List<String>,
    ): File = writeText(
        "issue273-summary.txt",
        buildString {
            appendLine("scenario=tmux-attach-timeout-viewmodel")
            appendLine("session_name=$sessionName")
            appendLine("failure_message=$failureMessage")
            appendLine("recovered_panes=${recoveredPaneIds.joinToString()}")
            appendLine("stamps:")
            stamps.forEach { appendLine(it) }
        },
    )

    private fun writeTimings(): File =
        writeText("timings.txt", timings.joinToString(separator = "\n", postfix = "\n"))

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE273_TEXT ${file.absolutePath}")
        return file
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private fun timing(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE273_TIMING $line")
    }

    private fun stamp(name: String) {
        val line = "[issue273-timing] $name at ${SystemClock.elapsedRealtime()}"
        stamps += line
        println(line)
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DEVICE_DIR_NAME: String = "tmux-attach-timeout"
    }
}
