package com.pocketshell.core.tmux

import android.util.Log
import com.pocketshell.core.tmux.protocol.ControlEvent
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #1204/#1212: owns output that arrives before a pane's live output pipe
 * is registered, plus the window->pane bookkeeping used to release dead panes.
 *
 * The hot path remains lock-free: callers check their live pipe map before
 * entering this helper. The helper re-checks under its lock to close the race
 * between pipe registration and pre-registration buffering.
 */
internal class PreRegistrationOutputBookkeeper<P : Any>(
    private val sessionName: String,
    private val diagnosticFields: () -> Map<String, Any?>,
) {
    // Guarded by [lock]. The lock is taken only on cold paths: output for an
    // as-yet-unregistered pane, pane registration, and structural cleanup.
    private val lock = Any()
    private val buffers = LinkedHashMap<String, PreRegistrationOutputBuffer>(
        16,
        0.75f,
        /* accessOrder = */ true,
    )
    private var totalBytes = 0L
    private val windowPanes = HashMap<String, MutableSet<String>>()
    private val droppedEvents = AtomicInteger(0)

    fun registerPipe(
        paneId: String,
        existingPipe: () -> P?,
        createPipe: (preRegistrationReplay: List<ControlEvent.Output>) -> P,
        publishPipe: (P) -> Unit,
    ): P = synchronized(lock) {
        existingPipe() ?: run {
            val drainedBuffer = buffers.remove(paneId)
            if (drainedBuffer != null) {
                totalBytes -= drainedBuffer.bufferedBytes
            }
            val created = createPipe(drainedBuffer?.drain().orEmpty())
            publishPipe(created)
            created
        }
    }

    fun deliverToPaneStream(
        event: ControlEvent.Output,
        pipeForPane: (String) -> P?,
        sendToPipe: (P, ControlEvent.Output) -> Unit,
    ) {
        pipeForPane(event.paneId)?.let {
            sendToPipe(it, event)
            return
        }
        val registered = synchronized(lock) {
            val existing = pipeForPane(event.paneId)
            if (existing != null) {
                existing
            } else {
                // `getOrPut` on the access-order map also moves this pane to the
                // most-recent end, so global eviction never targets the pane we
                // are actively buffering for.
                val buffer = buffers.getOrPut(event.paneId) {
                    PreRegistrationOutputBuffer()
                }
                val bytesBefore = buffer.bufferedBytes
                buffer.add(event) { droppedForPane, evictedBytes ->
                    recordDrop(event.paneId, droppedForPane, evictedBytes)
                }
                totalBytes += buffer.bufferedBytes - bytesBefore
                enforceGlobalBounds(protectPane = event.paneId)
                null
            }
        }
        registered?.let { sendToPipe(it, event) }
    }

    fun clear() {
        synchronized(lock) {
            buffers.clear()
            totalBytes = 0L
            windowPanes.clear()
        }
    }

    fun trackWindowPanes(windowId: String, layout: String) {
        synchronized(lock) {
            val panes = extractLayoutPaneIds(layout)
            if (panes.isEmpty()) return
            for (entry in windowPanes) {
                if (entry.key != windowId) entry.value.removeAll(panes)
            }
            windowPanes.getOrPut(windowId) { HashSet() }.addAll(panes)
        }
    }

    fun releaseBuffersForWindow(windowId: String) {
        synchronized(lock) {
            val panes = windowPanes.remove(windowId) ?: return
            for (paneId in panes) {
                val removed = buffers.remove(paneId) ?: continue
                totalBytes -= removed.bufferedBytes
                TmuxClientDiagnostics.record(
                    "tmux_client_preregistration_window_close_evict",
                    diagnosticFields() + mapOf(
                        "session" to sessionName,
                        "pane" to paneId,
                        "window" to windowId,
                        "bytes" to removed.bufferedBytes,
                        "droppedEvents" to removed.eventCount,
                        "retainedPanes" to buffers.size,
                        "retainedBytes" to totalBytes,
                    ),
                )
                Log.i(
                    ISSUE_105_DIAG_TAG,
                    "tmux-preregistration-window-close-evict pane=$paneId window=$windowId " +
                        "bytes=${removed.bufferedBytes} retainedPanes=${buffers.size}",
                )
            }
        }
    }

    fun retainedBytesForTest(): Long =
        synchronized(lock) { totalBytes }

    fun bufferCountForTest(): Int =
        synchronized(lock) { buffers.size }

    private fun enforceGlobalBounds(protectPane: String) {
        while (buffers.size > PRE_REGISTRATION_MAX_PANES) {
            val victim = firstEvictablePane(protectPane) ?: break
            evictBuffer(victim, reason = "pane_count")
        }
        while (totalBytes > PRE_REGISTRATION_TOTAL_MAX_BYTES && buffers.size > 1) {
            val victim = firstEvictablePane(protectPane) ?: break
            evictBuffer(victim, reason = "total_bytes")
        }
    }

    private fun firstEvictablePane(protectPane: String): String? {
        for (key in buffers.keys) {
            if (key != protectPane) return key
        }
        return null
    }

    private fun evictBuffer(paneId: String, reason: String) {
        val removed = buffers.remove(paneId) ?: return
        totalBytes -= removed.bufferedBytes
        val total = droppedEvents.addAndGet(removed.eventCount)
        TmuxClientDiagnostics.record(
            "tmux_client_preregistration_global_evict",
            diagnosticFields() + mapOf(
                "session" to sessionName,
                "pane" to paneId,
                "bytes" to removed.bufferedBytes,
                "reason" to reason,
                "droppedEvents" to removed.eventCount,
                "totalDroppedEvents" to total,
                "retainedPanes" to buffers.size,
                "retainedBytes" to totalBytes,
                "maxPanes" to PRE_REGISTRATION_MAX_PANES,
                "maxTotalBytes" to PRE_REGISTRATION_TOTAL_MAX_BYTES,
            ),
        )
        Log.w(
            ISSUE_105_DIAG_TAG,
            "tmux-preregistration-global-evict pane=$paneId reason=$reason " +
                "bytes=${removed.bufferedBytes} droppedEvents=${removed.eventCount} " +
                "retainedPanes=${buffers.size} retainedBytes=$totalBytes",
        )
    }

    private fun recordDrop(paneId: String, droppedForPane: Int, evictedBytes: Int) {
        val total = droppedEvents.incrementAndGet()
        if (droppedForPane == 1) {
            TmuxClientDiagnostics.record(
                "tmux_client_preregistration_output_drop",
                diagnosticFields() + mapOf(
                    "session" to sessionName,
                    "pane" to paneId,
                    "bytes" to evictedBytes,
                    "droppedEvents" to droppedForPane,
                    "totalDroppedEvents" to total,
                    "maxEvents" to PRE_REGISTRATION_MAX_EVENTS,
                    "maxBytes" to PRE_REGISTRATION_MAX_BYTES,
                ),
            )
        }
        if (shouldLogTmuxDropCount(droppedForPane)) {
            Log.w(
                ISSUE_105_DIAG_TAG,
                "tmux-preregistration-output-drop pane=$paneId bytes=$evictedBytes " +
                    "droppedEvents=$droppedForPane totalDroppedEvents=$total " +
                    "maxEvents=$PRE_REGISTRATION_MAX_EVENTS maxBytes=$PRE_REGISTRATION_MAX_BYTES",
            )
        }
    }

    /**
     * Extract pane ids from a tmux layout string. A leaf cell is
     * `<w>x<h>,<x>,<y>,<paneId>`; container cells (`{...}` / `[...]`) have no
     * trailing pane id, so the regex matches only leaves. tmux layouts use bare
     * pane numbers; we `%`-prefix them to match output keys.
     */
    private fun extractLayoutPaneIds(layout: String): Set<String> =
        LAYOUT_PANE_REGEX.findAll(layout)
            .map { "%" + it.groupValues[1] }
            .toSet()

    /**
     * Bounded FIFO buffer holding `%output` frames that arrive for a pane before
     * its output pipe is registered.
     */
    private class PreRegistrationOutputBuffer {
        private val events = ArrayDeque<ControlEvent.Output>()

        var bufferedBytes: Long = 0L
            private set
        private var droppedEvents = 0

        val eventCount: Int
            get() = events.size

        fun add(event: ControlEvent.Output, onDrop: (droppedForPane: Int, evictedBytes: Int) -> Unit) {
            events.addLast(event)
            bufferedBytes += event.data.size
            while (events.size > 1 &&
                (events.size > PRE_REGISTRATION_MAX_EVENTS || bufferedBytes > PRE_REGISTRATION_MAX_BYTES)
            ) {
                val evicted = events.removeFirst()
                bufferedBytes -= evicted.data.size
                droppedEvents++
                onDrop(droppedEvents, evicted.data.size)
            }
        }

        fun drain(): List<ControlEvent.Output> = events.toList()
    }

    private companion object {
        private const val PRE_REGISTRATION_MAX_EVENTS = 256
        private const val PRE_REGISTRATION_MAX_BYTES = 256L * 1024L
        private const val PRE_REGISTRATION_TOTAL_MAX_BYTES = 1024L * 1024L
        private const val PRE_REGISTRATION_MAX_PANES = 64
        private val LAYOUT_PANE_REGEX = Regex("""\d+x\d+,\d+,\d+,(\d+)""")
        private const val ISSUE_105_DIAG_TAG = "issue105-diag"
    }
}
