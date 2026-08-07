package com.pocketshell.app.composer

import android.net.Uri
import android.os.SystemClock
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connected/Docker E2E for issue #731 (parent audit #657, data-loss path
 * #581).
 *
 * Every existing [PromptAttachmentStager] test drives a `FakeStagingSshSession`
 * (`PromptAttachmentStagerTest`), so the REAL SSH upload path — the proven
 * #581 data-loss path — has never been exercised against a live server. A
 * silent regression in `PromptAttachmentStager.uploadFile`
 * (`PromptAttachmentStager.kt`, the unknown-size branch that drains to a temp
 * file and calls `SshSession.uploadFile`) would lose the user's composer
 * attachment with no test catching it.
 *
 * This test closes that blind spot. It stages a real attachment through the
 * PRODUCTION [PromptAttachmentStager] against the deterministic Docker
 * `agents:2222` fixture and proves the bytes landed on the host by reading
 * them back over a fresh SSH `exec` (an md5 + byte-for-byte base64 read-back),
 * mirroring the capture-pane read-back proof in `SharePasteIntoSessionE2eTest`.
 *
 * The attachment is provided via [Issue731AttachmentProvider] — a test-only
 * [android.content.ContentProvider] registered in the androidTest manifest —
 * whose `query` reports NO size (`OpenableColumns.SIZE` absent). That forces
 * the stager down the unknown-size branch: `drainToTempFile` ->
 * `session.uploadFile(...)`, i.e. the exact production line under guard. The
 * provider's `openFile` streams the real local bytes, so this is an end-to-end
 * real upload over SSH.
 *
 * Wiring: this is a `*DockerTest` connected test under `app/src/androidTest`,
 * so the nightly-extensive suite's phase-1 full connected run
 * (`scripts/nightly-extensive-suite.sh`, `:app:connectedDebugAndroidTest` with
 * only `notClass` exclusions) picks it up automatically. The `agents:2222`
 * fixture it needs is the same one that workflow already starts
 * (`.github/workflows/nightly-extensive.yml` -> "Start Docker fixtures"), so
 * no new fixture is required. The per-push CI journey suite
 * (`scripts/ci-journey-suite.sh`) explicitly includes this class so the
 * production picker-to-Send transfer contract is a per-push release gate.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class)
class AttachmentStagerRealUploadDockerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var sshSession: SshSession? = null
    private var remoteScopeDir: String? = null
    private var cacheDir: File? = null

    private class PickerResultRegistry(
        private val uri: Uri,
    ) : ActivityResultRegistry() {
        var openMultipleDocumentsLaunches: Int = 0
            private set

        override fun <I : Any?, O : Any?> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            check(contract is ActivityResultContracts.OpenMultipleDocuments) {
                "expected production OpenMultipleDocuments contract, got ${contract::class.java.name}"
            }
            openMultipleDocumentsLaunches++
            @Suppress("UNCHECKED_CAST")
            dispatchResult(requestCode, listOf(uri) as O)
        }
    }

    private class HeldExternalResultRegistry : ActivityResultRegistry() {
        var attachmentLaunches = 0
            private set
        var permissionLaunches = 0
            private set

        override fun <I : Any?, O : Any?> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            when (contract) {
                is ActivityResultContracts.OpenMultipleDocuments -> attachmentLaunches++
                is ActivityResultContracts.RequestPermission -> permissionLaunches++
                else -> error("unexpected external contract ${contract::class.java.name}")
            }
            // Deliberately hold the external UI open: no result callback.
        }
    }

    private data class PendingGateFixture(
        val vm: PromptComposerViewModel,
        val queue: InMemoryOutboundQueueStore,
        val row: OutboundItem,
        val visible: androidx.compose.runtime.MutableState<Boolean>,
    )

    @After
    fun teardown() {
        // Best-effort: remove the remote attachment dir we created so re-runs
        // against the shared fixture start clean and don't accumulate files.
        val dir = remoteScopeDir
        val key = readTestKeyOrNull()
        if (dir != null && key != null) {
            runCatching {
                runBlocking {
                    SshConnection.connect(
                        host = DEFAULT_HOST,
                        port = DEFAULT_PORT,
                        user = DEFAULT_USER,
                        key = SshKey.Pem(key),
                        knownHosts = KnownHostsPolicy.AcceptAll,
                        timeoutMs = 10_000,
                    ).getOrNull()?.use { session ->
                        session.exec("rm -rf \"\$HOME/$dir\" 2>/dev/null || true")
                    }
                }
            }
        }
        remoteScopeDir = null
        runCatching { sshSession?.close() }
        sshSession = null
        runCatching { cacheDir?.deleteRecursively() }
        cacheDir = null
    }

    @Test
    fun stagesAttachmentThroughRealUploadFileAndBytesLandOnHost() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // The size-less test provider is registered in the androidTest
        // manifest, so it lives in the TEST APK process and is only
        // resolvable via the instrumentation (test) context's resolver. The
        // production PromptAttachmentStager is resolver-injected, so feeding
        // it this resolver still exercises the real upload code path — only
        // the ContentResolver instance differs.
        val testContext = instrumentation.context
        val key = instrumentation.context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        waitForSshFixtureReady(SshKey.Pem(key))

        val marker = System.currentTimeMillis().toString()

        // The deterministic, non-trivial binary payload the provider serves
        // (1 KiB, every byte value). The provider lives in its OWN process/UID,
        // so we cannot hand it bytes via static state — instead both sides
        // compute the SAME bytes from the shared formula in
        // [Issue731AttachmentProvider.payloadBytes]. We assert the host received
        // THESE bytes, not a truncation or a charset-mangled copy.
        val payloadBytes = Issue731AttachmentProvider.payloadBytes()
        val displayName = "issue731-$marker.bin"
        val expectedMd5 = md5Hex(payloadBytes)

        // The provider's `query` omits SIZE (forcing the uploadFile branch) and
        // returns the display name from the URI's `name` query param.
        val authority = "${testContext.packageName}.issue731attachments"
        val attachmentUri = Uri.parse(
            "content://$authority/attachment?name=${Uri.encode(displayName)}",
        )

        val tmpCache = File.createTempFile("issue731-cache-", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        cacheDir = tmpCache

        // Stage over a REAL SSH session to the Docker fixture.
        val ssh = withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).getOrThrow()
        }
        sshSession = ssh

        val scopeKey = "issue731-$marker"
        // Record the remote dir for cleanup BEFORE staging, derived the same
        // way the production stager derives it, so teardown removes exactly
        // what was created.
        val safeScope = PromptAttachmentStager.safeScopeSegment(scopeKey)
        remoteScopeDir = "${PromptAttachmentStager.REMOTE_DIRECTORY}/$safeScope"

        val stager = PromptAttachmentStager(
            resolver = testContext.contentResolver,
            cacheDir = tmpCache,
        )

        val result = stager.stage(
            session = ssh,
            scopeKey = scopeKey,
            uris = listOf(attachmentUri),
        )

        // 1) The stage must succeed and return exactly one display path.
        val displayPaths = result.getOrThrow()
        assertEquals(
            "expected exactly one staged attachment path, got $displayPaths",
            1,
            displayPaths.size,
        )

        // 2) The remote filename must derive from our display name, which only
        //    happens when describe() produced a name and the stage ran the
        //    unknown-size -> drain-to-temp -> uploadFile branch (the SIZE-less
        //    provider guarantees that branch). The byte read-back below is the
        //    decisive proof the real bytes transferred.
        assertTrue(
            "remote display path must carry the sanitised display name, was " +
                displayPaths.single(),
            displayPaths.single().endsWith(".bin"),
        )

        // 3) The temp drain file must be cleaned up (production deletes it in
        //    the finally block around session.uploadFile).
        val drainDir = File(tmpCache, "prompt-attachments")
        assertTrue(
            "drain temp dir must be empty after a successful upload, " +
                "had ${drainDir.listFiles()?.toList()}",
            drainDir.listFiles().orEmpty().isEmpty(),
        )

        // The returned display path is `~/<remoteDir>/<remoteName>`. Convert it
        // to a $HOME-relative path we can read back over SSH.
        val displayPath = displayPaths.single()
        assertTrue(
            "display path should be tilde-rooted, was $displayPath",
            displayPath.startsWith("~/"),
        )
        val remoteRelative = displayPath.removePrefix("~/")

        // 4) THE PROOF: read the bytes back over a fresh exec and assert the
        //    file exists, has the exact size, and md5-matches the payload we
        //    handed the stager. This is the real-bytes-arrived guard #581 lacked.
        val stat = ssh.exec(
            "stat -c '%s' \"\$HOME/$remoteRelative\" 2>/dev/null || echo MISSING",
        )
        val reportedSize = stat.stdout.trim()
        assertTrue(
            "remote attachment $remoteRelative must exist on the host " +
                "(stat said '$reportedSize', stderr='${stat.stderr.trim()}')",
            reportedSize != "MISSING" && reportedSize.isNotEmpty(),
        )
        assertEquals(
            "remote attachment size must equal the uploaded payload size",
            payloadBytes.size.toString(),
            reportedSize,
        )

        val md5 = ssh.exec(
            "md5sum \"\$HOME/$remoteRelative\" | awk '{print \$1}'",
        )
        assertEquals(
            "remote md5 must match the locally-staged payload md5; " +
                "the real-upload bytes must arrive byte-for-byte",
            expectedMd5,
            md5.stdout.trim(),
        )

        // Byte-for-byte read-back via base64 so we never depend on a charset.
        val b64 = ssh.exec(
            "base64 \"\$HOME/$remoteRelative\" | tr -d '\\n'",
        )
        val downloaded = android.util.Base64.decode(
            b64.stdout.trim(),
            android.util.Base64.DEFAULT,
        )
        assertArrayEquals(
            "remote bytes must be byte-for-byte identical to the staged payload",
            payloadBytes,
            downloaded,
        )

        Unit
    } }

    @Test
    fun attachThenSendUsesOneRealUploadAndExactAttachTimeRemotePath() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val appContext = instrumentation.targetContext
        val key = testContext.assets.open("test_key").bufferedReader().use { it.readText() }
        waitForSshFixtureReady(SshKey.Pem(key))
        val marker = System.currentTimeMillis().toString()
        val payloadBytes = Issue731AttachmentProvider.payloadBytes()
        val displayName = "issue2036-$marker.bin"
        val authority = "${testContext.packageName}.issue731attachments"
        val attachmentUri = Uri.parse(
            "content://$authority/attachment?name=${Uri.encode(displayName)}",
        )
        val tmpCache = File.createTempFile("issue2036-cache-", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        cacheDir = tmpCache
        val ssh = withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).getOrThrow()
        }
        sshSession = ssh
        val scopeKey = "issue2036-$marker"
        val safeScope = PromptAttachmentStager.safeScopeSegment(scopeKey)
        remoteScopeDir = "${PromptAttachmentStager.REMOTE_DIRECTORY}/$safeScope"
        val stager = PromptAttachmentStager(testContext.contentResolver, tmpCache)

        appContext.getSharedPreferences(OutboundAttachmentSidecarStore.PREFS_NAME, 0)
            .edit().clear().commit()
        File(appContext.filesDir, OutboundAttachmentSidecarStore.DIRECTORY_NAME).deleteRecursively()
        val queue = InMemoryOutboundQueueStore()
        val sidecars = OutboundAttachmentSidecarStore(appContext)
        val vm = PromptComposerViewModel(
            audioRecorder = ConnectedNoopMic,
            whisperClientFactory = WhisperClientFactory { error("no transcription") },
            apiKeyStorage = ConnectedNoopVault,
            voiceSettings = ConnectedVoiceSettings,
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = sidecars,
            savedStateHandle = SavedStateHandle(),
        )
        vm.setSendWatchdogTimeoutForTest(null)
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val target = "1/issue2036"
        var attachUploads = 0
        var sendUploads = 0
        var attachTimePathForRetention: String? = null
        vm.setOutboundAttachmentSidecarUploader { refs ->
            sendUploads++
            stager.stage(
                session = ssh,
                scopeKey = scopeKey,
                uris = refs.map { Uri.fromFile(File(it.localPath)) },
                retainedAttachmentNames = {
                    setOfNotNull(attachTimePathForRetention?.substringAfterLast('/'), "upload-ledger")
                },
            ).also { result ->
                if (result.isSuccess) {
                    ssh.exec("printf 'SEND\\n' >> \"\$HOME/${remoteScopeDir}/upload-ledger\"")
                }
            }
        }
        val pickerRegistry = PickerResultRegistry(attachmentUri)
        val pickerOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = pickerRegistry
        }
        val visible = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerOwner) {
                PocketShellTheme {
                    Box(Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                        if (visible.value) {
                            PromptComposerSheet(
                                onDismiss = { visible.value = false },
                                onSend = { request ->
                                    synchronized(sent) { sent += request }
                                    ComposerSendResult.Delivered
                                },
                                composerTargetKey = target,
                                sendTargetSnapshotProvider = {
                                    PromptComposerViewModel.SendTargetSnapshot(sessionKey = target)
                                },
                                onStageAttachments = { uris ->
                                    attachUploads++
                                    stager.stage(ssh, scopeKey, uris).also { result ->
                                        if (result.isSuccess) {
                                            ssh.exec(
                                                "printf 'ATTACH\\n' >> " +
                                                    "\"\$HOME/${remoteScopeDir}/upload-ledger\"",
                                            )
                                        }
                                    }
                                },
                                viewModel = vm,
                            )
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            vm.composerTarget == target &&
                compose.onAllNodesWithTag(COMPOSER_ATTACH_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        // Drive the production UI path: Attach launches OpenMultipleDocuments;
        // the registry returns the provider Uri exactly as a picker result.
        compose.onNodeWithTag(COMPOSER_ATTACH_TAG).assertIsDisplayed().performClick()
        waitUntil("attach-time real upload completes") {
            !vm.isAttachmentJobActiveForTest() && vm.uiState.value.attachments.size == 1
        }
        assertEquals(1, pickerRegistry.openMultipleDocumentsLaunches)
        val attachTimePath = vm.uiState.value.attachments.single().remotePath
        attachTimePathForRetention = attachTimePath
        assertEquals(AttachmentTransferState.RemoteComplete, vm.uiState.value.attachments.single().transferState)
        assertTrue("preview remains thumbnail-only presentation state", vm.uiState.value.attachments.single().previewUri != null)
        compose.onNodeWithTag(COMPOSER_ATTACHMENT_CHIPS_TAG).assertIsDisplayed()

        // The editor remains usable beside the picked tile, and the production
        // Send button performs the handoff without a second transfer.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .assertIsDisplayed()
            .performTextInput("inspect the uploaded file")
        compose.activityRule.scenario.onActivity { activity ->
            val keyboard = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
        SystemClock.sleep(400)
        compose.onNodeWithTag(COMPOSER_ATTACHMENT_CHIPS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).assertIsEnabled().assertIsDisplayed()
        WalkthroughScreenshotArtifacts.capture("issue-2036-picker-tile-draft-send-before-handoff")
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG)
            .assertIsEnabled()
            .performClick()
        waitUntil("production composer emits one send") { synchronized(sent) { sent.size == 1 } }
        val request = synchronized(sent) { sent.single() }

        assertEquals("Attach picker + Send must perform exactly one transfer", 1, attachUploads)
        assertEquals("Remote-complete preview must not enter the send-time uploader", 0, sendUploads)
        assertEquals("Send reuses the exact attach-time host path", listOf(attachTimePath), request.attachments.map { it.remotePath })
        assertTrue("wire payload references the exact attach-time path", request.text.contains(attachTimePath))
        val relativePath = attachTimePath.removePrefix("~/")
        assertEquals(
            "host records only the attach-time upload invocation",
            "ATTACH",
            ssh.exec("cat \"\$HOME/${remoteScopeDir}/upload-ledger\"").stdout.trim(),
        )
        assertEquals(
            "only one payload file exists remotely after Send",
            "1",
            ssh.exec("find \"\$HOME/${remoteScopeDir}\" -type f ! -name upload-ledger | wc -l").stdout.trim(),
        )
        assertEquals(
            md5Hex(payloadBytes),
            ssh.exec("md5sum \"\$HOME/$relativePath\" | awk '{print \$1}'").stdout.trim(),
        )
        vm.clearForTest()
    } }

    @Test
    fun attachmentClickWithPickerHeldOpenPreventsLateAckFromClosingSheet() {
        val registry = HeldExternalResultRegistry()
        val fixture = mountPendingExternalGateComposer(
            registry = registry,
            vault = ConnectedNoopVault,
            enableAttachment = true,
        )

        compose.onNodeWithTag(COMPOSER_ATTACH_TAG).assertIsDisplayed().performClick()
        compose.waitUntil(2_000) { registry.attachmentLaunches == 1 }
        acknowledgeLateAndApplyProductionClose(fixture)

        assertTrue("the picker launch must remain held with no callback", registry.attachmentLaunches == 1)
        assertTrue("Attach intent owns the still-open sheet", fixture.visible.value)
        assertTrue("authoritative delivery still prunes the old row", fixture.queue.itemsFor(fixture.row.sessionKey).isEmpty())
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true).assertIsDisplayed()
        fixture.vm.clearForTest()
    }

    @Test
    fun micClickAtApiKeyGatePreventsLateAckFromClosingSheet() {
        val registry = HeldExternalResultRegistry()
        val fixture = mountPendingExternalGateComposer(
            registry = registry,
            vault = MissingKeyVault,
            enableAttachment = false,
        )

        compose.onNodeWithTag(COMPOSER_MIC_TAG).assertIsDisplayed().performClick()
        compose.waitUntil(2_000) {
            registry.permissionLaunches == 1 ||
                compose.onAllNodesWithText("OpenAI API key").fetchSemanticsNodes().isNotEmpty()
        }
        if (registry.permissionLaunches == 0) {
            compose.onNodeWithText("OpenAI API key").assertIsDisplayed()
        } else {
            assertEquals("denied permission routes to one held request", 1, registry.permissionLaunches)
        }
        assertEquals(PromptComposerViewModel.RecordingState.Idle, fixture.vm.uiState.value.recording)
        acknowledgeLateAndApplyProductionClose(fixture)

        assertTrue("Mic intent owns the sheet while its external gate is open", fixture.visible.value)
        assertTrue("authoritative delivery still prunes the old row", fixture.queue.itemsFor(fixture.row.sessionKey).isEmpty())
        fixture.vm.clearForTest()
    }

    private fun mountPendingExternalGateComposer(
        registry: HeldExternalResultRegistry,
        vault: PromptComposerViewModel.ApiKeyVault,
        enableAttachment: Boolean,
    ): PendingGateFixture {
        val queue = InMemoryOutboundQueueStore()
        val vm = PromptComposerViewModel(
            audioRecorder = ConnectedNoopMic,
            whisperClientFactory = WhisperClientFactory { error("no transcription") },
            apiKeyStorage = vault,
            voiceSettings = ConnectedVoiceSettings,
            outboundQueueStore = queue,
            savedStateHandle = SavedStateHandle(),
        )
        vm.setSendWatchdogTimeoutForTest(null)
        val target = "1/issue2034-external-gate"
        val visible = mutableStateOf(true)
        val sendCallbackCompleted = AtomicBoolean(false)
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                PocketShellTheme {
                    Box(Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                        if (visible.value) {
                            PromptComposerSheet(
                                onDismiss = { visible.value = false },
                                onSend = {
                                    sendCallbackCompleted.set(true)
                                    ComposerSendResult.AuthoritativeAckPending
                                },
                                composerTargetKey = target,
                                sendTargetSnapshotProvider = {
                                    PromptComposerViewModel.SendTargetSnapshot(sessionKey = target)
                                },
                                onStageAttachments = if (enableAttachment) {
                                    { Result.failure(AssertionError("held picker must not stage")) }
                                } else {
                                    null
                                },
                                viewModel = vm,
                            )
                        }
                    }
                }
            }
        }
        compose.waitUntil(5_000) {
            vm.composerTarget == target &&
                vm.outboundSendConsumers.activeGenerationForDispatch() != null
        }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, true).performTextInput("delayed authority")
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, true).performClick()
        compose.waitUntil(5_000) {
            queue.itemsFor(target).singleOrNull()?.state == OutboundState.Queued &&
                !vm.uiState.value.sendInFlight &&
                sendCallbackCompleted.get()
        }
        val queued = queue.itemsFor(target).single()
        val row = requireNotNull(queue.markWireSubmitAttempted(target, queued.id))
        assertEquals(OutboundState.Queued, row.state)
        assertTrue(row.wireSubmitAttempted)
        return PendingGateFixture(vm, queue, row, visible)
    }

    private fun acknowledgeLateAndApplyProductionClose(fixture: PendingGateFixture) {
        var shouldClose = true
        compose.runOnUiThread {
            shouldClose = fixture.vm.acknowledgeLateOutboundDeliveries(listOf(fixture.row))
            if (shouldClose) fixture.visible.value = false
        }
        compose.waitForIdle()
        assertFalse("new external intent must reject the old interaction's close", shouldClose)
    }

    private fun waitUntil(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(50)
        }
        assertTrue("timed out waiting for $label", predicate())
    }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun readTestKeyOrNull(): String? = runCatching {
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
    }.getOrNull()

    private object ConnectedNoopMic : PromptComposerViewModel.MicCapture {
        override fun start() = Unit
        override fun stop(): ByteArray = ByteArray(0)
        override fun currentAmplitude(): Float = 0f
    }

    private object ConnectedNoopVault : PromptComposerViewModel.ApiKeyVault {
        override fun save(key: CharArray) = Unit
        override fun load(): CharArray? = "sk-test".toCharArray()
        override fun clear() = Unit
    }

    private object MissingKeyVault : PromptComposerViewModel.ApiKeyVault {
        override fun save(key: CharArray) = Unit
        override fun load(): CharArray? = null
        override fun clear() = Unit
    }

    private object ConnectedVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
    }
}
