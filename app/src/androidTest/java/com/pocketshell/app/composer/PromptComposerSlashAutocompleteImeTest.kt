package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.pocketshell.app.agentcommands.AgentCommandCatalog
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #767 (composer redesign PR3): the `/`-triggered inline command
 * autocomplete dropdown in the real production [PromptComposerSheet].
 *
 * Raises the REAL soft IME by typing `/comp` into the draft and asserts, on the
 * keyboard-up screen, that:
 *  - the dropdown is displayed and its bottom edge stays at or above the IME top
 *    (never occluded — it rides the composer's inset-anchored column, above the
 *    field, which is above the keyboard), and the Send row stays above the IME
 *    too (no #765/#755 regression),
 *  - filtering works — `/comp` shows the `/compact` row and hides a
 *    non-matching command (`/clear`),
 *  - tapping a row inserts the command into the field (the field now holds the
 *    picked command — the reusable insert path #770 also uses).
 *
 * The mandatory full-device keyboard-up screenshot in the issue thread is the
 * visual acceptance (#641); this test is the regression guard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerSlashAutocompleteImeTest {

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

    @Test
    fun slashTriggersFilteredDropdownAboveKeyboardAndInsertsCommand() {
        val vm = newViewModel()
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }

        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    FauxTerminalBackdrop()
                    PromptComposerSheet(
                        onDismiss = {},
                        onSend = { _ -> true },
                        viewModel = vm,
                        // Issue #767: a Claude Code pane — the dropdown filters
                        // the Claude catalog.
                        agentKind = AgentKind.ClaudeCode,
                    )
                }
            }
        }
        compose.waitForIdle()

        // Type `/comp` into the draft, which raises the soft IME and opens the
        // dropdown filtered to commands matching "comp".
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput("/comp")

        val imeShown = waitForInputMethodVisible(
            scenario = compose.activityRule.scenario,
            expected = true,
            timeoutMs = 30_000L,
        )
        assumeTrue(
            "IME not available on this emulator; cannot validate issue #767 dropdown geometry",
            imeShown,
        )
        compose.waitUntil(timeoutMillis = 5_000) { readImeBottomPx() > 0 }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(400)
        compose.waitForIdle()

        // The Claude `/compact` row must be present and matching `/comp`; the
        // non-matching `/clear` row must be filtered OUT.
        val compactRowTag = composerSlashCommandRowTag("/compact")
        compose.onNodeWithTag(compactRowTag, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(
            composerSlashCommandRowTag("/clear"),
            useUnmergedTree = true,
        ).assertIsNotDisplayed()

        val dropdownBounds = compose.onNodeWithTag(COMPOSER_SLASH_DROPDOWN_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val imeTop = readDecorHeightPx() - readImeBottomPx()

        println(
            "ISSUE767_DROPDOWN_GEOMETRY dropdownTop=${dropdownBounds.top} " +
                "dropdownBottom=${dropdownBounds.bottom} sendTop=${sendBounds.top} " +
                "sendBottom=${sendBounds.bottom} imeTop=$imeTop",
        )

        // The dropdown must be fully above the keyboard (never occluded) — it
        // sits above the field, which is above the IME.
        assertTrue(
            "Dropdown must stay above the IME. dropdownBottom=${dropdownBounds.bottom} imeTop=$imeTop",
            dropdownBounds.bottom <= imeTop + 2f,
        )
        assertTrue(
            "Dropdown top must be on-screen above the IME. dropdownTop=${dropdownBounds.top} imeTop=$imeTop",
            dropdownBounds.top in 0f..imeTop.toFloat(),
        )
        // Don't regress #765/#755: Send still above the keyboard.
        assertTrue(
            "Send must stay above the IME with the dropdown open. sendBottom=${sendBounds.bottom} imeTop=$imeTop",
            sendBounds.bottom <= imeTop + 2f,
        )

        // Optional hold (off by default) for a host-side full-device screenshot.
        val holdMs = InstrumentationRegistry.getArguments()
            .getString("issue767HoldMs")?.toLongOrNull() ?: 0L
        if (holdMs > 0L) {
            println("ISSUE767_HOLD_BEGIN ms=$holdMs")
            android.os.SystemClock.sleep(holdMs)
            println("ISSUE767_HOLD_END")
        }

        // Tap the /compact row — the field must now hold the inserted command.
        // `/compact` takes an optional argument, so the insert lands `/compact `
        // (trailing space, caret after it).
        compose.onNodeWithTag(compactRowTag, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertTrue(
            "After tapping /compact the draft must hold the inserted command. draft=${vm.uiState.value.draft}",
            vm.uiState.value.draft.startsWith("/compact"),
        )
        // Sanity: /compact is in the Claude catalog (the catalog is the source).
        assertTrue(
            AgentCommandCatalog.commandsFor(AgentKind.ClaudeCode).any { it.command == "/compact" },
        )
    }

    @Composable
    private fun FauxTerminalBackdrop() {
        Text(
            text = "alex@pocketshell:~$ claude\n> ready",
            color = PocketShellColors.Text,
        )
    }

    private fun readImeBottomPx(): Int {
        var result = 0
        compose.activityRule.scenario.onActivity { activity ->
            val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
            result = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        }
        return result
    }

    private fun readDecorHeightPx(): Int {
        var result = 0
        compose.activityRule.scenario.onActivity { activity ->
            result = activity.window.decorView.height
        }
        return result
    }
}
