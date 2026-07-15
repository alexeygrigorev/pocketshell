package com.pocketshell.app.composer

import android.graphics.Color as AndroidColor
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
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
 * Issue #1613/#1619 — offline composing and bounded status-stack geometry.
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
 * With an overflowing long draft, the standalone offline banner, and a synthetic
 * keyboard up on the measured resized-sheet host, the draft must retain a complete
 * caret line while the Amber status stays fully above it. A separate multi-status
 * case proves the status region scrolls within its 48dp cap instead of expanding.
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
     * The link is down with no queued outbound item — the exact condition under
     * which the standalone offline banner renders (it is suppressed once a queue
     * banner exists). The long draft makes native caret-follow load-bearing.
     */
    private fun offlineLongDraftState(multiStatus: Boolean): PromptComposerViewModel.UiState =
        PromptComposerViewModel.UiState(
            draft = LONG_DRAFT,
            recording = PromptComposerViewModel.RecordingState.Idle,
            connectionDegraded = true,
            error = if (multiStatus) "Not sent. Keep editing or discard the draft." else null,
        )

    @Test
    fun shellPaneComposerTypableWhileOfflineWithKeyboardUp() {
        runOfflineComposeProof(agentKind = null, multiStatus = false)
    }

    @Test
    fun agentPaneComposerTypableWhileOfflineWithKeyboardUp() {
        runOfflineComposeProof(agentKind = AgentKind.ClaudeCode, multiStatus = false)
    }

    @Test
    fun multipleStatusesScrollWithoutTakingDraftCaretLine() {
        runOfflineComposeProof(agentKind = null, multiStatus = true)
    }

    private fun runOfflineComposeProof(agentKind: AgentKind?, multiStatus: Boolean) {
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
                            state = offlineLongDraftState(multiStatus),
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
        compose.onNodeWithText(OFFLINE_COPY, useUnmergedTree = true).assertExists()

        if (multiStatus) {
            val initialStatus = compose.onNodeWithTag(
                COMPOSER_STATUS_VIEWPORT_TAG,
                useUnmergedTree = true,
            ).getUnclippedBoundsInRoot()
            val initialBanner = compose.onNodeWithTag(
                COMPOSER_CONNECTION_LOST_TAG,
                useUnmergedTree = true,
            ).getUnclippedBoundsInRoot()
            assertTrue(
                "Multi-status proof must begin with offline below the bounded viewport, " +
                    "making scroll load-bearing. banner=$initialBanner status=$initialStatus",
                initialBanner.bottom > initialStatus.bottom + 1.dp,
            )
            compose.onNodeWithTag(COMPOSER_CONNECTION_LOST_TAG, useUnmergedTree = true)
                .performScrollTo()
            compose.waitForIdle()
        }

        val hostBounds = compose.onNodeWithTag(HOST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val draftBounds = compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val bannerBounds = compose.onNodeWithTag(COMPOSER_CONNECTION_LOST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val statusViewportBounds = compose.onNodeWithTag(
            COMPOSER_STATUS_VIEWPORT_TAG,
            useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()
        val draftViewportBounds = compose.onNodeWithTag(
            COMPOSER_DRAFT_VIEWPORT_TAG,
            useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()
        val bannerUnclippedBounds = compose.onNodeWithTag(
            COMPOSER_CONNECTION_LOST_TAG,
            useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()

        val draftNode = compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        val getLayout = draftNode.config.getOrNull(SemanticsActions.GetTextLayoutResult)
        assertTrue("Offline draft must expose its real text layout.", getLayout != null)
        getLayout!!.action!!.invoke(layouts)
        val layout = layouts.single()
        val caretRect = layout.getCursorRect(LONG_DRAFT.length)

        val keyboardTopPx = hostBounds.bottom
        val draftHeightDp = draftBounds.height / density

        println(
            "ISSUE1613_OFFLINE agent=$agentKind draftHeightDp=$draftHeightDp " +
                "draftTop=${draftBounds.top} draftBottom=${draftBounds.bottom} " +
                "bannerTop=${bannerBounds.top} " +
                "bannerHeightDp=${bannerBounds.height / density} " +
                "statusViewport=$statusViewportBounds draftViewport=$draftViewportBounds " +
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
            "Offline long text must overflow the editor so native caret-follow is exercised. " +
                "textHeight=${layout.size.height} draftHeight=${draftBounds.height}",
            layout.size.height > draftBounds.height + 1f,
        )
        assertTrue(
            "Offline draft must retain one complete editable caret line. " +
                "caretHeight=${caretRect.height} draftHeight=${draftBounds.height}",
            caretRect.height <= draftBounds.height + 1f,
        )
        assertTrue(
            "Offline banner must be fully contained in the bounded status viewport. " +
                "banner=$bannerUnclippedBounds status=$statusViewportBounds",
            bannerUnclippedBounds.top >= statusViewportBounds.top - 1.dp &&
                bannerUnclippedBounds.bottom <= statusViewportBounds.bottom + 1.dp,
        )
        assertTrue(
            "Keyboard-up status region must remain capped at 48dp. status=$statusViewportBounds",
            statusViewportBounds.bottom - statusViewportBounds.top <= 48.dp + 1.dp,
        )
        assertTrue(
            "Offline banner/status must stay above the long draft. " +
                "bannerBottom=${bannerUnclippedBounds.bottom} draftTop=${draftViewportBounds.top}",
            bannerUnclippedBounds.bottom <= draftViewportBounds.top + 1.dp,
        )

        assertAmberBannerPixel()

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

    private fun assertAmberBannerPixel() {
        val bitmap = compose.onNodeWithTag(
            COMPOSER_CONNECTION_LOST_TAG,
            useUnmergedTree = true,
        ).captureToImage().asAndroidBitmap()
        try {
            val insetPx = (6f * displayDensity()).toInt().coerceAtLeast(1)
            val actual = bitmap.getPixel(
                (bitmap.width - insetPx).coerceAtLeast(0),
                bitmap.height / 2,
            )
            val expected = PocketShellColors.Amber.copy(alpha = 0.12f)
                .compositeOver(PocketShellColors.Surface)
                .toArgb()
            fun delta(channel: (Int) -> Int): Int =
                kotlin.math.abs(channel(actual) - channel(expected))
            val maxDelta = maxOf(
                delta(AndroidColor::red),
                delta(AndroidColor::green),
                delta(AndroidColor::blue),
            )
            assertTrue(
                "Offline banner background must be Amber@12% over Surface. " +
                    "actual=${Integer.toHexString(actual)} expected=${Integer.toHexString(expected)} " +
                    "maxChannelDelta=$maxDelta",
                maxDelta <= 5,
            )
        } finally {
            bitmap.recycle()
        }
    }

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
        const val HOST_HEIGHT_DP = 470f

        // A narrow content width (the sheet's inner width after its 18dp side
        // paddings + the banner's 12dp padding + the cloud icon) so the
        // "Connection lost — Send will retry once reconnected." banner wraps to the
        // TWO lines the maintainer saw on-device — the tall sticky chrome that, on
        // base, crushes the weighted draft region below one line.
        const val HOST_WIDTH_DP = 360f

        // Small IME (keyboardUp true) with NAV 0. `availableAboveKeyboard ≈ 188-48 =
        // 140dp`. On base the sticky two-line offline banner (~56dp) + control row
        // (~48dp) + bottom padding (~26dp) leave the weighted draft region ~10dp —
        // a sub-line crush (editor → ~0). The #1613 fix moves the banner into the
        // scroll region, so the sticky chrome is just the control row (~74dp) and the
        // draft region is ~66dp — the one-line field is fully visible.
        const val IME_HEIGHT_DP = 295f
        const val NAV_BAR_DP = 0f
        const val STATUS_BAR_DP = 52f

        const val OFFLINE_COPY = "Offline — prompts will be queued and sent on reconnect."
        val LONG_DRAFT = (1..18).joinToString("\n") {
            "offline line $it; sentinel caret queued safely"
        }
    }
}
