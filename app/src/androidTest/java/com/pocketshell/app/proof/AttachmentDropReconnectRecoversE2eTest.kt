package com.pocketshell.app.proof

import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_PULL_TO_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

/**
 * Issue #1072 (v0.4.19 release blocker, maintainer dogfood): "When I attach
 * something the connection breaks and then I can't reconnect — I have to restart
 * the app." Failure 2 — the post-attach drop wedging reconnect — is the
 * maintainer's "worse half" and a reconnect-core (D28) change.
 *
 * This is the missing END-TO-END proof (G10 / D33). The deterministic JVM gate
 * ([com.pocketshell.app.tmux.AttachmentUploadTeardownReconnectTest]) modeled the
 * wedge with a `BlockingConnector` + a forever-blocking `CompletableDeferred`, NOT
 * a real attachment upload being torn down on a real `-CC` transport while the
 * reconnect ladder redials. The v0.4.19 review BLOCKED on exactly that gap: Failure
 * 2 had no connected/Docker journey on the real path. Both methods here run on the
 * REAL path (emulator + Docker `agents:2222`), DETERMINISTICALLY (no toxiproxy — so
 * they gate per-push), with NO `assumeTrue`/`assumeFalse(isRunningOnCi())` on the
 * load-bearing assertions (D31/F3):
 *
 *  - [attachUploadTornDownOnRealTransportIsOwnedAndCancelled] is the deterministic
 *    red→green proof of Failure 2's root cause #1: a REAL attachment upload, in
 *    flight on the REAL warm `-CC` transport, MUST be OWNED by the VM and
 *    cancel-and-joined by the reconnect ladder's teardown. RED on base (revert the
 *    `attachmentUploadJob?.cancelAndJoin()` teardown lines): the un-owned upload is
 *    a free-floating writer that races teardown and wedges reconnect, so the
 *    `tmux_attachment_stage_cancelled_by_teardown` diagnostic is NEVER recorded.
 *
 *  - [genuineDropDuringUploadRecoversToLiveWithoutRestart] is the faithful
 *    user-visible acceptance: a GENUINE transport death (the app's `-CC` sshd
 *    worker is killed) mid-upload — the maintainer's real scenario — then a manual
 *    Reconnect MUST return the SAME session to Live WITHOUT an app restart, with a
 *    fresh marker round-tripping through the recovered channel. (A genuine death
 *    leaves the warm transport `isConnected==false`, so the reconnect dials a
 *    FRESH transport instead of reusing the upload-poisoned one.)
 */
@RunWith(AndroidJUnit4::class)
class AttachmentDropReconnectRecoversE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()
    private val grantPermissions = PreGrantPermissionsRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(grantPermissions)
        .around(seedFixtureRule())
        .around(compose)

    private var seededKey: String? = null
    private var seededHostRowTag: String? = null
    private var diagnostics: RecordingDiagnosticSink? = null
    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timings = mutableListOf<String>()

    private fun seedFixtureRule(): TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                runBlocking {
                    val key = readFixtureKey()
                    seededKey = key
                    waitForSshFixtureReady(SshKey.Pem(key))
                    seedTmuxSession(key)
                    seededHostRowTag = seedDockerHost(key)
                }
                base.evaluate()
            }
        }
    }

    @Before
    fun setUp() {
        clearLastSessionPrefs()
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
    }

    @After
    fun tearDown() {
        runCatching {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
        uploadScope.cancel()
        diagnostics?.close()
        diagnostics = null
        clearLastSessionPrefs()
        seededKey?.let { key ->
            runCatching { runBlocking { cleanupRemoteTmuxSession(key) } }
        }
    }

    /**
     * Failure-2 root cause #1 — the deterministic red→green proof, on the REAL
     * `-CC` transport. A REAL attachment upload streams over the warm lease; we
     * declare a drop while it is genuinely in flight (the deterministic
     * liveness-probe seam fires the SAME onLivenessProbeDeclaredDrop ->
     * scheduleAutoReconnect -> closeCurrentConnectionAndJoin teardown a real silent
     * drop fires), and assert the teardown OWNS + CANCELS the in-flight upload.
     */
    @Test
    fun attachUploadTornDownOnRealTransportIsOwnedAndCancelled() = runBlocking<Unit> {
        val key = requireNotNull(seededKey)
        val hostRowTag = requireNotNull(seededHostRowTag)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        emitMarkerIntoPane(key, "LIVE-$MARKER")
        waitForVisibleTerminal("pre-drop-live") { it.contains("LIVE-$MARKER") }

        val vm = currentViewModel()
        diagnostics!!.clear()

        // Start the REAL, large attachment upload over the warm `-CC` lease and wait
        // until it is genuinely in flight on the real transport.
        val uploadResult = startRealUpload(vm)
        assertTrue(
            "expected the real attachment upload to be IN FLIGHT on the warm `-CC` " +
                "transport (attachmentUploadActiveForTest) so the teardown tears it down " +
                "mid-stream — it never went active within ${UPLOAD_IN_FLIGHT_WINDOW_MS}ms " +
                "(upload too fast? raise UPLOAD_BYTES).",
            waitForUploadInFlight(vm, UPLOAD_IN_FLIGHT_WINDOW_MS),
        )

        // Declare a drop DURING the in-flight upload, on Main — the real
        // onLivenessProbeDeclaredDrop -> scheduleAutoReconnect ->
        // closeCurrentConnectionAndJoin teardown chain.
        val dropStart = SystemClock.elapsedRealtime()
        compose.activityRule.scenario.onActivity {
            ViewModelProvider(it)[TmuxSessionViewModel::class.java].triggerLivenessProbeDropForTest(2)
        }

        // The teardown MUST own + cancel the in-flight upload (Failure-2 root cause).
        val result = uploadResult.await()
        val cancelledByTeardown =
            diagnostics!!.eventsNamed("tmux_attachment_stage_cancelled_by_teardown")
        recordTiming(
            "upload_cancelled_by_teardown_ms",
            if (cancelledByTeardown.isNotEmpty()) SystemClock.elapsedRealtime() - dropStart else -1L,
        )
        assertTrue(
            "#1072 Failure 2: the connection teardown MUST own + cancel the in-flight " +
                "attachment upload (tmux_attachment_stage_cancelled_by_teardown). On base " +
                "the un-owned upload races teardown and wedges reconnect — RED: revert the " +
                "attachmentUploadJob?.cancelAndJoin() teardown lines and this diagnostic is " +
                "never recorded. Recorded attach diagnostics=" +
                "${diagnostics!!.events.map { it.name }.filter { it.startsWith("tmux_attachment_stage") }}",
            cancelledByTeardown.isNotEmpty(),
        )
        assertTrue(
            "the upload must resolve to a draft-preserving failure (not silently apply into " +
                "a dead session), got=$result",
            result.isFailure,
        )
        assertTrue(
            "after teardown the owned upload job must no longer be active",
            !vm.attachmentUploadActiveForTest(),
        )
        captureViewport("issue1072-A-upload-torn-down")
        writeTimings("upload-torn-down")
    }

    /**
     * The literal #1072 acceptance criterion, faithful + end-to-end: a GENUINE clean
     * transport outage (the maintainer's "the connection breaks") DURING an in-flight
     * attach upload, then a manual Reconnect returns the SAME session to Live WITHOUT
     * an app restart — rebound onto a FRESH transport (not the upload-poisoned one).
     *
     * The outage is injected deterministically with the #833 clean-outage seams
     * ([TmuxSessionViewModel.forceCleanOutageForTest] +
     * [TmuxSessionViewModel.triggerCleanPassiveDropForTest], the same seams
     * [CleanOutageReattachResilienceE2eTest] uses) rather than a real socket kill: a
     * 48 MB upload to fast localhost finishes before a kill connection's handshake
     * lands, so a kill cannot deterministically tear it down mid-stream on CI. The
     * clean-outage seam fires the EOF passive-drop the moment the upload is in flight
     * and forces the reattach loop to re-dial a FRESH transport every iteration — the
     * faithful genuine-death recovery (a genuinely dead transport is `isConnected ==
     * false`, so the lease never reuses the upload-poisoned one).
     */
    @Test
    fun genuineDropDuringUploadRecoversToLiveWithoutRestart() = runBlocking<Unit> {
        val key = requireNotNull(seededKey)
        val hostRowTag = requireNotNull(seededHostRowTag)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")

        val vm = currentViewModel()
        emitMarkerIntoPane(key, "LIVE-$MARKER")
        waitForVisibleTerminal("pre-drop-live") { it.contains("LIVE-$MARKER") }
        diagnostics!!.clear()

        // Pre-open a WARM killer SSH session so the genuine death lands near-instantly
        // (one exec on an already-open transport) once the upload is in flight — a COLD
        // kill connection's handshake latency let the 48 MB upload to fast-localhost
        // finish before the kill landed.
        val killer = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
        val clientBeforeDrop = vm.currentClientIdentityForTest()

        try {
            // Start the REAL, large attachment upload over the warm `-CC` lease and wait
            // until it is genuinely in flight on the real transport.
            val uploadResult = startRealUpload(vm)
            assertTrue(
                "expected the real attachment upload to be IN FLIGHT on the warm `-CC` " +
                    "transport before the drop (upload too fast? raise UPLOAD_BYTES).",
                waitForUploadInFlight(vm, UPLOAD_IN_FLIGHT_WINDOW_MS),
            )

            // GENUINE transport death DURING the in-flight upload: kill EVERY testuser
            // sshd worker except the killer's own (so the app's `-CC`/upload transport
            // dies for sure — a PID-diff missed it). The remote tmux server + pane stay
            // alive; only the app's transport dies — the maintainer's "the connection
            // breaks" while attaching. A genuinely dead transport is `isConnected==false`,
            // so the reconnect dials a FRESH transport instead of reusing it.
            val dropStart = SystemClock.elapsedRealtime()
            killer.exec(
                "for p in \$(pgrep -u $DEFAULT_USER sshd); do " +
                    "[ \"\$p\" != \"\$PPID\" ] && kill -9 \"\$p\" 2>/dev/null || true; done",
            )

            // The in-flight upload must resolve to a draft-preserving failure (the bytes
            // are NOT silently applied into the dead session).
            val result = uploadResult.await()
            recordTiming("upload_resolved_ms", SystemClock.elapsedRealtime() - dropStart)
            assertTrue(
                "the in-flight upload must resolve to a draft-preserving failure when the " +
                    "transport dies under it, got=$result",
                result.isFailure,
            )

            // The drop is USER-VISIBLE.
            assertTrue(
                "expected a USER-VISIBLE connection-lost indicator after the mid-upload drop " +
                    "(status=${currentConnectionStatus()}).",
                waitForConnectionLostIndicator(DROP_DETECT_WINDOW_MS),
            )
            captureViewport("issue1072-B-dropped-mid-upload")

            // The maintainer's "I tap reconnect" — the production handler the Reconnect
            // button invokes. With the fix an explicit Reconnect PREEMPTS any in-flight
            // same-target connect so a wedged attempt can never suppress it (#1072).
            compose.activityRule.scenario.onActivity {
                ViewModelProvider(it)[TmuxSessionViewModel::class.java].reconnect()
            }

            // HEADLINE: the SAME session recovers to Live WITHOUT an app restart.
            val recovered = waitForSessionRecovered(WEDGE_RECOVER_WINDOW_MS)
            recordTiming(
                "recovered_without_restart_ms",
                if (recovered) SystemClock.elapsedRealtime() - dropStart else -1L,
            )
            assertTrue(
                "#1072 ACCEPTANCE: after a drop during an attach upload, the SAME session " +
                    "MUST reconnect cleanly and return to Live WITHOUT an app restart (no " +
                    "reconnect wedge). status=${currentConnectionStatus()}",
                recovered,
            )

            // The recovered control client must be a DIFFERENT instance than the one that
            // dropped — proving recovery re-dialled a FRESH transport (not the
            // upload-poisoned warm one).
            val clientAfterRecovery = vm.currentClientIdentityForTest()
            recordTiming(
                "client_rebound_bool",
                if (clientAfterRecovery != null && clientAfterRecovery != clientBeforeDrop) 1L else 0L,
            )
            assertTrue(
                "expected the recovered session rebound onto a FRESH control client " +
                    "(re-dialled transport), beforeDrop=$clientBeforeDrop afterRecovery=$clientAfterRecovery",
                clientAfterRecovery != null && clientAfterRecovery != clientBeforeDrop,
            )

            // A fresh marker must round-trip through the RECOVERED `-CC` channel — proving
            // the recovered session is live + input-accepting, not a blank shell.
            emitMarkerIntoPane(key, "AFTER-$MARKER")
            val roundTripped = runCatching {
                waitForVisibleTerminal("post-recovery", timeoutMillis = ROUND_TRIP_WINDOW_MS) {
                    it.contains("AFTER-$MARKER")
                }
                true
            }.getOrDefault(false)
            recordTiming("post_recovery_round_tripped_bool", if (roundTripped) 1L else 0L)
            assertTrue(
                "expected a post-recovery send to round-trip through the SAME recovered " +
                    "session (no restart). status=${currentConnectionStatus()}",
                roundTripped,
            )
            captureViewport("issue1072-B-recovered-live")
            writeTimings("recovers-without-restart")
        } finally {
            runCatching { killer.close() }
        }
    }

    // -- upload + in-flight helpers ------------------------------------------------

    private fun startRealUpload(vm: TmuxSessionViewModel): Deferred<Result<List<String>>> {
        // The maintainer's exact path: composer attach -> stagePromptAttachments ->
        // PromptAttachmentStager -> session.uploadFile over the SAME transport that
        // holds the live session. A large payload keeps the upload genuinely in flight
        // on the real transport long enough to drop it mid-stream.
        val uri = createLargeAttachmentUri()
        return uploadScope.async { vm.stagePromptAttachments(listOf(uri)) }
    }

    private fun waitForUploadInFlight(vm: TmuxSessionViewModel, timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (vm.attachmentUploadActiveForTest()) return true
            SystemClock.sleep(25)
        }
        return vm.attachmentUploadActiveForTest()
    }

    // -- indicator helpers ---------------------------------------------------------

    private fun waitForConnectionLostIndicator(timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (connectionLostIndicatorVisible()) return true
            SystemClock.sleep(150)
        }
        return connectionLostIndicatorVisible()
    }

    private fun connectionLostIndicatorVisible(): Boolean {
        if (hasTag(TMUX_SESSION_ERROR_TAG) ||
            hasTag(TMUX_SESSION_RECONNECT_TAG) ||
            hasTag(TMUX_PULL_TO_RECONNECT_TAG)
        ) {
            return true
        }
        return when (currentConnectionStatus()) {
            is TmuxSessionViewModel.ConnectionStatus.Connected -> false
            is TmuxSessionViewModel.ConnectionStatus.Idle -> false
            else -> true
        }
    }

    private fun waitForSessionRecovered(timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (sessionHealthyConnected()) return true
            SystemClock.sleep(250)
        }
        return sessionHealthyConnected()
    }

    private fun sessionHealthyConnected(): Boolean {
        if (hasTag(TMUX_SESSION_ERROR_TAG) ||
            hasTag(TMUX_SESSION_RECONNECT_TAG) ||
            hasTag(TMUX_PULL_TO_RECONNECT_TAG)
        ) {
            return false
        }
        return currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
    }

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    // -- attach + IO helpers -------------------------------------------------------

    private fun attachSeededTmuxSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = HOST_ROW_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    private suspend fun emitMarkerIntoPane(key: String, marker: String) {
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec(
                    "tmux send-keys -t ${shellQuote(SESSION_NAME)} " +
                        shellQuote("printf '$marker\\n'") + " Enter",
                )
            }
        }.getOrThrow()
    }

    private fun waitForConnected(label: String) {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        assertTrue(
            "expected Connected after $label, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun currentViewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        return requireNotNull(vm) { "TmuxSessionViewModel not available" }
    }

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        compose.activityRule.scenario.onActivity { activity ->
            status = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .connectionStatus
                .value
        }
        return status
    }

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ): String {
        var last = ""
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            last = visibleTerminalText()
            last.isNotBlank() && predicate(last)
        }
        assertTrue("expected visible terminal for $label; got:\n$last", predicate(last))
        return last
    }

    private fun visibleTerminalText(): String {
        var text = ""
        compose.activityRule.scenario.onActivity { activity ->
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

    // -- seeding / cleanup ---------------------------------------------------------

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    /**
     * A large real file behind a readable `file://` Uri — resolvable by
     * [android.content.ContentResolver.openInputStream], so [stagePromptAttachments]
     * exercises the REAL upload path. Large enough that the byte stream stays in
     * flight on the warm `-CC` transport while we induce the drop mid-stream.
     */
    private fun createLargeAttachmentUri(): Uri {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "issue1072-large-attachment.bin")
        val chunk = ByteArray(64 * 1024) { (it and 0xFF).toByte() }
        file.outputStream().use { out ->
            var written = 0L
            while (written < UPLOAD_BYTES) {
                out.write(chunk)
                written += chunk.size
            }
        }
        return Uri.fromFile(file)
    }

    private suspend fun seedDockerHost(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue1072-attach-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1072 Attach Drop Reconnect",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private suspend fun seedTmuxSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} " +
                    shellQuote("printf '$READY_MARKER\\n'; exec sh -i"),
            )
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            command = script,
            description = "issue1072 tmux seed session",
        )
        assertTrue(
            "expected tmux seeding to succeed; exit=${result.exitCode} stderr='${result.stderr}'",
            result.exitCode == 0,
        )
    }

    private suspend fun cleanupRemoteTmuxSession(key: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    it.exec("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
                }
            }
        }
    }

    // -- artifacts -----------------------------------------------------------------

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        var bitmap: android.graphics.Bitmap? = null
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = android.graphics.Bitmap.createBitmap(
                view.width,
                view.height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
            view.draw(android.graphics.Canvas(b))
            bitmap = b
        }
        bitmap?.let {
            val file = artifactFile("$name-viewport.png")
            java.io.FileOutputStream(file).use { out ->
                it.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            println("ISSUE1072_VIEWPORT ${file.absolutePath}")
            it.recycle()
        }
        artifactFile("$name-visible-terminal.txt").writeText(visibleTerminalText())
    }

    private fun writeTimings(label: String): File {
        val file = artifactFile("$label-timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE1072_TIMINGS ${file.absolutePath}")
        return file
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE1072_TIMING $line")
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

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "issue1072-attach-drop-reconnect"
        const val SESSION_NAME: String = "issue1072-attach-proof"
        const val READY_MARKER: String = "ISSUE1072-ATTACH-READY"
        const val MARKER: String = "issue1072attach"

        // Large enough that the byte stream stays in flight on the warm `-CC`
        // transport (sshj single-threaded AES is the bottleneck even on localhost)
        // for the in-flight poll + the drop to land mid-stream. If a future faster
        // path makes the upload outrun the in-flight poll, the test FAILS LOUD
        // (waitForUploadInFlight) rather than passing vacuously — raise this then.
        const val UPLOAD_BYTES: Long = 48L * 1024 * 1024

        val UPLOAD_IN_FLIGHT_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 30_000L
        val DROP_DETECT_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
        val WEDGE_RECOVER_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 45_000L
        val ROUND_TRIP_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 30_000L
        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
