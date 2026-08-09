package com.pocketshell.app.composer

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.insets.SyntheticImeStage
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1622 — the composer sheet's DEAD BAND, measured on the real production
 * `ModalBottomSheet`, in both keyboard states.
 *
 * ## The reported problem
 *
 * The maintainer reported this seven times (#567 -> #615 -> #682 -> #765 ->
 * #790 -> #801 -> #1622) and again on-device on v0.4.42. Measured by pixel scan
 * of his two 1080x2400 screenshots (density 2.625): the distance from the sheet's
 * top edge to the top of the grabber bar is **176 px = 67 dp in BOTH keyboard
 * states**, against Material's designed 22 dp. And keyboard-up the draft field
 * was 54 dp inside a 204 dp sheet — 26 % editor, the rest chrome and emptiness.
 *
 * Three mechanisms, all asserted here:
 *
 *  - **A** — `contentWindowInsets` carried `Top`, and `windowInsetsPadding` is
 *    not position aware, so a sheet floating far below the status bar still paid
 *    the device's full 45 dp top inset as painted nothing.
 *  - **B** — Material's default drag handle spends 48 dp on a 4 dp bar.
 *  - **C** — `SheetContent` capped its body to `maxHeight - (ime - navBars)`
 *    while material3 1.3.2 had **already** IME-padded the sheet container
 *    (`ModalBottomSheetKt$ModalBottomSheet$3`). The keyboard was subtracted
 *    twice, holding the body to ~173 dp of a genuinely available ~485 dp. The
 *    famous "~175 dp is all there is" budget the whole chain divided up was
 *    self-inflicted.
 *
 * ## Why this test had to be written this way (the July false-negative)
 *
 * A previous audit pass declared mechanism A a non-defect after measuring 22 dp
 * on an AVD. It was right about the AVD and wrong about the app: **a modal's
 * window on a plain emulator reports a ~0 top inset**, so mechanism A is
 * invisible there and an emulator-default screenshot passes with the bug fully
 * present. That is the G4/#780 class of proof failure.
 *
 * So this proof does not rely on the device producing the failing state. It
 * mounts the PRODUCTION [ComposerModalBottomSheet] (real Material sheet, its own
 * window, real `contentWindowInsets`, real drag handle, real #1744 anchor
 * policy) and drives the state through [SyntheticImeStage], which dispatches a
 * synthetic status-bar / nav-bar / `Type.ime()` inset set to **every attached
 * app window root including the modal's own**, and HARD-asserts the dispatch
 * landed. No `assumeTrue`, no skip: an environment that cannot reach the state
 * fails.
 *
 * ## What is asserted, and why these are the load-bearing numbers
 *
 *  1. **Chrome** = `sheet surface height - sheet content height`. That is
 *     precisely "everything painted above the first line of composer content",
 *     i.e. mechanisms A + B together, and it is placement-independent (it does
 *     not care where the anchor put the sheet). It must equal
 *     [ComposerDragHandleBlockHeight] — asserted from BOTH sides, so deleting
 *     the grabber to win the number fails too. On base this reads
 *     `48 + statusBarInset` in every state.
 *  2. **Editor ceiling**, keyboard-up, saturated (long draft + Offline banner +
 *     staged attachments): the draft field must reach its designed 220 dp cap.
 *     On base mechanism C holds it to roughly 100 dp in the same state.
 *  3. **The four constraints that have each already cost a regression** —
 *     #1619 (the editor is the weighted element and keeps native caret-follow),
 *     #567/#682/#1744 (Send / mic / attach stay fully above the keyboard,
 *     containment not `assertIsDisplayed`), #1613 (status banners stay in the
 *     bounded region ABOVE the draft), #2057 (attachment tiles stay BELOW it).
 *     Reclaiming space is only a fix if it does not re-break these.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class Issue1622ComposerSheetGeometryProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val stage by lazy { SyntheticImeStage(compose) }

    /** Material's real Surface height, read through the same seam #1744 uses. */
    @Volatile
    private var sheetSurfaceHeightPx: Int = 0

    @Volatile
    private var sheetState: SheetState? = null

    @Volatile
    private var sheetScope: CoroutineScope? = null

    @After
    fun releaseSyntheticInsets() {
        runCatching { stage.dispatch(imeBottomPx = 0, requireExtraWindowRoot = false) }
        runCatching { stage.hideRealImeBestEffort() }
    }

    // ------------------------------------------------------------------
    // Mechanisms A + B — the standing chrome, both keyboard states
    // ------------------------------------------------------------------

    @Test
    fun emptyComposerSpendsOnlyTheGrabberOnChromeKeyboardDown() {
        val geometry = mountAndMeasure(state = emptyDraftState(), keyboardUp = false)
        assertChromeIsGrabberOnly(geometry)
        // Keyboard-down the header is the browse/decide affordance and stays.
        assertTrue(
            "Keyboard-down the composer header must still render. ${geometry.log}",
            nodeExists(COMPOSER_CLOSE_TAG),
        )
        assertControlsReachable(geometry)
    }

    @Test
    fun emptyComposerSpendsOnlyTheGrabberOnChromeKeyboardUp() {
        val geometry = mountAndMeasure(state = emptyDraftState(), keyboardUp = true)
        assertChromeIsGrabberOnly(geometry)
        assertControlsReachable(geometry)
    }

    /**
     * Keyboard-DOWN with a draft long enough that the sheet is taller than half
     * the screen. Material then offers a `PartiallyExpanded` anchor and, per #234,
     * the composer deliberately RESTS there so the terminal stays visible behind
     * it — the tail of the sheet hangs below the screen until the user swipes up.
     *
     * So this case performs that swipe (`SheetState.expand()`, the same anchor the
     * user's drag lands on) when the sheet is resting partially, and HARD-asserts
     * the Expanded anchor either way before judging reachability. It is not an
     * excuse for a failing assertion: on base this sheet sits 111 px past the
     * screen bottom BY DESIGN, and asserting nav-bar clearance on a state the
     * product intends to be off-screen would be asserting the wrong thing.
     *
     * Worth recording: after #1622 this sheet is short enough that Material offers
     * no partial anchor at all here, so the swipe is now a no-op on this device —
     * the reclaimed chrome moved a saturated keyboard-down composer from
     * "hangs off the bottom until you drag it" to "fits". The branch stays because
     * a taller font scale or a shorter screen puts it back over the threshold. The
     * chrome measurement is anchor-independent and is taken in the same run.
     */
    @Test
    fun longDraftComposerSpendsOnlyTheGrabberOnChromeKeyboardDown() {
        val geometry = mountAndMeasure(
            state = longDraftState(),
            keyboardUp = false,
            expandPartialAnchor = true,
        )
        assertChromeIsGrabberOnly(geometry)
        assertControlsReachable(geometry)
    }

    // ------------------------------------------------------------------
    // Mechanism C — the reclaimed room reaches the DRAFT, keyboard-up
    // ------------------------------------------------------------------

    @Test
    fun longDraftEditorReachesItsDesignedCeilingKeyboardUp() {
        val geometry = mountAndMeasure(state = longDraftState(), keyboardUp = true)
        assertChromeIsGrabberOnly(geometry)
        assertEditorReachesDesignedCeiling(geometry)
        assertControlsReachable(geometry)
    }

    /**
     * The saturated keyboard-up state — long draft, Offline banner in the status
     * region, two staged attachments below the field. This is the state every
     * previous round tuned constants against on the phantom ~175 dp budget, so it
     * is where mechanism C bites hardest and where the four regression
     * constraints are most likely to be traded away for room.
     */
    @Test
    fun saturatedComposerKeepsCeilingBannersTilesAndControlsKeyboardUp() {
        val geometry = mountAndMeasure(state = saturatedState(), keyboardUp = true)

        assertChromeIsGrabberOnly(geometry)
        assertEditorReachesDesignedCeiling(geometry)
        assertControlsReachable(geometry)

        // #1613: the Offline banner lives in the bounded status region ABOVE the
        // draft, never in the sticky bottom chrome and never below the editor.
        val banner = boundsHeightAwareOf(COMPOSER_CONNECTION_LOST_TAG)
        val draft = boundsHeightAwareOf(COMPOSER_DRAFT_TAG)
        assertTrue(
            "#1613: the Offline banner must sit ABOVE the draft field. " +
                "bannerBottom=${banner.bottom} draftTop=${draft.top} ${geometry.log}",
            banner.bottom <= draft.top + geometry.slopPx,
        )
        stage.assertFullyAboveKeyboard(
            COMPOSER_CONNECTION_LOST_TAG,
            geometry.modalRoot,
            geometry.keyboardTopPx,
        )

        // #2057: staged tiles stay BELOW the draft field.
        val tiles = boundsHeightAwareOf(COMPOSER_ATTACHMENT_VIEWPORT_TAG)
        assertTrue(
            "#2057: staged attachment tiles must sit BELOW the draft field. " +
                "tilesTop=${tiles.top} draftBottom=${draft.bottom} ${geometry.log}",
            tiles.top >= draft.bottom - geometry.slopPx,
        )
        stage.assertFullyAboveKeyboard(
            COMPOSER_ATTACHMENT_VIEWPORT_TAG,
            geometry.modalRoot,
            geometry.keyboardTopPx,
        )
    }

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    /**
     * Mechanisms A + B. `surface - content` is everything Material paints above
     * the composer's first pixel: the `contentWindowInsets` padding plus the drag
     * handle block. After #1622 the only legitimate occupant is the grabber.
     *
     * Both bounds are load bearing. The upper bound is the fix; the lower bound
     * stops "reclaim" from being achieved by deleting the grabber, which is the
     * one visible cue that swipe-down dismisses.
     */
    private fun assertChromeIsGrabberOnly(geometry: SheetGeometry) {
        val expected = ComposerDragHandleBlockHeight.value
        assertTrue(
            "Issue #1622: the sheet must spend NOTHING above its content except the " +
                "grabber block (${expected}dp). Anything more is the dead band the " +
                "maintainer measured at 67dp: a non-position-aware top safe-area pad " +
                "(mechanism A) and/or Material's 48dp default handle (mechanism B). " +
                "${geometry.log}",
            geometry.chromeDp <= expected + CHROME_SLOP_DP,
        )
        assertTrue(
            "Issue #1622: the grabber must still be painted — it is the only visible " +
                "swipe-down-to-dismiss cue. Reclaiming chrome by deleting it is not the " +
                "fix. ${geometry.log}",
            geometry.chromeDp >= expected - CHROME_SLOP_DP,
        )
        assertTrue(
            "The grabber bar itself must be present in the sheet. ${geometry.log}",
            nodeExists(COMPOSER_DRAG_HANDLE_TAG),
        )
    }

    /**
     * Mechanism C. `ComposerDraftField` binds the editor to `heightIn(max = 220.dp)`
     * (`PromptComposerSheet.kt`), so with a draft far longer than that the field
     * measures exactly its ceiling whenever the room exists. Under the double
     * subtraction the room did not exist and it measured roughly 100 dp in this
     * state, so this is the assertion that fails on base and passes on the fix.
     */
    private fun assertEditorReachesDesignedCeiling(geometry: SheetGeometry) {
        val draftHeightDp = boundsHeightAwareOf(COMPOSER_DRAFT_TAG).heightPx / geometry.density
        assertTrue(
            "Issue #1622: with a long draft the editor must be able to reach its " +
                "designed ${COMPOSER_EDITOR_CEILING_DP}dp ceiling keyboard-up. A smaller " +
                "value means the body is still capped against a keyboard the sheet " +
                "container had already excluded (mechanism C, the double subtraction). " +
                "draftHeightDp=$draftHeightDp ${geometry.log}",
            draftHeightDp >= COMPOSER_EDITOR_CEILING_DP - EDITOR_SLOP_DP,
        )
    }

    /**
     * #567/#682/#1744. Containment in the sheet's OWN Compose root, and fully
     * above the keyboard boundary keyboard-up / the nav bar keyboard-down — never
     * `assertIsDisplayed()`, which a control shoved under the keyboard still
     * satisfies (F2/F3).
     */
    private fun assertControlsReachable(geometry: SheetGeometry) {
        val boundary = if (geometry.keyboardUp) geometry.keyboardTopPx else geometry.navBarTopPx
        listOf(COMPOSER_SEND_ENTER_TAG, COMPOSER_MIC_TAG, COMPOSER_ATTACH_TAG).forEach { tag ->
            if (geometry.keyboardUp) {
                stage.assertFullyAboveKeyboard(tag, geometry.modalRoot, boundary)
            } else {
                stage.assertFullyAboveNavigationBar(tag, geometry.modalRoot, boundary)
            }
        }
        // #1619: the draft field itself must remain reachable, not merely present —
        // a caret the user cannot see is the symptom that issue is about.
        if (geometry.keyboardUp) {
            stage.assertFullyAboveKeyboard(
                COMPOSER_DRAFT_TAG,
                geometry.modalRoot,
                geometry.keyboardTopPx,
            )
        } else {
            stage.assertFullyAboveNavigationBar(
                COMPOSER_DRAFT_TAG,
                geometry.modalRoot,
                geometry.navBarTopPx,
            )
        }
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private fun mountAndMeasure(
        state: PromptComposerViewModel.UiState,
        keyboardUp: Boolean,
        expandPartialAnchor: Boolean = false,
    ): SheetGeometry {
        stage.startEdgeToEdge()
        compose.setContent {
            PocketShellTheme {
                ProductionComposerSheet(state)
            }
        }

        assertTrue(
            "The production composer sheet must mount and settle.",
            stage.awaitStable(SHEET_CONTENT_TAG),
        )
        stage.hideRealImeAndAssertHidden(
            "Issue #1622 synthetic sheet geometry cannot be measured while a real IME is up.",
        )

        val imeBottomPx = if (keyboardUp) stage.imeBottomPx else 0
        stage.dispatch(imeBottomPx = imeBottomPx, requireExtraWindowRoot = true)
        assertTrue(
            "Sheet geometry must settle after the synthetic inset dispatch " +
                "(keyboardUp=$keyboardUp).",
            stage.awaitStable(SHEET_CONTENT_TAG) && stage.awaitStable(COMPOSER_SEND_ENTER_TAG),
        )

        if (expandPartialAnchor) {
            val settled = requireNotNull(sheetState) { "The production sheet state must exist." }
            val scope = requireNotNull(sheetScope) { "The sheet composition scope must exist." }
            // Per #234 a composer taller than half the container RESTS at
            // `PartiallyExpanded` with its tail below the screen, and the user
            // swipes up. Do that swipe when the sheet is actually there. Whether it
            // was or not, the state judged below is HARD-asserted to be the Expanded
            // anchor, so this branch cannot quietly excuse a sheet that failed to
            // reach it.
            if (settled.currentValue == SheetValue.PartiallyExpanded) {
                compose.runOnUiThread { scope.launch { settled.expand() } }
                compose.waitUntil(EXPAND_TIMEOUT_MS) {
                    settled.currentValue == SheetValue.Expanded &&
                        settled.targetValue == SheetValue.Expanded
                }
            }
            assertTrue(
                "Reachability keyboard-down is judged on the Expanded anchor — either the " +
                    "sheet already fits (no #234 partial anchor) or the user's swipe-up " +
                    "must land it there. currentValue=${settled.currentValue} " +
                    "hasPartial=${settled.hasPartiallyExpandedState}",
                settled.currentValue == SheetValue.Expanded,
            )
            assertTrue(
                "Expanded sheet geometry must settle before it is captured.",
                stage.awaitStable(COMPOSER_SEND_ENTER_TAG),
            )
        }

        val modalRoot = stage.owningRootOf(SHEET_CONTENT_TAG)
        val density = stage.density
        val contentHeightPx = boundsHeightAwareOf(SHEET_CONTENT_TAG).heightPx
        val surfaceHeightPx = sheetSurfaceHeightPx
        val chromeDp = (surfaceHeightPx - contentHeightPx) / density
        val geometry = SheetGeometry(
            keyboardUp = keyboardUp,
            density = density,
            chromeDp = chromeDp,
            modalRoot = modalRoot,
            keyboardTopPx = modalRoot.boundsInRoot.bottom - imeBottomPx,
            navBarTopPx = modalRoot.boundsInRoot.bottom - stage.navBarBottomPx,
            slopPx = CHROME_SLOP_DP * density,
            log = "ISSUE1622_SHEET_GEOMETRY keyboardUp=$keyboardUp " +
                "surfaceHeightPx=$surfaceHeightPx contentHeightPx=$contentHeightPx " +
                "chromeDp=$chromeDp expectedChromeDp=${ComposerDragHandleBlockHeight.value} " +
                "imeBottomPx=$imeBottomPx navBarBottomPx=${stage.navBarBottomPx} " +
                "modalRoot=${modalRoot.boundsInRoot} " +
                "content=${boundsHeightAwareOf(SHEET_CONTENT_TAG)} " +
                "draft=${runCatching { boundsHeightAwareOf(COMPOSER_DRAFT_TAG) }.getOrNull()} " +
                "send=${runCatching { boundsHeightAwareOf(COMPOSER_SEND_ENTER_TAG) }.getOrNull()} " +
                "status=${runCatching { boundsHeightAwareOf(COMPOSER_STATUS_VIEWPORT_TAG) }.getOrNull()} " +
                "draftViewport=${runCatching { boundsHeightAwareOf(COMPOSER_DRAFT_VIEWPORT_TAG) }.getOrNull()} " +
                "headerPresent=${nodeExists(COMPOSER_CLOSE_TAG)} " +
                "density=$density",
        )
        // Publish BEFORE any assertion so even a red run carries its evidence.
        Log.i(GEOMETRY_LOG_TAG, geometry.log)

        // The synthetic boundary must be real, or every measurement below is a
        // keyboard-down layout wearing a keyboard-up label (the #780 hard rule).
        if (keyboardUp) {
            assertTrue(
                "The synthetic keyboard must be nonzero for a keyboard-up measurement.",
                imeBottomPx > 0,
            )
        }
        assertTrue(
            "Material's real sheet Surface must have been measured; a zero height means " +
                "the production chrome wrapper never composed. ${geometry.log}",
            surfaceHeightPx > 0,
        )
        assertTrue(
            "The sheet content must have nonzero height. ${geometry.log}",
            contentHeightPx > 0,
        )
        return geometry
    }

    /**
     * The production sheet chrome, not a hand copy of it. [ComposerModalBottomSheet]
     * is the same composable `PromptComposerSheet` delegates to, so
     * `contentWindowInsets`, the drag handle and the #1744 anchor policy are the
     * real ones. Only the Hilt view model is replaced — `SheetContent` is the
     * pure renderer and takes its state as a parameter.
     */
    @Composable
    private fun ProductionComposerSheet(state: PromptComposerViewModel.UiState) {
        // Production default (#234): partial expand is available, and the #1744
        // policy inside the wrapper corrects it when it would put the sheet under
        // the keyboard. Hoisted so a case can drive the user's swipe-to-expand.
        val hoistedState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        val scope = rememberCoroutineScope()
        sheetState = hoistedState
        sheetScope = scope
        ComposerModalBottomSheet(
            onDismissRequest = {},
            sheetState = hoistedState,
            modifier = Modifier.onGloballyPositioned { coordinates ->
                sheetSurfaceHeightPx = coordinates.size.height
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SHEET_CONTENT_TAG),
            ) {
                SheetContent(
                    state = state,
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {},
                    onSend = {},
                    onAttachFiles = {},
                )
            }
        }
    }

    private fun nodeExists(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * Layout geometry that survives clipping. `SemanticsNode.boundsInRoot` is
     * CLIPPED to the root, so a sheet whose tail runs past the window edge would
     * report a short content box and make the chrome measurement lie. `size` is
     * the unclipped layout size, so heights come from it and only ordering /
     * containment comes from the clipped rect.
     */
    private fun boundsHeightAwareOf(tag: String): NodeGeometry {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val bounds = node.boundsInRoot
        return NodeGeometry(
            top = bounds.top,
            bottom = bounds.bottom,
            heightPx = node.size.height.toFloat(),
        )
    }

    private data class NodeGeometry(val top: Float, val bottom: Float, val heightPx: Float)

    private data class SheetGeometry(
        val keyboardUp: Boolean,
        val density: Float,
        val chromeDp: Float,
        val modalRoot: SemanticsNode,
        val keyboardTopPx: Float,
        val navBarTopPx: Float,
        val slopPx: Float,
        val log: String,
    )

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun emptyDraftState() = PromptComposerViewModel.UiState(
        draft = "",
        recording = PromptComposerViewModel.RecordingState.Idle,
    )

    private fun longDraftState() = PromptComposerViewModel.UiState(
        draft = LONG_DRAFT,
        recording = PromptComposerViewModel.RecordingState.Idle,
    )

    private fun saturatedState() = PromptComposerViewModel.UiState(
        draft = LONG_DRAFT,
        recording = PromptComposerViewModel.RecordingState.Idle,
        connectionDegraded = true,
        attachments = listOf(
            PromptComposerViewModel.StagedAttachment(
                remotePath = "/tmp/Screenshot_20260809-101501.png",
                displayName = "Screenshot_20260809-101501.png",
            ),
            PromptComposerViewModel.StagedAttachment(
                remotePath = "/tmp/Screenshot_20260809-101533.png",
                displayName = "Screenshot_20260809-101533.png",
            ),
        ),
    )

    private companion object {
        /**
         * Instrumentation `println` never reaches the Gradle console on a
         * connected run, so the evidence line is logged and read back from logcat.
         */
        const val GEOMETRY_LOG_TAG = "Issue1622Geometry"

        const val SHEET_CONTENT_TAG = "issue1622:composer:sheet-content"

        /**
         * `ComposerDraftField(maxHeight = 220.dp)` in `SheetContent`. Kept as a
         * literal so a silent shrink of the production ceiling is a red test
         * rather than a moving target.
         */
        const val COMPOSER_EDITOR_CEILING_DP = 220f

        /** Generous "this is hung, not slow" ceiling for the expand animation. */
        const val EXPAND_TIMEOUT_MS = 10_000L

        /** Sub-dp rounding wobble on the chrome measurement. */
        const val CHROME_SLOP_DP = 3f

        /**
         * Rounding slack only. A draft far longer than the ceiling makes the
         * editor measure the `heightIn(max)` bound exactly (220.19 dp observed at
         * density 2.625), so no text-line quantisation applies and a wide band
         * would only blunt the assertion: with the double subtraction restored the
         * same state measures 195 dp (saturated) / 190 dp (long draft), so 4 dp of
         * slack still separates fixed from broken by ~21 dp.
         */
        const val EDITOR_SLOP_DP = 4f

        /** Far longer than the 220 dp ceiling at any sane font scale. */
        val LONG_DRAFT = (1..40).joinToString("\n") {
            "line $it — reduce the connector cell width and re-run the journey suite"
        }
    }
}
