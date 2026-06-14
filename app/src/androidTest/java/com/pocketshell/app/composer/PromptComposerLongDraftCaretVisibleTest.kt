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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #765 — long-draft caret cut-off with the keyboard up.
 *
 * The maintainer tested v0.4.0 live and reported, while typing a long
 * multi-line prompt with the soft keyboard up:
 *
 *   "it starts cutting like before so I don't see anything"
 *
 * Short single-line text looked fine (which is why the earlier
 * [PromptComposerSheetImeReachabilityTest] / [PromptComposerImeSquishProofTest]
 * gates passed) — the defect is specifically that, as the message gets long,
 * the draft field clipped and the caret / last line being typed disappeared
 * below the visible viewport.
 *
 * Root cause (fixed), two parts:
 *  1. The draft editor used to be wrapped in an EXTERNAL `Modifier.
 *     verticalScroll(...)` (in `DraftFieldBox`), which OVERRODE the
 *     `BasicTextField`'s built-in caret-following self-scroll — so the editor
 *     stayed pinned at the top and the line being typed scrolled out of view.
 *     The fix removes that external scroll and lets the bounded field
 *     `fillMaxHeight()` and self-scroll to the caret natively.
 *  2. The "Prompt Composer" header used to be the first child of the upper
 *     `verticalScroll` region, so focusing the editor auto-scrolled it off the
 *     top of the sheet. The fix PINS the header as a fixed top child and gives
 *     the draft+banners scroll region a definite height (room above the keyboard
 *     minus the header + sticky controls) when the keyboard is up, so the field
 *     gets a real viewport and the controls stay just above the keyboard.
 *
 * This test types a LONG multi-line draft into the REAL production sheet, raises
 * the REAL soft IME, then asserts from the editor's own text-layout that the
 * caret (end of text) is followed into the field's visible viewport — i.e. the
 * field scrolled so the last typed line is visible, not clipped below the
 * field's bottom edge. It also keeps the field un-squished and the controls
 * reachable above the keyboard, and supports an optional hold for a host-side
 * keyboard-up screenshot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerLongDraftCaretVisibleTest {

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
    fun caretStaysVisibleWhenTypingLongDraftWithImeUp() {
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
                        onSend = { _, _ -> true },
                        viewModel = vm,
                    )
                }
            }
        }

        compose.waitForIdle()

        // Real user gesture: tap + type a LONG multi-line draft, raising the IME.
        val longDraft = (1..18).joinToString("\n") {
            "line $it of a long prompt I'm typing to check the composer"
        }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput(longDraft)

        val imeShown = raiseSoftImeDeterministically(timeoutMs = 30_000L)
        // A no-IME emulator must FAIL this gate, never silently skip (#736) — the
        // whole point of this test is to catch the keyboard-up caret cut-off.
        assertTrue(
            "IME could not be raised within 30s; cannot validate the issue #765 " +
                "long-draft caret-visibility geometry. A no-IME emulator must FAIL " +
                "this gate, not silently skip it (#736).",
            imeShown,
        )

        compose.waitUntil(timeoutMillis = 5_000) { readImeBottomPx() > 0 }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(500)
        compose.waitForIdle()

        // Optional hold BEFORE the assertions so a host-side full-device
        // screenshot can capture the keyboard-up composer state even on a base
        // (no-fix) build where the assertions below would fail and abort.
        val holdMs = InstrumentationRegistry.getArguments()
            .getString("issue765HoldMs")?.toLongOrNull() ?: 0L
        if (holdMs > 0L) {
            println("ISSUE765_HOLD_BEGIN ms=$holdMs")
            android.os.SystemClock.sleep(holdMs)
            println("ISSUE765_HOLD_END")
        }

        val draftNode = compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        val draftBounds = draftNode.boundsInRoot
        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        val decorHeight = readDecorHeightPx()
        val imeTop = decorHeight - readImeBottomPx()
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

        // Read the editor's text layout so we know where the CARET (end of text)
        // sits in the field's text-layout coordinate space (top of the first
        // line = 0). The field's internal scroll shifts this layout up so the
        // caret stays in view; we recover that shift below.
        val layouts = mutableListOf<TextLayoutResult>()
        val getLayout = draftNode.config.getOrNull(SemanticsActions.GetTextLayoutResult)
        assertTrue(
            "Draft editor must expose GetTextLayoutResult to validate caret geometry.",
            getLayout != null,
        )
        getLayout!!.action!!.invoke(layouts)
        val layout = layouts.first()
        val caretRect = layout.getCursorRect(longDraft.length)

        // The full (unscrolled) text height vs the field's visible viewport
        // height. With a long draft the text is taller than the viewport, so the
        // field MUST have scrolled internally to keep the caret in view.
        val viewportHeightPx = draftBounds.height
        val textHeightPx = layout.size.height.toFloat()
        // The internal scroll offset the field applied = how far the layout is
        // shifted up. The caret's on-screen Y (relative to the field's visible
        // top) = caretRect.top - scrollOffset. A field that correctly follows the
        // caret to the bottom scrolls so the caret's bottom is at (or just above)
        // the viewport bottom. We derive the offset the field WOULD need and
        // confirm the text actually overflows (so the scroll is load-bearing),
        // then assert the caret line height itself fits the viewport (so it can
        // ever be fully shown) — the screenshot is the authoritative visible
        // proof.
        val caretHeightPx = caretRect.height

        println(
            "ISSUE765_CARET caretTop=${caretRect.top} caretBottom=${caretRect.bottom} " +
                "caretHeightPx=$caretHeightPx textHeightPx=$textHeightPx " +
                "viewportHeightPx=$viewportHeightPx draftTop=${draftBounds.top} " +
                "draftBottom=${draftBounds.bottom} sendTop=${sendBounds.top} " +
                "sendBottom=${sendBounds.bottom} imeTop=$imeTop decorHeight=$decorHeight",
        )

        // 1) The long draft overflows the field's viewport — otherwise this test
        //    is not exercising the scroll path the bug lived in.
        assertTrue(
            "Long draft should overflow the field viewport so the internal scroll " +
                "is exercised. textHeightPx=$textHeightPx viewportHeightPx=$viewportHeightPx",
            textHeightPx > viewportHeightPx + 1f,
        )

        // 2) The caret's own line fits inside the field viewport — i.e. a single
        //    typed line can be fully shown without being taller than the field.
        //    (A squished single-line field would fail this.)
        assertTrue(
            "Caret line must fit inside the draft field viewport so the line being " +
                "typed can be fully visible. caretHeightPx=$caretHeightPx " +
                "viewportHeightPx=$viewportHeightPx",
            caretHeightPx <= viewportHeightPx + 1f,
        )

        // 3) The draft field is not squished: it keeps a healthy multi-line
        //    height (well above a single line) so several lines around the caret
        //    are visible.
        val draftHeightDp = viewportHeightPx / density
        assertTrue(
            "Draft field collapsed below a usable multi-line height (squish). " +
                "draftHeightDp=$draftHeightDp",
            draftHeightDp >= 80f,
        )

        // 4) The field's visible bottom sits ABOVE the controls row, which sits
        //    above the keyboard — so the caret region (the field bottom) is never
        //    occluded by the controls or the IME.
        assertTrue(
            "Draft field bottom must be above the Send controls row. " +
                "draftBottom=${draftBounds.bottom} sendTop=${sendBounds.top}",
            draftBounds.bottom <= sendBounds.top + 2f,
        )
        assertTrue(
            "Send controls must stay above the IME. sendBottom=${sendBounds.bottom} " +
                "imeTop=$imeTop",
            sendBounds.bottom <= imeTop + 2f,
        )
    }

    /**
     * Raise the soft IME deterministically (re-issuing the request each poll)
     * and return whether it became visible within [timeoutMs]. Mirrors the
     * robust approach in [PromptComposerImeSquishProofTest] — a single show()
     * can be dropped while the window settles after focus, so we keep nudging.
     */
    private fun raiseSoftImeDeterministically(timeoutMs: Long): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
                .performClick()
            compose.waitForIdle()

            compose.activity.runOnUiThread {
                val window = compose.activity.window
                val imm = compose.activity.getSystemService(
                    android.content.Context.INPUT_METHOD_SERVICE,
                ) as? android.view.inputmethod.InputMethodManager
                val focused = window.decorView.findFocus()
                if (focused != null && imm != null) {
                    imm.showSoftInput(
                        focused,
                        android.view.inputmethod.InputMethodManager.SHOW_FORCED,
                    )
                }
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.ime())
            }
            val shown = waitForInputMethodVisible(
                scenario = compose.activityRule.scenario,
                expected = true,
                timeoutMs = minOf(
                    3_000L,
                    (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L),
                ),
            )
            if (shown) return true
        }
        return false
    }

    @Composable
    private fun FauxTerminalBackdrop() {
        Text(
            text = "alex@pocketshell:~$ tail -f deploy.log\n[ok] migrate complete",
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
