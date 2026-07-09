package com.pocketshell.core.tmux

import android.util.Log
import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val PANE_OUTPUT_EVENT_BUFFER = 256
private const val PANE_OUTPUT_BACKLOG_EVENTS = 4096
private const val PANE_OUTPUT_FIRST_SUBSCRIBER_REPLAY_GRACE_MS = 30_000L
private const val ISSUE_105_DIAG_TAG = "issue105-diag"

internal class PaneOutputPipe private constructor(
    val flow: MutableSharedFlow<ControlEvent.Output>,
    private val channel: Channel<ControlEvent.Output>,
    private val job: Job,
    private val onOverflow: (TmuxOutputBacklogOverflow) -> Unit,
    private val diagnosticFields: () -> Map<String, Any?>,
    // Issue #1297: when `true` the drain job stops emitting to [flow] and
    // frames accumulate in the bounded [channel] instead of being dropped
    // into a zero-subscriber flow. Driven by [pauseDelivery]/[resumeDelivery]
    // across the overflow-reseed producer swap.
    private val paused: MutableStateFlow<Boolean>,
    // Issue #1305: `true` while the drain loop holds a frame RECEIVED into its
    // local but not yet emitted (it parked at the pause boundary). The frame
    // is no longer in [channel], so [drainBacklog] cannot reach it -- this flag
    // lets a drain-during-pause mark it stale so it is dropped, not
    // double-applied on top of the authoritative capture snapshot.
    private val holdingFrameAtPause: AtomicBoolean = AtomicBoolean(false),
    // Issue #1305: armed by [drainBacklog] when it runs while a frame is parked
    // at the pause boundary; consumed by the drain loop on resume to DISCARD
    // that pre-capture frame instead of emitting it.
    private val discardParkedFrame: AtomicBoolean = AtomicBoolean(false),
    private val droppedEvents: AtomicInteger = AtomicInteger(0),
    private val overflowDiagnosticEmitted: AtomicBoolean = AtomicBoolean(false),
) {
    /** Issue #1297: freeze delivery so a collector gap holds, not drops. */
    fun pauseDelivery() {
        paused.value = true
    }

    /** Issue #1297: thaw delivery; held frames drain to the collector. */
    fun resumeDelivery() {
        paused.value = false
    }

    fun send(event: ControlEvent.Output) {
        val result = channel.trySend(event)
        if (result.isFailure) {
            val dropped = droppedEvents.incrementAndGet()
            onOverflow(TmuxOutputBacklogOverflow(event.paneId, dropped))
            if (overflowDiagnosticEmitted.compareAndSet(false, true)) {
                TmuxClientDiagnostics.record(
                    "tmux_client_pane_output_backlog_overflow",
                    diagnosticFields() + mapOf(
                        "pane" to event.paneId,
                        "bytes" to event.data.size,
                        "droppedEvents" to dropped,
                        "capacity" to PANE_OUTPUT_BACKLOG_EVENTS,
                    ),
                )
            }
            if (shouldLogTmuxDropCount(dropped)) {
                Log.w(
                    ISSUE_105_DIAG_TAG,
                    "tmux-output-backlog-overflow pane=${event.paneId} " +
                        "bytes=${event.data.size} droppedEvents=$dropped " +
                        "capacity=$PANE_OUTPUT_BACKLOG_EVENTS",
                    result.exceptionOrNull(),
                )
            }
        }
    }

    fun close() {
        channel.close()
        job.cancel()
    }

    /**
     * Issue #1205: discard every frame currently buffered in the live
     * channel (best-effort, non-blocking) and return the count drained.
     * The pipe's drain job keeps running; only the queued-but-undelivered
     * burst frames are dropped so a post-overflow reseed is authoritative.
     */
    fun drainBacklog(armStaleParkedFrameDiscard: Boolean): Int {
        // Issue #1305: a frame the drain loop received into its local and
        // PARKED at the pause boundary is NOT in [channel] -- the channel drain
        // below cannot reach it. Only the AUTHORITATIVE post-capture drain
        // ([armStaleParkedFrameDiscard] = true) may mark it for discard so it is
        // dropped on resume instead of double-applying on top of the fresh
        // capture snapshot. The pre-capture drain (step 2 of the overflow-reseed
        // swap) passes false: on a capture FAILURE it is the ONLY drain that
        // runs, and the parked frame must still REPLAY on resume (the #1297
        // held-frame guarantee), not be silently dropped.
        if (armStaleParkedFrameDiscard && holdingFrameAtPause.get()) {
            discardParkedFrame.set(true)
        }
        var drained = 0
        while (channel.tryReceive().isSuccess) {
            drained++
        }
        return drained
    }

    companion object {
        fun create(
            scope: CoroutineScope,
            paneId: String,
            preRegistrationReplay: List<ControlEvent.Output> = emptyList(),
            firstSubscriberReplayGraceMs: Long = PANE_OUTPUT_FIRST_SUBSCRIBER_REPLAY_GRACE_MS,
            onOverflow: (TmuxOutputBacklogOverflow) -> Unit,
            diagnosticFields: () -> Map<String, Any?>,
        ): PaneOutputPipe {
            val flow = MutableSharedFlow<ControlEvent.Output>(
                replay = 0,
                extraBufferCapacity = PANE_OUTPUT_EVENT_BUFFER,
            )
            val channel = Channel<ControlEvent.Output>(PANE_OUTPUT_BACKLOG_EVENTS)
            val paused = MutableStateFlow(false)
            // Issue #1305: coordinate the frame parked in the drain loop's
            // local at the pause boundary with a concurrent [drainBacklog].
            val holdingFrameAtPause = AtomicBoolean(false)
            val discardParkedFrame = AtomicBoolean(false)
            val job = scope.launch {
                // Issue #1204: replay any output buffered before this pipe
                // was registered, IN ORDER, ahead of the live channel. The
                // flow has replay = 0, so a value emitted before the first
                // subscriber attaches is dropped -- so wait for the first
                // collector before replaying (the pipe's own live channel,
                // capacity PANE_OUTPUT_BACKLOG_EVENTS, holds new frames in order
                // meanwhile). This gate applies ONLY when there is buffered
                // output to replay; the normal path (no pre-registration
                // frames) drains the live channel immediately, unchanged.
                var replay: List<ControlEvent.Output>? = preRegistrationReplay
                if (!replay.isNullOrEmpty()) {
                    // Issue #1212: bound the wait for the first collector.
                    // If the returned flow is NEVER collected, this job used
                    // to park here forever, pinning the replay list for the
                    // life of the connection. Time-box the wait: on timeout
                    // abandon (release) the replay and fall through to the
                    // live drain so the pane is never wedged and its replay
                    // bytes are freed.
                    val gotSubscriber = withTimeoutOrNull(firstSubscriberReplayGraceMs) {
                        flow.subscriptionCount.first { it > 0 }
                        true
                    } == true
                    if (gotSubscriber) {
                        for (event in replay) {
                            flow.emit(event)
                        }
                    } else {
                        var abandonedBytes = 0L
                        for (event in replay) abandonedBytes += event.data.size
                        TmuxClientDiagnostics.record(
                            "tmux_client_preregistration_replay_abandoned",
                            diagnosticFields() + mapOf(
                                "pane" to paneId,
                                "bytes" to abandonedBytes,
                                "droppedEvents" to replay.size,
                                "graceMs" to firstSubscriberReplayGraceMs,
                            ),
                        )
                        Log.w(
                            ISSUE_105_DIAG_TAG,
                            "tmux-preregistration-replay-abandoned pane=$paneId " +
                                "bytes=$abandonedBytes droppedEvents=${replay.size} " +
                                "graceMs=$firstSubscriberReplayGraceMs",
                        )
                    }
                    replay = null // release the (bounded) replay list either way
                }
                while (true) {
                    val event = channel.receiveCatching().getOrNull() ?: break
                    // Issue #1297: HOLD delivery while paused. The overflow-
                    // reseed producer swap tears the sole collector down and
                    // reattaches a fresh one; without this gate every %output
                    // emitted in that window used to be emitted into the
                    // zero-subscriber (replay = 0) flow and vanish, leaving
                    // recovery to depend SOLELY on the step-4 capture (#1297's
                    // SPOF). The just-received frame is HELD in `event` (never
                    // dropped) and every later frame stays queued in the bounded
                    // [channel] (its own PANE_OUTPUT_BACKLOG_EVENTS overflow bound
                    // still applies); on resume the held frame emits first, then
                    // the queued frames drain -- arrival order preserved. Checked
                    // AFTER the receive so a frame parked in [receiveCatching]
                    // when the pause lands is held, not emitted into a paused
                    // (subscriber-detached) flow.
                    if (paused.value) {
                        // Issue #1305: this frame was received into `event`
                        // BEFORE the pause released -- it is now parked in a
                        // local, out of [channel] and beyond [drainBacklog]'s
                        // reach. Expose it and arm discard detection for THIS
                        // park so an overflow drain that runs while we're parked
                        // can flag the frame stale. Re-arm to false at the start
                        // of every park so a stale flag from a prior park (or a
                        // drain that ran with nothing parked) never discards a
                        // legitimate post-snapshot frame.
                        discardParkedFrame.set(false)
                        holdingFrameAtPause.set(true)
                        paused.first { !it }
                        holdingFrameAtPause.set(false)
                        if (discardParkedFrame.compareAndSet(true, false)) {
                            // A drain ran while this frame was parked: the
                            // authoritative capture snapshot already reflects
                            // it, so emitting now would double-apply on top of
                            // the fresh grid. Drop it and continue.
                            continue
                        }
                    }
                    flow.emit(event)
                }
            }
            return PaneOutputPipe(
                flow = flow,
                channel = channel,
                job = job,
                onOverflow = onOverflow,
                diagnosticFields = diagnosticFields,
                paused = paused,
                holdingFrameAtPause = holdingFrameAtPause,
                discardParkedFrame = discardParkedFrame,
            )
        }
    }
}

/**
 * Slot held while a single [TmuxClient.sendCommand] call is awaiting its
 * response. The reader populates [output] line-by-line as payload between
 * `%begin` and `%end` / `%error` arrives, then completes [deferred] when the
 * closing event lands.
 *
 * [commandNumber] is filled in by the reader when the matching `%begin` arrives
 * -- it's the tmux-assigned identifier surfaced on the eventual
 * [CommandResponse.number].
 */
internal class PendingCommand(
    val deferred: CompletableDeferred<CommandResponse>,
) {
    @Volatile
    var commandNumber: Long = -1L

    @Volatile
    var abandoned: Boolean = false

    val output: MutableList<String> = mutableListOf()
}
