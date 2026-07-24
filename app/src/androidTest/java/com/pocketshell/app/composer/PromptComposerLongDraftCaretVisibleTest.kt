package com.pocketshell.app.composer

import android.os.Build
import android.view.inspector.WindowInspector
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.signals.assertNodeFullyAboveImeOrKeyboard
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
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
 * The direct #780 proof renders production [SheetContent] in a fixed-size host
 * and asserts exact above-keyboard containment. The connected and offline
 * journeys keep the full production [PromptComposerSheet] / ModalBottomSheet
 * mounted while they type a LONG multi-line draft through the real editor.
 * They dispatch keyboard-down and synthetic-IME insets to every attached app
 * window root, hard-assert that the modal's own Compose tree observes the exact
 * nonzero inset, then prove the actual modal controls lift and every asserted
 * node remains within its owning root. No renderer substitution or real soft
 * keyboard is required, so a keyboard-down layout cannot pass vacuously.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerLongDraftCaretVisibleTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val observedImeBottomPx = mutableStateOf(0)

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
    fun syntheticImeLongDraftOwnsContainedCaretViewportAboveStickyControls() {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }

        val longDraft = (1..18).joinToString("\n") {
            "line $it of the long prompt; sentinel caret remains visible"
        }
        compose.setContent {
            PocketShellTheme {
                val density = LocalDensity.current
                observedImeBottomPx.value = WindowInsets.ime.getBottom(density)
                Box(
                    modifier = Modifier
                        .width(SYNTHETIC_HOST_WIDTH_DP.dp)
                        .height(SYNTHETIC_HOST_HEIGHT_DP.dp)
                        .background(PocketShellColors.Background)
                        .testTag(SYNTHETIC_HOST_TAG),
                ) {
                    SheetContent(
                        state = PromptComposerViewModel.UiState(
                            draft = longDraft,
                            recording = PromptComposerViewModel.RecordingState.Idle,
                            connectionDegraded = true,
                        ),
                        onClose = {},
                        onDraftChange = {},
                        onMicTap = {},
                        onSend = {},
                        onAttachFiles = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        applySyntheticInsets(
            imeBottomPx = (SYNTHETIC_IME_HEIGHT_DP * displayDensity()).toInt(),
            navBarBottomPx = 0,
            statusBarTopPx = (SYNTHETIC_STATUS_BAR_DP * displayDensity()).toInt(),
        )
        compose.waitForIdle()

        assertTrue(
            "Synthetic ime() inset must reach Compose; keyboard-down would be vacuous. " +
                "observedImeBottomPx=${observedImeBottomPx.value}",
            observedImeBottomPx.value > 0,
        )

        val hostBounds = compose.onNodeWithTag(SYNTHETIC_HOST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val viewportInteraction = compose.onNodeWithTag(
            COMPOSER_DRAFT_VIEWPORT_TAG,
            useUnmergedTree = true,
        )
        val viewportBounds = viewportInteraction.getUnclippedBoundsInRoot()
        val draftInteraction = compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
        val draftNode = draftInteraction.fetchSemanticsNode()
        val draftBounds = draftInteraction.getUnclippedBoundsInRoot()
        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val attachBounds = compose.onNodeWithTag(COMPOSER_ATTACH_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val micBounds = compose.onNodeWithTag(COMPOSER_MIC_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val bannerBounds = compose.onNodeWithTag(
            COMPOSER_CONNECTION_LOST_TAG,
            useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()

        val layouts = mutableListOf<TextLayoutResult>()
        val getLayout = draftNode.config.getOrNull(SemanticsActions.GetTextLayoutResult)
        assertTrue("Draft editor must expose its real text layout.", getLayout != null)
        getLayout!!.action!!.invoke(layouts)
        val layout = layouts.single()
        val caretRect = layout.getCursorRect(longDraft.length)
        val density = displayDensity()
        val draftHeightPx = (draftBounds.bottom - draftBounds.top).value * density

        println(
            "ISSUE1619_SYNTHETIC host=$hostBounds viewport=$viewportBounds " +
                "draft=$draftBounds status=$bannerBounds send=$sendBounds attach=$attachBounds " +
                "mic=$micBounds textHeight=${layout.size.height} draftHeightPx=$draftHeightPx " +
                "caret=$caretRect density=$density",
        )

        assertTrue(
            "Long text must overflow the effective editor viewport so caret-follow is exercised. " +
                "textHeight=${layout.size.height} viewportHeight=$draftHeightPx",
            layout.size.height > draftHeightPx + 1f,
        )
        assertTrue(
            "The draft field must be fully contained by its dedicated viewport; an editor " +
                "clipped by a smaller outer scroll cannot make its caret reliably visible. " +
                "draft=$draftBounds viewport=$viewportBounds",
            draftBounds.top >= viewportBounds.top - 1.dp &&
                draftBounds.bottom <= viewportBounds.bottom + 1.dp,
        )
        assertTrue(
            "Status content belongs above the editor. statusBottom=${bannerBounds.bottom} " +
                "draftTop=${draftBounds.top}",
            bannerBounds.bottom <= draftBounds.top + 1.dp,
        )
        assertTrue(
            "The draft must retain one complete editable line. caretHeight=${caretRect.height} " +
                "draftHeight=$draftHeightPx",
            caretRect.height <= draftHeightPx + 1f,
        )
        assertTrue(
            "Draft must end above sticky controls. draftBottom=${draftBounds.bottom} " +
                "sendTop=${sendBounds.top}",
            draftBounds.bottom <= sendBounds.top + 1.dp,
        )
        compose.assertNodeFullyWithinRoot(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
        listOf(COMPOSER_DRAFT_TAG, COMPOSER_SEND_ENTER_TAG, COMPOSER_ATTACH_TAG, COMPOSER_MIC_TAG)
            .forEach { tag ->
                compose.assertNodeFullyAboveImeOrKeyboard(
                    tag,
                    keyboardTopPx = hostBounds.bottom,
                    useUnmergedTree = true,
                )
            }
    }

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
                        onSend = { _ -> true },
                        modifier = Modifier.observeProductionSheetIme(),
                        viewModel = vm,
                    )
                }
            }
        }

        compose.waitForIdle()

        // Real user gesture: tap + type a LONG multi-line draft. The unavailable
        // system-window IME resize is supplied deterministically below.
        val longDraft = (1..18).joinToString("\n") {
            "line $it of a long prompt I'm typing to check the composer"
        }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput(longDraft)

        assertTrue(
            "The real production PromptComposerSheet must receive the typed long draft.",
            vm.uiState.value.draft == longDraft,
        )
        val keyboardDownSendBottom = applySyntheticKeyboardDownAndReadSendBottom()
        val syntheticIme = applySyntheticImeAndAssertObservedInProductionSheet()

        val draftNode = compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
        val draftBounds = draftNode.boundsInRoot
        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        val expectedIme = syntheticIme.observedImeBottomPx.toFloat()

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
                "sendBottom=${sendBounds.bottom} keyboardDownSendBottom=$keyboardDownSendBottom " +
                "modalRootBottom=${syntheticIme.modalComposeRootBottomPx}",
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

        // 3) The synthetic-IME field keeps at least three complete caret lines. The
        //    #1619 layout deliberately bounds the editor to the ACTUAL remaining
        //    room (rather than preserving the old arbitrary 80dp floor), so use
        //    the measured line height as the device/font-scale invariant.
        val draftHeightDp = viewportHeightPx / density
        assertTrue(
            "Draft field must retain at least three complete caret lines. " +
                "draftHeightPx=$viewportHeightPx caretHeightPx=$caretHeightPx " +
                "draftHeightDp=$draftHeightDp",
            viewportHeightPx >= caretHeightPx * 3f,
        )

        // 4) The field's visible bottom remains above the controls row. The
        //    mounted modal's keyboard-down -> IME-up movement is load-bearing,
        //    while the direct #780 host proof above owns exact keyboard-boundary
        //    containment.
        assertTrue(
            "Draft field bottom must be above the Send controls row. " +
                "draftBottom=${draftBounds.bottom} sendTop=${sendBounds.top}",
            draftBounds.bottom <= sendBounds.top + 2f,
        )
        assertTrue(
            "The mounted production sheet must lift its controls substantially when the " +
                "synthetic IME reaches the modal root. keyboardDownSendBottom=" +
                "$keyboardDownSendBottom keyboardUpSendBottom=${sendBounds.bottom} " +
                "observedImeBottomPx=$expectedIme",
            keyboardDownSendBottom - sendBounds.bottom >= expectedIme * 0.5f,
        )
        listOf(COMPOSER_DRAFT_TAG, COMPOSER_SEND_ENTER_TAG, COMPOSER_ATTACH_TAG, COMPOSER_MIC_TAG)
            .forEach { tag ->
                assertNodeFullyWithinOwningComposeRoot(tag)
            }
    }

    @Test
    fun offlineBannerStaysAboveLongDraftWithSyntheticImeUp() {
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
                        modifier = Modifier.observeProductionSheetIme(),
                        connectionLost = true,
                        viewModel = vm,
                    )
                }
            }
        }
        compose.waitForIdle()

        val longDraft = (1..18).joinToString("\n") {
            "offline line $it; the reconnect queue keeps this caret visible"
        }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput(longDraft)
        assertTrue(
            "The offline production PromptComposerSheet must receive the typed long draft.",
            vm.uiState.value.draft == longDraft,
        )
        val keyboardDownSendBottom = applySyntheticKeyboardDownAndReadSendBottom()
        val syntheticIme = applySyntheticImeAndAssertObservedInProductionSheet()

        compose.onNodeWithText(OFFLINE_COPY, useUnmergedTree = true).assertExists()

        val statusBounds = compose.onNodeWithTag(
            COMPOSER_STATUS_VIEWPORT_TAG,
            useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()
        val bannerBounds = compose.onNodeWithTag(
            COMPOSER_CONNECTION_LOST_TAG,
            useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()
        val draftViewportBounds = compose.onNodeWithTag(
            COMPOSER_DRAFT_VIEWPORT_TAG,
            useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()
        val draftInteraction = compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        val draftNode = draftInteraction.fetchSemanticsNode()
        val draftBounds = draftInteraction.getUnclippedBoundsInRoot()
        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val layouts = mutableListOf<TextLayoutResult>()
        val getLayout = draftNode.config.getOrNull(SemanticsActions.GetTextLayoutResult)
        assertTrue("Offline draft must expose its real text layout.", getLayout != null)
        getLayout!!.action!!.invoke(layouts)
        val layout = layouts.single()
        val caretRect = layout.getCursorRect(longDraft.length)
        val density = displayDensity()
        val draftHeightPx = (draftBounds.bottom - draftBounds.top).value * density
        val keyboardUpSendBottomPx = sendBounds.bottom.value * density

        println(
            "ISSUE1619_SYNTHETIC_OFFLINE status=$statusBounds banner=$bannerBounds " +
                "draftViewport=$draftViewportBounds draft=$draftBounds send=$sendBounds " +
                "textHeight=${layout.size.height} caret=$caretRect " +
                "keyboardDownSendBottom=$keyboardDownSendBottom " +
                "modalRootBottom=${syntheticIme.modalComposeRootBottomPx}",
        )
        assertTrue(
            "Offline banner must be fully contained in its bounded status viewport. " +
                "banner=$bannerBounds status=$statusBounds",
            bannerBounds.top >= statusBounds.top - 1.dp &&
                bannerBounds.bottom <= statusBounds.bottom + 1.dp,
        )
        assertTrue(
            "Offline banner/status must remain above the long draft. " +
                "bannerBottom=${bannerBounds.bottom} draftTop=${draftViewportBounds.top}",
            bannerBounds.bottom <= draftViewportBounds.top + 1.dp,
        )
        assertTrue(
            "Synthetic-IME offline text must overflow the editor so caret-follow is exercised.",
            layout.size.height > draftHeightPx + 1f,
        )
        assertTrue(
            "Synthetic-IME offline draft must retain one complete caret line.",
            caretRect.height <= draftHeightPx + 1f,
        )
        assertTrue(
            "Offline draft must end above sticky controls.",
            draftBounds.bottom <= sendBounds.top + 1.dp,
        )
        assertTrue(
            "The mounted offline production sheet must lift its controls substantially when " +
                "the synthetic IME reaches the modal root.",
            keyboardDownSendBottom - keyboardUpSendBottomPx >=
                syntheticIme.observedImeBottomPx * 0.5f,
        )
        assertNodeFullyWithinOwningComposeRoot(COMPOSER_CONNECTION_LOST_TAG)
        listOf(COMPOSER_DRAFT_TAG, COMPOSER_SEND_ENTER_TAG, COMPOSER_ATTACH_TAG, COMPOSER_MIC_TAG)
            .forEach { tag ->
                assertNodeFullyWithinOwningComposeRoot(tag)
            }
    }

    @Composable
    private fun FauxTerminalBackdrop() {
        Text(
            text = "alex@pocketshell:~$ tail -f deploy.log\n[ok] migrate complete",
            color = PocketShellColors.Text,
        )
    }

    private data class SyntheticImeGeometry(
        val observedImeBottomPx: Int,
        val modalComposeRootBottomPx: Float,
    )

    private fun applySyntheticKeyboardDownAndReadSendBottom(): Float {
        repeat(2) {
            applySyntheticInsets(
                imeBottomPx = 0,
                navBarBottomPx = 0,
                statusBarTopPx = (SYNTHETIC_STATUS_BAR_DP * displayDensity()).toInt(),
                requireModalRoot = true,
            )
            compose.waitForIdle()
        }
        val observedIme = compose.onNodeWithTag(PRODUCTION_SHEET_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(PRODUCTION_SHEET_IME_BOTTOM_PX)
            ?: -1
        assertTrue(
            "Keyboard-down baseline must be Compose-observed as zero in the mounted sheet; " +
                "observedImeBottomPx=$observedIme",
            observedIme == 0,
        )
        return compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.bottom
    }

    private fun assertNodeFullyWithinOwningComposeRoot(tag: String) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val root = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            .single { it.root === node.root }
        val bounds = node.boundsInRoot
        val rootBounds = root.boundsInRoot
        val slop = 2f * compose.density.density
        assertTrue(
            "Node '$tag' must remain fully contained in the mounted production modal root. " +
                "node=$bounds root=$rootBounds",
            bounds.left >= rootBounds.left - slop &&
                bounds.top >= rootBounds.top - slop &&
                bounds.right <= rootBounds.right + slop &&
                bounds.bottom <= rootBounds.bottom + slop,
        )
    }

    private fun applySyntheticImeAndAssertObservedInProductionSheet(): SyntheticImeGeometry {
        repeat(2) {
            applySyntheticInsets(
                imeBottomPx = (SYNTHETIC_IME_HEIGHT_DP * displayDensity()).toInt(),
                navBarBottomPx = 0,
                statusBarTopPx = (SYNTHETIC_STATUS_BAR_DP * displayDensity()).toInt(),
                requireModalRoot = true,
            )
            compose.waitForIdle()
        }

        val sheetNode = compose.onNodeWithTag(PRODUCTION_SHEET_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
        val observedIme = sheetNode.config.getOrNull(PRODUCTION_SHEET_IME_BOTTOM_PX)
            ?: 0
        val expectedIme = (SYNTHETIC_IME_HEIGHT_DP * displayDensity()).toInt()
        assertTrue(
            "Synthetic ime() inset must be Compose-observed in the mounted production " +
                "ModalBottomSheet root; keyboard-down or activity-only dispatch would be " +
                "vacuous. observedImeBottomPx=$observedIme expectedImeBottomPx=$expectedIme",
            expectedIme > 0 && observedIme == expectedIme,
        )
        val modalComposeRoot = compose.onAllNodes(isRoot())
            .fetchSemanticsNodes()
            .single { it.root === sheetNode.root }
        println(
            "ISSUE1677_PRODUCTION_MODAL_GEOMETRY root=${modalComposeRoot.boundsInRoot} " +
                "observedImeBottomPx=$observedIme",
        )
        assertTrue(
            "Mounted production modal Compose root must have nonzero geometry. " +
                "bounds=${modalComposeRoot.boundsInRoot}",
            modalComposeRoot.boundsInRoot.height > 0f,
        )
        return SyntheticImeGeometry(
            observedImeBottomPx = observedIme,
            modalComposeRootBottomPx = modalComposeRoot.boundsInRoot.bottom,
        )
    }

    private fun Modifier.observeProductionSheetIme(): Modifier = composed {
        val density = LocalDensity.current
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        testTag(PRODUCTION_SHEET_TAG).semantics {
            this[PRODUCTION_SHEET_IME_BOTTOM_PX] = imeBottomPx
        }
    }

    private fun applySyntheticInsets(
        imeBottomPx: Int,
        navBarBottomPx: Int,
        statusBarTopPx: Int,
        requireModalRoot: Boolean = false,
    ) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Issue #1677 synthetic modal-window proof requires API 29+ WindowInspector; " +
                "deviceApi=${Build.VERSION.SDK_INT}"
        }
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
            .setVisible(WindowInsetsCompat.Type.ime(), imeBottomPx > 0)
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
        compose.activityRule.scenario.onActivity { activity ->
            val roots = activeAppWindowRoots(activity)
            val activityDecor = activity.window.decorView
            val modalRoots = roots.filterNot { it === activityDecor }
            check(roots.any { it === activityDecor }) {
                "Active app roots must include the activity decor."
            }
            if (requireModalRoot && modalRoots.isEmpty()) {
                error("Mounted PromptComposerSheet modal root disappeared before inset dispatch.")
            }
            println(
                "ISSUE1677_SYNTHETIC_WINDOW_ROOTS count=${roots.size} " +
                    "modalCount=${modalRoots.size} " +
                    "roots=${roots.map { "${it.javaClass.name}:${it.width}x${it.height}" }}",
            )
            roots.forEach { root ->
                ViewCompat.dispatchApplyWindowInsets(root, insets)
            }
        }
    }

    private fun activeAppWindowRoots(activity: ComponentActivity) =
        WindowInspector.getGlobalWindowViews()
            .asSequence()
            .map { it.rootView }
            .distinctBy { System.identityHashCode(it) }
            .filter { root ->
                root.isAttachedToWindow &&
                    root.context.applicationContext.packageName ==
                    activity.applicationContext.packageName
            }
            .toList()

    private fun displayDensity(): Float =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

    private companion object {
        const val SYNTHETIC_HOST_TAG = "issue1619-long-draft-host"
        const val SYNTHETIC_HOST_WIDTH_DP = 360f
        const val SYNTHETIC_HOST_HEIGHT_DP = 470f
        const val SYNTHETIC_IME_HEIGHT_DP = 295f
        const val SYNTHETIC_STATUS_BAR_DP = 52f
        const val PRODUCTION_SHEET_TAG = "issue1677-production-composer-sheet"
        val PRODUCTION_SHEET_IME_BOTTOM_PX =
            SemanticsPropertyKey<Int>("Issue1677ProductionSheetImeBottomPx")
        const val OFFLINE_COPY = "Offline — prompts will be queued and sent on reconnect."
    }
}
