package com.pocketshell.app.composer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.app.session.UNDELIVERED_TRANSCRIPT_BANNER_TAG
import com.pocketshell.app.session.UNDELIVERED_TRANSCRIPT_RETRY_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Issue #1272 — the PRODUCTION-WIRING proof (the #1341 reviewer's blocking gap).
 *
 * The sibling [PromptComposerUndeliveredRetryTest] cases all BYPASS the guard in
 * `rememberComposerUndeliveredBinding`:
 *  - tests 1–2 call `SheetContent(...)` directly with the list injected;
 *  - test 3 forces the surface via the `inlineDictationViewModel` OVERRIDE seam.
 *
 * So `rememberComposerUndeliveredBinding`'s REAL branch —
 * `storeOwner is GeneratedComponentManagerHolder -> hiltViewModel(storeOwner)` —
 * never executes there. Those prove "SheetContent renders a banner when handed a
 * list", NOT that in the real composer the guard resolves the activity-scoped
 * [InlineDictationViewModel] that `TmuxSessionScreen` collects.
 *
 * This test closes that gap. It launches the REAL `@AndroidEntryPoint`
 * [ComposerHiltHostActivity], which mounts the PRODUCTION [PromptComposerSheet]
 * with NO seam (`inlineDictationViewModel` left null). Because the host is a
 * `GeneratedComponentManagerHolder`, the guard takes its REAL Hilt branch and
 * resolves [InlineDictationViewModel] from the live app graph — the exact path
 * the session screen uses.
 *
 * The test then reaches the SAME activity-scoped VM instance via
 * `ViewModelProvider(activity)` (the composer's `hiltViewModel()` and
 * `ViewModelProvider(activity)` share the activity's ViewModelStore + the Hilt
 * factory, so it is literally the same object the composer resolved), drives a
 * durable undelivered transcript into that VM's own store, and asserts:
 *
 *  1. The composer surfaces the persisted transcript as a VISIBLE retry row —
 *     asserted with viewport CONTAINMENT (`assertNodeFullyWithinRoot`, not a bare
 *     `assertIsDisplayed`, per #657/F1). This is what PROVES the guard resolved
 *     the SAME VM the test drove: if the guard had taken the inert `null` branch
 *     (the base/broken state), the composer would read an empty list and the
 *     banner tag would not exist.
 *  2. Tapping Retry re-injects the transcript into that VM's live delivery
 *     channel; a live collector on `vm.transcriptions` receives it (re-delivered
 *     to a live pane) and the row clears.
 *
 * There is no soft-IME dependency (pure Compose geometry), so there is NO
 * `assumeTrue` / `assumeFalse(isRunningOnCi())` on the load-bearing assertions —
 * they HARD-fail on every device, CI included.
 *
 * ## Driving the transcript
 *
 * The undelivered queue is the durable holding pen the whole #1272 feature is
 * about: `vm.undeliveredTranscripts` is exactly `vm.undeliveredTranscriptStore.items`.
 * Persisting into that store is precisely what the VM's own `onCleared` /
 * channel-overflow paths do to enqueue an undeliverable transcript (see
 * [InlineDictationViewModel.onCleared]). We drive it directly here rather than
 * re-run the permanent-dead-pane FSM because the Hilt-resolved production VM owns
 * a REAL microphone / Whisper client that cannot be driven to record in a test —
 * and the dead-pane FSM itself is already proven red→green by the JVM
 * `InlineDictationViewModelTest` and the sibling connected test. What is UNPROVEN
 * without this test, and what this test exists to prove, is the GUARD RESOLUTION:
 * that the live composer binds the production activity-scoped VM.
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerUndeliveredRetryHiltWiringTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComposerHiltHostActivity>()

    /**
     * The SAME activity-scoped [InlineDictationViewModel] the composer's
     * `hiltViewModel()` guard resolved: `ViewModelProvider(activity)` reads the
     * activity's ViewModelStore through its Hilt default factory, under the
     * default key (the canonical class name) — identical to `hiltViewModel()`.
     */
    private fun activityScopedInlineVm(): InlineDictationViewModel {
        lateinit var vm: InlineDictationViewModel
        compose.runOnUiThread {
            vm = ViewModelProvider(compose.activity)[InlineDictationViewModel::class.java]
        }
        return vm
    }

    private fun liveCollector(
        vm: InlineDictationViewModel,
        sink: CopyOnWriteArrayList<String>,
    ): Job {
        var job: Job? = null
        val scope = CoroutineScope(Dispatchers.Main)
        compose.runOnUiThread {
            job = scope.launch { vm.transcriptions.collect { sink.add(it) } }
        }
        compose.waitForIdle()
        return job!!
    }

    private var vmUnderTest: InlineDictationViewModel? = null

    @After
    fun drainDurableStore() {
        // The production VM binds the DURABLE SharedPreferences store; clear any
        // residue so a failed run can't leak an item into a sibling test/run.
        vmUnderTest?.let { vm ->
            vm.undeliveredTranscriptStore.snapshot().forEach { vm.undeliveredTranscriptStore.remove(it.id) }
        }
    }

    @Test
    fun liveComposerResolvesActivityScopedVmViaGuardAndSurfacesRetryRowThatReDelivers() {
        val vm = activityScopedInlineVm().also { vmUnderTest = it }
        // Start from a clean durable store (residue from a prior aborted run).
        vm.undeliveredTranscriptStore.snapshot().forEach { vm.undeliveredTranscriptStore.remove(it.id) }
        compose.waitForIdle()

        val delivered = CopyOnWriteArrayList<String>()
        val collector = liveCollector(vm, delivered)

        // Enqueue an undeliverable transcript into the PRODUCTION VM's own durable
        // store — the same enqueue the VM's onCleared / channel-overflow paths do.
        val text = "git push origin main"
        val item = vm.undeliveredTranscriptStore.persist(text)!!
        val id = item.id

        // The LIVE composer must now surface it. If the guard had taken the inert
        // `null` branch (the base/broken state), the composer would read an empty
        // list and this banner tag would never appear — so a rendered, CONTAINED
        // row proves the guard resolved the SAME activity-scoped VM we drove.
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(UNDELIVERED_TRANSCRIPT_BANNER_TAG)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UNDELIVERED_TRANSCRIPT_BANNER_TAG).assertIsDisplayed()
        assertNodeFullyWithinSheetWindow(UNDELIVERED_TRANSCRIPT_BANNER_TAG)
        compose.onNodeWithText("Couldn't deliver — retry").assertIsDisplayed()
        compose.onNodeWithText(text).assertIsDisplayed()
        assertNodeFullyWithinSheetWindow("$UNDELIVERED_TRANSCRIPT_RETRY_TAG-$id")

        // Retry must re-inject into the VM's live delivery channel and clear the row.
        compose.onNodeWithTag("$UNDELIVERED_TRANSCRIPT_RETRY_TAG-$id").performClick()
        compose.waitUntil(TIMEOUT_MS) { delivered.isNotEmpty() }
        assertEquals(
            "Retry from the live composer must re-deliver the transcript into the " +
                "activity-scoped VM's live delivery channel (proves the composer bound " +
                "the SAME production VM the session screen collects)",
            listOf(text),
            delivered.toList(),
        )
        compose.waitUntil(TIMEOUT_MS) { vm.undeliveredTranscriptStore.snapshot().isEmpty() }
        compose.onNodeWithTag(UNDELIVERED_TRANSCRIPT_BANNER_TAG).assertDoesNotExist()
        assertTrue(
            "the durable store must be empty after a successful re-delivery",
            vm.undeliveredTranscriptStore.snapshot().isEmpty(),
        )

        collector.cancel()
    }

    /**
     * Viewport CONTAINMENT for the retry surface — the #657/F1 property ("the user
     * can actually SEE + tap it", not merely `assertIsDisplayed`, which is
     * satisfied by layout participation even off-screen).
     *
     * The shared [com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot]
     * helper compares against `onRoot()`, but the full production
     * [PromptComposerSheet] opens a `ModalBottomSheet` — a SECOND full-screen
     * window root — so `onRoot()` is ambiguous here (fails "expected exactly 1
     * root, found 2"). This asserts the node's `boundsInRoot` sits within the
     * on-screen WINDOW rect (the union of the full-screen roots) instead, so a row
     * pushed off any window edge / clipped fails exactly as the shared helper
     * would in a single-root scene. NOT a bare `assertIsDisplayed`.
     */
    private fun assertNodeFullyWithinSheetWindow(tag: String) {
        val slopPx = 2f * compose.density.density
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().map { it.boundsInRoot }
        check(roots.isNotEmpty()) { "no window roots found" }
        val left = roots.minOf { it.left }
        val top = roots.minOf { it.top }
        val right = roots.maxOf { it.right }
        val bottom = roots.maxOf { it.bottom }
        assertTrue(
            "Node '$tag' is not fully within the on-screen window (off-screen / " +
                "clipped): nodeBounds=$bounds window=[l=$left,t=$top,r=$right,b=$bottom] " +
                "slopPx=$slopPx",
            bounds.left >= left - slopPx &&
                bounds.top >= top - slopPx &&
                bounds.right <= right + slopPx &&
                bounds.bottom <= bottom + slopPx,
        )
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
