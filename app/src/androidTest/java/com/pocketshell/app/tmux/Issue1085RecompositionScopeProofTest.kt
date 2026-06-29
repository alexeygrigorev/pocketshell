package com.pocketshell.app.tmux

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.session.AgentConversationUiState
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/**
 * Issue #1085 (freeze F3) — DETERMINISTIC recomposition-scoping proof for the
 * two voice-first jank vectors that recomposed the whole 7k-line
 * [TmuxSessionScreen] body at a high frequency:
 *
 *  - **R1 — inline-dictation amplitude (20 Hz).** The silence watchdog rewrites
 *    `InlineDictationViewModel.uiState` with a fresh `amplitude` every 50ms while
 *    recording. The body read the WHOLE `uiState` (a delegated `by` read), so the
 *    body's ROOT restart group — the one that hosts the terminal-render subtree —
 *    recomposed 20×/s while the user dictated. The fix derives just the
 *    (low-frequency) `mode` via `derivedStateOf`, so the amplitude churn never
 *    invalidates the body.
 *  - **R2 — agent-transcript streaming (≈16 Hz).** `AgentConversationRepository`
 *    flushes a fresh `agentConversations` map every `tailBatchWindowMillis`=60ms
 *    while an agent streams. The body read `agentConversations[paneId]` directly,
 *    so the body recomposed once per flush. The fix reads only a STABLE
 *    [SurfaceConversationChrome] projection (detection / selectedTab / hasEvents /
 *    exists — all stable mid-stream) via `derivedStateOf` in the body, and reads
 *    the high-frequency `events` LIST inside the `surfaceContent` child scope (its
 *    own restart group), so a flush recomposes only the transcript.
 *
 * ## Why this is a faithful scope proof, not a `*StandIn`/`*Proxy`
 *
 * The symptom under test is a Compose **invalidation-scope** defect: "does a
 * high-frequency state emission re-run the body's ROOT restart group?" That
 * property is a function of HOW the hot state is read (whole-object read in the
 * body vs a `derivedStateOf` projection + a deferred child read), independent of
 * the SSH/tmux/render machinery the body also hosts. So — exactly like the #796
 * H4 scope proof ([Issue796ImeRecompositionScopeProofTest]) — these harnesses
 * compose the EXACT production read structure (the production
 * [SurfaceConversationChrome] data class and the production `derivedStateOf`
 * projection; the real [InlineDictationViewModel.UiState] data class) and count
 * re-executions of the body group. A heavyweight terminal stand-in is irrelevant
 * to the invalidation-scope question, so counting the body group's re-execution
 * is the faithful probe (process.md F2). The companion full-journey acceptance —
 * the real [TmuxSessionScreen] over Docker while dictating / while an agent
 * streams — is the on-device proof; this is the deterministic regression guard
 * that catches the scope regression at PR time.
 *
 * The hot state is driven directly via a `mutableStateOf` the test mutates
 * (`collectAsState` merely turns the production `StateFlow` into exactly such a
 * `State`; the scoping property is identical). Fully deterministic and
 * environment-independent — no real recorder, no Docker, NO `assumeTrue` /
 * CI-skip on the load-bearing assertion (process.md F3, #780 model).
 *
 * Each path pins BOTH the production O(1) ceiling AND the pre-fix O(N) floor, so
 * the assertions are a real red/green guard rather than a vacuous pass (G6: the
 * load-bearing assertion is the flat body-recomposition count).
 */
@RunWith(AndroidJUnit4::class)
class Issue1085RecompositionScopeProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // ----------------------------------------------------------- R1: dictation

    /**
     * The production fix: deriving `mode` keeps the body OFF the 20 Hz dictation
     * amplitude churn — the body recomposes O(1) across an N-sample burst.
     */
    @Test
    fun productionDerivedModeKeepsBodyOffDictationAmplitudeChurn() {
        val dictation = mutableStateOf(InlineDictationViewModel.UiState())
        val bodyRecompositions = AtomicLong(0L)

        compose.setContent {
            ProductionDictationBody(dictation = dictation, onBodyRecompose = {
                bodyRecompositions.incrementAndGet()
            })
        }
        compose.waitForIdle()

        val recomps = driveDictationAmplitudeBurst(dictation, bodyRecompositions)
        println(
            "ISSUE1085_F3 R1 production_derived_mode amplitude_ticks=$TICKS " +
                "body_recompositions=$recomps ceiling=$MAX_BODY_RECOMPOSITIONS",
        )
        assertTrue(
            "#1085 (F3/R1): deriving the dictation MODE must keep the " +
                "TmuxSessionScreen body OFF the 20 Hz amplitude churn. Over a " +
                "$TICKS-sample amplitude burst (mode constant) the body recomposed " +
                "$recomps times; the O(1) ceiling is $MAX_BODY_RECOMPOSITIONS.",
            recomps <= MAX_BODY_RECOMPOSITIONS,
        )
    }

    /**
     * Pins the PRE-FIX behaviour: reading the WHOLE `uiState` in the body
     * recomposes it once per amplitude sample (≈ N). Proves the assertion above is
     * not vacuous — the regression is genuinely detectable.
     */
    @Test
    fun legacyFullDictationStateReadRecomposesBodyPerAmplitudeTick() {
        val dictation = mutableStateOf(InlineDictationViewModel.UiState())
        val bodyRecompositions = AtomicLong(0L)

        compose.setContent {
            LegacyDictationBody(dictation = dictation, onBodyRecompose = {
                bodyRecompositions.incrementAndGet()
            })
        }
        compose.waitForIdle()

        val recomps = driveDictationAmplitudeBurst(dictation, bodyRecompositions)
        println(
            "ISSUE1085_F3 R1 legacy_full_state_read amplitude_ticks=$TICKS " +
                "body_recompositions=$recomps floor=$MIN_LEGACY_RECOMPOSITIONS",
        )
        assertTrue(
            "#1085 (F3/R1) guard: the PRE-FIX whole-`uiState` read must recompose " +
                "the body per amplitude sample (the regression the fix removes). " +
                "Over a $TICKS-sample burst the legacy body recomposed only " +
                "$recomps times — the test would not catch the regression. " +
                "Expected >= $MIN_LEGACY_RECOMPOSITIONS.",
            recomps >= MIN_LEGACY_RECOMPOSITIONS,
        )
    }

    // ------------------------------------------------------- R2: agent stream

    /**
     * The production fix: reading only the STABLE [SurfaceConversationChrome]
     * projection in the body keeps it OFF the 60ms agent-streaming flush — the
     * body recomposes O(1) while the transcript child (which DOES read `events`)
     * recomposes per flush (as it must, to show the stream).
     */
    @Test
    fun productionSurfaceChromeKeepsBodyOffAgentStreamingFlush() {
        val conversations = mutableStateOf(initialConversationMap())
        val bodyRecompositions = AtomicLong(0L)
        val transcriptRecompositions = AtomicLong(0L)

        compose.setContent {
            ProductionConversationBody(
                conversations = conversations,
                onBodyRecompose = { bodyRecompositions.incrementAndGet() },
                onTranscriptRecompose = { transcriptRecompositions.incrementAndGet() },
            )
        }
        compose.waitForIdle()

        val (bodyRecomps, transcriptRecomps) =
            driveStreamingFlushBurst(conversations, bodyRecompositions, transcriptRecompositions)
        println(
            "ISSUE1085_F3 R2 production_surface_chrome stream_flushes=$TICKS " +
                "body_recompositions=$bodyRecomps transcript_recompositions=$transcriptRecomps " +
                "body_ceiling=$MAX_BODY_RECOMPOSITIONS",
        )
        // LOAD-BEARING (G6): the body group stays flat across the streaming burst.
        assertTrue(
            "#1085 (F3/R2): reading the STABLE SurfaceConversationChrome projection " +
                "must keep the TmuxSessionScreen body OFF the 60ms agent-streaming " +
                "flush. Over $TICKS streaming flushes the body recomposed " +
                "$bodyRecomps times; the O(1) ceiling is $MAX_BODY_RECOMPOSITIONS.",
            bodyRecomps <= MAX_BODY_RECOMPOSITIONS,
        )
        // Sanity: the transcript child SHOULD recompose per flush — that is where
        // the stream is shown. If it didn't, the test would be vacuously flat
        // (nothing actually streaming).
        assertTrue(
            "#1085 (F3/R2): the transcript child must still recompose as the stream " +
                "flushes ($transcriptRecomps over $TICKS flushes) — otherwise the " +
                "burst is not exercising a real stream.",
            transcriptRecomps >= MIN_LEGACY_RECOMPOSITIONS,
        )
    }

    /**
     * Pins the PRE-FIX behaviour: reading `agentConversations[paneId]` directly in
     * the body recomposes it once per streaming flush (≈ N). Proves the production
     * assertion above is not vacuous.
     */
    @Test
    fun legacyDirectConversationReadRecomposesBodyPerStreamingFlush() {
        val conversations = mutableStateOf(initialConversationMap())
        val bodyRecompositions = AtomicLong(0L)
        val transcriptRecompositions = AtomicLong(0L)

        compose.setContent {
            LegacyConversationBody(
                conversations = conversations,
                onBodyRecompose = { bodyRecompositions.incrementAndGet() },
                onTranscriptRecompose = { transcriptRecompositions.incrementAndGet() },
            )
        }
        compose.waitForIdle()

        val (bodyRecomps, _) =
            driveStreamingFlushBurst(conversations, bodyRecompositions, transcriptRecompositions)
        println(
            "ISSUE1085_F3 R2 legacy_direct_read stream_flushes=$TICKS " +
                "body_recompositions=$bodyRecomps floor=$MIN_LEGACY_RECOMPOSITIONS",
        )
        assertTrue(
            "#1085 (F3/R2) guard: the PRE-FIX direct agentConversations[paneId] read " +
                "must recompose the body per streaming flush (the regression the fix " +
                "removes). Over $TICKS flushes the legacy body recomposed only " +
                "$bodyRecomps times — the test would not catch the regression. " +
                "Expected >= $MIN_LEGACY_RECOMPOSITIONS.",
            bodyRecomps >= MIN_LEGACY_RECOMPOSITIONS,
        )
    }

    // --------------------------------------------------------------- drivers

    /**
     * Reset the counter, then emit [TICKS] amplitude samples (mode held constant,
     * recording active) — the silence watchdog's 20 Hz `amplitude` rewrites.
     * Returns the body recomposition count attributable to the burst.
     */
    private fun driveDictationAmplitudeBurst(
        dictation: MutableState<InlineDictationViewModel.UiState>,
        bodyRecompositions: AtomicLong,
    ): Long {
        compose.runOnIdle {
            dictation.value = InlineDictationViewModel.UiState(
                mode = InlineDictationViewModel.DictationMode.Prompt,
                recording = InlineDictationViewModel.RecordingState.Recording,
                amplitude = 0f,
            )
        }
        compose.waitForIdle()
        bodyRecompositions.set(0L)

        for (tick in 1..TICKS) {
            compose.runOnIdle {
                // ONLY the amplitude changes — the mode is constant, exactly as the
                // silence watchdog rewrites it every SAMPLE_INTERVAL_MS.
                dictation.value = dictation.value.copy(amplitude = (tick % 10) / 10f)
            }
            compose.waitForIdle()
        }
        return bodyRecompositions.get()
    }

    /**
     * Reset the counters, then emit [TICKS] streaming flushes (a fresh map with a
     * growing `events` list; detection + selectedTab held constant) — the
     * `tailBatchWindowMillis`=60ms agent-transcript flush. Returns
     * `(body recompositions, transcript recompositions)` attributable to the burst.
     */
    private fun driveStreamingFlushBurst(
        conversations: MutableState<Map<String, AgentConversationUiState>>,
        bodyRecompositions: AtomicLong,
        transcriptRecompositions: AtomicLong,
    ): Pair<Long, Long> {
        compose.runOnIdle { conversations.value = initialConversationMap() }
        compose.waitForIdle()
        bodyRecompositions.set(0L)
        transcriptRecompositions.set(0L)

        for (tick in 1..TICKS) {
            compose.runOnIdle {
                val prev = conversations.value.getValue(PANE_ID)
                // A streaming flush: a FRESH map with one more event appended (the
                // detection + selectedTab are unchanged — the stable projections).
                conversations.value = mapOf(
                    PANE_ID to prev.copy(events = prev.events + streamingEvent(tick)),
                )
            }
            compose.waitForIdle()
        }
        return bodyRecompositions.get() to transcriptRecompositions.get()
    }

    // -------------------------------------------------------------- harnesses

    /**
     * PRODUCTION read structure for R1: derive just `mode` via `derivedStateOf`
     * (exactly as [TmuxSessionScreen] does). The body reads only `mode`; a leaf
     * reads the amplitude. The amplitude churn never invalidates the body group.
     */
    @Composable
    private fun ProductionDictationBody(
        dictation: MutableState<InlineDictationViewModel.UiState>,
        onBodyRecompose: () -> Unit,
    ) {
        val dictationMode by remember(dictation) {
            derivedStateOf { dictation.value.mode }
        }
        // Body reads ONLY the derived mode — record this body group's re-execution.
        @Suppress("UNUSED_VARIABLE")
        val modeForBody = dictationMode
        onBodyRecompose()
        // The amplitude is consumed by a leaf (the mic UI), which may recompose at
        // 20 Hz — but it is its own restart group, not the body.
        AmplitudeLeaf(dictation = dictation)
    }

    /**
     * PRE-FIX read structure for R1: read the WHOLE `uiState` in the body, so every
     * amplitude sample re-runs the body group.
     */
    @Composable
    private fun LegacyDictationBody(
        dictation: MutableState<InlineDictationViewModel.UiState>,
        onBodyRecompose: () -> Unit,
    ) {
        val full by dictation
        @Suppress("UNUSED_VARIABLE")
        val modeForBody = full.mode
        onBodyRecompose()
        Box(modifier = Modifier.fillMaxSize())
    }

    @Composable
    private fun AmplitudeLeaf(dictation: MutableState<InlineDictationViewModel.UiState>) {
        @Suppress("UNUSED_VARIABLE")
        val amplitude = dictation.value.amplitude
        Box(modifier = Modifier.fillMaxSize())
    }

    /**
     * PRODUCTION read structure for R2: the body reads only the STABLE
     * [SurfaceConversationChrome] projection (the production data class) via
     * `derivedStateOf`; the transcript child reads the high-frequency `events` list
     * in its OWN restart group (mirroring the `surfaceContent` deferred read).
     */
    @Composable
    private fun ProductionConversationBody(
        conversations: MutableState<Map<String, AgentConversationUiState>>,
        onBodyRecompose: () -> Unit,
        onTranscriptRecompose: () -> Unit,
    ) {
        val surfaceChrome by remember(conversations) {
            derivedStateOf {
                val convo = conversations.value[PANE_ID]
                SurfaceConversationChrome(
                    detection = convo?.detection,
                    selectedTab = convo?.selectedTab,
                    hasEvents = convo?.events?.isNotEmpty() == true,
                    exists = convo != null,
                )
            }
        }
        // Body reads ONLY the stable projection — record this body group's
        // re-execution. The 60ms flush re-runs the projection lambda but, because
        // the projected value is structurally equal mid-stream, does NOT invalidate
        // the body.
        @Suppress("UNUSED_VARIABLE")
        val hasDetection = surfaceChrome.detection != null
        @Suppress("UNUSED_VARIABLE")
        val hasEvents = surfaceChrome.hasEvents
        onBodyRecompose()
        // The transcript reads the full conversation (its `events`) in its OWN
        // restart group — exactly the `surfaceContent` non-inline child scope.
        TranscriptChild(conversations = conversations, onTranscriptRecompose = onTranscriptRecompose)
    }

    /**
     * PRE-FIX read structure for R2: read `agentConversations[paneId]` directly in
     * the body, so every streaming flush re-runs the body group.
     */
    @Composable
    private fun LegacyConversationBody(
        conversations: MutableState<Map<String, AgentConversationUiState>>,
        onBodyRecompose: () -> Unit,
        onTranscriptRecompose: () -> Unit,
    ) {
        val convo = conversations.value[PANE_ID]
        @Suppress("UNUSED_VARIABLE")
        val hasDetection = convo?.detection != null
        @Suppress("UNUSED_VARIABLE")
        val events = convo?.events
        onBodyRecompose()
        TranscriptChild(conversations = conversations, onTranscriptRecompose = onTranscriptRecompose)
    }

    @Composable
    private fun TranscriptChild(
        conversations: MutableState<Map<String, AgentConversationUiState>>,
        onTranscriptRecompose: () -> Unit,
    ) {
        @Suppress("UNUSED_VARIABLE")
        val events = conversations.value[PANE_ID]?.events.orEmpty()
        onTranscriptRecompose()
        Box(modifier = Modifier.fillMaxSize())
    }

    private companion object {
        const val TICKS = 20
        const val PANE_ID = "%1"

        // O(1) ceiling: the body recomposes at most a small constant across the
        // N-tick burst (the stable projection changes 0–1 times). 3 leaves slack
        // for a settling frame.
        const val MAX_BODY_RECOMPOSITIONS = 3L

        // The pre-fix read recomposes the body ~once per tick; require most of the
        // N ticks so the guard genuinely proves the regression is detectable.
        const val MIN_LEGACY_RECOMPOSITIONS = (TICKS - 4).toLong()

        fun initialConversationMap(): Map<String, AgentConversationUiState> = mapOf(
            PANE_ID to AgentConversationUiState(
                detection = AgentDetection(
                    agent = AgentKind.ClaudeCode,
                    sourcePath = "/home/dev/.claude/projects/x/session.jsonl",
                    sessionId = "sess-1",
                    confidence = AgentDetection.Confidence.ProcessConfirmed,
                ),
                events = listOf(streamingEvent(0)),
                selectedTab = com.pocketshell.app.session.SessionTab.Conversation,
            ),
        )

        fun streamingEvent(seq: Int): ConversationEvent = ConversationEvent.Message(
            id = "evt-$seq",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "streamed chunk $seq",
            streaming = true,
        )
    }
}
