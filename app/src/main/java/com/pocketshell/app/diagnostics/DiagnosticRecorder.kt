package com.pocketshell.app.diagnostics

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.pocketshell.app.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ## Off-main store build + sequence seed (issue #1124, cold-launch freeze)
 *
 * [DiagnosticRecorder] is an `@Inject @Singleton` field on `App`, constructed
 * during `App.onCreate` Hilt injection — on the **Main thread**, before
 * `StrictModeInstaller` arms (so it is invisible to the freeze gate). The old
 * `<init>` computed `AtomicLong(store.lastSequence())`, and
 * [DiagnosticLogStore.lastSequence] does a **full synchronous `readLines()`** of
 * the unbounded, ever-growing diagnostics JSONL. That blocked Main at every cold
 * launch and got *worse with usage* as the file accumulated events — the highest-
 * impact launch freeze found in the #1085 hunt.
 *
 * The store build + `lastSequence()` seed is moved off-main onto the recorder's
 * `Dispatchers.IO` scope (eager [async], mirroring the #1087
 * `UpdateCheckStore` pattern). The [AtomicLong] sequence is seeded when that
 * warm-up completes. Sequence numbers are assigned in the channel-consumer
 * coroutine (which `await`s the seed before processing any command), so no event
 * can be numbered before the seed lands — the recorder's monotonic-sequence
 * contract is preserved. Hard-cut (D22): no synchronous on-Main fallback.
 */
@Singleton
class DiagnosticRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : DiagnosticEventSink {
    private val clock: Clock = Clock.systemUTC()
    private val sequence = AtomicLong(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<RecorderCommand>(capacity = RECORDER_BUFFER_CAPACITY)

    @Volatile
    private var lastSequenceReadThreadName: String? = null

    /**
     * The store build + the expensive `lastSequence()` JSONL read, deferred off
     * the constructing (Main) thread (#1124). Seeds [sequence] when it completes.
     * Every store consumer (the channel loop, [exportSnapshot], [readEvents])
     * awaits this, so the warm-up runs exactly once on IO.
     */
    private val storeDeferred: Deferred<DiagnosticLogStore> = scope.async {
        val store = DiagnosticLogStore(
            logFile = File(context.filesDir, "diagnostics/pocketshell-diagnostics.jsonl"),
            exportDirectory = File(context.cacheDir, DIAGNOSTICS_EXPORT_CACHE_DIR),
        )
        lastSequenceReadThreadName = currentPhysicalThreadName()
        sequence.set(store.lastSequence())
        store
    }

    init {
        scope.launch {
            // Await the off-main seed BEFORE processing any command, so every
            // sequence assigned below starts from the persisted high-water mark.
            val store = storeDeferred.await()
            for (command in commands) {
                when (command) {
                    is RecorderCommand.Line -> store.appendLine(encode(command.pending))
                    is RecorderCommand.Flush -> command.done.complete(Unit)
                    is RecorderCommand.Clear -> {
                        store.clear()
                        sequence.set(0L)
                        command.done.complete(Unit)
                    }
                    is RecorderCommand.ClearAndRecord -> {
                        store.clear()
                        sequence.set(0L)
                        if (settingsRepository.settings.value.diagnosticsRecordingEnabled) {
                            store.appendLine(
                                encode(pendingEvent(command.category, command.event, command.fields)),
                            )
                        }
                        command.done.complete(Unit)
                    }
                }
            }
        }
    }

    override fun record(category: String, event: String, fields: Map<String, Any?>) {
        if (!settingsRepository.settings.value.diagnosticsRecordingEnabled) return
        val pending = pendingEvent(category, event, fields)
        if (commands.trySend(RecorderCommand.Line(pending)).isFailure) {
            val overflow = PendingEvent(
                category = "diagnostics",
                name = "recorder_overflow",
                wallClockTime = Instant.now(clock),
                monotonicTimestampNanos = android.os.SystemClock.elapsedRealtimeNanos(),
                metadata = emptyMap(),
            )
            commands.trySend(RecorderCommand.Line(overflow))
        }
    }

    suspend fun clear() {
        val done = CompletableDeferred<Unit>()
        commands.send(RecorderCommand.Clear(done))
        done.await()
    }

    suspend fun clearAndRecord(category: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        val done = CompletableDeferred<Unit>()
        commands.send(RecorderCommand.ClearAndRecord(category, event, fields, done))
        done.await()
    }

    suspend fun exportSnapshot(filter: DiagnosticEventFilter = DiagnosticEventFilter.All): File? {
        flush()
        return withContext(Dispatchers.IO) {
            storeDeferred.await().exportSnapshot(deviceLabel(), appVersion(), filter)
        }
    }

    suspend fun readEvents(filter: DiagnosticEventFilter = DiagnosticEventFilter.All): List<DiagnosticsEvent> {
        flush()
        return withContext(Dispatchers.IO) {
            storeDeferred.await().readEvents(filter)
        }
    }

    /**
     * The reconnect-cause breadcrumbs ([ReconnectCauseTrail]) rendered as JSONL —
     * one JSON object per line, the same on-disk encoding the rolling diagnostics
     * file uses. This is the payload
     * [com.pocketshell.app.diagnostics.ConnectionLogHostMirror] mirrors to the host
     * so the maintainer can attribute a real-world drop in the in-app file viewer
     * (#969/#972).
     *
     * Blank when nothing has been recorded yet (recording off, or no reconnect):
     * the mirror treats a blank payload as a no-op so it never writes an empty host
     * file.
     */
    suspend fun connectionLogJsonl(): String {
        val events = readEvents(
            DiagnosticEventFilter(
                category = ReconnectCauseTrail.CATEGORY,
                name = ReconnectCauseTrail.NAME,
            ),
        )
        return events.joinToString(separator = "\n") { DiagnosticEventJson.encode(it) }
    }

    /**
     * Test-only (#1124): block until the off-main store build + `lastSequence()`
     * read completes and return the name of the thread it ran on. Proves the
     * unbounded JSONL read did NOT run on the constructing/Main thread.
     */
    @VisibleForTesting
    internal fun awaitLastSequenceReadThreadNameForTest(): String {
        runBlocking { storeDeferred.await() }
        return lastSequenceReadThreadName
            ?: error("lastSequence read thread was not recorded")
    }

    private suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        commands.send(RecorderCommand.Flush(done))
        done.await()
    }

    // The seed runs inside a coroutine, whose framework decorates the thread name
    // with a " @coroutine#N" suffix. Strip it so the recorded value is the
    // PHYSICAL thread name — otherwise an on-Main build (the un-fixed base) would
    // still differ from the captured constructing name by the suffix alone,
    // giving a false off-main pass (#1087 G6: keep the assertion load-bearing).
    private fun currentPhysicalThreadName(): String =
        Thread.currentThread().name.substringBefore(" @coroutine")

    /**
     * Builds the event payload at record time (cheap, caller-thread safe). The
     * monotonic [sequence] is deliberately NOT assigned here — it is assigned in
     * the channel consumer ([encode]) after the off-main seed completes, so an
     * event recorded before warm-up still gets a correct, monotonic sequence.
     */
    private fun pendingEvent(category: String, event: String, fields: Map<String, Any?>): PendingEvent =
        PendingEvent(
            category = category,
            name = event,
            wallClockTime = Instant.now(clock),
            monotonicTimestampNanos = android.os.SystemClock.elapsedRealtimeNanos(),
            metadata = DiagnosticRedactor.redact(fields, category),
        )

    private fun encode(pending: PendingEvent): String =
        DiagnosticEventJson.encode(
            DiagnosticsEvent(
                sequence = sequence.incrementAndGet(),
                wallClockTime = pending.wallClockTime,
                monotonicTimestampNanos = pending.monotonicTimestampNanos,
                category = pending.category,
                name = pending.name,
                metadata = pending.metadata,
            ),
        )

    private fun deviceLabel(): String =
        listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(separator = " ")
            .ifBlank { "device" }

    private fun appVersion(): String =
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val name = info.versionName?.takeIf { it.isNotBlank() } ?: "unknown"
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "$name ($code)"
        }.getOrDefault("unknown")

    private class PendingEvent(
        val category: String,
        val name: String,
        val wallClockTime: Instant,
        val monotonicTimestampNanos: Long,
        val metadata: Map<String, Any?>,
    )

    private sealed interface RecorderCommand {
        data class Line(val pending: PendingEvent) : RecorderCommand
        data class Flush(val done: CompletableDeferred<Unit>) : RecorderCommand
        data class Clear(val done: CompletableDeferred<Unit>) : RecorderCommand
        data class ClearAndRecord(
            val category: String,
            val event: String,
            val fields: Map<String, Any?>,
            val done: CompletableDeferred<Unit>,
        ) : RecorderCommand
    }

    private companion object {
        const val RECORDER_BUFFER_CAPACITY = 256
    }
}

internal const val DIAGNOSTICS_EXPORT_CACHE_DIR = "diagnostics-export"
