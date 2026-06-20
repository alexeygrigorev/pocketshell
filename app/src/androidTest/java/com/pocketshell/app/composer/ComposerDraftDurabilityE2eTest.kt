package com.pocketshell.app.composer

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #832 (CHANGES-REQUESTED follow-up) — end-to-end, on-device proof that the
 * **production** durable per-session draft store actually restores the visible
 * composer text on a real session switch.
 *
 * ## Why this test exists (the reviewer's blocking finding)
 *
 * [PromptComposerViewModelTest] covers the store contract at the JVM layer, and
 * [PromptComposerDiscardE2eTest] drives the real composer UI — but BOTH use the
 * in-memory double ([InMemoryComposerDraftStore]) / the no-op
 * [DisabledComposerDraftStore], never the production
 * [SharedPrefsComposerDraftStore] that Hilt actually binds in `VoiceModule`.
 * The issue body itself documents that the *previous* draft chain shipped inert
 * end-to-end because the wiring was never exercised on-device. This test closes
 * that gap: it constructs the ViewModel with the EXACT production store Hilt
 * provides (`SharedPrefsComposerDraftStore(applicationContext)` —
 * `provideComposerDraftStore` is a pass-through `store -> store`, so this is the
 * same object the production graph injects), drives the real composer body
 * through a session switch, and asserts the **visible composer text** — not just
 * VM state — round-trips through real `SharedPreferences`.
 *
 * Each `@Test` wipes the `composer_drafts` prefs file in [Before] so the run is
 * deterministic and isolated from a prior run on the same emulator.
 *
 * The `sendRequests` collector is started on a Main-dispatcher scope (the proven
 * pattern from [PromptComposerDiscardE2eTest]) so the single-consumer
 * `Channel.receiveAsFlow()` is drained by exactly one collector for the test.
 *
 * ## AC2 attachment-refs note
 *
 * The maintainer's reported case is a FAILED SEND. On that path
 * [PromptComposerViewModel.restoreFailedSend] folds the staged attachment paths
 * INTO the restored draft text (#694), so the intended attachment refs are part
 * of the durable string this test round-trips: [failedSendDraftWithAttachmentRef]
 * proves the attachment path survives switch-away-and-back as visible composer
 * text. Live in-flight attachment TILES (not yet sent) are session-local
 * transient state and are intentionally not carried across a switch — but the
 * failed-send refs the AC names ARE preserved, because they are already in the
 * draft string.
 */
@RunWith(AndroidJUnit4::class)
class ComposerDraftDurabilityE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var productionStore: SharedPrefsComposerDraftStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Start from a clean prefs file so a previous run on this emulator cannot
        // leak a stored draft into this assertion.
        context.applicationContext
            .getSharedPreferences("composer_drafts", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        // The EXACT object Hilt's provideComposerDraftStore binds (it is a
        // pass-through over this same SharedPreferences-backed implementation).
        productionStore = SharedPrefsComposerDraftStore(context)
    }

    @After
    fun tearDown() {
        collectorScope.cancel()
    }

    private class TestMicCapture : PromptComposerViewModel.MicCapture {
        override fun start() {}
        override fun stop(): ByteArray = ByteArray(0)
        override fun currentAmplitude(): Float = 0f
    }

    private class TestVault : PromptComposerViewModel.ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
    }

    private class TestVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
    }

    /**
     * Production ViewModel wired to the REAL [SharedPrefsComposerDraftStore]
     * (the same store Hilt binds), not the in-memory double. The mic / Whisper
     * / vault collaborators are test doubles because they are irrelevant to the
     * draft-durability path under test (no audio is recorded in this journey).
     */
    private fun newViewModel(): PromptComposerViewModel = PromptComposerViewModel(
        audioRecorder = TestMicCapture(),
        whisperClientFactory = WhisperClientFactory {
            object : WhisperClient {
                override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                    Result.success("")
            }
        },
        apiKeyStorage = TestVault(),
        voiceSettings = TestVoiceSettings(),
        composerDraftStore = productionStore,
    )

    private fun renderComposer(
        vm: PromptComposerViewModel,
        onAttachFiles: (() -> Unit)? = null,
    ) {
        compose.activityRule.scenario.onActivity { activity ->
            val dark = PocketShellColors.Background.toArgb()
            activity.window.decorView.setBackgroundColor(dark)
            @Suppress("DEPRECATION")
            activity.window.statusBarColor = dark
            @Suppress("DEPRECATION")
            activity.window.navigationBarColor = dark
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activity.window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        compose.setContent {
            PocketShellTheme {
                val visible by remember { mutableStateOf(true) }
                if (visible) {
                    val state by vm.uiState.collectAsState()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PocketShellColors.Surface)
                            .statusBarsPadding()
                            .navigationBarsPadding(),
                    ) {
                        SheetContent(
                            state = state,
                            onClose = {},
                            onDraftChange = vm::onDraftChange,
                            onMicTap = {},
                            onSend = { withEnter -> vm.requestSend(withEnter) },
                            onDiscard = vm::discardDraft,
                            onAttachFiles = onAttachFiles,
                        )
                    }
                }
            }
        }
    }

    private fun awaitComposerComposed() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(COMPOSER_DRAFT_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Issue #832 AC1/AC4 — the maintainer's exact dogfood scenario, end-to-end
     * over the production [SharedPrefsComposerDraftStore]: a failed send keeps the
     * draft in session A, the user switches to session B (field MUST be empty —
     * no bleed, no resurrection), then returns to A and the **visible composer
     * text** is the restored draft, loaded back out of real SharedPreferences.
     *
     * RED on the pre-#832 base: the old `onComposerTargetChanged` discarded the
     * outgoing draft on the switch, so the field was still empty on return —
     * this assertion fails. GREEN with the fix: the per-session store reloads it.
     */
    @Test
    fun failedSendDraftRestoredAsVisibleTextOnSwitchBack() {
        val vm = newViewModel()
        collectorScope.launch {
            vm.sendRequests.collect { request -> vm.restoreFailedSend(request) }
        }
        vm.onComposerTargetChanged("1/sessionA")
        renderComposer(vm)
        awaitComposerComposed()

        // Type a draft in session A and trigger a (failing) send → the composer
        // keeps the draft and stamps the "Not sent" banner.
        val draftA = "draft kept in session A"
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput(draftA)
        compose.waitForIdle()
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.error?.contains("Not sent") == true
        }
        compose.waitForIdle()
        // The kept draft is visible AND persisted in the REAL store under A.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertTextContains(draftA, substring = true)
        assertEquals(draftA, productionStore.load("1/sessionA"))
        WalkthroughScreenshotArtifacts.capture("issue-832-01-sessionA-not-sent-kept")

        // Switch the composer to session B → the field MUST be empty (no bleed).
        compose.runOnUiThread { vm.onComposerTargetChanged("1/sessionB") }
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.draft.isEmpty() && vm.uiState.value.error == null
        }
        compose.waitForIdle()
        compose.onAllNodesWithText(draftA, substring = true)
            .fetchSemanticsNodes().also { assertEquals(0, it.size) }
        WalkthroughScreenshotArtifacts.capture("issue-832-02-sessionB-empty-no-bleed")

        // Switch BACK to session A → the kept draft is restored as visible text,
        // round-tripped out of real SharedPreferences.
        compose.runOnUiThread { vm.onComposerTargetChanged("1/sessionA") }
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.draft == draftA
        }
        compose.waitForIdle()
        // The LOAD-BEARING assertion: the user sees their draft restored.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertTextContains(draftA, substring = true)
        assertEquals(draftA, vm.uiState.value.draft)
        WalkthroughScreenshotArtifacts.capture("issue-832-03-sessionA-restored")
    }

    /**
     * Issue #832 AC2 — after a failed attachment-send the draft INCLUDING the
     * intended attachment ref is discoverably restored. On the failed-send path
     * the staged attachment path is folded into the restored draft text (#694),
     * so it travels with the durable per-session string. This proves the
     * attachment ref survives switch-away-and-back as visible composer text via
     * the production store.
     */
    @Test
    fun failedSendDraftWithAttachmentRefRestoredOnSwitchBack() {
        val vm = newViewModel()
        val attachPath = "~/.pocketshell/attachments/host-1/20260620-shot.png"
        collectorScope.launch {
            vm.sendRequests.collect { request -> vm.restoreFailedSend(request) }
        }
        vm.onComposerTargetChanged("1/sessionA")
        renderComposer(
            vm,
            onAttachFiles = {
                vm.attachFiles(count = 1) { Result.success(listOf(attachPath)) }
            },
        )
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(COMPOSER_ATTACH_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        // Attach a file + type text, then trigger a failing send. restoreFailedSend
        // folds the attachment path into the restored draft.
        compose.onNodeWithTag(COMPOSER_ATTACH_TAG).assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.attachments.isNotEmpty() }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput("review this")
        compose.waitForIdle()
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.error?.contains("Not sent") == true
        }
        compose.waitForIdle()
        // The restored draft now contains the attachment ref; it is persisted.
        val keptA = vm.uiState.value.draft
        assert(keptA.contains(attachPath)) {
            "expected restored draft to fold in the attachment ref, was: $keptA"
        }
        assertEquals(keptA, productionStore.load("1/sessionA"))

        // Switch away (empty) and back (the attachment-bearing draft is restored).
        compose.runOnUiThread { vm.onComposerTargetChanged("1/sessionB") }
        compose.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.draft.isEmpty() }
        compose.runOnUiThread { vm.onComposerTargetChanged("1/sessionA") }
        compose.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.draft == keptA }
        compose.waitForIdle()
        // The attachment ref is visible in the composer again, via the real store.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertTextContains(attachPath, substring = true)
        WalkthroughScreenshotArtifacts.capture("issue-832-04-attachment-ref-restored")
    }
}
