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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * Issue #1613 — "when there's no connection I can't type anything" (reproduce-
 * first, D33 / G10).
 *
 * ## The blocker this proof pins
 *
 * The maintainer reported (screenshot: header **Reconnecting**, terminal
 * **Attaching…**, keyboard UP) that with the link down they cannot type into the
 * composer. The input path itself is NOT gated: [PromptComposerDegradedSendE2eTest]
 * already proves `performTextInput` reaches the draft and Send → queue →
 * exactly-once-on-reconnect all work while `connectionDegraded == true`. But that
 * sibling renders the composer with NO keyboard, so it never exercises the crush
 * the maintainer actually hit — and `performTextInput` bypasses viewport
 * reachability, so it passes even when the field is invisible on-device.
 *
 * The REAL blocker is a LAYOUT crush. Keyboard-up in the resized `ModalBottomSheet`
 * window there is only ~175dp above the keyboard (measured pixel_7, #801). When the
 * link is down the sticky "Connection lost — Send will retry once reconnected."
 * banner ([PromptComposerSheet.kt] ~:1254) wraps to TWO lines and, together with
 * the sticky control row, consumes almost the whole budget — crushing the
 * `weight(1f, fill = false)` draft scroll region ([PromptComposerSheet.kt] ~:964)
 * to a near-zero sliver. The field is focused and accepts input, but it is crushed
 * so small the user sees no field and no typed characters → "I can't type."
 *
 * ## What this proof asserts (the reachability `performTextInput` does NOT)
 *
 * With the EMPTY draft the maintainer had (about to type), the offline banner
 * showing, and a synthetic keyboard up on the measured resized-sheet host, the
 * draft field must keep a USABLE height (not the sub-line crush) AND be fully
 * within the host AND above the keyboard — containment (#657 / F1), never a bare
 * `assertIsDisplayed()`. Both a SHELL pane (`agentKind = null`) and an AGENT pane
 * (`agentKind = ClaudeCode`) are exercised (G2 class coverage). On base the draft
 * crushes below [MIN_DRAFT_HEIGHT_DP] (RED); the #1613 fix keeps it usable (GREEN).
 *
 * ## Determinism (#780 model — no real keyboard, no `assumeTrue`)
 *
 * A synthetic `Type.ime()` inset is dispatched to the decor so `WindowInsets.ime`
 * reports keyboard-up, read back from INSIDE the composition and HARD-asserted to
 * have applied before any geometry is judged (never a skip).
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerOfflineComposeUsableProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val observedImeBottomPx = mutableStateOf(0)
    private val observedNavBottomPx = mutableStateOf(0)
    private val observedStatusTopPx = mutableStateOf(0)

    /**
     * The maintainer's reported state: EMPTY draft (about to type), the link down
     * ([connectionDegraded] = true) and NO queued outbound item — the exact
     * condition under which the standalone "Connection lost — Send will retry once
     * reconnected." banner renders (it is suppressed once a queue banner exists).
     */
    private fun offlineEmptyDraftState(): PromptComposerViewModel.UiState =
        PromptComposerViewModel.UiState(
            draft = "",
            recording = PromptComposerViewModel.RecordingState.Idle,
            connectionDegraded = true,
        )

    @Test
    fun shellPaneComposerTypableWhileOfflineWithKeyboardUp() {
        runOfflineComposeProof(agentKind = null)
    }

    @Test
    fun agentPaneComposerTypableWhileOfflineWithKeyboardUp() {
        runOfflineComposeProof(agentKind = AgentKind.ClaudeCode)
    }

    private fun runOfflineComposeProof(agentKind: AgentKind?) {
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
                    FauxTerminalBackdrop()
                    // RESIZED-sheet host (measured real pixel_7, #801): its height
                    // IS the room above the keyboard, the keyboard sits at its
                    // bottom edge. SheetContent's BoxWithConstraints reads
                    // `maxHeight = HOST_HEIGHT_DP` and subtracts the keyboard
                    // intrusion to land on the genuine ~175dp above the keyboard.
                    Box(
                        modifier = Modifier
                            .width(HOST_WIDTH_DP.dp)
                            .height(HOST_HEIGHT_DP.dp)
                            .testTag(HOST_TAG),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        SheetContent(
                            state = offlineEmptyDraftState(),
                            onClose = {},
                            onDraftChange = {},
                            onMicTap = {},
                            onSend = {},
                            onAttachFiles = {},
                            agentKind = agentKind,
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

        // HARD-assert the synthetic IME inset reached Compose; otherwise we would
        // judge a keyboard-DOWN layout and pass vacuously. Never a skip (#736 / F3).
        val expectedImePx = (IME_HEIGHT_DP * density).toInt()
        assertTrue(
            "Synthetic ime() inset did not reach Compose; cannot validate the " +
                "issue #1613 offline keyboard-up crush. observedImeBottomPx=$imeBottomPx " +
                "(expected ~$expectedImePx).",
            imeBottomPx > 0,
        )

        // The offline banner MUST be present — it is the sticky chrome that crushes
        // the field on base. If it is absent the state is wrong and the proof is
        // meaningless.
        compose.onNodeWithTag(COMPOSER_CONNECTION_LOST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()

        val hostBounds = compose.onNodeWithTag(HOST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val draftBounds = compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val bannerBounds = compose.onNodeWithTag(COMPOSER_CONNECTION_LOST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        val keyboardTopPx = hostBounds.bottom
        val draftHeightDp = draftBounds.height / density

        println(
            "ISSUE1613_OFFLINE agent=$agentKind draftHeightDp=$draftHeightDp " +
                "draftTop=${draftBounds.top} draftBottom=${draftBounds.bottom} " +
                "bannerTop=${bannerBounds.top} " +
                "bannerHeightDp=${bannerBounds.height / density} " +
                "sendBottom=${sendBounds.bottom} keyboardTopPx=$keyboardTopPx " +
                "hostHeightDp=${(hostBounds.bottom - hostBounds.top) / density} density=$density",
        )

        // THE LOAD-BEARING DISCRIMINATOR (#1613, G6): the draft editor must keep at
        // least its healthy one-line height. On base the sticky two-line offline
        // banner + control row consume the tight room above the keyboard and crush
        // the `weight(1f, fill = false)` draft region to ~0 — the editor is
        // compressed to a sub-line sliver / zero (`draftHeightDp` well below
        // [MIN_DRAFT_HEIGHT_DP]), which is the maintainer's "I can't type anything".
        // The #1613 fix moves the offline banner out of the sticky chrome and into
        // the scroll region below the field, so the draft keeps its 24dp editor line
        // (GREEN). This is the assertion that is RED on base / GREEN on the fix.
        assertTrue(
            "Draft editor crushed below one text line while offline + keyboard up " +
                "(the #1613 blocker — the field is an unusable sliver, 'I can't type " +
                "anything'). draftHeightDp=$draftHeightDp minDp=$MIN_DRAFT_HEIGHT_DP " +
                "(draftTop=${draftBounds.top} draftBottom=${draftBounds.bottom})",
            draftHeightDp >= MIN_DRAFT_HEIGHT_DP,
        )

        // CONTAINMENT (#657 / F1): the draft the user must type into, and the Send
        // that queues it, must be fully within the host AND above the keyboard — the
        // reachability `performTextInput` / `assertIsDisplayed()` do NOT check. These
        // confirm the now-usable field + Send are on-screen and reachable (they pass
        // vacuously against the zero-sized crushed base node, which is why the height
        // assertion above is the load-bearing red→green one — G6).
        compose.assertNodeFullyWithinRoot(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
        compose.assertNodeFullyAboveImeOrKeyboard(
            COMPOSER_DRAFT_TAG,
            keyboardTopPx = keyboardTopPx,
            useUnmergedTree = true,
        )
        compose.assertNodeFullyAboveImeOrKeyboard(
            COMPOSER_SEND_ENTER_TAG,
            keyboardTopPx = keyboardTopPx,
            useUnmergedTree = true,
        )
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

    @androidx.compose.runtime.Composable
    private fun FauxTerminalBackdrop() {
        Text(
            text = "alex@pocketshell:~$ tail -f deploy.log\nAttaching…",
            color = PocketShellColors.Text,
        )
    }

    private companion object {
        const val HOST_TAG = "issue1613-offline-sheet-host"

        // Model the maintainer's real keyboard-up state faithfully. On a real
        // pixel_7 the resized sheet leaves ~175dp of room above the keyboard, and
        // keyboard-up the nav bar is COVERED by the keyboard so `WindowInsets.
        // navigationBars` reads 0 (the composer's `navigationBarsPadding()`
        // contributes nothing keyboard-up). We reproduce those EFFECTIVE inputs:
        // NAV = 0 (no nav padding stealing room), a small IME inset so `keyboardUp`
        // is true (the field uses its 24dp keyboard-up min) while the intrusion
        // subtracted from the host is small. `availableAboveKeyboard ≈ HOST - IME`,
        // which we size into the crush→usable band (see below).
        const val HOST_HEIGHT_DP = 188f

        // A narrow content width (the sheet's inner width after its 18dp side
        // paddings + the banner's 12dp padding + the cloud icon) so the
        // "Connection lost — Send will retry once reconnected." banner wraps to the
        // TWO lines the maintainer saw on-device — the tall sticky chrome that, on
        // base, crushes the weighted draft region below one line.
        const val HOST_WIDTH_DP = 270f

        // Small IME (keyboardUp true) with NAV 0. `availableAboveKeyboard ≈ 188-48 =
        // 140dp`. On base the sticky two-line offline banner (~56dp) + control row
        // (~48dp) + bottom padding (~26dp) leave the weighted draft region ~10dp —
        // a sub-line crush (editor → ~0). The #1613 fix moves the banner into the
        // scroll region, so the sticky chrome is just the control row (~74dp) and the
        // draft region is ~66dp — the one-line field is fully visible.
        const val IME_HEIGHT_DP = 48f
        const val NAV_BAR_DP = 0f
        const val STATUS_BAR_DP = 52f

        // The empty draft editor is a single 24dp line keyboard-up. On base the
        // sticky offline banner crushes the weighted draft region so the editor is
        // compressed to ~0 (a sub-line sliver); the fix keeps the full 24dp line.
        // 20dp sits below the healthy 24dp line and well above the crush, so it is
        // decisively red-on-base / green-on-fix while tolerating sub-dp rounding.
        const val MIN_DRAFT_HEIGHT_DP = 20f
    }
}
