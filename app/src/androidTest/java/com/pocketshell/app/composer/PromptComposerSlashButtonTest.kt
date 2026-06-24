package com.pocketshell.app.composer

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.agentcommands.AgentCommandCatalog
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.test.testArtifactsRoot
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #787: the `/` slash-command button — the single consolidated entry in
 * the composer action row (📎 / `{}` / `/`). It opens the SAME #767
 * autocomplete dropdown (no new picker), seeding a leading `/` so the full
 * catalog appears, and is disabled on shell panes (no agent → empty catalog).
 *
 * These assertions exercise the REAL production [PromptComposerSheet] (per the
 * #657 test-validity rules — no proxy, no stand-in). The button tap + dropdown
 * open + insert are pure-UI and do not depend on the soft IME, so there is NO
 * `assumeTrue` skip on the load-bearing assertions: they HARD-fail on every
 * device, CI included.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerSlashButtonTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

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
    )

    private fun setComposer(vm: PromptComposerViewModel, agentKind: AgentKind?) {
        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        text = "alex@pocketshell:~$ claude\n> ready",
                        color = PocketShellColors.Text,
                    )
                    PromptComposerSheet(
                        onDismiss = {},
                        onSend = { _ -> true },
                        viewModel = vm,
                        agentKind = agentKind,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun slashButtonOpensFullCatalogAndInsertsCommandOnAgentPane() {
        val vm = newViewModel()
        setComposer(vm, AgentKind.ClaudeCode)

        // The `/` button sits in the action row next to 📎 + `{}`, enabled on an
        // agent pane.
        compose.onNodeWithTag(COMPOSER_SLASH_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertIsEnabled()

        // Tapping it seeds a leading `/` and opens the SAME #767 dropdown with
        // the FULL Claude catalog (no filter applied).
        compose.onNodeWithTag(COMPOSER_SLASH_TAG, useUnmergedTree = true).performClick()
        compose.waitForIdle()

        assertTrue(
            "Tapping the / button must seed a leading slash into the draft. " +
                "draft=${vm.uiState.value.draft}",
            vm.uiState.value.draft.startsWith("/"),
        )

        compose.onNodeWithTag(COMPOSER_SLASH_DROPDOWN_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        // Bare `/` -> blank query -> full catalog: both /clear and /compact show.
        compose.onNodeWithTag(composerSlashCommandRowTag("/clear"), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithTag(composerSlashCommandRowTag("/compact"), useUnmergedTree = true)
            .assertIsDisplayed()

        // Picking a row inserts the command into the draft (the reusable #767
        // insert path).
        compose.onNodeWithTag(composerSlashCommandRowTag("/clear"), useUnmergedTree = true)
            .performClick()
        compose.waitForIdle()
        assertTrue(
            "After picking /clear the draft must hold the inserted command. " +
                "draft=${vm.uiState.value.draft}",
            vm.uiState.value.draft.startsWith("/clear"),
        )

        // Sanity: the catalog is the real source of the rows.
        assertTrue(
            AgentCommandCatalog.commandsFor(AgentKind.ClaudeCode).any { it.command == "/clear" },
        )
    }

    @Test
    fun slashButtonDisabledOnShellPane() {
        val vm = newViewModel()
        // A plain shell pane has no detected agent — empty catalog, nothing to
        // show, so the `/` button is present but disabled.
        setComposer(vm, agentKind = null)

        compose.onNodeWithTag(COMPOSER_SLASH_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    /**
     * Captures the two acceptance screenshots from the REAL production composer:
     *  - the action row showing 📎 / `{}` / `/`, and
     *  - the picker (the #767 dropdown) open after tapping `/`.
     * Gradle auto-pulls `additional_test_output/` artifacts off the device.
     */
    @Test
    fun slashButtonActionRowAndPickerScreenshots() {
        val vm = newViewModel()
        setComposer(vm, AgentKind.ClaudeCode)

        val dir = ensureArtifactDir()
        compose.onNodeWithTag(COMPOSER_SLASH_TAG, useUnmergedTree = true).assertIsDisplayed()
        captureFullDevice(File(dir, "issue-787-action-row.png"))

        compose.onNodeWithTag(COMPOSER_SLASH_TAG, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(COMPOSER_SLASH_DROPDOWN_TAG, useUnmergedTree = true).assertIsDisplayed()
        captureFullDevice(File(dir, "issue-787-picker-open.png"))
    }

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-787-slash")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-787 screenshot directory: ${dir.absolutePath}"
        }
        return dir
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write issue-787 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE787_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
