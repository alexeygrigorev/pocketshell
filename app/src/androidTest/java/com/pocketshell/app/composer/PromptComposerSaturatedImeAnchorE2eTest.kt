package com.pocketshell.app.composer

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.insets.dispatchSyntheticWindowInsets
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1744 D33/G10 proof for the real Material3 anchor failure exposed by
 * #1743. The production [PromptComposerSheet] remains mounted while every
 * method dispatches an exact synthetic IME inset to the modal root only
 * (the #780 model). Issue #2139 hard-cut the real-system-IME raise: a
 * contended CI AVD cannot be the load-bearing keyboard.
 *
 * A long durable draft plus the real queued-Sending banner saturates
 * [SheetContent]. On the base implementation Material3 creates a half-height
 * [SheetValue.PartiallyExpanded] anchor for the total Surface (including its
 * drag handle), and that Surface plus sticky Send crosses the same-root
 * keyboard boundary. The production fix must move only that overlapping modal
 * to [SheetValue.Expanded].
 *
 * Residual unfocused `TYPE_INPUT_METHOD` windows (leftover from an earlier
 * class on a shared emulator) are dismissed best-effort and do not block
 * synthetic dispatch. A serving (active or focused) physical IME still
 * hard-fails, so a synthetic boundary is never mixed with a live keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerSaturatedImeAnchorE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var viewModel: PromptComposerViewModel? = null
    private var releaseDelivery: CompletableDeferred<Unit>? = null
    private var releaseAutoCloseReduction: CompletableDeferred<Unit>? = null

    @After
    fun tearDown() {
        hideRealImeBestEffort()
        releaseAutoCloseReduction?.complete(Unit)
        releaseAutoCloseReduction = null
        releaseDelivery?.complete(Unit)
        releaseDelivery = null
        viewModel?.clearForTest()
        viewModel = null
    }

    @Test
    fun saturatedQueuedDraftExpandsAboveImeThenReturnsWithoutDismissalOrLoss() {
        val drafts = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newViewModel(drafts, queue)
        val visible = mutableStateOf(true)
        val sendEntered = CompletableDeferred<Unit>()
        val deliveryGate = CompletableDeferred<Unit>().also { releaseDelivery = it }
        val reductionEntered = CompletableDeferred<Unit>()
        val reductionGate =
            CompletableDeferred<Unit>().also { releaseAutoCloseReduction = it }
        val sheetStateRef = AtomicReference<SheetState?>()
        val targetKey = "1/issue-1744"
        vm.beforeHandoffAutoCloseReductionForTest = {
            reductionEntered.complete(Unit)
            reductionGate.await()
        }

        prepareActivityWindow()
        compose.setContent {
            PocketShellTheme {
                Box(Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    PromptComposerSendDispatcher(
                        viewModel = vm,
                        onSend = {
                            sendEntered.complete(Unit)
                            deliveryGate.await()
                            ComposerSendResult.Delivered
                        },
                        onDelivered = { visible.value = false },
                    )
                    if (visible.value) {
                        val sheetState =
                            rememberModalBottomSheetState(skipPartiallyExpanded = false)
                        SideEffect { sheetStateRef.set(sheetState) }
                        PromptComposerSheet(
                            onDismiss = { visible.value = false },
                            onSend = { error("screen-scoped dispatcher owns delivery") },
                            composerTargetKey = targetKey,
                            sendTargetSnapshotProvider = {
                                PromptComposerViewModel.SendTargetSnapshot(sessionKey = targetKey)
                            },
                            modifier = Modifier.observeProductionSheetIme(),
                            sheetState = sheetState,
                            viewModel = vm,
                            collectSendRequests = false,
                        )
                    }
                }
            }
        }
        waitUntilNamed(
            timeoutMs = 5_000,
            label = "saturated sheet mounted for target $targetKey",
            details = { sheetWaitDetails(vm, sheetStateRef, visible.value) },
            unsatisfiableWhen = { visible.value.not() },
            unsatisfiableReason = { "the sheet was dismissed before it mounted" },
        ) {
            vm.composerTarget == targetKey &&
                sheetStateRef.get()?.currentValue != null &&
                sheetStateRef.get()?.currentValue != SheetValue.Hidden
        }
        val sheetState = checkNotNull(sheetStateRef.get())

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput("prompt A")
        waitUntilNamed(
            timeoutMs = 5_000,
            label = "prompt A committed to draft + durable store",
            details = { draftWaitDetails(vm, drafts, targetKey, "prompt A") },
        ) {
            vm.uiState.value.draft == "prompt A" && drafts.load(targetKey) == "prompt A"
        }
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .performClick()
        waitUntilNamed(
            timeoutMs = 5_000,
            label = "prompt A entered the queued Sending banner",
            details = {
                "reductionEntered=${reductionEntered.isCompleted} " +
                    "sendInFlight=${vm.uiState.value.sendInFlight} " +
                    "queueRows=${queue.itemsFor(targetKey).size}"
            },
        ) {
            reductionEntered.isCompleted &&
                vm.uiState.value.sendInFlight &&
                queue.itemsFor(targetKey).size == 1
        }
        assertFalse("delivery must wait for the saturated sheet's close-or-keep decision", sendEntered.isCompleted)

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput(LONG_DRAFT_B)
        waitUntilNamed(
            timeoutMs = 5_000,
            label = "draft B committed while prompt A is sending",
            details = { draftWaitDetails(vm, drafts, targetKey, LONG_DRAFT_B) },
        ) {
            vm.uiState.value.draft == LONG_DRAFT_B &&
                drafts.load(targetKey) == LONG_DRAFT_B
        }
        reductionGate.complete(Unit)
        waitUntilNamed(
            timeoutMs = 5_000,
            label = "prompt A send entered after draft B took the sheet",
            details = {
                "sendEntered=${sendEntered.isCompleted} visible=${visible.value} " +
                    "draft=${vm.uiState.value.draft.take(48)}"
            },
            unsatisfiableWhen = { visible.value.not() },
            unsatisfiableReason = { "the saturated sheet dismissed before send entered" },
        ) { sendEntered.isCompleted }
        assertTrue("draft B must retain ownership of the saturated sheet", visible.value)
        assertPromptAExactlyOnce(queue, targetKey)
        hideRealImeAndAssertHidden(
            "Synthetic saturated proof must start with no physical keyboard window.",
        )

        val keyboardDown = applyInsetsAndReadGeometry(
            imeBottomPx = 0,
            sheetState = sheetState,
        )
        assertEquals(0, keyboardDown.observedImeBottomPx)

        val expectedIme = (SYNTHETIC_IME_HEIGHT_DP * displayDensity()).toInt()
        val keyboardUpBeforeEdit = applyInsetsAndReadGeometry(
            imeBottomPx = expectedIme,
            sheetState = sheetState,
        )
        assertEquals(expectedIme, keyboardUpBeforeEdit.observedImeBottomPx)

        // The base/mutant reproduction must exercise the real editor AFTER the
        // exact nonzero modal-root inset is present. Keep this mutation and its
        // durable-store assertion before the load-bearing geometry oracle so a
        // 28px red cannot short-circuit the contract's editing journey.
        val editedDraft = "$LONG_DRAFT_B$POST_IME_SUFFIX"
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performTextInput(POST_IME_SUFFIX)
        waitUntilNamed(
            timeoutMs = 5_000,
            label = "post-inset suffix committed to draft + durable store",
            details = { draftWaitDetails(vm, drafts, targetKey, editedDraft) },
        ) {
            vm.uiState.value.draft == editedDraft &&
                drafts.load(targetKey) == editedDraft
        }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains(editedDraft, substring = false)
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertIsEnabled()
        assertPromptAExactlyOnce(queue, targetKey)
        assertNoServingPhysicalImeWindow(
            "Post-inset semantics input must not summon LatinIME in the synthetic proof.",
        )

        // Re-read authoritative post-anchor geometry after the edit: this is
        // the state captured for base, mutant, and restored evidence.
        val keyboardUp = applyInsetsAndReadGeometry(
            imeBottomPx = expectedIme,
            sheetState = sheetState,
        )
        val keyboardTopPx = keyboardUp.rootBottomPx - keyboardUp.observedImeBottomPx
        println(
            "ISSUE1744_SATURATED_PRE_ASSERT keyboardUpBeforeEdit=$keyboardUpBeforeEdit " +
                "keyboardUp=$keyboardUp keyboardTopPx=$keyboardTopPx " +
                "draftLength=${editedDraft.length} durable=" +
                "${drafts.load(targetKey) == editedDraft} queueRows=" +
                "${queue.itemsFor(targetKey).size} state=" +
                "${sheetState.currentValue}/${sheetState.targetValue}",
        )
        assertNoServingPhysicalImeWindow(
            "Synthetic saturated screenshot must not contain a physical keyboard.",
        )
        WalkthroughScreenshotArtifacts.capture("issue-1744-saturated-ime-state")

        // Load-bearing #1744 oracle. On current main this fails with the exact
        // fixture discovered by #1743: keyboardTop=1626px and sticky Send
        // bottom=1654px. Publishing an inset without moving the real Material
        // Surface and its content cannot pass.
        assertTrue(
            "Saturated production ModalBottomSheet Surface's sticky Send must be " +
                "fully above its owning root's exact keyboard boundary. " +
                "keyboardTopPx=$keyboardTopPx surfaceTop=${keyboardUp.surfaceTopPx} " +
                "surfaceHeight=${keyboardUp.surfaceHeightPx} " +
                "surfaceBottom=${keyboardUp.displayedSurfaceBottomPx} " +
                "modifierRect=${keyboardUp.sheetBounds} send=${keyboardUp.sendBounds} " +
                "state=${sheetState.currentValue}/${sheetState.targetValue}",
            keyboardUp.displayedSurfaceBottomPx <= keyboardTopPx + ROOT_SLOP_PX &&
                keyboardUp.sendBounds.bottom <= keyboardTopPx + ROOT_SLOP_PX,
        )
        assertEquals(
            "An overlapping settled partial anchor must finish at Material's Expanded anchor.",
            SheetValue.Expanded,
            sheetState.currentValue,
        )
        assertEquals(SheetValue.Expanded, sheetState.targetValue)

        listOf(
            COMPOSER_STATUS_VIEWPORT_TAG,
            COMPOSER_OUTBOUND_QUEUE_BANNER_TAG,
            COMPOSER_DRAFT_VIEWPORT_TAG,
            COMPOSER_DRAFT_TAG,
            COMPOSER_SEND_ENTER_TAG,
        ).forEach { tag ->
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNode()
            assertTrue(
                "Node '$tag' must belong to the same modal root as the measured Surface.",
                node.root === keyboardUp.sheetNode.root,
            )
            assertTrue(
                "Node '$tag' must stay above the same-root keyboard boundary. " +
                    "node=${node.boundsInRoot} keyboardTopPx=$keyboardTopPx",
                node.boundsInRoot.bottom <= keyboardTopPx + ROOT_SLOP_PX,
            )
        }

        val keyboardRestored = applyInsetsAndReadGeometry(
            imeBottomPx = 0,
            sheetState = sheetState,
        )
        assertNoServingPhysicalImeWindow(
            "Resetting the synthetic inset must leave the physical keyboard absent.",
        )
        val returnSlopPx = RETURN_GEOMETRY_SLOP_DP * displayDensity()
        assertTrue("Composer must remain mounted after the IME hides.", visible.value)
        assertNotEquals(SheetValue.Hidden, sheetState.currentValue)
        assertEquals(editedDraft, vm.uiState.value.draft)
        assertEquals(editedDraft, drafts.load(targetKey))
        assertPromptAExactlyOnce(queue, targetKey)
        assertTrue(
            "IME-owned expansion must return to the stable keyboard-down compact " +
                "geometry without a Pixel-only offset. before=$keyboardDown " +
                "restored=$keyboardRestored slopPx=$returnSlopPx",
            abs(keyboardRestored.surfaceTopPx - keyboardDown.surfaceTopPx) <=
                returnSlopPx &&
                abs(
                    keyboardRestored.displayedSurfaceBottomPx -
                        keyboardDown.displayedSurfaceBottomPx,
                ) <=
                returnSlopPx,
        )
        println(
            "ISSUE1744_ANCHOR keyboardDown=$keyboardDown keyboardUp=$keyboardUp " +
                "keyboardRestored=$keyboardRestored state=" +
                "${sheetState.currentValue}/${sheetState.targetValue}",
        )
        WalkthroughScreenshotArtifacts.capture("issue-1744-ime-hidden-draft-preserved")

        deliveryGate.complete(Unit)
        waitUntilNamed(
            timeoutMs = 5_000,
            label = "prompt A delivery finished after IME restore",
            details = { "sendInFlight=${vm.uiState.value.sendInFlight}" },
        ) { !vm.uiState.value.sendInFlight }
        assertEquals(editedDraft, vm.uiState.value.draft)
        assertEquals(editedDraft, drafts.load(targetKey))
        assertTrue(queue.itemsFor(targetKey).isEmpty())
    }

    @Test
    fun shortComposerRemainsCompactAndDoesNotAutoExpandForNonOverlappingIme() {
        val vm = newViewModel(
            drafts = InMemoryComposerDraftStore(),
            queue = InMemoryOutboundQueueStore(),
        )
        val sheetStateRef = AtomicReference<SheetState?>()
        val expandedTransitionRequests = AtomicInteger(0)
        val targetKey = "1/issue-1744-short"

        prepareActivityWindow()
        compose.setContent {
            PocketShellTheme {
                Box(Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    androidx.compose.material3.Text(
                        text = TERMINAL_MARKER,
                        color = PocketShellColors.Text,
                        modifier = Modifier.testTag(TERMINAL_MARKER_TAG),
                    )
                    val sheetState =
                        rememberModalBottomSheetState(
                            skipPartiallyExpanded = false,
                            confirmValueChange = { target ->
                                if (target == SheetValue.Expanded) {
                                    expandedTransitionRequests.incrementAndGet()
                                }
                                true
                            },
                        )
                    SideEffect { sheetStateRef.set(sheetState) }
                    PromptComposerSheet(
                        onDismiss = {},
                        onSend = { com.pocketshell.app.composer.ComposerSendResult.Delivered },
                        composerTargetKey = targetKey,
                        modifier = Modifier.observeProductionSheetIme(),
                        sheetState = sheetState,
                        viewModel = vm,
                    )
                }
            }
        }
        waitUntilNamed(
            timeoutMs = 5_000,
            label = "short composer sheet mounted for target $targetKey",
            details = { sheetWaitDetails(vm, sheetStateRef, visible = true) },
        ) {
            vm.composerTarget == targetKey &&
                sheetStateRef.get()?.currentValue != null &&
                sheetStateRef.get()?.currentValue != SheetValue.Hidden
        }
        val sheetState = checkNotNull(sheetStateRef.get())
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput("short draft")
        hideRealImeAndAssertHidden(
            "Synthetic short-draft proof must start with no physical keyboard window.",
        )
        val keyboardDown = applyInsetsAndReadGeometry(
            imeBottomPx = 0,
            sheetState = sheetState,
        )

        assertEquals(0, keyboardDown.observedImeBottomPx)
        assertNotEquals(SheetValue.Hidden, sheetState.currentValue)
        assertTrue(
            "Keyboard-down short composer must stay compact and leave the upper " +
                "terminal visible. geometry=$keyboardDown",
            keyboardDown.surfaceTopPx >
                keyboardDown.rootBounds.top +
                keyboardDown.rootBounds.height * MINIMUM_TERMINAL_FRACTION,
        )
        val keyboardDownCurrent = sheetState.currentValue
        val keyboardDownTarget = sheetState.targetValue
        val expandedRequestsBeforeIme = expandedTransitionRequests.get()

        val shortImePx = (SHORT_SYNTHETIC_IME_HEIGHT_DP * displayDensity()).toInt()
        val keyboardUp = applyInsetsAndReadGeometry(
            imeBottomPx = shortImePx,
            sheetState = sheetState,
        )
        val keyboardTopPx = keyboardUp.rootBottomPx - keyboardUp.observedImeBottomPx
        assertTrue(
            "A short Surface that does not overlap the exact visible-IME boundary " +
                "must stay contained without a policy expansion. " +
                "keyboardTopPx=$keyboardTopPx geometry=$keyboardUp",
            keyboardUp.displayedSurfaceBottomPx <= keyboardTopPx + ROOT_SLOP_PX &&
                keyboardUp.sendBounds.bottom <= keyboardTopPx + ROOT_SLOP_PX,
        )
        assertEquals(keyboardDownCurrent, sheetState.currentValue)
        assertEquals(keyboardDownTarget, sheetState.targetValue)
        assertEquals(
            "Non-overlapping visible IME must not request another Expanded transition.",
            expandedRequestsBeforeIme,
            expandedTransitionRequests.get(),
        )
        println(
            "ISSUE1744_SHORT_NON_OVERLAP keyboardDown=$keyboardDown " +
                "keyboardUp=$keyboardUp expandedRequests=$expandedRequestsBeforeIme",
        )
        assertNoServingPhysicalImeWindow(
            "Synthetic short-draft screenshot must not contain a physical keyboard.",
        )
        WalkthroughScreenshotArtifacts.capture("issue-1744-short-ime-non-overlap")
        compose.onNodeWithTag(TERMINAL_MARKER_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("short draft", substring = false)
        assertFalse(vm.uiState.value.draft.isEmpty())
    }

    /**
     * Issue #1800 / #2139: reachability of draft/send/attach/mic inside the
     * measured modal root and above the exact keyboard boundary, keyboard-up
     * editability + draft durability, and keyboard-down geometry restoration.
     *
     * Every one of those is a property of the composer's anchor policy, NOT of
     * the physical input-method window. Issue #2139 hard-cut the real-IME twin:
     * the CI swiftshader AVD cannot raise a system keyboard under contention,
     * and a leftover unfocused IME window from an earlier class is itself a
     * real-IME dependence. This method is now the only reachability proof. It
     * asserts the property set with a HARD failure, driven by the deterministic
     * synthetic `Type.ime()` inset (the #780 model).
     *
     * It deliberately uses the SATURATED fixture — a durable long draft plus
     * the real queued `Sending` banner — because that is the state whose
     * Material [SheetValue.PartiallyExpanded] anchor crosses the keyboard
     * boundary. It also mounts the production attachment-availability contract
     * so Attach is enabled and can be asserted reachable alongside Mic.
     *
     * A serving (active/focused) physical keyboard is hard-asserted ABSENT
     * throughout. Residual unfocused IME windows are dismissed best-effort
     * and do not fail the dispatch precondition.
     */
    @Test
    fun saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide() {
        val drafts = InMemoryComposerDraftStore()
        val queue = InMemoryOutboundQueueStore()
        val vm = newViewModel(drafts, queue)
        val visible = mutableStateOf(true)
        val sendEntered = CompletableDeferred<Unit>()
        val deliveryGate = CompletableDeferred<Unit>().also { releaseDelivery = it }
        val reductionEntered = CompletableDeferred<Unit>()
        val reductionGate =
            CompletableDeferred<Unit>().also { releaseAutoCloseReduction = it }
        val sheetStateRef = AtomicReference<SheetState?>()
        val targetKey = "1/issue-1800-synthetic-reach"
        vm.beforeHandoffAutoCloseReductionForTest = {
            reductionEntered.complete(Unit)
            reductionGate.await()
        }

        prepareActivityWindow()
        compose.setContent {
            PocketShellTheme {
                Box(Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    PromptComposerSendDispatcher(
                        viewModel = vm,
                        onSend = {
                            sendEntered.complete(Unit)
                            deliveryGate.await()
                            ComposerSendResult.Delivered
                        },
                        onDelivered = { visible.value = false },
                    )
                    if (visible.value) {
                        val sheetState =
                            rememberModalBottomSheetState(skipPartiallyExpanded = false)
                        SideEffect { sheetStateRef.set(sheetState) }
                        PromptComposerSheet(
                            onDismiss = { visible.value = false },
                            onSend = { error("screen-scoped dispatcher owns delivery") },
                            composerTargetKey = targetKey,
                            sendTargetSnapshotProvider = {
                                PromptComposerViewModel.SendTargetSnapshot(sessionKey = targetKey)
                            },
                            // Production availability contract: a non-null
                            // callback enables Attach without opening the
                            // picker or touching geometry.
                            onStageAttachments = { uris ->
                                Result.success(
                                    uris.mapIndexed { index, uri ->
                                        "/tmp/issue-1800-" +
                                            (uri.lastPathSegment ?: "attachment-$index")
                                    },
                                )
                            },
                            modifier = Modifier.observeProductionSheetIme(),
                            sheetState = sheetState,
                            viewModel = vm,
                            collectSendRequests = false,
                        )
                    }
                }
            }
        }
        waitUntilNamed(
            timeoutMs = 5_000,
            label = "synthetic reachability sheet mounted for target $targetKey",
            details = { sheetWaitDetails(vm, sheetStateRef, visible.value) },
            unsatisfiableWhen = { visible.value.not() },
            unsatisfiableReason = { "the sheet was dismissed before it mounted" },
        ) {
            vm.composerTarget == targetKey &&
                sheetStateRef.get()?.currentValue != null &&
                sheetStateRef.get()?.currentValue != SheetValue.Hidden
        }
        val sheetState = checkNotNull(sheetStateRef.get())

        // Saturate exactly like the #1744 anchor oracle: one in-flight queued
        // send (the real `Sending` banner) plus the long durable draft.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput("prompt A")
        waitUntilNamed(
            timeoutMs = 5_000,
            label = "prompt A committed before synthetic reachability inset",
            details = { draftWaitDetails(vm, drafts, targetKey, "prompt A") },
        ) {
            vm.uiState.value.draft == "prompt A" && drafts.load(targetKey) == "prompt A"
        }
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .performClick()
        waitUntilNamed(
            timeoutMs = 5_000,
            label = "prompt A entered the queued Sending banner",
            details = {
                "reductionEntered=${reductionEntered.isCompleted} " +
                    "sendInFlight=${vm.uiState.value.sendInFlight} " +
                    "queueRows=${queue.itemsFor(targetKey).size}"
            },
        ) {
            reductionEntered.isCompleted &&
                vm.uiState.value.sendInFlight &&
                queue.itemsFor(targetKey).size == 1
        }
        assertFalse("delivery must wait for the saturated sheet's close-or-keep decision", sendEntered.isCompleted)
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput(LONG_DRAFT_B)
        waitUntilNamed(
            timeoutMs = 5_000,
            label = "draft B committed while prompt A is sending",
            details = { draftWaitDetails(vm, drafts, targetKey, LONG_DRAFT_B) },
        ) {
            vm.uiState.value.draft == LONG_DRAFT_B &&
                drafts.load(targetKey) == LONG_DRAFT_B
        }
        reductionGate.complete(Unit)
        waitUntilNamed(
            timeoutMs = 5_000,
            label = "prompt A send entered after draft B took the sheet",
            details = {
                "sendEntered=${sendEntered.isCompleted} visible=${visible.value} " +
                    "draft=${vm.uiState.value.draft.take(48)}"
            },
            unsatisfiableWhen = { visible.value.not() },
            unsatisfiableReason = { "the saturated sheet dismissed before send entered" },
        ) { sendEntered.isCompleted }
        assertTrue("draft B must retain ownership of the saturated sheet", visible.value)
        assertPromptAExactlyOnce(queue, targetKey)
        hideRealImeAndAssertHidden(
            "Synthetic reachability mirror must start with no physical keyboard window.",
        )

        val keyboardDown = applyInsetsAndReadGeometry(
            imeBottomPx = 0,
            sheetState = sheetState,
        )
        assertEquals(0, keyboardDown.observedImeBottomPx)

        // HARD "the keyboard-up state actually applied" precondition — the
        // synthetic equivalent of the real method's physical-IME-window +
        // positive-app-inset preconditions. `applyInsetsAndReadGeometry`
        // asserts the exact value was Compose-observed on the production
        // modifier; no assumeTrue, no skip.
        val expectedIme = (SYNTHETIC_IME_HEIGHT_DP * displayDensity()).toInt()
        val keyboardUpBeforeEdit = applyInsetsAndReadGeometry(
            imeBottomPx = expectedIme,
            sheetState = sheetState,
        )
        assertEquals(expectedIme, keyboardUpBeforeEdit.observedImeBottomPx)

        // Keyboard-up editability + durability while the synthetic boundary is up.
        val editedDraft = "$LONG_DRAFT_B$SYNTHETIC_REACH_SUFFIX"
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performTextReplacement(editedDraft)
        waitUntilNamed(
            timeoutMs = 5_000,
            label = "synthetic-reach suffix committed to draft + durable store",
            details = { draftWaitDetails(vm, drafts, targetKey, editedDraft) },
        ) {
            vm.uiState.value.draft == editedDraft &&
                drafts.load(targetKey) == editedDraft
        }
        assertNoServingPhysicalImeWindow(
            "Post-inset semantics input must not summon LatinIME in the synthetic mirror.",
        )

        val keyboardUp = applyInsetsAndReadGeometry(
            imeBottomPx = expectedIme,
            sheetState = sheetState,
        )
        val keyboardTopPx = keyboardUp.rootBottomPx - keyboardUp.observedImeBottomPx
        assertTrue(
            "The synthetic keyboard must expose a positive inset on the production " +
                "modal root. geometry=$keyboardUp",
            keyboardUp.observedImeBottomPx > 0,
        )
        // Reachability is asserted on a settled anchor, never mid-animation.
        waitUntilNamed(
            timeoutMs = REAL_IME_TIMEOUT_MS,
            label = "synthetic keyboard-up anchor settled",
            details = {
                "current=${sheetState.currentValue} target=${sheetState.targetValue} " +
                    "visible=${visible.value}"
            },
            unsatisfiableWhen = { visible.value.not() || sheetState.currentValue == SheetValue.Hidden },
            unsatisfiableReason = { "the sheet dismissed before the keyboard-up anchor settled" },
        ) {
            sheetState.currentValue == sheetState.targetValue
        }
        assertEquals(sheetState.targetValue, sheetState.currentValue)

        // Load-bearing reachability: every action the maintainer must be able
        // to see and tap stays inside the measured modal root AND above the
        // exact same-root keyboard boundary. Containment (all four edges),
        // never a bare assertIsDisplayed().
        REAL_IME_REACHABLE_TAGS.forEach { tag ->
            compose.onNodeWithTag(tag, useUnmergedTree = true)
                .assertIsDisplayed()
                .assertIsEnabled()
            assertNodeBelongsToMeasuredModalAndIsReachable(
                tag = tag,
                geometry = keyboardUp,
                keyboardTopPx = keyboardTopPx,
            )
        }
        // The whole Surface (drag handle included) must clear the boundary too,
        // so a partially-contained sheet cannot pass on its children alone.
        assertTrue(
            "The saturated production Surface must be fully above the exact same-root " +
                "keyboard boundary. keyboardTopPx=$keyboardTopPx geometry=$keyboardUp " +
                "state=${sheetState.currentValue}/${sheetState.targetValue}",
            keyboardUp.displayedSurfaceBottomPx <= keyboardTopPx + ROOT_SLOP_PX,
        )
        assertEquals(editedDraft, vm.uiState.value.draft)
        assertEquals(editedDraft, drafts.load(targetKey))
        assertPromptAExactlyOnce(queue, targetKey)
        assertTrue(visible.value)
        assertNotEquals(SheetValue.Hidden, sheetState.currentValue)
        println(
            "ISSUE1800_SYNTHETIC_REACH keyboardDown=$keyboardDown keyboardUp=$keyboardUp " +
                "keyboardTopPx=$keyboardTopPx " +
                "physicalImeWindows=${visiblePhysicalImeWindows()} " +
                "state=${sheetState.currentValue}/${sheetState.targetValue}",
        )
        assertNoServingPhysicalImeWindow(
            "Synthetic reachability screenshot must not contain a physical keyboard.",
        )
        WalkthroughScreenshotArtifacts.capture(
            "issue-1800-synthetic-ime-all-actions-reachable",
        )

        // Keyboard-down restoration after the synthetic inset is cleared.
        applyInsetsAndReadGeometry(imeBottomPx = 0, sheetState = sheetState)
        waitUntilNamed(
            timeoutMs = REAL_IME_TIMEOUT_MS,
            label = "synthetic keyboard-down anchor settled",
            details = {
                "current=${sheetState.currentValue} target=${sheetState.targetValue} " +
                    "visible=${visible.value}"
            },
            unsatisfiableWhen = { visible.value.not() || sheetState.currentValue == SheetValue.Hidden },
            unsatisfiableReason = { "the sheet dismissed before the keyboard-down anchor settled" },
        ) {
            sheetState.currentValue == sheetState.targetValue
        }
        assertEquals(sheetState.targetValue, sheetState.currentValue)
        val restored = readMountedGeometry(
            sheetState = sheetState,
            expectedImeBottomPx = 0,
        )
        val returnSlopPx = RETURN_GEOMETRY_SLOP_DP * displayDensity()
        assertEquals(0, restored.observedImeBottomPx)
        assertTrue(visible.value)
        assertNotEquals(SheetValue.Hidden, sheetState.currentValue)
        assertEquals(editedDraft, vm.uiState.value.draft)
        assertEquals(editedDraft, drafts.load(targetKey))
        assertTrue(
            "Hiding the synthetic keyboard must restore the stable keyboard-down " +
                "sheet geometry. before=$keyboardDown restored=$restored " +
                "slopPx=$returnSlopPx",
            abs(restored.surfaceTopPx - keyboardDown.surfaceTopPx) <= returnSlopPx &&
                abs(
                    restored.displayedSurfaceBottomPx -
                        keyboardDown.displayedSurfaceBottomPx,
                ) <= returnSlopPx,
        )
        REAL_IME_REACHABLE_TAGS.forEach { tag ->
            assertNodeBelongsToMeasuredModalAndIsReachable(
                tag = tag,
                geometry = restored,
                keyboardTopPx = restored.rootBottomPx,
            )
        }
        println(
            "ISSUE1800_SYNTHETIC_REACH_RESTORED restored=$restored " +
                "physicalImeWindows=${visiblePhysicalImeWindows()} " +
                "draftDurable=${drafts.load(targetKey) == editedDraft} " +
                "state=${sheetState.currentValue}/${sheetState.targetValue}",
        )
        assertNoServingPhysicalImeWindow(
            "The restored synthetic screenshot must not contain the system keyboard.",
        )
        WalkthroughScreenshotArtifacts.capture(
            "issue-1800-synthetic-ime-hidden-draft-restored",
        )

        deliveryGate.complete(Unit)
        waitUntilNamed(
            timeoutMs = 5_000,
            label = "prompt A delivery finished after synthetic restore",
            details = { "sendInFlight=${vm.uiState.value.sendInFlight}" },
        ) { !vm.uiState.value.sendInFlight }
        assertEquals(editedDraft, vm.uiState.value.draft)
        assertEquals(editedDraft, drafts.load(targetKey))
    }

    private fun newViewModel(
        drafts: ComposerDraftStore,
        queue: OutboundQueueStore,
    ): PromptComposerViewModel = PromptComposerViewModel(
        audioRecorder = TestMicCapture(),
        whisperClientFactory = WhisperClientFactory {
            object : WhisperClient {
                override suspend fun transcribe(
                    audio: ByteArray,
                    language: String?,
                ): Result<String> = Result.success("")
            }
        },
        apiKeyStorage = TestVault(),
        voiceSettings = TestVoiceSettings(),
        composerDraftStore = drafts,
        outboundQueueStore = queue,
    ).also { viewModel = it }

    /**
     * Issue #2139: a named wait with an optional unsatisfiable fast-fail.
     * The #2126 lesson — a wait that can never succeed is indistinguishable
     * from slowness if the timeout message has no condition name or state.
     */
    private fun waitUntilNamed(
        timeoutMs: Long,
        label: String,
        details: () -> String = { "" },
        unsatisfiableWhen: () -> Boolean = { false },
        unsatisfiableReason: () -> String = { "" },
        condition: () -> Boolean,
    ) {
        var lastDetails = ""
        var unsatisfiable = false
        val startedAt = SystemClock.elapsedRealtime()
        val satisfied = runCatching {
            compose.waitUntil(timeoutMs) {
                lastDetails = details()
                if (condition()) return@waitUntil true
                unsatisfiable = unsatisfiableWhen()
                unsatisfiable
            }
            true
        }.getOrDefault(false)
        if (unsatisfiable && !condition()) {
            throw AssertionError(
                "Condition '$label' can no longer become true: " +
                    "${unsatisfiableReason()}. Aborted after " +
                    "${SystemClock.elapsedRealtime() - startedAt}ms of a " +
                    "${timeoutMs}ms budget. $lastDetails",
            )
        }
        assertTrue(
            "Condition '$label' still not satisfied after ${timeoutMs}ms. $lastDetails",
            satisfied && condition(),
        )
    }

    private fun sheetWaitDetails(
        vm: PromptComposerViewModel,
        sheetStateRef: AtomicReference<SheetState?>,
        visible: Boolean,
    ): String =
        "composerTarget=${vm.composerTarget} visible=$visible " +
            "sheet=${sheetStateRef.get()?.currentValue} " +
            "draftLen=${vm.uiState.value.draft.length}"

    private fun draftWaitDetails(
        vm: PromptComposerViewModel,
        drafts: ComposerDraftStore,
        targetKey: String,
        expected: String,
    ): String =
        "uiDraftLen=${vm.uiState.value.draft.length} " +
            "durableLen=${drafts.load(targetKey)?.length ?: -1} " +
            "expectedLen=${expected.length} " +
            "uiMatches=${vm.uiState.value.draft == expected} " +
            "durableMatches=${drafts.load(targetKey) == expected}"

    private fun prepareActivityWindow() {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            val dark = PocketShellColors.Background.toArgb()
            activity.window.decorView.setBackgroundColor(dark)
            @Suppress("DEPRECATION")
            activity.window.statusBarColor = dark
            @Suppress("DEPRECATION")
            activity.window.navigationBarColor = dark
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activity.window.isNavigationBarContrastEnforced = false
            }
        }
    }

    private fun assertPromptAExactlyOnce(
        queue: OutboundQueueStore,
        targetKey: String,
    ) {
        val rows = queue.itemsFor(targetKey)
        assertEquals("exactly one durable row must own prompt A", 1, rows.size)
        assertEquals("prompt A", rows.single().cleanText)
        assertEquals(
            "Sending status must have exactly one visible owner",
            1,
            compose.onAllNodesWithText(
                "Sending",
                substring = false,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size,
        )
        assertEquals(
            "prompt A preview must be rendered exactly once",
            1,
            compose.onAllNodesWithText(
                " · “prompt A”",
                substring = false,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size,
        )
    }

    private data class ModalGeometry(
        val observedImeBottomPx: Int,
        val rootBottomPx: Float,
        val rootBounds: androidx.compose.ui.geometry.Rect,
        val surfaceTopPx: Float,
        val surfaceHeightPx: Int,
        val displayedSurfaceBottomPx: Float,
        val sheetBounds: androidx.compose.ui.geometry.Rect,
        val sendBounds: androidx.compose.ui.geometry.Rect,
        val sheetNode: androidx.compose.ui.semantics.SemanticsNode,
    )

    private fun applyInsetsAndReadGeometry(
        imeBottomPx: Int,
        sheetState: SheetState,
    ): ModalGeometry {
        repeat(2) {
            dispatchSyntheticInsets(imeBottomPx)
            compose.waitForIdle()
        }
        return readMountedGeometry(
            sheetState = sheetState,
            expectedImeBottomPx = imeBottomPx,
        )
    }

    private fun readMountedGeometry(
        sheetState: SheetState,
        expectedImeBottomPx: Int? = null,
    ): ModalGeometry {
        val sheetNode = compose.onNodeWithTag(PRODUCTION_SHEET_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
        val observedIme = sheetNode.config.getOrNull(PRODUCTION_SHEET_IME_BOTTOM_PX)
            ?: -1
        val surfaceHeightPx =
            sheetNode.config.getOrNull(PRODUCTION_SHEET_HEIGHT_PX) ?: -1
        if (expectedImeBottomPx != null) {
            assertEquals(
                "The exact synthetic IME inset must be Compose-observed on the existing " +
                    "outer modifier, which Material3 applies to its real Surface.",
                expectedImeBottomPx,
                observedIme,
            )
        }
        assertTrue(
            "The outer modifier must measure Material's total Surface height.",
            surfaceHeightPx > 0,
        )
        val root = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            .single { it.root === sheetNode.root }
        val send = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
        assertTrue(send.root === sheetNode.root)
        val surfaceTopPx = sheetState.requireOffset()
        return ModalGeometry(
            observedImeBottomPx = observedIme,
            rootBottomPx = root.boundsInRoot.bottom,
            rootBounds = root.boundsInRoot,
            surfaceTopPx = surfaceTopPx,
            surfaceHeightPx = surfaceHeightPx,
            displayedSurfaceBottomPx = surfaceTopPx + surfaceHeightPx,
            sheetBounds = sheetNode.boundsInRoot,
            sendBounds = send.boundsInRoot,
            sheetNode = sheetNode,
        )
    }

    private fun assertNodeBelongsToMeasuredModalAndIsReachable(
        tag: String,
        geometry: ModalGeometry,
        keyboardTopPx: Float,
    ) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val rootBounds = geometry.rootBounds
        assertTrue(
            "Node '$tag' must belong to the measured production modal root.",
            node.root === geometry.sheetNode.root,
        )
        assertTrue(
            "Node '$tag' must be fully reachable above the visible keyboard and " +
                "inside the modal root. node=$bounds keyboardTopPx=$keyboardTopPx " +
                "root=$rootBounds",
            bounds.left >= rootBounds.left - ROOT_SLOP_PX &&
                bounds.right <= rootBounds.right + ROOT_SLOP_PX &&
                bounds.top >= rootBounds.top - ROOT_SLOP_PX &&
                bounds.bottom <= keyboardTopPx + ROOT_SLOP_PX,
        )
    }

    private fun Modifier.observeProductionSheetIme(): Modifier = composed {
        val density = LocalDensity.current
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        val surfaceHeightPx = remember { mutableStateOf(-1) }
        onGloballyPositioned { coordinates ->
            surfaceHeightPx.value = coordinates.size.height
        }.testTag(PRODUCTION_SHEET_TAG).semantics {
            this[PRODUCTION_SHEET_IME_BOTTOM_PX] = imeBottomPx
            this[PRODUCTION_SHEET_HEIGHT_PX] = surfaceHeightPx.value
        }
    }

    private fun dispatchSyntheticInsets(imeBottomPx: Int) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Issue #1744 modal-root proof requires API 29+ WindowInspector; " +
                "deviceApi=${Build.VERSION.SDK_INT}"
        }
        // Issue #2139: leftover unfocused IME windows from an earlier class on
        // the shared emulator used to fail this precondition. Hide first, then
        // only refuse dispatch when a SERVING keyboard remains.
        hideRealImeBestEffort()
        assertNoServingPhysicalImeWindow(
            "Synthetic #1744 proof cannot dispatch while a serving physical IME is visible.",
        )
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
            .setVisible(WindowInsetsCompat.Type.ime(), imeBottomPx > 0)
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 0))
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 0, 0, 0))
            .build()
        compose.activityRule.scenario.onActivity { activity ->
            val roots = activeAppWindowRoots(activity)
            val activityDecor = activity.window.decorView
            val modalRoots = roots.filterNot { it === activityDecor }
            check(roots.any { it === activityDecor }) {
                "Active app roots must include the activity decor."
            }
            check(modalRoots.isNotEmpty()) {
                "Mounted PromptComposerSheet modal root disappeared before inset dispatch."
            }
            val activityIme = ViewCompat.getRootWindowInsets(activityDecor)
            check(
                activityIme?.isVisible(WindowInsetsCompat.Type.ime()) != true &&
                    (activityIme?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0) == 0,
            ) {
                "Synthetic #1744 proof cannot start while the activity owns a real " +
                    "IME inset: insets=$activityIme"
            }
            println(
                "ISSUE1744_SYNTHETIC_ROOTS imeBottomPx=$imeBottomPx count=${roots.size} " +
                    "modalCount=${modalRoots.size} roots=" +
                    roots.map { "${it.javaClass.name}:${it.width}x${it.height}" },
            )
            // Keep the activity decor on its real keyboard-hidden insets. Only
            // the production ModalBottomSheet root receives the deterministic
            // synthetic inset, so the activity remains an independent physical-
            // IME sentinel and screenshots can never silently mix LatinIME with
            // the synthetic boundary.
            modalRoots.forEach { dispatchSyntheticWindowInsets(it, insets) }
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

    private fun hideRealImeAndAssertHidden(message: String) {
        hideRealImeBestEffort()
        assertTrue(
            "$message A serving physical input-method window remained visible: " +
                "serving=${servingPhysicalImeWindows()} " +
                "residual=${visiblePhysicalImeWindows()}",
            waitForServingPhysicalImeWindow(expected = false),
        )
        assertTrue(
            "$message An app root still reports a visible positive real-IME inset.",
            waitForAnyAppRootImeVisible(expected = false),
        )
        // Keep the established framework signal in the contract as well as the
        // cross-window checks above. The activity decor must explicitly report
        // visibility=false, never merely a zero-height synthetic replacement.
        assertFalse(
            "$message Activity decor did not report IME visibility=false.",
            waitForInputMethodVisible(
                scenario = compose.activityRule.scenario,
                expected = false,
                timeoutMs = REAL_IME_TIMEOUT_MS,
            ),
        )
        assertNoServingPhysicalImeWindow(message)
    }

    private fun hideRealImeBestEffort() {
        runCatching {
            compose.activityRule.scenario.onActivity { activity ->
                val inputMethodManager =
                    activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                activeAppWindowRoots(activity).forEach { root ->
                    ViewCompat.getWindowInsetsController(root)
                        ?.hide(WindowInsetsCompat.Type.ime())
                    root.windowToken?.let { token ->
                        inputMethodManager.hideSoftInputFromWindow(token, 0)
                    }
                }
                ViewCompat.getWindowInsetsController(activity.window.decorView)
                    ?.hide(WindowInsetsCompat.Type.ime())
            }
        }
    }

    private fun assertNoServingPhysicalImeWindow(message: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline =
            SystemClock.elapsedRealtime() + PHYSICAL_IME_ABSENCE_STABILITY_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val serving = servingPhysicalImeWindows()
            val residual = visiblePhysicalImeWindows()
            assertTrue(
                "$message servingImeWindows=$serving residualImeWindows=$residual",
                serving.isEmpty(),
            )
            SystemClock.sleep(50)
        }
    }

    /**
     * A leftover `TYPE_INPUT_METHOD` window that is neither active nor focused
     * is ambient emulator residue (issue #2139 inverse signature). Only a
     * serving keyboard can mix with the synthetic boundary.
     */
    private fun servingPhysicalImeWindows(): List<String> =
        visiblePhysicalImeWindows().filter { description ->
            description.contains("active=true") || description.contains("focused=true")
        }

    private fun waitForServingPhysicalImeWindow(
        expected: Boolean,
        timeoutMs: Long = REAL_IME_TIMEOUT_MS,
    ): Boolean = waitForWallClock(timeoutMs) {
        servingPhysicalImeWindows().isNotEmpty() == expected
    }

    private fun waitForAnyAppRootImeVisible(
        expected: Boolean,
        timeoutMs: Long = REAL_IME_TIMEOUT_MS,
    ): Boolean = waitForWallClock(timeoutMs) {
        readAnyAppRootImeVisible() == expected
    }

    private fun waitForWallClock(
        timeoutMs: Long,
        predicate: () -> Boolean,
    ): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            if (predicate()) return true
            SystemClock.sleep(50)
        }
        return predicate()
    }

    private fun readAnyAppRootImeVisible(): Boolean {
        var visible = false
        compose.activityRule.scenario.onActivity { activity ->
            visible = activeAppWindowRoots(activity).any { root ->
                val insets = ViewCompat.getRootWindowInsets(root)
                insets?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
            }
        }
        return visible
    }

    /**
     * Accessibility exposes the physical IME as a distinct
     * [AccessibilityWindowInfo.TYPE_INPUT_METHOD] window. That signal is
     * independent of the synthetic WindowInsetsCompat object dispatched to the
     * app's modal root, so it catches the exact fixture bug from the 2026-07-25
     * preflight: a green synthetic boundary while LatinIME was still visibly
     * covering the controls.
     */
    private fun visiblePhysicalImeWindows(): List<String> =
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .let { automation ->
                val serviceInfo = automation.serviceInfo
                if (
                    serviceInfo.flags and
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0
                ) {
                    serviceInfo.flags =
                        serviceInfo.flags or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    automation.serviceInfo = serviceInfo
                }
                automation.windows
                    .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    .map { window ->
                        val rootPackage = runCatching {
                            window.root?.packageName?.toString()
                        }.getOrNull()
                        "package=$rootPackage active=${window.isActive} " +
                            "focused=${window.isFocused} bounds=" +
                            "${android.graphics.Rect().also(window::getBoundsInScreen)}"
                    }
            }

    private fun displayDensity(): Float =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

    private class TestMicCapture : PromptComposerViewModel.MicCapture {
        override fun start() = Unit
        override fun stop(): ByteArray = ByteArray(0)
        override fun currentAmplitude(): Float = 0f
    }

    private class TestVault : PromptComposerViewModel.ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()
        override fun save(key: CharArray) {
            this.key = key.copyOf()
        }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() {
            key = null
        }
    }

    private class TestVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
    }

    private companion object {
        const val SYNTHETIC_IME_HEIGHT_DP = 295f
        const val SHORT_SYNTHETIC_IME_HEIGHT_DP = 96f
        const val ROOT_SLOP_PX = 2f
        const val RETURN_GEOMETRY_SLOP_DP = 2f
        const val MINIMUM_TERMINAL_FRACTION = 0.25f
        const val REAL_IME_TIMEOUT_MS = 30_000L
        const val PHYSICAL_IME_ABSENCE_STABILITY_MS = 500L
        const val PRODUCTION_SHEET_TAG = "issue1744-production-composer-sheet"
        const val TERMINAL_MARKER_TAG = "issue1744-terminal-marker"
        const val TERMINAL_MARKER = "agent output remains visible above the composer"
        const val POST_IME_SUFFIX = " remains editable above the keyboard"
        const val SYNTHETIC_REACH_SUFFIX =
            " edited while the synthetic keyboard boundary is up"

        /**
         * Issue #1800 / #2139: the controls whose keyboard-up reachability is
         * the load-bearing property of the synthetic-inset journey.
         */
        val REAL_IME_REACHABLE_TAGS = listOf(
            COMPOSER_DRAFT_TAG,
            COMPOSER_SEND_ENTER_TAG,
            COMPOSER_ATTACH_TAG,
            COMPOSER_MIC_TAG,
        )
        val LONG_DRAFT_B = (1..14).joinToString(separator = " ") {
            "draft B segment $it stays durable while prompt A is sending;"
        }
        val PRODUCTION_SHEET_IME_BOTTOM_PX =
            SemanticsPropertyKey<Int>("Issue1744ProductionSheetImeBottomPx")
        val PRODUCTION_SHEET_HEIGHT_PX =
            SemanticsPropertyKey<Int>("Issue1744ProductionSheetHeightPx")
    }
}
