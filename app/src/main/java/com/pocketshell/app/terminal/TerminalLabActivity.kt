package com.pocketshell.app.terminal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.pocketshell.app.proof.SshShellHandle
import com.pocketshell.app.proof.openShell
import com.pocketshell.app.proof.readKeyFromRawResource
import com.pocketshell.app.session.SessionDefaults
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.terminal.ui.DefaultTerminalBackground
import com.pocketshell.core.terminal.ui.TerminalSurface
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionChannel

class TerminalLabActivity : FragmentActivity() {

    lateinit var controller: TerminalLabController
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(DarkSystemBarColor))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(DarkSystemBarColor),
            navigationBarStyle = SystemBarStyle.dark(DarkSystemBarColor),
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        controller = TerminalLabController(
            target = TerminalLabTarget.fromIntent(this, intent),
        )
        controller.connect(lifecycleScope)

        setContent {
            PocketShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TerminalLabScreen(controller = controller)
                }
            }
        }
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_HOST: String = "com.pocketshell.app.terminal.HOST"
        const val EXTRA_PORT: String = "com.pocketshell.app.terminal.PORT"
        const val EXTRA_USER: String = "com.pocketshell.app.terminal.USER"
        const val EXTRA_KEY_PEM: String = "com.pocketshell.app.terminal.KEY_PEM"

        fun intent(
            context: Context,
            host: String,
            port: Int,
            user: String,
            privateKeyPem: String,
        ): Intent = Intent(context, TerminalLabActivity::class.java)
            .putExtra(EXTRA_HOST, host)
            .putExtra(EXTRA_PORT, port)
            .putExtra(EXTRA_USER, user)
            .putExtra(EXTRA_KEY_PEM, privateKeyPem)
    }
}

data class TerminalLabUiState(
    val status: String = "idle",
    val transcript: String = "",
    val connectToPromptMs: Long? = null,
    val lastSendToOutputMs: Long? = null,
)

data class TerminalLabTarget(
    val host: String,
    val port: Int,
    val user: String,
    val key: SshKey.Pem,
) {
    companion object {
        fun fromIntent(context: Context, intent: Intent): TerminalLabTarget {
            val keyPem = intent.getStringExtra(TerminalLabActivity.EXTRA_KEY_PEM)
            return TerminalLabTarget(
                host = intent.getStringExtra(TerminalLabActivity.EXTRA_HOST) ?: SessionDefaults.HOST,
                port = intent.getIntExtra(TerminalLabActivity.EXTRA_PORT, SessionDefaults.PORT),
                user = intent.getStringExtra(TerminalLabActivity.EXTRA_USER) ?: SessionDefaults.USER,
                key = if (keyPem != null) SshKey.Pem(keyPem) else SshKey.Pem(readKeyFromRawResource(context)),
            )
        }
    }
}

class TerminalLabController(
    private val target: TerminalLabTarget,
) : AutoCloseable {
    val terminalState: TerminalSurfaceState = TerminalSurfaceState()

    private val _uiState = MutableStateFlow(
        TerminalLabUiState(status = "connecting to ${target.user}@${target.host}:${target.port}"),
    )
    val uiState: StateFlow<TerminalLabUiState> = _uiState.asStateFlow()

    private val transcript = StringBuilder()
    private var connectJob: Job? = null
    private var producerJob: Job? = null
    private var shellRef: SshShellHandle? = null
    private var connectStartedAtMs: Long = 0L
    private var pendingSendStartedAtMs: Long? = null
    private var remoteColumns: Int = 0
    private var remoteRows: Int = 0

    fun connect(scope: CoroutineScope) {
        if (connectJob?.isActive == true || terminalState.isAttached) return
        connectStartedAtMs = SystemClock.elapsedRealtime()
        connectJob = scope.launch {
            try {
                val handle = openShell(
                    host = target.host,
                    port = target.port,
                    user = target.user,
                    key = target.key,
                )
                shellRef = handle
                producerJob = terminalState.attachExternalProducer(
                    scope = scope,
                    stdout = shellStdoutFlow(handle.shell).onEach(::recordOutput),
                    remoteStdin = handle.shell.outputStream,
                )
                _uiState.value = _uiState.value.copy(
                    status = "connected to ${target.user}@${target.host}:${target.port}",
                )
                withContext(Dispatchers.IO) {
                    handle.shell.outputStream.write("\r".toByteArray())
                    handle.shell.outputStream.flush()
                }
            } catch (t: Throwable) {
                _uiState.value = _uiState.value.copy(
                    status = "error: ${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }
    }

    fun sendText(text: String, withEnter: Boolean) {
        if (text.isEmpty() && !withEnter) return
        pendingSendStartedAtMs = SystemClock.elapsedRealtime()
        val payload = if (withEnter) text + "\r" else text
        terminalState.writeInput(payload.toByteArray(Charsets.UTF_8))
    }

    fun transcriptSnapshot(): String = synchronized(transcript) { transcript.toString() }

    fun resizeRemotePty(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        if (columns == remoteColumns && rows == remoteRows) return
        remoteColumns = columns
        remoteRows = rows
        val channel = shellRef?.sessionChannel as? SessionChannel ?: return
        Thread({
            runCatching { channel.changeWindowDimensions(columns, rows, 0, 0) }
        }, "PocketShellTerminalResize").apply {
            isDaemon = true
            start()
        }
    }

    private fun recordOutput(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val text = bytes.toString(Charsets.UTF_8)
        val snapshot = synchronized(transcript) {
            transcript.append(text)
            if (transcript.length > MaxTranscriptChars) {
                transcript.delete(0, transcript.length - MaxTranscriptChars)
            }
            transcript.toString()
        }

        val pendingSendMs = pendingSendStartedAtMs?.let { now - it }
        if (pendingSendMs != null) {
            pendingSendStartedAtMs = null
        }

        val current = _uiState.value
        val connectToPromptMs = current.connectToPromptMs
            ?: if (looksLikePrompt(snapshot) || snapshot.isNotBlank()) now - connectStartedAtMs else null
        _uiState.value = current.copy(
            transcript = snapshot,
            connectToPromptMs = connectToPromptMs,
            lastSendToOutputMs = pendingSendMs ?: current.lastSendToOutputMs,
        )
    }

    override fun close() {
        val handle = shellRef
        shellRef = null
        connectJob?.cancel()
        producerJob?.cancel()
        terminalState.detachExternalProducer()
        ignoreClose { handle?.shell?.close() }
        ignoreClose { handle?.sessionChannel?.close() }
        ignoreClose { handle?.client?.disconnect() }
    }

    private fun looksLikePrompt(text: String): Boolean {
        val normalized = text.replace("\r", "")
        return normalized
            .lineSequence()
            .toList()
            .takeLast(4)
            .map { it.trimEnd() }
            .any { it == "$" || it.endsWith(" $") || it.endsWith("~ $") || it.endsWith("#") }
    }

    private companion object {
        const val MaxTranscriptChars: Int = 24_000
    }
}

private inline fun ignoreClose(block: () -> Unit) {
    try {
        block()
    } catch (_: Throwable) {
    }
}

private fun shellStdoutFlow(shell: Session.Shell): Flow<ByteArray> = flow {
    val input = shell.inputStream
    val buffer = ByteArray(4096)
    while (true) {
        val read = try {
            input.read(buffer)
        } catch (_: java.net.SocketTimeoutException) {
            continue
        } catch (_: java.io.InterruptedIOException) {
            break
        } catch (_: Throwable) {
            break
        }
        if (read < 0) break
        if (read > 0) emit(buffer.copyOf(read))
    }
}.flowOn(Dispatchers.IO)

@Composable
fun TerminalLabScreen(
    controller: TerminalLabController,
    modifier: Modifier = Modifier,
) {
    controller.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag(TERMINAL_LAB_SCREEN_TAG),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(DefaultTerminalBackground),
        ) {
            TerminalSurface(
                state = controller.terminalState,
                modifier = Modifier.fillMaxSize(),
                onTerminalSizeChanged = controller::resizeRemotePty,
            )
        }
    }
}

const val TERMINAL_LAB_SCREEN_TAG: String = "terminal-lab:screen"
const val TERMINAL_LAB_INPUT_TAG: String = "terminal-lab:input"
const val TERMINAL_LAB_SEND_TAG: String = "terminal-lab:send"
const val TERMINAL_LAB_SEND_ENTER_TAG: String = "terminal-lab:send-enter"

private val DarkSystemBarColor: Int = android.graphics.Color.rgb(13, 17, 23)
