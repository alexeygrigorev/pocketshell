package com.pocketshell.app.proof

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.FolderListViewModel
import com.pocketshell.app.projects.folderDetailRowTestTag
import com.pocketshell.app.projects.folderHeaderClickTestTag
import com.pocketshell.app.projects.folderRowTestTag
import com.pocketshell.app.tmux.TMUX_CONNECT_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Shared harness for opt-in network-fault proof tests.
 *
 * The tests route PocketShell through the Docker `network-fault-proxy`
 * service on host port 2228. Control traffic uses Toxiproxy's HTTP API
 * on host port 8474. Toxiproxy documents `timeout=0` as a non-closing
 * data drop, which gives us a half-open/no-FIN failure instead of the
 * existing EOF/process-kill fixtures.
 */
abstract class NetworkFaultProofBase {

    @get:Rule
    val compose: ComposeTestRule = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found"). Inherited
    // by every NetworkFaultProofBase subclass.
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    protected var launchedActivity: ActivityScenario<MainActivity>? = null
    protected val timings: MutableList<String> = mutableListOf()
    private var networkFaultProofEnabled: Boolean = false

    @After
    fun closeNetworkFaultActivity() {
        launchedActivity?.close()
        launchedActivity = null
        if (networkFaultProofEnabled) {
            runCatching { toxiproxy().reset() }
        }
    }

    protected fun assumeNetworkFaultProofsEnabled() {
        Assume.assumeFalse(
            "Network-fault proofs require opt-in Docker proxy fixtures; tests.yml does not start them.",
            TerminalTestTimeouts.isRunningOnCi(),
        )
        val enabled = InstrumentationRegistry.getArguments()
            .getString(NETWORK_FAULT_ARG)
            ?.toBooleanStrictOrNull() == true
        Assume.assumeTrue(
            "Enable with -Pandroid.testInstrumentationRunnerArguments.$NETWORK_FAULT_ARG=true " +
                "after starting `docker compose -f tests/docker/docker-compose.yml up -d --build " +
                "agents network-fault-proxy packet-loss-proxy`.",
            enabled,
        )
        networkFaultProofEnabled = true
    }

    protected fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    protected fun toxiproxy(): ToxiproxyControl =
        ToxiproxyControl(baseUrl = "http://$DEFAULT_HOST:$TOXIPROXY_API_PORT")

    protected suspend fun prepareProxyAndRemoteSession(
        key: String,
        sessionName: String,
        readyText: String,
    ) {
        toxiproxy().reset()
        waitForSshFixtureReady(SshKey.Pem(key), port = DEFAULT_PORT)
        seedTmuxSession(key, sessionName, readyText)
        waitForSshFixtureReady(SshKey.Pem(key), port = NETWORK_FAULT_SSH_PORT)
    }

    protected suspend fun preparePacketLossProxyAndRemoteSession(
        key: String,
        sessionName: String,
        readyText: String,
    ) {
        waitForSshFixtureReady(SshKey.Pem(key), port = DEFAULT_PORT)
        seedTmuxSession(key, sessionName, readyText)
        waitForSshFixtureReady(SshKey.Pem(key), port = PACKET_LOSS_SSH_PORT)
    }

    protected suspend fun seedNetworkFaultHost(
        key: String,
        hostName: String,
        port: Int = NETWORK_FAULT_SSH_PORT,
    ): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "network-fault-key-${System.currentTimeMillis()}",
                content = key,
            )
            // Keep this aligned with HostListViewModel.canUseBootstrapCache:
            // the connected proof wants host-card tap -> folder/session list,
            // not setup/probe, so all cached tool/daemon checks must be fresh.
            val now = System.currentTimeMillis()
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
                    hostname = DEFAULT_HOST,
                    port = port,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = now,
                    pocketshellInstalled = true,
                    pocketshellLastDetectedAt = now,
                    pocketshellVersionCompatible = true,
                    pocketshellDaemonRunning = true,
                    pocketshellDaemonEnabled = true,
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    protected fun attachToSession(hostRowTag: String, hostName: String, sessionName: String) {
        val pickerTimeoutMs = TerminalTestTimeouts.terminalVisibilityTimeoutMs()
        waitUntilWithDiagnostics(
            label = "host row $hostName",
            timeoutMillis = pickerTimeoutMs,
            textProbes = listOf(hostName),
            tagProbes = listOf(hostRowTag),
        ) {
            hasTag(hostRowTag)
        }
        compose.onNodeWithText(hostName, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        var folderPath = FolderListViewModel.UNTRACKED_PATH
        waitUntilWithDiagnostics(
            label = "folder row for $sessionName",
            timeoutMillis = pickerTimeoutMs,
            textProbes = listOf(hostName, sessionName),
            tagProbes = NETWORK_FAULT_FOLDER_CANDIDATES.map(::folderRowTestTag),
        ) {
            val match = NETWORK_FAULT_FOLDER_CANDIDATES.firstOrNull { candidate ->
                hasTag(folderRowTestTag(candidate))
            }
            if (match != null) {
                folderPath = match
                true
            } else {
                false
            }
        }
        expandFolderUntilSessionRowVisible(folderPath, sessionName, pickerTimeoutMs)
        val sessionRowTag = folderDetailRowTestTag(folderPath, sessionName)
        compose.onNodeWithTag(sessionRowTag, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForVisibleTerminalText("tmux pane ready") { it.isNotBlank() }
    }

    protected fun sendCommandThroughTerminalInput(command: String, label: String) {
        command.chunked(4).forEach { chunk ->
            val committed = terminalInputConnection().commitText(chunk, 1)
            assertTrue("expected terminal input commit for $label chunk `$chunk`", committed)
            SystemClock.sleep(35)
        }
        val enterCommitted = terminalInputConnection().commitText("\n", 1)
        assertTrue("expected terminal input submit for $label", enterCommitted)
    }

    protected fun waitForVisibleTerminalText(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ) {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                predicate(last)
            }
            true
        }.getOrDefault(false)
        if (!satisfied) {
            artifactFile("failure-$label-visible-terminal.txt").writeText(last.printableForFailure())
        }
        assertTrue(
            "expected visible terminal text for $label, got:\n${last.printableForFailure()}",
            predicate(last),
        )
    }

    protected fun waitForDisconnectBand(label: String, timeoutMillis: Long = 35_000) {
        val start = SystemClock.elapsedRealtime()
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        recordTiming("${label}_disconnect_visible_ms", SystemClock.elapsedRealtime() - start)
        compose.onAllNodesWithText("Reconnect", useUnmergedTree = true).fetchSemanticsNodes().let {
            assertTrue("expected Reconnect button for $label; found ${it.size}", it.isNotEmpty())
        }
    }

    protected fun tapReconnectAndWait(label: String) {
        val start = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(TMUX_SESSION_RECONNECT_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 45_000) {
            compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        waitForTerminalViewAttached()
        recordTiming("${label}_reconnect_ms", SystemClock.elapsedRealtime() - start)
    }

    protected fun disconnectBandCount(): Int =
        compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

    protected fun assertNoDisconnectBand(label: String) {
        val count = disconnectBandCount()
        assertTrue("expected no disconnect band for $label, found $count", count == 0)
    }

    protected fun waitForNoDisconnectBandDuring(label: String, durationMillis: Long) {
        val start = SystemClock.elapsedRealtime()
        var maxCount = 0
        while (SystemClock.elapsedRealtime() - start < durationMillis) {
            val count = disconnectBandCount()
            maxCount = maxOf(maxCount, count)
            assertTrue("expected no disconnect band during $label, found $count", count == 0)
            SystemClock.sleep(250)
        }
        recordTiming("${label}_stable_no_disconnect_ms", SystemClock.elapsedRealtime() - start)
        recordTiming("${label}_max_disconnect_bands", maxCount.toLong())
    }

    protected suspend fun listClientsCount(key: String, sessionName: String): Int =
        listClientsRaw(key, sessionName).lines().count { it.isNotBlank() }

    protected suspend fun waitForClientCountAtMost(
        key: String,
        sessionName: String,
        max: Int,
        label: String,
        timeoutMs: Long = 6_000L,
    ) {
        var last = -1
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            last = listClientsCount(key, sessionName)
            if (last <= max) return
            SystemClock.sleep(150)
        }
        assertTrue("expected at most $max tmux client(s) for $label, got $last", last <= max)
    }

    protected suspend fun openDirectTmuxConnection(
        key: String,
        sessionName: String,
        scope: CoroutineScope,
    ): DirectTmuxConnection {
        val session = withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = NETWORK_FAULT_SSH_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).getOrThrow()
        }
        val client = TmuxClientFactory(scope).create(
            session = session,
            sessionName = sessionName,
        )
        try {
            client.connect()
            return DirectTmuxConnection(session = session, client = client)
        } catch (t: Throwable) {
            runCatching { client.close() }
            runCatching { session.close() }
            throw t
        }
    }

    protected suspend fun sendShellMarkerViaTmux(
        client: TmuxClient,
        marker: String,
        label: String,
    ) {
        val response = client.sendCommand("send-keys ${tmuxSingleQuoted("printf '$marker\\n'")} Enter")
        assertTrue("expected send-keys to succeed for $label, got ${response.output}", !response.isError)
    }

    protected suspend fun waitForCapturedPaneText(
        client: TmuxClient,
        expected: String,
        label: String,
        timeoutMs: Long = 10_000L,
    ) {
        var last = ""
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            last = capturePane(client).output.joinToString("\n")
            if (expected in last) return
            SystemClock.sleep(150)
        }
        artifactFile("failure-$label-capture-pane.txt").writeText(last)
        assertTrue("expected captured pane text for $label to contain $expected, got:\n$last", expected in last)
    }

    /**
     * Half-open/no-FIN link starvation for short ride-through checks. Toxiproxy
     * keeps the socket established while dropping bytes for [downMillis], then
     * removes the toxic so the same SSH/tmux connection can make progress again.
     */
    protected fun starveLinkFor(label: String, downMillis: Long) {
        val proxy = toxiproxy()
        val cutStart = SystemClock.elapsedRealtime()
        proxy.addBlackhole()
        try {
            recordTiming("${label}_link_starved_ms", downMillis)
            waitForNoDisconnectBandDuring("${label}_while_starved", downMillis)
        } finally {
            proxy.clearToxics()
            recordTiming("${label}_link_starve_total_ms", SystemClock.elapsedRealtime() - cutStart)
        }
    }

    /**
     * Clean socket-drop outage for sustained reconnect checks. Toxiproxy
     * disables the proxy, which drops active connections and refuses new ones
     * until [ToxiproxyControl.enable] is called.
     */
    protected fun disableProxyFor(label: String, downMillis: Long) {
        val proxy = toxiproxy()
        val cutStart = SystemClock.elapsedRealtime()
        proxy.disable()
        try {
            recordTiming("${label}_proxy_disabled_ms", downMillis)
            SystemClock.sleep(downMillis)
        } finally {
            proxy.enable()
            recordTiming("${label}_proxy_disable_total_ms", SystemClock.elapsedRealtime() - cutStart)
        }
    }

    protected fun assertNoExtraConnectAttempts(
        before: Int,
        expectedDelta: Int,
        label: String,
    ) {
        val after = TMUX_CONNECT_ATTEMPTS.get()
        val delta = after - before
        assertTrue(
            "expected $expectedDelta tmux connect attempt(s) for $label, got $delta " +
                "(before=$before after=$after)",
            delta == expectedDelta,
        )
    }

    protected fun writeSummary(testName: String, lines: List<String>) {
        artifactFile("$testName-summary.txt").writeText(
            buildString {
                appendLine("test=$testName")
                appendLine("proxy_port=$NETWORK_FAULT_SSH_PORT")
                appendLine("packet_loss_proxy_port=$PACKET_LOSS_SSH_PORT")
                appendLine("toxiproxy_api_port=$TOXIPROXY_API_PORT")
                appendLine("timings:")
                timings.forEach { appendLine(it) }
                appendLine("details:")
                lines.forEach { appendLine(it) }
            },
        )
    }

    protected fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE342_TIMING $line")
    }

    protected fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create issue #342 artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private suspend fun seedTmuxSession(key: String, sessionName: String, readyText: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(sessionName)} " +
                    "${shellQuote("printf '${escapeSingleQuotedForPrintf(readyText)}\\n'; exec sh -i")}",
            )
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec(script) }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux seeding to succeed; exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private suspend fun listClientsRaw(key: String, sessionName: String): String {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec("tmux list-clients -t ${shellQuote(sessionName)} 2>/dev/null || true")
            }
        }
        return result.getOrNull()?.stdout.orEmpty()
    }

    private suspend fun capturePane(client: TmuxClient): CommandResponse =
        client.sendCommand("capture-pane -p")

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            launchedActivity?.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    private fun expandFolderUntilSessionRowVisible(
        folderPath: String,
        sessionName: String,
        timeoutMillis: Long,
    ) {
        val detailTag = folderDetailRowTestTag(folderPath, sessionName)
        val headerTag = folderHeaderClickTestTag(folderPath)
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var taps = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()
            if (hasTag(detailTag)) {
                recordTiming("attach_folder_expand_taps", taps.toLong())
                return
            }
            if (hasTag(headerTag)) {
                compose.onNodeWithTag(headerTag, useUnmergedTree = true).performClick()
                taps += 1
            }
            SystemClock.sleep(250)
        }
        waitUntilWithDiagnostics(
            label = "expanded folder $folderPath showing session row $sessionName after $taps tap(s)",
            timeoutMillis = 5_000,
            textProbes = listOf(sessionName, folderPath.substringAfterLast('/')),
            tagProbes = listOf(
                folderRowTestTag(folderPath),
                folderHeaderClickTestTag(folderPath),
                folderDetailRowTestTag(folderPath, sessionName),
            ),
        ) {
            hasTag(detailTag)
        }
    }

    private fun waitUntilWithDiagnostics(
        label: String,
        timeoutMillis: Long,
        textProbes: List<String> = emptyList(),
        tagProbes: List<String> = emptyList(),
        condition: () -> Boolean,
    ) {
        try {
            compose.waitUntil(timeoutMillis = timeoutMillis, condition = condition)
        } catch (error: Throwable) {
            throw AssertionError(
                buildString {
                    appendLine("Timed out after ${timeoutMillis}ms waiting for $label.")
                    appendLine(screenDiagnostics(textProbes = textProbes, tagProbes = tagProbes))
                },
                error,
            )
        }
    }

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun screenDiagnostics(textProbes: List<String>, tagProbes: List<String>): String = buildString {
        appendLine("Tag probe counts:")
        tagProbes.distinct().forEach { tag ->
            val count = runCatching {
                compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
            }.getOrDefault(-1)
            appendLine("  $tag=$count")
        }
        appendLine("Text probe counts:")
        textProbes.distinct().forEach { text ->
            val count = runCatching {
                compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size
            }.getOrDefault(-1)
            appendLine("  \"$text\"=$count")
        }
        appendLine("Compose semantics tree:")
        appendLine(
            runCatching {
                compose.waitForIdle()
                compose.onRoot(useUnmergedTree = true).printToString()
            }.getOrElse { diagnosticsError ->
                "  <failed to capture semantics tree: ${diagnosticsError.javaClass.simpleName}: " +
                    "${diagnosticsError.message.orEmpty()}>"
            },
        )
    }

    private fun terminalInputConnection(): InputConnection {
        var connection: InputConnection? = null
        launchedActivity?.onActivity { activity ->
            val terminalView = activity.window.decorView.findTerminalView()
            requireNotNull(terminalView) { "TerminalView was not found" }
            terminalView.requestFocus()
            connection = terminalView.onCreateInputConnection(EditorInfo())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return requireNotNull(connection) { "TerminalView did not create an InputConnection" }
    }

    private fun visibleTerminalText(): String {
        var text = ""
        launchedActivity?.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return text
    }

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findTerminalView()
            if (match != null) return match
        }
        return null
    }

    private fun String.printableForFailure(): String =
        buildString(length) {
            for (ch in this@printableForFailure) {
                when {
                    ch == '\u001B' -> append("<ESC>")
                    ch == '\r' -> append("<CR>")
                    ch == '\u0000' -> append("<NUL>")
                    ch < ' ' && ch != '\n' && ch != '\t' -> append("<0x${ch.code.toString(16)}>")
                    else -> append(ch)
                }
            }
        }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun escapeSingleQuotedForPrintf(value: String): String =
        value.replace("'", "'\"'\"'")

    private fun tmuxSingleQuoted(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    protected companion object {
        const val NETWORK_FAULT_ARG: String = "pocketshellNetworkFaultProofs"
        const val NETWORK_FAULT_SSH_PORT: Int = 2228
        const val PACKET_LOSS_SSH_PORT: Int = 2229
        const val TOXIPROXY_API_PORT: Int = 8474
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "issue342-network-faults"
        val NETWORK_FAULT_FOLDER_CANDIDATES: List<String> = listOf(
            FolderListViewModel.UNTRACKED_PATH,
            "/home/testuser",
            "~",
        )
    }
}

data class DirectTmuxConnection(
    val session: SshSession,
    val client: TmuxClient,
) {
    suspend fun detachAndClose(timeoutMs: Long = 2_000L) {
        runCatching { client.detachCleanly(timeoutMs) }
        runCatching { session.close() }
    }

    fun close() {
        runCatching { client.close() }
        runCatching { session.close() }
    }
}

class ToxiproxyControl(private val baseUrl: String) {

    fun reset() {
        runCatching { request("DELETE", "/proxies/$PROXY_NAME") }
        createProxy()
    }

    fun addBlackhole() {
        addToxic(
            name = "blackhole_upstream",
            type = "timeout",
            stream = "upstream",
            attributesJson = """{"timeout":0}""",
        )
        addToxic(
            name = "blackhole_downstream",
            type = "timeout",
            stream = "downstream",
            attributesJson = """{"timeout":0}""",
        )
    }

    fun addLatencyModel() {
        addToxic(
            name = "latency_upstream",
            type = "latency",
            stream = "upstream",
            attributesJson = """{"latency":120,"jitter":40}""",
        )
        addToxic(
            name = "latency_downstream",
            type = "latency",
            stream = "downstream",
            attributesJson = """{"latency":160,"jitter":60}""",
        )
    }

    fun clearToxics() {
        KNOWN_TOXICS.forEach { name ->
            runCatching { request("DELETE", "/proxies/$PROXY_NAME/toxics/$name") }
        }
    }

    /** Drop active connections and refuse new ones until [enable] restores the proxy. */
    fun disable() {
        request("POST", "/proxies/$PROXY_NAME", """{"enabled":false}""")
    }

    /** Restore the link after [disable]; new connections are accepted again. */
    fun enable() {
        request("POST", "/proxies/$PROXY_NAME", """{"enabled":true}""")
    }

    private fun createProxy() {
        request(
            "POST",
            "/proxies",
            """{"name":"$PROXY_NAME","listen":"0.0.0.0:2228","upstream":"agents:22","enabled":true}""",
        )
    }

    private fun addToxic(name: String, type: String, stream: String, attributesJson: String) {
        request(
            "POST",
            "/proxies/$PROXY_NAME/toxics",
            """{"name":"$name","type":"$type","stream":"$stream","toxicity":1.0,"attributes":$attributesJson}""",
        )
    }

    private fun request(method: String, path: String, body: String? = null): String {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            doInput = true
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) {
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        check(code in 200..299) {
            "toxiproxy $method $path failed: HTTP $code $response"
        }
        return response
    }

    private companion object {
        const val PROXY_NAME: String = "agents_ssh"
        val KNOWN_TOXICS: List<String> = listOf(
            "blackhole_upstream",
            "blackhole_downstream",
            "latency_upstream",
            "latency_downstream",
        )
    }
}
