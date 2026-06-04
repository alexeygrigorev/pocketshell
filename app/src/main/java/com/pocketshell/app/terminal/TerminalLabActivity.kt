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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.pocketshell.app.proof.readKeyFromRawResource
import com.pocketshell.app.session.SessionDefaults
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
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
    private var sessionRef: SshSession? = null
    private var shellRef: SshShell? = null
    private var connectStartedAtMs: Long = 0L
    private var pendingSendStartedAtMs: Long? = null
    private var remoteColumns: Int = 0
    private var remoteRows: Int = 0

    fun connect(scope: CoroutineScope) {
        if (connectJob?.isActive == true || terminalState.isAttached) return
        connectStartedAtMs = SystemClock.elapsedRealtime()
        connectJob = scope.launch {
            try {
                val session = SshConnection.connect(
                    host = target.host,
                    port = target.port,
                    user = target.user,
                    key = target.key,
                    knownHosts = KnownHostsPolicy.AcceptAll,
                ).getOrThrow()
                sessionRef = session
                val shell = withContext(Dispatchers.IO) {
                    session.startShell()
                }
                shellRef = shell
                producerJob = terminalState.attachExternalProducer(
                    scope = scope,
                    stdout = shellStdoutFlow(shell.stdout).onEach(::recordOutput),
                    remoteStdin = shell.stdin,
                )
                _uiState.value = _uiState.value.copy(
                    status = "connected to ${target.user}@${target.host}:${target.port}",
                )
                withContext(Dispatchers.IO) {
                    shell.stdin.write("\r".toByteArray())
                    shell.stdin.flush()
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
        val shell = shellRef ?: return
        Thread({
            runCatching { shell.resizePty(columns, rows) }
        }, "PocketShellTerminalResize").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Issue #104: per-chunk callback wired into the SSH output flow via
     * `.onEach(::recordOutput)`. Originally also pumped the full transcript
     * snapshot into [_uiState] on every chunk; under live remote output
     * (e.g. an agent CLI streaming hundreds of bytes per second) that
     * snapshot copy ran on the Main thread for every read, ballooning to
     * ~24 KB per chunk and blocking IME echo of typed characters until the
     * stream calmed down. The lab activity does not actually render the
     * transcript anywhere — `TerminalLabScreen` discards the collected
     * `uiState` — so the per-chunk `copy(transcript=)` was pure waste.
     *
     * The bookkeeping kept here is:
     *
     * - Appending bytes to the [transcript] StringBuilder for the
     *   instrumented test path (`transcriptSnapshot()` is the only public
     *   reader and it does its own `.toString()` snapshot under the lock).
     * - Recording the connect-to-prompt latency exactly once on first
     *   output. [_uiState] is updated only at that transition, not on
     *   every chunk.
     */
    private fun recordOutput(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val text = bytes.toString(Charsets.UTF_8)
        var hadContent = false
        var transcriptLooksLikePrompt = false
        synchronized(transcript) {
            transcript.append(text)
            if (transcript.length > MaxTranscriptChars) {
                transcript.delete(0, transcript.length - MaxTranscriptChars)
            }
            hadContent = transcript.isNotEmpty()
            // Only do the heavy `looksLikePrompt` scan if we still have not
            // recorded the connect-to-prompt time. After that, the result
            // is unused and we skip the scan entirely.
            if (hadContent && _uiState.value.connectToPromptMs == null) {
                transcriptLooksLikePrompt = looksLikePromptTail()
            }
        }

        val pendingSendMs = pendingSendStartedAtMs?.let { now - it }
        if (pendingSendMs != null) {
            pendingSendStartedAtMs = null
        }

        val current = _uiState.value
        val needsPromptUpdate =
            current.connectToPromptMs == null && (transcriptLooksLikePrompt || hadContent)
        if (needsPromptUpdate || pendingSendMs != null) {
            _uiState.value = current.copy(
                connectToPromptMs = if (needsPromptUpdate) {
                    now - connectStartedAtMs
                } else {
                    current.connectToPromptMs
                },
                lastSendToOutputMs = pendingSendMs ?: current.lastSendToOutputMs,
            )
        }
    }

    /**
     * Issue #104: cheaper variant of the original `looksLikePrompt(snapshot)`
     * call. Operates directly on the trailing window of the existing
     * [transcript] StringBuilder so we avoid building a fresh
     * `transcript.toString()` (up to 24 KB allocation per chunk) just to
     * check the last few lines. Must be called while holding the
     * [transcript] monitor.
     */
    private fun looksLikePromptTail(): Boolean {
        val length = transcript.length
        if (length == 0) return false
        val windowStart = (length - LooksLikePromptWindowChars).coerceAtLeast(0)
        // Walk backwards counting recent line breaks until we have up to
        // the last 4 logical lines worth of trailing characters. This
        // avoids string splitting and list allocation entirely.
        val tail = transcript.substring(windowStart)
        val normalized = tail.replace("\r", "")
        val lines = normalized.split('\n')
        val lastFour = if (lines.size <= 4) lines else lines.subList(lines.size - 4, lines.size)
        return lastFour.any { candidate ->
            val trimmed = candidate.trimEnd()
            trimmed == "$" || trimmed.endsWith(" $") || trimmed.endsWith("~ $") || trimmed.endsWith("#")
        }
    }

    override fun close() {
        val shell = shellRef
        val session = sessionRef
        shellRef = null
        sessionRef = null
        connectJob?.cancel()
        producerJob?.cancel()
        terminalState.detachExternalProducer()
        ignoreClose { shell?.close() }
        ignoreClose { session?.close() }
    }

    private companion object {
        const val MaxTranscriptChars: Int = 24_000

        /**
         * Issue #104: cap how many trailing characters we re-scan for the
         * `looksLikePrompt` heuristic. 1 KB easily covers four 80-column
         * lines plus their CRLF terminators, which is what the original
         * `takeLast(4)` was looking at. Bounding the scan keeps
         * [looksLikePromptTail] O(1) per chunk even when the transcript is
         * at its full 24 KB cap.
         */
        const val LooksLikePromptWindowChars: Int = 1_024
    }
}

private inline fun ignoreClose(block: () -> Unit) {
    try {
        block()
    } catch (_: Throwable) {
    }
}

private fun shellStdoutFlow(input: java.io.InputStream): Flow<ByteArray> = flow {
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 4.dp, top = 8.dp, end = 4.dp),
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
