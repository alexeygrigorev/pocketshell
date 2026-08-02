package com.pocketshell.app.proof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.composer.COMPOSER_CLOSE_TAG
import com.pocketshell.app.composer.DurableAttachmentRef
import com.pocketshell.app.composer.LocalAttachmentSidecarRef
import com.pocketshell.app.composer.COMPOSER_OUTBOUND_QUEUE_BANNER_TAG
import com.pocketshell.app.composer.OutboundAttachmentSidecarStore
import com.pocketshell.app.composer.OutboundItem
import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.OutboundState
import com.pocketshell.app.composer.PromptAttachmentStager
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.composer.SharedPrefsOutboundQueueStore
import com.pocketshell.app.composer.composerOutboundQueueRetryTestTag
import com.pocketshell.app.composer.pendingAttachmentRemotePath
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.proof.signals.assertScreenshotNotBlank
import com.pocketshell.app.share.FilenameSanitiser
import com.pocketshell.app.share.ShareUploader
import com.pocketshell.app.tmux.QueueSidecarUploadJourneySeam
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SWITCHING_LOADING_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_TAB_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.app.tmux.durableTmuxSessionKey
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.QueueSidecarResumableUploadResult
import com.pocketshell.core.ssh.QueueSidecarUploadDisposition
import com.pocketshell.core.ssh.QueueSidecarUploadProgress
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.queueSidecarCheckpointPaths
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Issue #1733: production durable queue-sidecar upload resumes from the exact
 * remote checkpoint after the app's real SSH worker dies. This is deliberately
 * not the immediate/generic attachment staging path.
 */
@RunWith(AndroidJUnit4::class)
class OutboundAttachmentOffsetResumeJourneyE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String
    private lateinit var queueSessionKey: String
    private lateinit var queuedItem: OutboundItem
    private lateinit var sidecar: LocalAttachmentSidecarRef
    private lateinit var finalRemotePath: String
    private lateinit var finalDisplayPath: String
    private lateinit var localSha256: String
    private lateinit var proxy: ToxiproxyControl
    private var diagnostics: RecordingDiagnosticSink? = null
    private val firstProgress = AtomicReference<QueueSidecarUploadProgress>()
    private val secondProgress = AtomicReference<QueueSidecarUploadProgress>()
    private val resumedResult = AtomicReference<QueueSidecarResumableUploadResult>()
    private val stopFirstAttempt = AtomicBoolean(true)
    private val firstProgressReady = CountDownLatch(1)
    private val releaseFirstAttempt = CountDownLatch(1)
    private val secondProgressReady = CountDownLatch(1)
    private val releaseSecondAttempt = CountDownLatch(1)
    private val resumedResultReady = CountDownLatch(1)
    private val timings = mutableListOf<String>()
    private val artifactRunId = "run-${System.currentTimeMillis()}"

    private suspend fun seedBeforeLaunch() {
        val context = targetContext()
        clearLastSessionPrefs()
        context.getSharedPreferences(OUTBOUND_QUEUE_PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(OutboundAttachmentSidecarStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.filesDir, OutboundAttachmentSidecarStore.DIRECTORY_NAME).deleteRecursively()

        fixtureKey = readFixtureKey()
        assertTrue(
            "issue #1733 requires the explicitly opted-in Toxiproxy fixture",
            InstrumentationRegistry.getArguments()
                .getString(NETWORK_FAULT_ARG)
                ?.toBooleanStrictOrNull() == true,
        )
        waitForSshFixtureReady(SshKey.Pem(fixtureKey), port = DIRECT_AGENTS_SSH_PORT)
        proxy = ToxiproxyControl(baseUrl = "http://$DEFAULT_HOST:$TOXIPROXY_API_PORT")
        proxy.reset()
        waitForSshFixtureReady(SshKey.Pem(fixtureKey), port = NETWORK_FAULT_SSH_PORT)
        val identity = seedFakeAgentSession(fixtureKey)
        val hostId = seedDockerHost(fixtureKey)
        hostRowTag = HOST_ROW_TAG_PREFIX + hostId
        queueSessionKey = requireNotNull(
            durableTmuxSessionKey(hostId, identity.sessionId, identity.sessionCreated),
        )

        val source = createImmutableAttachment(context)
        localSha256 = sha256(source)
        val itemId = "issue1733-row-${System.currentTimeMillis()}"
        sidecar = requireNotNull(
            OutboundAttachmentSidecarStore(context)
                .stage(itemId, listOf(Uri.fromFile(source)), listOf(0))
                .singleOrNull(),
        )
        assertEquals(UPLOAD_BYTES, sidecar.byteSize)

        val safeScope = PromptAttachmentStager.safeScopeSegment("host-$hostId-$SESSION_NAME")
        val remoteName = PromptAttachmentStager.composeAttachmentName(
            ShareUploader.formatTimestamp(sidecar.createdAtMs),
            sidecar.attachmentIndex ?: 0,
            FilenameSanitiser.sanitise(
                sidecar.displayName,
                defaultExtension = ShareUploader.extensionForMimeType(sidecar.mimeType),
            ),
        )
        finalRemotePath = "${PromptAttachmentStager.REMOTE_DIRECTORY}/$safeScope/$remoteName"
        finalDisplayPath = "~/$finalRemotePath"
        queuedItem = OutboundItem(
            id = itemId,
            sessionKey = queueSessionKey,
            cleanText = PROMPT,
            attachments = listOf(
                DurableAttachmentRef(
                    remotePath = pendingAttachmentRemotePath(queueSessionKey, 0, sidecar.displayName),
                    displayName = sidecar.displayName,
                    mimeType = sidecar.mimeType,
                ),
            ),
            withEnter = true,
            state = OutboundState.Queued,
            createdAtMs = sidecar.createdAtMs,
            // Use the authoritative pane identity seeded on the server. A blank
            // pane would make production fall back to the visible pane while the
            // durable wire ledger still searched for "", falsely reporting
            // wireAttempted=false after bytes had demonstrably landed on %N.
            paneId = identity.paneId,
            // The fake-agent pane models the production attachment-bearing agent
            // path: a multi-line payload is pasted atomically, acknowledged, then
            // submitted with exactly one Enter. RawBytes deliberately has different
            // terminal semantics and is covered by its own delivery journeys.
            route = OutboundRoute.AgentPayload,
            agentKind = "claude",
        )
        SharedPrefsOutboundQueueStore(context).enqueueExisting(queuedItem)
    }

    @Before
    fun setUp() {
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
        QueueSidecarUploadJourneySeam.onProgress = { ref, progress ->
            if (ref.id == sidecar.id &&
                progress.resumedFromBytes == 0L &&
                progress.bytesTransferred >= CUT_AFTER_BYTES &&
                stopFirstAttempt.compareAndSet(true, false)
            ) {
                firstProgress.set(progress)
                firstProgressReady.countDown()
                check(releaseFirstAttempt.await(HOOK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    "timed out waiting for the journey to kill the real app SSH worker"
                }
            } else if (ref.id == sidecar.id &&
                progress.resumedFromBytes > 0L &&
                progress.bytesTransferred == progress.resumedFromBytes &&
                secondProgress.compareAndSet(null, progress)
            ) {
                secondProgressReady.countDown()
                check(releaseSecondAttempt.await(HOOK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    "timed out waiting for the journey to verify the resume offset"
                }
            }
        }
        QueueSidecarUploadJourneySeam.onResult = { ref, result ->
            if (ref.id == sidecar.id && result.resumedFromBytes > 0L) {
                resumedResult.set(result)
                resumedResultReady.countDown()
            }
        }
    }

    @After
    fun tearDown() {
        releaseFirstAttempt.countDown()
        releaseSecondAttempt.countDown()
        QueueSidecarUploadJourneySeam.reset()
        diagnostics?.close()
        diagnostics = null
        clearLastSessionPrefs()
        if (::proxy.isInitialized) {
            runCatching { proxy.reset() }
        }
        if (::fixtureKey.isInitialized) {
            runCatching { runBlocking { cleanupRemote(fixtureKey) } }
        }
    }

    @Test
    fun durableQueueSidecarResumesExactOffsetAfterRealWorkerKill() {
        runBlocking {
            val killer = connectSidecar()
            try {
            writeText("00-queue-seed.txt", queueSnapshot())
            writeText("00-sshd-before.txt", remoteExec(killer, SSHD_SNAPSHOT).stdout)
            writeText(
                "00-preserved-run.txt",
                "$PRESERVED_DEVICE_ROOT/$artifactRunId\n",
            )
            preserveArtifacts(
                phase = "00-seed",
                "00-queue-seed.txt",
                "00-sshd-before.txt",
                "00-preserved-run.txt",
            )
            attachSeededTmuxSession()
            waitForConnected("initial attach")
            waitForVisibleTerminal("fake-agent ready") { it.contains(FAKE_AGENT_READY) }
            val vm = currentViewModel()
            val clientBefore = vm.currentClientIdentityForTest()

            val initialManualRetryUsed = ensureQueuedRowReachableOrClaimed()
            recordTiming("initial_manual_retry_used", if (initialManualRetryUsed) 1L else 0L)
            assertEquals(
                "the durable row must be owned by the production sidecar upload before " +
                    "the composer is dismissed",
                OutboundState.Uploading,
                queueStore().item(queuedItem.id)?.state,
            )
            dismissComposerAfterOwnership()
            preserveArtifacts(
                phase = "00-ownership",
                "00-queue-ownership.txt",
            )
            assertTrue(
                "production durable queue upload never reached $CUT_AFTER_BYTES bytes; " +
                    "queue=${queueSnapshot()} status=${currentConnectionStatus()} " +
                    "diagnostics=${diagnosticSummary()}",
                firstProgressReady.await(PROGRESS_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            )
            val progress = requireNotNull(firstProgress.get())
            val checkpoint = queueSidecarCheckpointPaths(finalRemotePath, sidecar.id)
            val committedN = waitForCommittedRemotePrefix(
                killer,
                checkpoint.dataPath,
                progress.bytesTransferred,
            )
            assertTrue("checkpoint must be a strict prefix; N=$committedN total=${sidecar.byteSize}", committedN in 1 until sidecar.byteSize)
            assertTrue(
                "the server cannot commit beyond the client-side progress watermark; " +
                    "checkpoint=$committedN progress=${progress.bytesTransferred}",
                committedN <= progress.bytesTransferred,
            )
            assertRemoteAbsent(killer, finalRemotePath, "before worker kill")
            captureViewport("01-committed-prefix")
            writeText(
                "01-checkpoint.txt",
                "checkpoint=${checkpoint.dataPath}\ncheckpoint_size=$committedN\n" +
                    "progress_resumed_from=${progress.resumedFromBytes}\n" +
                    "progress_transferred=${progress.bytesTransferred}\ntotal=${sidecar.byteSize}\n",
            )
            preserveArtifacts(
                phase = "01-checkpoint",
                "01-checkpoint.txt",
                "01-committed-prefix-viewport.png",
                "01-committed-prefix-visible-terminal.txt",
            )

            val killedAt = SystemClock.elapsedRealtime()
            val kill = remoteExec(
                killer,
                "for p in \$(pgrep -u $DEFAULT_USER sshd); do " +
                    "[ \"\$p\" != \"\$PPID\" ] && kill -9 \"\$p\" 2>/dev/null || true; done",
            )
            assertEquals("real worker-kill command failed: ${kill.stderr}", 0, kill.exitCode)
            proxy.withDisabledProxy {
                releaseFirstAttempt.countDown()
                writeText(
                    "02-real-outage-hold.txt",
                    "worker_kill_exit=${kill.exitCode}\n" +
                        "app_transport_port=$NETWORK_FAULT_SSH_PORT\n" +
                        "direct_checkpoint_sidecar_port=$DIRECT_AGENTS_SSH_PORT\n" +
                        "toxiproxy_state=disabled_during_loss_assertion_and_capture\n",
                )

                val reconnectProof = waitForVisibleReconnectSurface(CONNECTION_LOST_TIMEOUT_MS)
                assertTrue(
                    "the genuine app-worker death must project raw+display Reconnecting AND " +
                        "the visible contained $ATTACHING_LABEL surface while the real proxy " +
                        "outage is held; raw=${currentConnectionStatus()} " +
                        "display=${currentDisplayConnectionStatus()} " +
                        "diagnostics=${diagnosticSummary()}",
                    reconnectProof != null,
                )
                val displayedReconnectProof = requireNotNull(reconnectProof)
                assertVisibleReconnectSurface()
                val connectionLostScreenshot = captureDecorViewport("02-connection-lost")
                val connectionLostText = writeText(
                    "02-connection-lost-visible-label.txt",
                    "$ATTACHING_LABEL\n",
                )
                val reconnectStateText = writeText(
                    "02-reconnect-state.txt",
                    "raw=${displayedReconnectProof.raw}\n" +
                        "display=${displayedReconnectProof.display}\n",
                )
                assertTrue(
                    "connection-lost screenshot artifact must exist and be nonempty: " +
                        connectionLostScreenshot.absolutePath,
                    connectionLostScreenshot.isFile && connectionLostScreenshot.length() > 0L,
                )
                assertTrue(
                    "connection-lost label artifact must exist and be nonempty: " +
                        connectionLostText.absolutePath,
                    connectionLostText.isFile && connectionLostText.length() > 0L,
                )
                assertTrue(
                    "reconnect-state artifact must exist and be nonempty: " +
                        reconnectStateText.absolutePath,
                    reconnectStateText.isFile && reconnectStateText.length() > 0L,
                )
                preserveArtifacts(
                    phase = "02-loss-visible",
                    "02-connection-lost-viewport.png",
                    "02-connection-lost-visible-label.txt",
                    "02-reconnect-state.txt",
                    "02-real-outage-hold.txt",
                )
                recordTiming("connection_lost_visible_ms", SystemClock.elapsedRealtime() - killedAt)
                assertTrue(
                    "killed upload must return the durable row to a retryable state; ${queueSnapshot()}",
                    waitForRetryableRow(QUEUE_RETRYABLE_TIMEOUT_MS),
                )
                writeText("02-queue-retryable.txt", queueSnapshot())
                writeText("02-sshd-after-kill.txt", remoteExec(killer, SSHD_SNAPSHOT).stdout)
                preserveArtifacts(
                    phase = "02-retryable",
                    "02-queue-retryable.txt",
                    "02-sshd-after-kill.txt",
                )
                assertEquals(committedN, remoteSize(killer, checkpoint.dataPath))
                assertRemoteAbsent(killer, finalRemotePath, "after worker kill")
            }

            if (currentConnectionStatus() !is TmuxSessionViewModel.ConnectionStatus.Connected) {
                compose.activityRule.scenario.onActivity {
                    ViewModelProvider(it)[TmuxSessionViewModel::class.java].reconnect()
                }
            }
            waitForConnected("reconnect without app restart")
            if (!secondProgressReady.await(AUTO_RETRY_WINDOW_MS, TimeUnit.MILLISECONDS)) {
                openComposer()
                val retryTag = composerOutboundQueueRetryTestTag(queuedItem.id)
                val retryDeadline = SystemClock.elapsedRealtime() + RESUME_TIMEOUT_MS
                var retryClicked = false
                while (secondProgressReady.count > 0L &&
                    SystemClock.elapsedRealtime() < retryDeadline
                ) {
                    if (!retryClicked && hasTag(retryTag)) {
                        compose.onNodeWithTag(retryTag, useUnmergedTree = true).performClick()
                        retryClicked = true
                    }
                    SystemClock.sleep(100)
                }
                recordTiming("manual_retry_used", if (retryClicked) 1L else 0L)
            } else {
                recordTiming("manual_retry_used", 0L)
            }
            assertTrue(
                "auto-flush/retry never opened the resumed queue-sidecar attempt",
                secondProgressReady.await(RESUME_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            )
            val resumeStart = requireNotNull(secondProgress.get())
            assertEquals(committedN, resumeStart.resumedFromBytes)
            assertEquals(committedN, resumeStart.bytesTransferred)
            assertEquals(committedN, remoteSize(killer, checkpoint.dataPath))
            assertRemoteAbsent(killer, finalRemotePath, "at resumed-at-N attempt start")
            releaseSecondAttempt.countDown()
            assertTrue(
                "resumed queue attempt never completed; queue=${queueSnapshot()} diagnostics=${diagnosticSummary()}",
                resumedResultReady.await(RESUME_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            )
            val result = requireNotNull(resumedResult.get())
            assertEquals(QueueSidecarUploadDisposition.Uploaded, result.disposition)
            assertEquals(finalRemotePath, result.remotePath)
            assertEquals(committedN, result.resumedFromBytes)
            assertEquals(sidecar.byteSize - committedN, result.transmittedBytes)
            val clientAfter = currentViewModel().currentClientIdentityForTest()
            assertTrue(
                "resume must use a fresh app tmux client; before=$clientBefore after=$clientAfter",
                clientAfter != null && clientAfter != clientBefore,
            )

            val finalSize = remoteSize(killer, finalRemotePath)
            val remoteSha = remoteExec(
                killer,
                "sha256sum ${shellQuote(finalRemotePath)} | awk '{print \$1}'",
            ).stdout.trim()
            assertEquals(sidecar.byteSize, finalSize)
            assertEquals(localSha256, remoteSha)
            assertRemoteAbsent(killer, checkpoint.dataPath, "after atomic promotion")
            assertRemoteAbsent(killer, checkpoint.identityPath, "after atomic promotion")

            waitForDurableDeliveryTerminal(killer, DELIVERY_TERMINAL_TIMEOUT_MS)
            val submitted = waitForPaneOutput(killer, INPUT_TIMEOUT_MS) {
                it.contains(PROMPT) && it.contains(finalDisplayPath) && it.contains(FAKE_AGENT_SUBMITTED)
            }
            val flattened = submitted.filterNot(Char::isWhitespace)
            assertEquals(1, countOccurrences(flattened, PROMPT.filterNot(Char::isWhitespace)))
            assertEquals(
                "attachment-bearing prompt must receive exactly one Enter; capture:\n" +
                    Issue1733JourneyContract.xmlSafeFailureText(submitted),
                1,
                countOccurrences(flattened, (FAKE_AGENT_SUBMITTED + PROMPT).filterNot(Char::isWhitespace)),
            )

            selectTerminalTabForLiveEvidence()
            writeThroughTerminalSession(LIVE_INPUT_MARKER)
            val liveCapture = waitForCapture(killer, INPUT_TIMEOUT_MS) { it.contains(LIVE_INPUT_MARKER) }
            assertTrue(
                "terminal input must remain live after resume; capture:\n" +
                    Issue1733JourneyContract.xmlSafeFailureText(liveCapture),
                liveCapture.contains(LIVE_INPUT_MARKER),
            )
            captureViewport(
                name = "03-resumed-delivered-live",
                requiredVisibleText = LIVE_INPUT_MARKER,
            )
            writeText("03-final-capture.txt", liveCapture)
            writeText("03-pane-output.txt", submitted)
            writeText("03-queue-final.txt", queueSnapshot())
            writeText("03-diagnostics.txt", diagnosticSummary())
            writeText("03-sshd-final.txt", remoteExec(killer, SSHD_SNAPSHOT).stdout)
            writeText(
                "03-resume-evidence.txt",
                "checkpoint_size=$committedN\nresumed_from=${result.resumedFromBytes}\n" +
                    "second_attempt_transmitted=${result.transmittedBytes}\ntotal=${sidecar.byteSize}\n" +
                    "final_size=$finalSize\nlocal_sha256=$localSha256\nremote_sha256=$remoteSha\n" +
                    "final_absent_before_promotion=true\ncheckpoint_removed=true\n" +
                    "client_before=$clientBefore\nclient_after=$clientAfter\n",
            )
            recordTiming("resume_completed_ms", SystemClock.elapsedRealtime() - killedAt)
            writeText("timings.txt", timings.joinToString("\n", postfix = "\n"))
            preserveArtifacts(
                phase = "03-complete",
                "03-final-capture.txt",
                "03-pane-output.txt",
                "03-queue-final.txt",
                "03-diagnostics.txt",
                "03-sshd-final.txt",
                "03-resume-evidence.txt",
                "03-resumed-delivered-live-viewport.png",
                "03-resumed-delivered-live-visible-terminal.txt",
                "timings.txt",
            )
            } finally {
                releaseFirstAttempt.countDown()
                releaseSecondAttempt.countDown()
                killer.close()
            }
        }
    }

    private fun ensureQueuedRowReachableOrClaimed(): Boolean {
        openComposer()
        val retryTag = composerOutboundQueueRetryTestTag(queuedItem.id)
        assertTrue(
            "production queue row never became visible or owned; " +
                "item=${queueStore().item(queuedItem.id)} queue=${queueSnapshot()}",
            waitForQueueOwnershipOrProjection(),
        )
        val observedBeforeRetry = queueStore().item(queuedItem.id)
        val alreadyClaimed = firstProgressReady.count == 0L ||
            observedBeforeRetry?.state in CLAIMED_OUTBOUND_STATES
        if (alreadyClaimed) {
            writeText(
                "00-queue-ownership.txt",
                "path=auto-claimed\nitem=$observedBeforeRetry\n",
            )
            return false
        }

        assertTrue(
            "seeded durable row must be projected into the production composer; " +
                "item=$observedBeforeRetry queue=${queueSnapshot()}",
            hasTag(COMPOSER_OUTBOUND_QUEUE_BANNER_TAG),
        )
        compose.onNodeWithTag(
            COMPOSER_OUTBOUND_QUEUE_BANNER_TAG,
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(
            COMPOSER_OUTBOUND_QUEUE_BANNER_TAG,
            useUnmergedTree = true,
        )
        compose.waitUntil(UI_TIMEOUT_MS) {
            firstProgressReady.count == 0L ||
                queueStore().item(queuedItem.id)?.state in CLAIMED_OUTBOUND_STATES ||
                hasTag(retryTag)
        }
        val claimedWhileVisible = firstProgressReady.count == 0L ||
            queueStore().item(queuedItem.id)?.state in CLAIMED_OUTBOUND_STATES
        if (claimedWhileVisible) {
            writeText(
                "00-queue-ownership.txt",
                "path=auto-claimed-after-projection\nitem=${queueStore().item(queuedItem.id)}\n",
            )
            return false
        }

        compose.onNodeWithTag(
            retryTag,
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNodeWithTag(retryTag, useUnmergedTree = true).performClick()
        compose.waitUntil(UI_TIMEOUT_MS) {
            firstProgressReady.count == 0L ||
                queueStore().item(queuedItem.id)?.state in CLAIMED_OUTBOUND_STATES
        }
        val claimed = queueStore().item(queuedItem.id)
        assertTrue(
            "production Retry must claim the projected durable row; item=$claimed",
            firstProgressReady.count == 0L || claimed?.state in CLAIMED_OUTBOUND_STATES,
        )
        writeText(
            "00-queue-ownership.txt",
            "path=visible-production-retry\nitem=$claimed\n",
        )
        return true
    }

    private fun openComposer() {
        if (hasTag(COMPOSER_OUTBOUND_QUEUE_BANNER_TAG) ||
            hasTag(composerOutboundQueueRetryTestTag(queuedItem.id))
        ) {
            return
        }
        compose.waitUntil(UI_TIMEOUT_MS) { hasTag(SESSION_COMPOSER_LAUNCHER_TAG) }
        compose.onNodeWithTag(
            SESSION_COMPOSER_LAUNCHER_TAG,
            useUnmergedTree = true,
        ).performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { hasTag(COMPOSER_CLOSE_TAG) }
    }

    /**
     * Queue auto-flush owns real-IO work while Compose owns a virtual test clock.
     * A tight Compose `waitUntil` can starve that IO on a contended emulator, so
     * use the repository's Shape-B rule: a hard wall-clock deadline, periodic
     * Compose/semantics drains, and the load-bearing condition as the exit.
     */
    private fun waitForQueueOwnershipOrProjection(): Boolean {
        val deadline = System.currentTimeMillis() + PROGRESS_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (
                firstProgressReady.count == 0L ||
                queueStore().item(queuedItem.id)?.state in CLAIMED_OUTBOUND_STATES ||
                hasTag(COMPOSER_OUTBOUND_QUEUE_BANNER_TAG)
            ) {
                return true
            }
            SystemClock.sleep(100)
        }
        return firstProgressReady.count == 0L ||
            queueStore().item(queuedItem.id)?.state in CLAIMED_OUTBOUND_STATES ||
            hasTag(COMPOSER_OUTBOUND_QUEUE_BANNER_TAG)
    }

    private fun dismissComposerAfterOwnership() {
        compose.waitUntil(UI_TIMEOUT_MS) { hasTag(COMPOSER_CLOSE_TAG) }
        compose.onNodeWithTag(
            COMPOSER_CLOSE_TAG,
            useUnmergedTree = true,
        ).assertIsDisplayed().performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { !hasTag(COMPOSER_CLOSE_TAG) }
        compose.onNodeWithTag(
            TMUX_SESSION_SCREEN_TAG,
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    private fun attachSeededTmuxSession() {
        compose.waitUntil(HOST_ROW_TIMEOUT_MS) { hasTag(hostRowTag) }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(HOST_ROW_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        compose.waitUntil(PROGRESS_TIMEOUT_MS) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                attached = activity.window.decorView.findTerminalView()?.currentSession?.emulator != null
            }
            attached
        }
    }

    private fun currentViewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        compose.activityRule.scenario.onActivity {
            vm = ViewModelProvider(it)[TmuxSessionViewModel::class.java]
        }
        return requireNotNull(vm)
    }

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus = TmuxSessionViewModel.ConnectionStatus.Idle
        compose.activityRule.scenario.onActivity {
            status = ViewModelProvider(it)[TmuxSessionViewModel::class.java].connectionStatus.value
        }
        return status
    }

    private fun currentDisplayConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus = TmuxSessionViewModel.ConnectionStatus.Idle
        compose.activityRule.scenario.onActivity {
            status = ViewModelProvider(it)[TmuxSessionViewModel::class.java]
                .displayConnectionStatus.value
        }
        return status
    }

    private fun waitForConnected(label: String) {
        compose.waitUntil(CONNECTED_TIMEOUT_MS) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        assertTrue("$label must end Connected; status=${currentConnectionStatus()}", currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    private fun waitForVisibleTerminal(label: String, predicate: (String) -> Boolean): String {
        var last = ""
        compose.waitUntil(PROGRESS_TIMEOUT_MS) {
            last = visibleTerminalText()
            predicate(last)
        }
        assertTrue("$label missing from terminal:\n$last", predicate(last))
        return last
    }

    private data class VisibleReconnectProof(
        val raw: TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        val display: TmuxSessionViewModel.ConnectionStatus.Reconnecting,
    )

    private fun waitForVisibleReconnectSurface(timeoutMs: Long): VisibleReconnectProof? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            visibleReconnectProof()?.let { return it }
            SystemClock.sleep(100)
        }
        return visibleReconnectProof()
    }

    private fun visibleReconnectProof(): VisibleReconnectProof? {
        val raw = currentConnectionStatus()
        val display = currentDisplayConnectionStatus()
        if (raw !is TmuxSessionViewModel.ConnectionStatus.Reconnecting ||
            display !is TmuxSessionViewModel.ConnectionStatus.Reconnecting
        ) {
            return null
        }
        return runCatching {
            assertVisibleReconnectSurface()
            VisibleReconnectProof(raw = raw, display = display)
        }.getOrNull()
    }

    private fun assertVisibleReconnectSurface() {
        compose.onNodeWithTag(
            TMUX_SWITCHING_LOADING_TAG,
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(
            TMUX_SWITCHING_LOADING_TAG,
            useUnmergedTree = true,
        )
        compose.onNodeWithText(
            ATTACHING_LABEL,
            useUnmergedTree = true,
        ).assertIsDisplayed().assertTextEquals(ATTACHING_LABEL)
    }

    private fun hasTag(tag: String): Boolean = runCatching {
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun waitForRetryableRow(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = queueStore().item(queuedItem.id)?.state
            if (state == OutboundState.Queued || state == OutboundState.Failed) return true
            SystemClock.sleep(100)
        }
        val state = queueStore().item(queuedItem.id)?.state
        return state == OutboundState.Queued || state == OutboundState.Failed
    }

    private fun queueSnapshot(): String =
        queueStore().item(queuedItem.id)?.toString() ?: "item=${queuedItem.id} delivered/pruned"

    private fun queueStore() = SharedPrefsOutboundQueueStore(targetContext())

    private fun diagnosticSummary(): String =
        diagnostics?.events?.joinToString("\n") { "${it.category}/${it.name} ${it.fields}" }.orEmpty()

    private suspend fun connectSidecar(): SshSession =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DIRECT_AGENTS_SSH_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(fixtureKey),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()

    private suspend fun remoteExec(session: SshSession, command: String) = session.exec(command)

    private suspend fun remoteSize(session: SshSession, path: String): Long {
        val result = remoteExec(
            session,
            "if [ -f ${shellQuote(path)} ]; then wc -c < ${shellQuote(path)}; else printf -- -1; fi",
        )
        assertEquals("remote size probe failed for $path: ${result.stderr}", 0, result.exitCode)
        return result.stdout.trim().toLong()
    }

    private suspend fun waitForCommittedRemotePrefix(
        session: SshSession,
        path: String,
        progressWatermark: Long,
    ): Long {
        val deadline = SystemClock.elapsedRealtime() + REMOTE_PROBE_TIMEOUT_MS
        var last = -1L
        var stableReads = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val observed = remoteSize(session, path)
            stableReads = if (observed == last && observed in 1..progressWatermark) {
                stableReads + 1
            } else {
                0
            }
            last = observed
            if (stableReads >= REQUIRED_STABLE_REMOTE_READS) return last
            SystemClock.sleep(100)
        }
        assertTrue(
            "checkpoint never settled to a strict committed prefix below the progress " +
                "watermark; checkpoint=$last progress=$progressWatermark",
            last in 1..progressWatermark && stableReads >= REQUIRED_STABLE_REMOTE_READS,
        )
        return last
    }

    private suspend fun assertRemoteAbsent(session: SshSession, path: String, label: String) {
        assertEquals("$label: $path must be absent", -1L, remoteSize(session, path))
    }

    private suspend fun waitForCapture(
        session: SshSession,
        timeoutMs: Long,
        predicate: (String) -> Boolean,
    ): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = remoteExec(
                session,
                "tmux capture-pane -p -t ${shellQuote(SESSION_NAME)}",
            ).stdout
            if (predicate(last)) return last
            SystemClock.sleep(200)
        }
        val serverTranscript = remoteExec(
            session,
            "if [ -f ${shellQuote(PANE_OUTPUT_PATH)} ]; then " +
                "cat ${shellQuote(PANE_OUTPUT_PATH)}; else printf '<absent>\\n'; fi",
        ).stdout
        preserveTailFailure(
            phase = "03-live-input-failure",
            paneCapture = last,
            serverTranscript = serverTranscript,
        )
        assertTrue(
            "capture predicate timed out; queue=${queueSnapshot()}; capture:\n" +
                Issue1733JourneyContract.xmlSafeFailureText(last),
            predicate(last),
        )
        return last
    }

    /**
     * Wait for the durable queue owner, not the pane transcript, to finish the
     * delivery leg. The deadline is deliberately longer than production's 50s
     * send bound, so the dispatcher has time to mark Sent/prune or defer the row
     * before this journey judges it stuck.
     *
     * Current-main #1739 rule: [createAndroidComposeRule] virtualises app Main,
     * which owns the production paste-ack delay and queue retry backoff. Advance
     * that clock in small steps while retaining this independent hard wall-clock
     * bound; otherwise a first capture just before the fake agent paints its
     * collapsed-paste chip freezes forever at the production 40ms poll delay.
     */
    private suspend fun waitForDurableDeliveryTerminal(
        session: SshSession,
        timeoutMs: Long,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastItem = queueStore().item(queuedItem.id)
        var lastPaneCapture = ""
        var lastPaneOutput = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastItem = queueStore().item(queuedItem.id)
            if (lastItem == null || lastItem.state == OutboundState.Delivered) return
            lastPaneCapture = remoteExec(
                session,
                "tmux capture-pane -p -t ${shellQuote(SESSION_NAME)}",
            ).stdout
            lastPaneOutput = remoteExec(
                session,
                "if [ -f ${shellQuote(PANE_OUTPUT_PATH)} ]; then " +
                    "cat ${shellQuote(PANE_OUTPUT_PATH)}; else printf '<absent>\\n'; fi",
            ).stdout
            compose.mainClock.advanceTimeBy(DELIVERY_MAIN_CLOCK_STEP_MS)
            SystemClock.sleep(DELIVERY_MAIN_CLOCK_STEP_MS)
        }

        // Preserve the authoritative bytes before constructing the XML-safe
        // assertion. These raw files intentionally retain ESC/control bytes.
        writeText("03-delivery-failure-queue.txt", lastItem?.toString() ?: "delivered/pruned")
        writeText("03-delivery-failure-diagnostics.txt", diagnosticSummary().ifEmpty { "<no diagnostics>\n" })
        writeText("03-delivery-failure-pane-capture-raw.txt", lastPaneCapture)
        writeText("03-delivery-failure-pane-output-raw.txt", lastPaneOutput)
        writeText(
            "03-delivery-failure-report.txt",
            Issue1733JourneyContract.xmlSafeFailureText(
                "queue=${lastItem ?: "delivered/pruned"}\n" +
                    "paneCapture:\n$lastPaneCapture\nserverTranscript:\n$lastPaneOutput\n",
            ),
        )
        preserveArtifacts(
            phase = "03-delivery-failure",
            "03-delivery-failure-queue.txt",
            "03-delivery-failure-diagnostics.txt",
            "03-delivery-failure-pane-capture-raw.txt",
            "03-delivery-failure-pane-output-raw.txt",
            "03-delivery-failure-report.txt",
        )
        assertTrue(
            "durable delivery did not reach Sent/pruned within ${timeoutMs}ms; " +
                Issue1733JourneyContract.xmlSafeFailureText(
                    "queue=$lastItem paneCapture=$lastPaneCapture serverTranscript=$lastPaneOutput",
                ),
            lastItem == null || lastItem.state == OutboundState.Delivered,
        )
    }

    private suspend fun waitForPaneOutput(
        session: SshSession,
        timeoutMs: Long,
        predicate: (String) -> Boolean,
    ): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = remoteExec(
                session,
                "if [ -f ${shellQuote(PANE_OUTPUT_PATH)} ]; then " +
                    "cat ${shellQuote(PANE_OUTPUT_PATH)}; fi",
            ).stdout
            if (predicate(last)) return last
            SystemClock.sleep(200)
        }
        val paneCapture = remoteExec(
            session,
            "tmux capture-pane -p -t ${shellQuote(SESSION_NAME)}",
        ).stdout
        preserveTailFailure(
            phase = "03-transcript-failure",
            paneCapture = paneCapture,
            serverTranscript = last,
        )
        assertTrue(
            "server pane-output predicate timed out; queue=${queueSnapshot()}; output:\n" +
                Issue1733JourneyContract.xmlSafeFailureText(last),
            predicate(last),
        )
        return last
    }

    private fun preserveTailFailure(
        phase: String,
        paneCapture: String,
        serverTranscript: String,
    ) {
        val safePhase = phase.replace(Regex("[^a-z0-9-]"), "-")
        val queue = writeText("$safePhase-queue.txt", queueSnapshot())
        val diagnostics = writeText(
            "$safePhase-diagnostics.txt",
            diagnosticSummary().ifEmpty { "<no diagnostics>\n" },
        )
        val rawCapture = writeText("$safePhase-pane-capture-raw.txt", paneCapture)
        val rawTranscript = writeText("$safePhase-server-transcript-raw.txt", serverTranscript)
        val report = writeText(
            "$safePhase-report.txt",
            Issue1733JourneyContract.xmlSafeFailureText(
                "queue=${queueSnapshot()}\npaneCapture:\n$paneCapture\n" +
                    "serverTranscript:\n$serverTranscript\n",
            ),
        )
        preserveArtifacts(
            phase = phase,
            queue.name,
            diagnostics.name,
            rawCapture.name,
            rawTranscript.name,
            report.name,
        )
    }

    private fun writeThroughTerminalSession(text: String) {
        val bytes = text.toByteArray()
        var wrote = false
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.decorView.findTerminalView()?.currentSession?.let {
                it.write(bytes, 0, bytes.size)
                wrote = true
            }
        }
        assertTrue("expected a live TerminalView input session", wrote)
    }

    private fun selectTerminalTabForLiveEvidence() {
        compose.onNodeWithTag(TMUX_TERMINAL_TAB_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()

        var terminalState = "not inspected"
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            compose.activityRule.scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val terminal = decor.findTerminalView()
                terminalState = if (terminal == null) {
                    "missing; decorShown=${decor.isShown} decorBounds=${decor.width}x${decor.height}"
                } else {
                    "shown=${terminal.isShown} attached=${terminal.isAttachedToWindow} " +
                        "bounds=${terminal.width}x${terminal.height} session=${terminal.currentSession != null}"
                }
                ready = terminal?.isShown == true &&
                    terminal.width > 0 && terminal.height > 0 && terminal.currentSession != null
            }
            if (!ready) SystemClock.sleep(100)
        }
        assertTrue(
            "Terminal tab did not expose a capturable live viewport; $terminalState",
            ready,
        )
    }

    private fun visibleTerminalText(): String {
        var text = ""
        compose.activityRule.scenario.onActivity { activity ->
            text = activity.window.decorView.findTerminalView()
                ?.currentSession?.emulator?.screen?.transcriptText.orEmpty()
        }
        return text
    }

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findTerminalView()?.let { return it }
        }
        return null
    }

    private fun captureViewport(name: String, requiredVisibleText: String? = null) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        lateinit var bitmap: Bitmap
        var capturedVisibleScreenText = ""
        compose.activityRule.scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val view = requireNotNull(decor.findTerminalView()) {
                "TerminalView was not found while capturing $name; " +
                    "decorShown=${decor.isShown} decorBounds=${decor.width}x${decor.height}"
            }
            require(view.width > 0 && view.height > 0) {
                "TerminalView has invalid bounds while capturing $name: " +
                    "${view.width}x${view.height}; shown=${view.isShown} " +
                    "attached=${view.isAttachedToWindow} decorShown=${decor.isShown} " +
                    "decorBounds=${decor.width}x${decor.height}"
            }
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
            // Read rows 0..screenRows from the same emulator/View callback that drew the bitmap.
            // transcriptText is forbidden here: it includes scrollback, so a marker scrolled off
            // the captured frame would otherwise satisfy the semantic screenshot oracle.
            capturedVisibleScreenText =
                view.currentSession?.emulator?.screen?.visibleScreenText.orEmpty()
        }
        val artifact = artifactFile("$name-viewport.png")
        try {
            FileOutputStream(artifact).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "failed to encode ${artifact.absolutePath}"
                }
            }
        } finally {
            bitmap.recycle()
        }
        assertTrue(
            "terminal screenshot artifact must exist and be nonempty: ${artifact.absolutePath}",
            artifact.isFile && artifact.length() > 0L,
        )
        assertScreenshotNotBlank(artifact)
        println("ISSUE1733_ARTIFACT ${artifact.absolutePath}")
        if (requiredVisibleText != null) {
            assertTrue(
                "captured terminal viewport must contain $requiredVisibleText; text=\n" +
                    Issue1733JourneyContract.xmlSafeFailureText(capturedVisibleScreenText),
                capturedVisibleScreenText.contains(requiredVisibleText),
            )
        }
        writeText("$name-visible-terminal.txt", capturedVisibleScreenText)
    }

    private fun captureDecorViewport(name: String): File {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        lateinit var captured: Bitmap
        compose.activityRule.scenario.onActivity { activity ->
            val decor = activity.window.decorView
            check(decor.isShown) { "activity decor is not shown" }
            check(decor.width > 0 && decor.height > 0) {
                "activity decor has invalid bounds ${decor.width}x${decor.height}"
            }
            captured = Bitmap.createBitmap(
                decor.width,
                decor.height,
                Bitmap.Config.ARGB_8888,
            ).also { decor.draw(Canvas(it)) }
        }
        val artifact = artifactFile("$name-viewport.png")
        try {
            FileOutputStream(artifact).use { output ->
                check(captured.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "failed to encode ${artifact.absolutePath}"
                }
            }
        } finally {
            captured.recycle()
        }
        assertTrue(
            "full-decor screenshot artifact must exist and be nonempty: ${artifact.absolutePath}",
            artifact.isFile && artifact.length() > 0L,
        )
        assertScreenshotNotBlank(artifact)
        println("ISSUE1733_ARTIFACT ${artifact.absolutePath}")
        return artifact
    }

    private fun writeText(name: String, text: String): File =
        artifactFile(name).also {
            it.writeText(text)
            println("ISSUE1733_ARTIFACT ${it.absolutePath}")
        }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(root, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) { "could not create ${dir.absolutePath}" }
        return File(dir, name)
    }

    private fun preserveArtifacts(phase: String, vararg names: String) {
        val sources = names.map(::artifactFile)
        val destination = "$PRESERVED_DEVICE_ROOT/$artifactRunId/$phase"
        Issue1733ArtifactPreserver.preserve(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            phase = phase,
            sources = sources,
            destination = destination,
        )
        println("ISSUE1733_PRESERVED phase=$phase destination=$destination")
    }

    private fun recordTiming(name: String, value: Long) {
        timings += "$name=$value"
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count += 1
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }

    private fun targetContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open("test_key")
            .bufferedReader().use { it.readText() }

    private fun createImmutableAttachment(context: Context): File {
        val file = File(context.cacheDir, ATTACHMENT_NAME)
        val chunk = ByteArray(64 * 1024) { ((it * 31 + 17) and 0xff).toByte() }
        file.outputStream().use { output ->
            var remaining = UPLOAD_BYTES
            while (remaining > 0) {
                val count = minOf(chunk.size.toLong(), remaining).toInt()
                output.write(chunk, 0, count)
                remaining -= count
            }
        }
        return file
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun seedDockerHost(key: String): Long {
        val context = targetContext()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context,
                db.sshKeyDao(),
                "issue1733-offset-resume-key-${System.currentTimeMillis()}",
                key,
            )
            db.hostDao().insert(
                HostEntity(
                    name = "Issue1733 Offset Resume",
                    hostname = DEFAULT_HOST,
                    port = NETWORK_FAULT_SSH_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
        } finally {
            db.close()
        }
    }

    private suspend fun seedFakeAgentSession(
        key: String,
    ): Issue1733JourneyContract.FakeAgentTmuxIdentity {
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            port = DIRECT_AGENTS_SSH_PORT,
            description = "issue1733 fake-agent durable queue seed",
            command = """
                set -eu
                tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true
                rm -rf ${shellQuote(PromptAttachmentStager.REMOTE_DIRECTORY)}
                rm -f ${shellQuote(PANE_OUTPUT_PATH)}
                tmux new-session -d -s ${shellQuote(SESSION_NAME)} -x 80 -y 40 \
                  ${shellQuote("exec /usr/local/bin/pocketshell-fake-agent")}
                tmux pipe-pane -O -t ${shellQuote(SESSION_NAME)} \
                  ${shellQuote("cat >> $PANE_OUTPUT_PATH")}
                tmux display-message -p -t ${shellQuote(SESSION_NAME)} \
                  '#{session_id}:#{session_created}:#{pane_id}'
            """.trimIndent(),
        )
        return Issue1733JourneyContract.parseFakeAgentTmuxIdentity(result.stdout)
    }

    private suspend fun cleanupRemote(key: String) {
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DIRECT_AGENTS_SSH_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec(
                    "tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true; " +
                        "rm -rf ${shellQuote(PromptAttachmentStager.REMOTE_DIRECTORY)}; " +
                        "rm -f ${shellQuote(PANE_OUTPUT_PATH)}",
                )
            }
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME = "pocketshell.db"
        const val OUTBOUND_QUEUE_PREFS = "outbound_queue"
        const val DEVICE_DIR_NAME = "issue1733-offset-resume"
        const val SESSION_NAME = "issue1733-offset-resume"
        const val PANE_OUTPUT_PATH = "/tmp/issue1733-offset-resume-pane-output"
        const val ATTACHMENT_NAME = "issue1733-immutable.bin"
        const val PROMPT = "issue1733 resumable attachment prompt"
        const val LIVE_INPUT_MARKER = "issue1733-live-input"
        const val FAKE_AGENT_READY = "FAKE-AGENT-READY"
        const val FAKE_AGENT_SUBMITTED = "FAKE-AGENT SUBMITTED: "
        const val UPLOAD_BYTES = 16L * 1024 * 1024
        const val CUT_AFTER_BYTES = 512L * 1024
        const val REQUIRED_STABLE_REMOTE_READS = 8
        const val DELIVERY_MAIN_CLOCK_STEP_MS = 20L
        const val AUTO_RETRY_WINDOW_MS = 5_000L
        const val HOOK_TIMEOUT_MS = 90_000L
        const val SSHD_SNAPSHOT = "ps -o pid,ppid,stat,cmd -u testuser | grep '[s]shd' || true"
        const val PRESERVED_DEVICE_ROOT = "/sdcard/Download/pocketshell-issue1733"
        const val NETWORK_FAULT_ARG = "pocketshellNetworkFaultProofs"
        const val DIRECT_AGENTS_SSH_PORT = 2222
        const val NETWORK_FAULT_SSH_PORT = 2228
        const val TOXIPROXY_API_PORT = 8474
        val CLAIMED_OUTBOUND_STATES = setOf(OutboundState.Uploading, OutboundState.InFlight)
        const val ATTACHING_LABEL = "Attaching…"

        val HOST_ROW_TIMEOUT_MS = if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 25_000L
        val PROGRESS_TIMEOUT_MS = if (TerminalTestTimeouts.isRunningOnCi()) 120_000L else 60_000L
        val REMOTE_PROBE_TIMEOUT_MS = if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
        val QUEUE_RETRYABLE_TIMEOUT_MS = if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 25_000L
        val CONNECTION_LOST_TIMEOUT_MS = if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 25_000L
        val CONNECTED_TIMEOUT_MS = if (TerminalTestTimeouts.isRunningOnCi()) 90_000L else 45_000L
        val RESUME_TIMEOUT_MS = if (TerminalTestTimeouts.isRunningOnCi()) 180_000L else 90_000L
        val DELIVERY_TERMINAL_TIMEOUT_MS = Issue1733JourneyContract.deliveryTerminalTimeoutMs(
            productionSendTimeoutMs = PromptComposerViewModel.SEND_TIMEOUT_MS,
            environmentFloorMs = if (TerminalTestTimeouts.isRunningOnCi()) 120_000L else 90_000L,
        )
        val INPUT_TIMEOUT_MS = if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 25_000L
        val UI_TIMEOUT_MS = if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
