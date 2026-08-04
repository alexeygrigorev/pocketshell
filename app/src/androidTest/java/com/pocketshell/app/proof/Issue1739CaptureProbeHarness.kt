package com.pocketshell.app.proof

import com.pocketshell.app.composer.OutboundQueueStore
import com.pocketshell.app.composer.OutboundState
import com.pocketshell.app.tmux.AGENT_SUBMIT_ACK_TIMEOUT_MS
import com.pocketshell.app.tmux.AgentSubmitCaptureSeams
import com.pocketshell.app.tmux.TmuxSessionViewModel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

internal class Issue1739CaptureProbe {
    val observed = AtomicBoolean(false)
    val firstRealCapture = AtomicReference("")
    val routedRowId = AtomicReference("")
    val routedSessionKey = AtomicReference("")
    val readyOnly = CopyOnWriteArrayList<String>()
    val rejected = CopyOnWriteArrayList<String>()
    val matched = AtomicReference("")
    lateinit var timeoutDetails: () -> String
}

/**
 * Correlates the chip-bearing callback with its exact durable route, capture,
 * pane, connection generation, and transcript-submit invocation.
 */
internal class Issue1739CaptureProbeHarness(
    private val diagnostics: RecordingDiagnosticSink,
    private val capturePane: suspend () -> String,
    private val countCollapsedPasteChips: (String) -> Int,
    private val boundedEventTail: (List<RecordedDiagnosticEvent>, Int) -> String,
    private val sessionName: String,
    private val fakeAgentReady: String,
) {
    suspend fun arm(
        viewModel: TmuxSessionViewModel,
        store: OutboundQueueStore,
        sessionKey: String,
        expectedClient: Int,
        onCancelled: suspend () -> Unit,
    ): Issue1739CaptureProbe {
        val probe = Issue1739CaptureProbe()
        val expectedGeneration = viewModel.currentConnectGenerationForTest()
        val collapsedChipBaseline = countCollapsedPasteChips(capturePane())
        val diagnosticOffset = diagnostics.events.size
        AgentSubmitCaptureSeams.afterRealCapture = captureHook@{ observation ->
            val capture = observation.response.output.joinToString("\n")
            val events = diagnostics.events.drop(diagnosticOffset)
            val routeEvent = events.lastOrNull {
                it.name == "composer_tmux_send_route" && it.fields["rowId"] != "none"
            }
            val routeRowId = routeEvent?.fields?.get("rowId") as? String
            val routeSessionKey = routeEvent?.fields?.get("sessionKey") as? String
            val row = routeRowId?.let(store::item)
            val claimIndex = events.indexOfFirst {
                it.name == "row_state" &&
                    it.fields["itemId"] == row?.id &&
                    it.fields["toState"] == "InFlight" &&
                    it.fields["reason"] == "claimed"
            }
            val routeIndex = routeEvent?.let(events::indexOf) ?: -1
            val startIndex = events.indexOfFirst {
                it.name == "agent_submit_capture" &&
                    it.fields["result"] == "started" &&
                    it.fields["captureId"] == observation.captureId &&
                    it.fields["timeoutMs"] == observation.timeoutMs &&
                    it.fields["pane"] == observation.paneId &&
                    it.fields["clientHash"] == observation.clientHash &&
                    it.fields["generation"] == observation.generation &&
                    it.fields["session"] == observation.session
            }
            val chipCount = countCollapsedPasteChips(capture)
            val identityMatches =
                observation.scrollbackLines == 0 &&
                    observation.timeoutMs == AGENT_SUBMIT_ACK_TIMEOUT_MS &&
                    observation.paneId == row?.paneId &&
                    observation.clientHash == expectedClient &&
                    observation.generation == expectedGeneration &&
                    observation.session == sessionName &&
                    viewModel.currentClientIdentityForTest() == expectedClient &&
                    viewModel.currentConnectGenerationForTest() == expectedGeneration &&
                    viewModel.currentTargetSessionKeyForTest() == sessionKey &&
                    row?.sessionKey == routeSessionKey
            val ordered =
                row != null &&
                    row.state == OutboundState.InFlight &&
                    row.wireAttempted &&
                    claimIndex >= 0 &&
                    routeIndex > claimIndex &&
                    startIndex > routeIndex
            val summary =
                "captureId=${observation.captureId} pane=${observation.paneId} " +
                    "timeout=${observation.timeoutMs} " +
                    "client=${observation.clientHash} generation=${observation.generation} " +
                    "session=${observation.session} row=${row?.id}/${row?.state}/" +
                    "${row?.wireAttempted} order=$claimIndex<$routeIndex<$startIndex " +
                    "chips=$collapsedChipBaseline->$chipCount"

            if (
                observation.scrollbackLines == 0 &&
                capture.contains(fakeAgentReady) &&
                chipCount == collapsedChipBaseline
            ) {
                probe.readyOnly.addBounded("$summary\n${capture.take(CAPTURE_LIMIT)}")
                return@captureHook
            }
            if (
                !identityMatches ||
                !ordered ||
                observation.response.isError ||
                !capture.contains(fakeAgentReady) ||
                chipCount != collapsedChipBaseline + 1
            ) {
                probe.rejected.addBounded("$summary\n${capture.take(CAPTURE_LIMIT)}")
                return@captureHook
            }
            if (!probe.observed.compareAndSet(false, true)) return@captureHook
            probe.firstRealCapture.set(capture)
            probe.routedRowId.set(requireNotNull(routeRowId))
            probe.routedSessionKey.set(requireNotNull(routeSessionKey))
            probe.matched.set(summary.take(CAPTURE_LIMIT))
            try {
                CompletableDeferred<Unit>().await()
            } catch (cancelled: CancellationException) {
                onCancelled()
                throw cancelled
            }
        }
        probe.timeoutDetails = {
            val rows = store.itemsFor(sessionKey)
            val routedRows = diagnostics.events.drop(diagnosticOffset)
                .filter { it.name == "composer_tmux_send_route" }
                .takeLast(ROW_TAIL_COUNT)
                .map { event ->
                    val rowId = event.fields["rowId"] as? String
                    val routedSession = event.fields["sessionKey"] as? String
                    "route=$rowId@$routedSession item=${rowId?.let(store::item)} " +
                        "routedItems=${routedSession?.let(store::itemsFor)}"
                }
            val rowTail = rows.takeLast(ROW_TAIL_COUNT).joinToString {
                "id=${it.id.take(ID_LIMIT)} state=${it.state} " +
                    "attempt=${it.attemptCount} pane=${it.paneId?.take(ID_LIMIT)} " +
                    "wireAttempted=${it.wireAttempted}"
            }
            "currentClient=${viewModel.currentClientIdentityForTest()} " +
                "currentGeneration=${viewModel.currentConnectGenerationForTest()} " +
                "currentSession=${viewModel.currentTargetSessionKeyForTest()?.take(ID_LIMIT)} " +
                "expected=$expectedClient/$expectedGeneration/${sessionKey.take(ID_LIMIT)} " +
                "rowsTotal=${rows.size} rowsTail=[$rowTail] " +
                "routedRows=$routedRows " +
                "events=${boundedEventTail(diagnostics.events, diagnosticOffset)} " +
                "readyOnly=${probe.readyOnly.boundedEntries()} " +
                "rejected=${probe.rejected.boundedEntries()}"
        }
        return probe
    }

    private fun CopyOnWriteArrayList<String>.addBounded(value: String) {
        if (size < CAPTURE_LIMIT_COUNT) add(value.take(CAPTURE_LIMIT))
    }

    private fun List<String>.boundedEntries(): String =
        takeLast(CAPTURE_LIMIT_COUNT)
            .joinToString(prefix = "[", postfix = "]") { it.take(CAPTURE_LIMIT) }

    private companion object {
        const val CAPTURE_LIMIT = 1_024
        const val CAPTURE_LIMIT_COUNT = 3
        const val ROW_TAIL_COUNT = 4
        const val ID_LIMIT = 96
    }
}
