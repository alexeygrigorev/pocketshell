package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.agentcommands.AgentCommandCatalog
import com.pocketshell.app.proof.signals.assertNodeFullyAboveImeOrKeyboard
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #791 — the REDESIGNED `/`-command dropdown, keyboard-up CONTAINMENT
 * proof.
 *
 * ## What this proves (the #791 acceptance)
 *
 * The maintainer reported the slash-command picker reads cluttered and that the
 * rows float awkwardly above the keyboard. #791 redesigns [SlashCommandDropdown]
 * to a clean command-palette row (command token leading, inline `<arg>` hint, a
 * short wrapping description, no duplicate badge). This proof composes the EXACT
 * reported state — the production [SheetContent] with a `/comp` draft and a
 * Claude Code pane, keyboard up — and HARD-asserts, with `boundsInRoot`
 * CONTAINMENT (NOT a bare `assertIsDisplayed()`), that:
 *
 *  - the dropdown filters as you type: the `/compact` row is present and the
 *    non-matching `/clear` row is gone (the `/comp` query),
 *  - the dropdown is fully WITHIN the window root AND fully ABOVE the keyboard —
 *    never occluded by the soft IME (the #767 invariant, re-asserted with
 *    containment for the redesign),
 *  - the Send row also stays above the keyboard (no #765/#755 regression),
 *  - tapping the `/compact` row INSERTS the command into the draft (the #770
 *    reusable insert path).
 *
 * ## Why this is CI-DETERMINISTIC (the #780 / #790 synthetic-inset model)
 *
 * There is NO real soft keyboard. The production [SheetContent] is composed in a
 * FIXED-height [Box] host (so the room-above-keyboard math is a known constant on
 * every AVD) and a SYNTHETIC `Type.ime()` window inset is dispatched to the decor
 * view. Compose's `WindowInsets.ime` mirrors the dispatched value exactly, so the
 * keyboard-up geometry is fully deterministic and local-green implies CI-green.
 * The synthetic inset is HARD-asserted to have applied before any geometry is
 * judged — NO `assumeTrue` / `assumeFalse(isRunningOnCi())` self-skip (F3): if
 * the inset never reached Compose the test FAILS loud rather than passing
 * vacuously on a keyboard-down layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerSlashDropdownImeContainmentProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val observedImeBottomPx = mutableStateOf(0)
    private val observedNavBottomPx = mutableStateOf(0)
    private val observedStatusTopPx = mutableStateOf(0)

    // The draft is the single source of truth the dropdown reacts to. Hold it in
    // mutable state so the tap-to-insert path can update it and we can assert the
    // command landed.
    private val draft = mutableStateOf("/comp")

    private fun slashDraftState(): PromptComposerViewModel.UiState =
        PromptComposerViewModel.UiState(
            draft = draft.value,
            recording = PromptComposerViewModel.RecordingState.Idle,
            attachments = emptyList(),
        )

    @Test
    fun redesignedSlashDropdownFiltersStaysAboveKeyboardAndInserts() {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }

        compose.setContent {
            PocketShellTheme {
                val density = LocalDensity.current
                observedImeBottomPx.value = WindowInsets.ime.getBottom(density)
                observedNavBottomPx.value = WindowInsets.navigationBars.getBottom(density)
                observedStatusTopPx.value = WindowInsets.statusBars.getTop(density)

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
                    Box(
                        modifier = Modifier
                            .width(CONTAINER_WIDTH_DP.dp)
                            .height(CONTAINER_HEIGHT_DP.dp)
                            .testTag(CONTAINER_TAG),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        SheetContent(
                            state = slashDraftState(),
                            onClose = {},
                            onDraftChange = { draft.value = it },
                            onMicTap = {},
                            onSend = {},
                            onAttachFiles = {},
                            // Issue #791: a Claude Code pane — the dropdown filters
                            // the Claude catalog (which has `/compact` and `/clear`).
                            agentKind = AgentKind.ClaudeCode,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        applySyntheticInsets(
            imeBottomPx = (IME_HEIGHT_DP * displayDensity()).toInt(),
            navBarBottomPx = (NAV_BAR_DP * displayDensity()).toInt(),
            statusBarTopPx = (STATUS_BAR_DP * displayDensity()).toInt(),
        )
        compose.waitForIdle()

        val density = displayDensity()
        val imeBottomPx = observedImeBottomPx.value
        val navBottomPx = observedNavBottomPx.value

        // HARD-assert the synthetic IME inset reached Compose (#780 / F3). Without
        // it we would measure a keyboard-DOWN layout and the containment checks
        // would pass vacuously. Never a skip.
        val expectedImePx = (IME_HEIGHT_DP * density).toInt()
        assertTrue(
            "Synthetic ime() inset did not reach Compose; cannot validate the " +
                "issue #791 keyboard-up dropdown geometry. " +
                "observedImeBottomPx=$imeBottomPx (expected ~$expectedImePx).",
            imeBottomPx > 0,
        )

        // Filter: `/comp` shows `/compact`, hides `/clear`.
        val compactRowTag = composerSlashCommandRowTag("/compact")
        compose.onNodeWithTag(compactRowTag, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(
            composerSlashCommandRowTag("/clear"),
            useUnmergedTree = true,
        ).assertIsNotDisplayed()

        val containerBounds = compose.onNodeWithTag(CONTAINER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val keyboardIntrusionPx = (imeBottomPx - navBottomPx).coerceAtLeast(0)
        val imeTopPx = containerBounds.bottom - keyboardIntrusionPx

        // CONTAINMENT (F2/F3): the redesigned dropdown is fully within the window
        // AND fully above the keyboard — reachable, not occluded. Uses the
        // boundsInRoot containment helpers, NOT a bare assertIsDisplayed().
        compose.assertNodeFullyWithinRoot(
            tag = COMPOSER_SLASH_DROPDOWN_TAG,
            useUnmergedTree = true,
        )
        compose.assertNodeFullyAboveImeOrKeyboard(
            tag = COMPOSER_SLASH_DROPDOWN_TAG,
            keyboardTopPx = imeTopPx,
            useUnmergedTree = true,
        )
        // The matching command row itself must be reachable above the keyboard.
        compose.assertNodeFullyAboveImeOrKeyboard(
            tag = compactRowTag,
            keyboardTopPx = imeTopPx,
            useUnmergedTree = true,
        )
        // Don't regress #765/#755: Send still above the keyboard with the dropdown
        // open.
        compose.assertNodeFullyAboveImeOrKeyboard(
            tag = COMPOSER_SEND_ENTER_TAG,
            keyboardTopPx = imeTopPx,
            useUnmergedTree = true,
        )

        // Tap the /compact row — the draft must now hold the inserted command.
        compose.onNodeWithTag(compactRowTag, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertTrue(
            "After tapping /compact the draft must hold the inserted command. " +
                "draft=${draft.value}",
            draft.value.startsWith("/compact"),
        )
        // Sanity: /compact + /clear are in the Claude catalog (the source of the
        // filter).
        val claude = AgentCommandCatalog.commandsFor(AgentKind.ClaudeCode)
        assertTrue(claude.any { it.command == "/compact" })
        assertTrue(claude.any { it.command == "/clear" })
    }

    private fun applySyntheticInsets(
        imeBottomPx: Int,
        navBarBottomPx: Int,
        statusBarTopPx: Int,
    ) {
        compose.activityRule.scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
                .setInsets(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, 0, navBarBottomPx),
                )
                .setInsets(
                    WindowInsetsCompat.Type.statusBars(),
                    Insets.of(0, statusBarTopPx, 0, 0),
                )
                .setInsets(
                    WindowInsetsCompat.Type.systemBars(),
                    Insets.of(0, statusBarTopPx, 0, navBarBottomPx),
                )
                .build()
            ViewCompat.dispatchApplyWindowInsets(decor, insets)
        }
    }

    private fun displayDensity(): Float =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

    private companion object {
        const val CONTAINER_TAG = "issue791-composer-host"

        // Fixed synthetic host geometry (DP) — deterministic on every AVD. Same
        // proportions as the #780/#790 proofs so the keyboard-up math is identical.
        const val CONTAINER_HEIGHT_DP = 740f
        const val CONTAINER_WIDTH_DP = 392f

        // Synthetic system insets (a realistic ~300dp soft keyboard).
        const val IME_HEIGHT_DP = 300f
        const val NAV_BAR_DP = 24f
        const val STATUS_BAR_DP = 28f
    }
}
