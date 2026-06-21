package com.pocketshell.app.projects

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Epic #821 Slice 1 — UI proof for the session-kind picker (maintainer Option
 * B). Drives [SessionKindPickerContent] directly (no SSH / sheet animation).
 *
 * Covers:
 *  - an UNKNOWN (foreign) session shows the "we don't know this session" prompt
 *    and starts with NOTHING selected (no guess) — Save disabled until a pick.
 *  - picking a kind enables Save and emits that kind.
 *  - the CHANGE-kind flow pre-selects the session's current recorded kind.
 *  - the OPTIONAL [suggestedKind] pre-highlights an option (the Option-A hook)
 *    with zero rework — passing non-null pre-selects + marks it "Suggested".
 */
@RunWith(AndroidJUnit4::class)
class SessionKindPickerUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun unknownSessionShowsChoosePromptAndPicksAKind() {
        var picked: SessionAgentKind? = null
        compose.setContent {
            PocketShellTheme {
                SessionKindPickerContent(
                    sessionName = "stray",
                    onCancel = {},
                    onPick = { picked = it },
                    isUnknown = true,
                )
            }
        }

        // Option B: we do NOT guess — we surface the "we don't know" prompt.
        compose.onNodeWithTag(SESSION_KIND_PICKER_TITLE_TAG).assertIsDisplayed()
        compose.onNodeWithText("We don't know what this session is").assertIsDisplayed()
        // Every classifiable kind is offered.
        compose.onNodeWithTag(sessionKindPickerOptionTag(SessionAgentKind.Claude)).assertIsDisplayed()
        compose.onNodeWithTag(sessionKindPickerOptionTag(SessionAgentKind.Codex)).assertIsDisplayed()
        compose.onNodeWithTag(sessionKindPickerOptionTag(SessionAgentKind.OpenCode)).assertIsDisplayed()
        compose.onNodeWithTag(sessionKindPickerOptionTag(SessionAgentKind.Shell)).assertIsDisplayed()

        // No suggestion + unknown -> nothing pre-selected, Save disabled.
        compose.onNodeWithTag(SESSION_KIND_PICKER_SAVE_TAG).assertIsNotEnabled()
        captureScreenshot("issue821-kind-picker-unknown")

        // Pick Codex -> Save enabled -> emits Codex.
        compose.onNodeWithTag(sessionKindPickerOptionTag(SessionAgentKind.Codex)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SESSION_KIND_PICKER_SAVE_TAG).assertIsEnabled()
        compose.onNodeWithTag(SESSION_KIND_PICKER_SAVE_TAG).performClick()
        compose.waitForIdle()
        assertEquals(SessionAgentKind.Codex, picked)
    }

    @Test
    fun changeKindFlowPreselectsCurrentRecordedKind() {
        var picked: SessionAgentKind? = null
        compose.setContent {
            PocketShellTheme {
                SessionKindPickerContent(
                    sessionName = "work",
                    onCancel = {},
                    onPick = { picked = it },
                    isUnknown = false,
                    currentKind = SessionAgentKind.Claude,
                )
            }
        }

        // Change-kind copy, not the unknown prompt.
        compose.onNodeWithText("Change session kind").assertIsDisplayed()
        // The current kind is pre-selected, so Save is immediately enabled and
        // keeping it emits the same kind.
        compose.onNodeWithTag(SESSION_KIND_PICKER_SAVE_TAG).assertIsEnabled()
        captureScreenshot("issue821-kind-picker-change")
        // Re-classify to Shell -> emits Shell.
        compose.onNodeWithTag(sessionKindPickerOptionTag(SessionAgentKind.Shell)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SESSION_KIND_PICKER_SAVE_TAG).performClick()
        compose.waitForIdle()
        assertEquals(SessionAgentKind.Shell, picked)
    }

    @Test
    fun suggestedKindPreHighlightsTheOptionAndPreselectsIt() {
        // The Option-A forward-compat hook: a non-null suggestedKind pre-selects
        // and marks the option "Suggested", with no other change.
        var picked: SessionAgentKind? = null
        compose.setContent {
            PocketShellTheme {
                SessionKindPickerContent(
                    sessionName = "stray",
                    onCancel = {},
                    onPick = { picked = it },
                    isUnknown = true,
                    suggestedKind = SessionAgentKind.OpenCode,
                )
            }
        }

        // Pre-selected -> Save enabled, and the suggested option is labelled.
        compose.onNodeWithTag(SESSION_KIND_PICKER_SAVE_TAG).assertIsEnabled()
        compose.onNodeWithText("Suggested").assertIsDisplayed()
        compose.onNodeWithTag(SESSION_KIND_PICKER_SAVE_TAG).performClick()
        compose.waitForIdle()
        assertEquals(SessionAgentKind.OpenCode, picked)
    }

    @Test
    fun recordedProfileShowsProviderProfileLine() {
        // Issue #858 AC3 — "What is this session?" must surface the recorded
        // non-default profile/provider so a z.ai Claude is distinguishable from
        // a default Claude. When a profile is recorded the sheet renders the
        // "Provider/profile:" line.
        compose.setContent {
            PocketShellTheme {
                SessionKindPickerContent(
                    sessionName = "work",
                    onCancel = {},
                    onPick = {},
                    isUnknown = false,
                    currentKind = SessionAgentKind.Claude,
                    currentProfile = "Claude (Z.AI)",
                )
            }
        }

        compose.onNodeWithTag(SESSION_KIND_PICKER_PROFILE_TAG)
            .assertIsDisplayed()
            .assertTextContains("Provider/profile: Claude (Z.AI)")
    }

    @Test
    fun noRecordedProfileOmitsProviderProfileLine() {
        // Issue #858 AC3 (negative) — a default / legacy session has no recorded
        // profile, so the "Provider/profile:" line must NOT render (the plain
        // kind only, no spurious provider attribution).
        compose.setContent {
            PocketShellTheme {
                SessionKindPickerContent(
                    sessionName = "work",
                    onCancel = {},
                    onPick = {},
                    isUnknown = false,
                    currentKind = SessionAgentKind.Claude,
                    currentProfile = null,
                )
            }
        }

        compose.onNodeWithTag(SESSION_KIND_PICKER_PROFILE_TAG).assertDoesNotExist()
    }

    private fun captureScreenshot(name: String) {
        // Best-effort visual evidence: a content-only Compose test has no real
        // Activity decor window, so PixelCopy-backed captureToImage() can fail
        // on some emulator images — the load-bearing proof is the semantics
        // assertions above. Write into the instrumentation context's media
        // additional-test-output dir so a successful capture is pullable.
        runCatching {
            compose.waitForIdle()
            val instr = InstrumentationRegistry.getInstrumentation()
            // uiAutomation.takeScreenshot grabs the real on-device frame (the
            // ComponentActivity hosting setContent IS on the window decor), so
            // it works where the content-only captureToImage() PixelCopy does
            // not. Falls through to captureToImage if unavailable.
            val bitmap = instr.uiAutomation.takeScreenshot()
                ?: compose.onRoot().captureToImage().asAndroidBitmap()
            val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instr.targetContext)
            val dir = File(mediaRoot, "additional_test_output/issue821-kind-picker")
                .apply { mkdirs() }
            FileOutputStream(File(dir, "$name.png")).use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
