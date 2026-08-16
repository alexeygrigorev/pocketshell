package com.pocketshell.app.composer

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.insets.dispatchSyntheticWindowInsets
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.app.proof.signals.assertNodeFullyAboveImeOrKeyboard
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.proof.signals.waitForComposeLayoutStable
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2057 — the staged attachment tiles must render BELOW the
 * `Compose prompt…` draft field, between the editor and the bottom controls row
 * (📎 · `{}` · `/` · Send · mic).
 *
 * ## The maintainer's report
 *
 * On a real device the tiles rendered ABOVE the editor, between the
 * "Prompt Composer" header and the field. That header only renders while the
 * soft keyboard is DOWN, so the reported state is the keyboard-down composer
 * with at least one attachment staged. The tiles moved there in `3eeea505`
 * (#1619, "Fix long-draft composer caret visibility") as a side effect of
 * hoisting all status chrome into a bounded scroll region above a weighted
 * editor — not as a design decision about attachments.
 *
 * ## What this proof pins (and how it goes RED on the reported bug)
 *
 * Every method asserts the ORDER of three real production nodes:
 *
 *     draft viewport bottom   <=  attachment strip top
 *     attachment strip bottom <=  controls-row top (min of attach / Send / mic)
 *
 * With the tiles back in the #1619 status region — i.e. the tree the maintainer
 * reported — the strip sits ABOVE the editor, so `stripTop >= draftBottom` fails
 * immediately. That is the load-bearing assertion and it is red on the unfixed
 * tree. The second half is load-bearing in the other direction: it fails if the
 * strip is ever pushed past the sticky controls row.
 *
 * ## Why the move cannot regress #567 / #682 / #1619 / #1744
 *
 * Dropping a 64dp tile strip below a weighted editor is exactly how the sticky
 * controls get pushed under the keyboard (a Column measures its NON-weighted
 * children first, so the strip would claim its height before the controls row is
 * measured). Production therefore puts the editor AND the strip inside one
 * `BoxWithConstraints` that is the single weighted child of the sheet body, and
 * caps the strip at `flexibleRoom - editorFloor - gap`. These methods assert the
 * consequences, keyboard-up:
 *
 *  - CONTAINMENT (#657 / F1 — never a bare `assertIsDisplayed()`) of the draft,
 *    the tiles, Send, attach and mic above the keyboard;
 *  - the editor keeps a COMPLETE caret line with a long draft and tiles staged
 *    (#1619) — this is what fails if the editor floor ever drifts away from the
 *    real draft-box geometry;
 *  - the saturated composer (Offline banner + long draft + tiles) keeps the
 *    banner ABOVE the editor (#1613 — only the tiles moved) with every control
 *    still reachable (#1744);
 *  - a wrapping stack of tiles stays inside its bounded, internally scrolling
 *    strip instead of growing without limit.
 *
 * ## Determinism (the #780 model — no real keyboard, no `assumeTrue`)
 *
 * The keyboard-up methods dispatch a synthetic `Type.ime()` inset to the decor
 * and HARD-assert (never skip) that Compose observed it before any geometry is
 * judged, so a keyboard-DOWN layout can never pass vacuously. The host is a
 * fixed-size Box modelling the measured resized pixel_7 sheet window, so the
 * geometry is identical on a dev-box AVD and on CI swiftshader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class Issue2057AttachmentTilesBelowDraftProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val observedImeBottomPx = mutableStateOf(0)
    private val observedNavBottomPx = mutableStateOf(0)

    // -----------------------------------------------------------------------
    // 1. The maintainer's REPORTED state: keyboard DOWN (the "Prompt Composer"
    //    header is visible), empty draft, attachments staged — on the REAL
    //    production `PromptComposerSheet` modal, staged through the production
    //    `PromptComposerViewModel.seedAttachment` API.
    // -----------------------------------------------------------------------

    @Test
    fun keyboardDownEmptyDraftRendersTilesBelowFieldAndAboveControls() {
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
                        onSend = { _ -> ComposerSendResult.Delivered },
                        // A non-null staging callback is the production
                        // availability contract that enables the 📎 control.
                        onStageAttachments = { uris -> Result.success(uris.map { it.toString() }) },
                        viewModel = vm,
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.runOnUiThread {
            vm.seedAttachment(ATTACHMENT_A)
            vm.seedAttachment(ATTACHMENT_B)
        }
        compose.waitUntil(WAIT_MS) { vm.uiState.value.attachments.size == 2 }
        compose.waitForIdle()

        assertTrue(
            "The reported state is the keyboard-DOWN composer: the 'Prompt " +
                "Composer' header must be present, otherwise this is not the state " +
                "the maintainer screenshotted.",
            compose.nodeCount(COMPOSER_CLOSE_TAG) == 1,
        )
        assertTrue("Draft must be EMPTY for this case.", vm.uiState.value.draft.isEmpty())

        val file = WalkthroughScreenshotArtifacts.capture(
            "issue2057-composer-attachments-below-field-keyboard-down",
        )
        println("ISSUE2057_SCREENSHOT ${file.absolutePath}")

        val geometry = readGeometry(label = "KEYBOARD_DOWN_EMPTY_DRAFT")
        assertTilesBelowFieldAndAboveControls(geometry)

        // Containment inside the modal's OWN window root (the sheet lives in a
        // dialog window) — never a bare assertIsDisplayed().
        listOf(
            COMPOSER_DRAFT_TAG,
            COMPOSER_ATTACHMENT_CHIPS_TAG,
            COMPOSER_ATTACH_TAG,
            COMPOSER_SEND_ENTER_TAG,
            COMPOSER_MIC_TAG,
        ).forEach { tag ->
            compose.assertNodeFullyWithinRoot(tag, useUnmergedTree = true)
        }

        // Keyboard-DOWN there is room for the tiles at full size, so both staged
        // tiles are individually laid out (and reachable) inside the strip.
        listOf(ATTACHMENT_A, ATTACHMENT_B).forEach { path ->
            val tile = compose
                .onNodeWithTag(composerAttachmentChipTestTag(path), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Staged tile '$path' must be laid out inside the strip below the " +
                    "field. tile=$tile strip=${geometry.strip}",
                tile.height > 0f && tile.top >= geometry.draft.bottom - geometry.slopPx,
            )
            compose.assertNodeFullyWithinRoot(
                composerAttachmentRemoveTestTag(path),
                useUnmergedTree = true,
            )
        }
    }

    /**
     * Same real production modal, but the user TAPS the field so the device's
     * own soft keyboard is requested — the reviewer's full-device
     * "attachments staged + keyboard up" artifact.
     *
     * The load-bearing assertions here hold in BOTH keyboard states, and the
     * screenshot is captured unconditionally, so this method never self-skips
     * and never depends on the CI swiftshader AVD raising a real IME (the exact
     * keyboard-up GEOMETRY is owned by the deterministic synthetic-inset methods
     * below, per the #780 model).
     */
    @Test
    fun realSoftKeyboardCaptureKeepsTilesBelowFieldAndSendReachable() {
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
                        onSend = { _ -> ComposerSendResult.Delivered },
                        onStageAttachments = { uris -> Result.success(uris.map { it.toString() }) },
                        viewModel = vm,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            vm.seedAttachment(ATTACHMENT_A)
            vm.seedAttachment(ATTACHMENT_B)
        }
        compose.waitUntil(WAIT_MS) { vm.uiState.value.attachments.size == 2 }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput("look at these two")
        compose.waitUntil(WAIT_MS) { vm.uiState.value.draft == "look at these two" }
        compose.waitForIdle()
        // Best effort: let the device's own IME actually appear and the sheet
        // finish lifting before the capture, so the artifact really shows the
        // keyboard-up state. Bounded and NON-asserting — on an AVD that cannot
        // raise a real IME this simply times out and the (keyboard-state
        // agnostic) assertions below still run.
        val realImeVisible = waitForRealImeBestEffort()
        waitForComposeLayoutStable(compose, COMPOSER_SEND_ENTER_TAG)
        println("ISSUE2057_REAL_IME_VISIBLE $realImeVisible")

        val file = WalkthroughScreenshotArtifacts.capture(
            "issue2057-composer-attachments-below-field-keyboard-up",
        )
        println("ISSUE2057_SCREENSHOT ${file.absolutePath}")

        val geometry = readGeometry(label = "REAL_KEYBOARD")
        assertTilesBelowFieldAndAboveControls(geometry)
        listOf(
            COMPOSER_DRAFT_TAG,
            COMPOSER_ATTACHMENT_CHIPS_TAG,
            COMPOSER_ATTACH_TAG,
            COMPOSER_SEND_ENTER_TAG,
            COMPOSER_MIC_TAG,
        ).forEach { tag ->
            compose.assertNodeFullyWithinRoot(tag, useUnmergedTree = true)
        }
    }

    @After
    fun hideSoftKeyboard() {
        runCatching {
            compose.activityRule.scenario.onActivity { activity ->
                WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    .hide(WindowInsetsCompat.Type.ime())
            }
        }
    }

    // -----------------------------------------------------------------------
    // 2. Keyboard UP, empty draft + attachments: order preserved and every
    //    control still fully above the keyboard.
    // -----------------------------------------------------------------------

    @Test
    fun syntheticImeEmptyDraftKeepsTilesBelowFieldAndControlsAboveKeyboard() {
        val geometry = runResizedSheetProof(
            label = "IME_EMPTY_DRAFT",
            state = uiState(draft = "", attachmentCount = 2),
        )
        assertTilesBelowFieldAndAboveControls(geometry)
        assertEverythingContainedAboveKeyboard(geometry)
        val file = WalkthroughScreenshotArtifacts.capture(
            "issue2057-composer-attachments-below-field-synthetic-ime",
        )
        println("ISSUE2057_SCREENSHOT ${file.absolutePath}")
    }

    // -----------------------------------------------------------------------
    // 3. #1619 long-draft caret visibility must NOT regress with the tiles
    //    below the field: the editor keeps a complete caret line and the text
    //    genuinely overflows it, so native caret-follow is exercised.
    // -----------------------------------------------------------------------

    @Test
    fun syntheticImeLongDraftWithAttachmentsKeepsCaretLineAndTileOrder() {
        val geometry = runResizedSheetProof(
            label = "IME_LONG_DRAFT",
            state = uiState(draft = LONG_DRAFT, attachmentCount = 2),
        )
        assertTilesBelowFieldAndAboveControls(geometry)
        assertEverythingContainedAboveKeyboard(geometry)

        val caret = readCaretGeometry(LONG_DRAFT)
        assertTrue(
            "The long draft must overflow the editor viewport, otherwise the " +
                "caret-follow path #1619 fixed is not exercised at all. " +
                "textHeightPx=${caret.textHeightPx} editorHeightPx=${caret.editorHeightPx}",
            caret.textHeightPx > caret.editorHeightPx + 1f,
        )
        assertCompleteCaretLine(caret)
    }

    // -----------------------------------------------------------------------
    // 4. #1744 saturated composer WITH attachments staged: the offline banner
    //    stays in the status region ABOVE the editor (#1613 — only the tiles
    //    moved) and every control stays reachable above the keyboard.
    // -----------------------------------------------------------------------

    @Test
    fun syntheticImeSaturatedComposerWithAttachmentsKeepsBannerAboveAndControlsReachable() {
        val geometry = runResizedSheetProof(
            label = "IME_SATURATED",
            state = uiState(
                draft = LONG_DRAFT,
                attachmentCount = 2,
                connectionDegraded = true,
            ),
        )
        assertTilesBelowFieldAndAboveControls(geometry)
        assertEverythingContainedAboveKeyboard(geometry)

        val banner = compose.onNodeWithTag(COMPOSER_CONNECTION_LOST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "#1613: the offline / connection-lost banner must STAY in the bounded " +
                "status region ABOVE the editor — only the attachment tiles move. " +
                "bannerBottom=${banner.bottom} draftTop=${geometry.draft.top}",
            banner.bottom <= geometry.draft.top + geometry.slopPx,
        )
        compose.assertNodeFullyAboveImeOrKeyboard(
            COMPOSER_CONNECTION_LOST_TAG,
            keyboardTopPx = geometry.keyboardTopPx,
            useUnmergedTree = true,
        )

        // The saturated keyboard-up budget is the case the editor floor exists
        // for: the strip must yield, never the editor's caret line.
        assertCompleteCaretLine(readCaretGeometry(LONG_DRAFT))
    }

    // -----------------------------------------------------------------------
    // 5. Many attachments (enough tiles to wrap several rows): the strip stays
    //    BOUNDED and scrolls internally instead of pushing the controls row
    //    under the keyboard, and the editor keeps a usable line.
    // -----------------------------------------------------------------------

    @Test
    fun syntheticImeManyAttachmentsStayInBoundedStripAndKeepControlsReachable() {
        val shortDraft = "have a look at these"
        val geometry = runResizedSheetProof(
            label = "IME_MANY_ATTACHMENTS",
            state = uiState(draft = shortDraft, attachmentCount = MANY_ATTACHMENTS),
        )
        assertTilesBelowFieldAndAboveControls(geometry)
        assertEverythingContainedAboveKeyboard(geometry)

        val stripHeightDp = geometry.strip.height / geometry.density
        val unwrappedRowsDp = MANY_ATTACHMENTS * (TILE_SIZE_DP + TILE_GAP_DP)
        println(
            "ISSUE2057_MANY stripHeightDp=$stripHeightDp attachments=$MANY_ATTACHMENTS " +
                "capDp=$ATTACHMENT_STRIP_IME_CAP_DP",
        )
        assertTrue(
            "$MANY_ATTACHMENTS tiles must WRAP past what the strip can show, " +
                "otherwise the bounded/scrolling path is not exercised. " +
                "unwrappedRowsDp=$unwrappedRowsDp capDp=$ATTACHMENT_STRIP_IME_CAP_DP",
            unwrappedRowsDp > ATTACHMENT_STRIP_IME_CAP_DP,
        )
        assertTrue(
            "The attachment strip must stay within its keyboard-up bound so a stack " +
                "of tiles can never push the sticky controls under the keyboard. " +
                "stripHeightDp=$stripHeightDp capDp=$ATTACHMENT_STRIP_IME_CAP_DP",
            stripHeightDp <= ATTACHMENT_STRIP_IME_CAP_DP + SLOP_DP,
        )
        assertCompleteCaretLine(readCaretGeometry(shortDraft))

        // The bounded strip — not the sheet body — owns the overflow scroll, so
        // the tiles that do not fit stay reachable.
        val stripViewport = compose
            .onNodeWithTag(COMPOSER_ATTACHMENT_VIEWPORT_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
        val scrollRange = stripViewport.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        assertTrue(
            "The bounded attachment strip must expose a vertical scroll range so " +
                "the wrapped tiles beyond its cap remain reachable. range=$scrollRange",
            scrollRange != null && scrollRange.maxValue() > 0f,
        )
    }

    // -----------------------------------------------------------------------
    // Shared harness
    // -----------------------------------------------------------------------

    private data class Geometry(
        val host: Rect,
        val draft: Rect,
        val strip: Rect,
        val controlsTopPx: Float,
        val keyboardTopPx: Float,
        val density: Float,
    ) {
        val slopPx: Float get() = SLOP_DP * density
    }

    private data class CaretGeometry(
        val caretHeightPx: Float,
        val editorHeightPx: Float,
        val textHeightPx: Float,
    )

    /**
     * Renders the PRODUCTION [SheetContent] inside a fixed host modelling the
     * measured resized pixel_7 sheet window (the keyboard sits at the host's
     * bottom edge), dispatches the synthetic `Type.ime()` inset, HARD-asserts it
     * applied, and returns the geometry.
     */
    private fun runResizedSheetProof(
        label: String,
        state: PromptComposerViewModel.UiState,
    ): Geometry {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }
        compose.setContent {
            PocketShellTheme {
                val density = LocalDensity.current
                observedImeBottomPx.value = WindowInsets.ime.getBottom(density)
                observedNavBottomPx.value = WindowInsets.navigationBars.getBottom(density)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    FauxTerminalBackdrop()
                    Box(
                        modifier = Modifier
                            .width(HOST_WIDTH_DP.dp)
                            .height(HOST_HEIGHT_DP.dp)
                            .testTag(HOST_TAG),
                        contentAlignment = Alignment.TopCenter,
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
        }
        compose.waitForIdle()

        applySyntheticInsets(
            imeBottomPx = (IME_HEIGHT_DP * displayDensity()).toInt(),
            navBarBottomPx = (NAV_BAR_DP * displayDensity()).toInt(),
            statusBarTopPx = (STATUS_BAR_DP * displayDensity()).toInt(),
        )
        compose.waitForIdle()

        // HARD assertion, never a skip (#736 / F3): without the inset we would be
        // judging a keyboard-DOWN layout and every containment check below would
        // pass vacuously.
        assertTrue(
            "Synthetic ime() inset did not reach Compose; the keyboard-up geometry " +
                "for issue #2057 cannot be validated. observedImeBottomPx=" +
                "${observedImeBottomPx.value} " +
                "(expected ~${(IME_HEIGHT_DP * displayDensity()).toInt()}).",
            observedImeBottomPx.value > 0,
        )
        assertTrue(
            "Keyboard-UP must hide the 'Prompt Composer' header (#801); its presence " +
                "means the synthetic inset did not take effect.",
            compose.nodeCount(COMPOSER_CLOSE_TAG) == 0,
        )
        return readGeometry(label)
    }

    private fun readGeometry(label: String): Geometry {
        val density = displayDensity()
        // The keyboard-down modal case has no synthetic host: the sheet owns its
        // own window and the keyboard boundary is not part of that case.
        val host = if (compose.nodeCount(HOST_TAG) == 1) {
            compose.onNodeWithTag(HOST_TAG, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
        } else {
            Rect.Zero
        }
        val draft = compose.onNodeWithTag(COMPOSER_DRAFT_VIEWPORT_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val strip = compose.onNodeWithTag(COMPOSER_ATTACHMENT_CHIPS_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val attach = compose.onNodeWithTag(COMPOSER_ATTACH_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val mic = compose.onNodeWithTag(COMPOSER_MIC_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val controlsTopPx = minOf(attach.top, send.top, mic.top)

        println(
            "ISSUE2057_$label host=$host draft=$draft strip=$strip attach=$attach " +
                "send=$send mic=$mic controlsTop=$controlsTopPx density=$density",
        )
        return Geometry(
            host = host,
            draft = draft,
            strip = strip,
            controlsTopPx = controlsTopPx,
            keyboardTopPx = host.bottom,
            density = density,
        )
    }

    /**
     * THE load-bearing assertion pair for issue #2057. The first half is red on
     * the unfixed tree (tiles hoisted into the status region above the editor);
     * the second is red if the strip ever escapes past the sticky controls.
     */
    private fun assertTilesBelowFieldAndAboveControls(geometry: Geometry) {
        assertTrue(
            "Issue #2057: the staged attachment tiles must render BELOW the " +
                "'Compose prompt…' field. stripTop=${geometry.strip.top} " +
                "draftBottom=${geometry.draft.bottom} draftTop=${geometry.draft.top}",
            geometry.strip.top >= geometry.draft.bottom - geometry.slopPx,
        )
        assertTrue(
            "Issue #2057: the staged attachment tiles must render ABOVE the bottom " +
                "controls row (📎 / {} / / / Send / mic). " +
                "stripBottom=${geometry.strip.bottom} controlsTop=${geometry.controlsTopPx}",
            geometry.strip.bottom <= geometry.controlsTopPx + geometry.slopPx,
        )
        assertTrue(
            "The attachment strip must have a real laid-out size, otherwise the " +
                "ordering assertions above are vacuous. strip=${geometry.strip}",
            geometry.strip.height > 0f && geometry.strip.width > 0f,
        )
    }

    private fun assertEverythingContainedAboveKeyboard(geometry: Geometry) {
        compose.assertNodeFullyWithinRoot(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
        listOf(
            COMPOSER_DRAFT_TAG,
            COMPOSER_ATTACHMENT_CHIPS_TAG,
            COMPOSER_ATTACH_TAG,
            COMPOSER_SEND_ENTER_TAG,
            COMPOSER_MIC_TAG,
        ).forEach { tag ->
            compose.assertNodeFullyAboveImeOrKeyboard(
                tag,
                keyboardTopPx = geometry.keyboardTopPx,
                useUnmergedTree = true,
            )
        }
    }

    /** Reads the real editor text layout so caret geometry is not a proxy. */
    private fun readCaretGeometry(draft: String): CaretGeometry {
        val editor = compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
        val getLayout = editor.config.getOrNull(SemanticsActions.GetTextLayoutResult)
        assertTrue("Draft editor must expose its real text layout.", getLayout != null)
        val layouts = mutableListOf<TextLayoutResult>()
        getLayout!!.action!!.invoke(layouts)
        val layout = layouts.single()
        return CaretGeometry(
            caretHeightPx = layout.getCursorRect(draft.length).height,
            editorHeightPx = editor.boundsInRoot.height,
            textHeightPx = layout.size.height.toFloat(),
        )
    }

    private fun assertCompleteCaretLine(caret: CaretGeometry) {
        assertTrue(
            "The editor must keep at least one COMPLETE caret line with tiles staged " +
                "below it — otherwise the line being typed can never be fully visible " +
                "(#1619) and the attachment strip has eaten the editor's floor. " +
                "caretHeightPx=${caret.caretHeightPx} editorHeightPx=${caret.editorHeightPx}",
            caret.caretHeightPx > 0f && caret.caretHeightPx <= caret.editorHeightPx + 1f,
        )
    }

    private fun uiState(
        draft: String,
        attachmentCount: Int,
        connectionDegraded: Boolean = false,
    ): PromptComposerViewModel.UiState = PromptComposerViewModel.UiState(
        draft = draft,
        recording = PromptComposerViewModel.RecordingState.Idle,
        connectionDegraded = connectionDegraded,
        attachments = (1..attachmentCount).map { index ->
            PromptComposerViewModel.StagedAttachment(
                remotePath = "/tmp/issue-2057-screenshot-$index.png",
                displayName = "issue-2057-screenshot-$index.png",
            )
        },
    )

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
                .build()
            dispatchSyntheticWindowInsets(decor, insets)
        }
    }

    /**
     * Polls the decor's real window insets until the device IME reports visible,
     * up to [REAL_IME_WAIT_MS]. Returns whether it appeared. NOTHING asserts on
     * the result — it only makes the captured artifact show the keyboard-up
     * state on an AVD that has a working soft IME.
     */
    private fun waitForRealImeBestEffort(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + REAL_IME_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val visible = AtomicBoolean(false)
            compose.activityRule.scenario.onActivity { activity ->
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                visible.set(insets?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            }
            if (visible.get()) return true
            SystemClock.sleep(REAL_IME_POLL_MS)
        }
        return false
    }

    private fun displayDensity(): Float =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

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

    @Composable
    private fun FauxTerminalBackdrop() {
        Text(
            text = "alex@pocketshell:~$ tail -f deploy.log\n[ok] migrate complete",
            color = PocketShellColors.Text,
        )
    }

    private class TestMicCapture : PromptComposerViewModel.MicCapture {
        override fun start() = Unit
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

    private companion object {
        const val HOST_TAG = "issue2057-resized-sheet-host"

        // The measured real pixel_7 sheet content area above the keyboard
        // (814dp keyboard-down -> 470dp keyboard-up). Same model as the #801
        // tight-screen proof: the host height IS the room above the keyboard.
        const val HOST_HEIGHT_DP = 470f
        const val HOST_WIDTH_DP = 392f

        const val IME_HEIGHT_DP = 343f
        const val NAV_BAR_DP = 48f
        const val STATUS_BAR_DP = 52f

        const val SLOP_DP = 2f

        // Mirrors `PromptComposerAttachmentRegionImeMaxHeight` (private to the
        // production file): the keyboard-up upper bound on the strip.
        const val ATTACHMENT_STRIP_IME_CAP_DP = 40f
        const val TILE_SIZE_DP = 64f
        const val TILE_GAP_DP = 8f

        // Enough tiles that the FlowRow wraps well past the strip's bound.
        const val MANY_ATTACHMENTS = 12

        const val ATTACHMENT_A = "/tmp/issue-2057-report-a.png"
        const val ATTACHMENT_B = "/tmp/issue-2057-report-b.pdf"

        const val WAIT_MS = 5_000L

        // Bounded, NON-asserting wait for the device soft IME before the
        // full-device capture (see waitForRealImeBestEffort).
        const val REAL_IME_WAIT_MS = 6_000L
        const val REAL_IME_POLL_MS = 100L

        val LONG_DRAFT = (1..18).joinToString("\n") {
            "line $it of the long prompt; the caret must stay visible"
        }
    }
}

private fun ComposeTestRule.nodeCount(tag: String): Int =
    onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
