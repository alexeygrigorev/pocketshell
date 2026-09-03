package com.pocketshell.next.diagnostics

import android.content.Context
import androidx.annotation.VisibleForTesting
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

/**
 * Trimmed port of the old app's `com.pocketshell.app.diagnostics.DiagnosticRecorder`
 * (rewrite task P-10 / scope amendment): a generic bounded event-log recorder,
 * with the connection-journal/mirror/part-store stack cut entirely — no
 * `ConnectionLogPartStore`, no `ConnectionJournalSchema` check, no
 * `MirroredDiagnostics` host-mirroring, no `connectionLog*`/`connectionJournal*`
 * accessors. Every event goes to the one bounded ring-buffer [DiagnosticLogStore]
 * and nowhere else.
 *
 * Also drops the old app's `SettingsRepository.diagnosticsRecordingEnabled`
 * on/off gate: app2 has no diagnostics settings screen yet (out of this
 * task's scope, and D22 disfavours a toggle with no UI consumer), so recording
 * is unconditionally on, bounded by [DiagnosticLogStore]'s own size/count caps.
 *
 * ## Off-main store build + sequence seed (ported from old app issue #1124)
 *
 * The store build + `lastSequence()` seed runs on [Dispatchers.IO] via an eager
 * [async], not on the constructing thread — constructing this class during
 * `App.onCreate` Hilt injection must never block Main on an unbounded JSONL
 * read. The [sequence] counter is seeded when that warm-up completes, and
 * every command is processed only after the seed lands, so no event can be
 * numbered before it.
 */
class DiagnosticRecorder(
    private val context: Context,
) : DiagnosticEventSink {
    private val clock: Clock = Clock.systemUTC()
    private val sequence = AtomicLong(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<RecorderCommand>(capacity = RECORDER_BUFFER_CAPACITY)

    @Volatile
    private var lastSequenceReadThreadName: String? = null

    /**
     * The store build + the expensive `lastSequence()` JSONL read, deferred off
     * the constructing (Main) thread. Seeds [sequence] when it completes. Every
     * store consumer ([exportSnapshot], [readEvents], the channel loop) awaits
     * this, so the warm-up runs exactly once on IO.
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
                    is RecorderCommand.Line -> persist(store, command.pending)
                    is RecorderCommand.Flush -> command.done.complete(Unit)
                    is RecorderCommand.Clear -> {
                        store.clear()
                        sequence.set(0L)
                        command.done.complete(Unit)
                    }
                    is RecorderCommand.ClearAndRecord -> {
                        store.clear()
                        sequence.set(0L)
                        persist(store, pendingEvent(command.category, command.event, command.fields))
                        command.done.complete(Unit)
                    }
                }
            }
        }
    }

    override fun record(category: String, event: String, fields: Map<String, Any?>) {
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
            storeDeferred.await().exportSnapshot(deviceLabel(), appVersionLabel(), filter)
        }
    }

    suspend fun readEvents(filter: DiagnosticEventFilter = DiagnosticEventFilter.All): List<DiagnosticsEvent> {
        flush()
        return withContext(Dispatchers.IO) {
            storeDeferred.await().readEvents(filter)
        }
    }

    /**
     * Test-only: block until the off-main store build + `lastSequence()` read
     * completes and return the name of the thread it ran on. Proves the
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
    // giving a false off-main pass.
    private fun currentPhysicalThreadName(): String =
        Thread.currentThread().name.substringBefore(" @coroutine")

    /**
     * Builds the event payload at record time (cheap, caller-thread safe). The
     * monotonic [sequence] is deliberately NOT assigned here — it is assigned in
     * the channel consumer ([buildEvent]) after the off-main seed completes, so
     * an event recorded before warm-up still gets a correct, monotonic sequence.
     */
    private fun pendingEvent(category: String, event: String, fields: Map<String, Any?>): PendingEvent =
        PendingEvent(
            category = category,
            name = event,
            wallClockTime = Instant.now(clock),
            monotonicTimestampNanos = android.os.SystemClock.elapsedRealtimeNanos(),
            metadata = DiagnosticRedactor.redact(fields, category),
        )

    /** Assign the monotonic [sequence], version-stamp, and persist [pending]. */
    private fun persist(store: DiagnosticLogStore, pending: PendingEvent) {
        val event = buildEvent(pending)
        store.appendLine(DiagnosticEventJson.encode(event))
    }

    private fun buildEvent(pending: PendingEvent): DiagnosticsEvent =
        DiagnosticsEvent(
            sequence = sequence.incrementAndGet(),
            wallClockTime = pending.wallClockTime,
            monotonicTimestampNanos = pending.monotonicTimestampNanos,
            category = pending.category,
            name = pending.name,
            metadata = pending.metadata,
            versionName = appVersion.name,
            versionCode = appVersion.code,
        )

    private fun deviceLabel(): String =
        listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(separator = " ")
            .ifBlank { "device" }

    /**
     * The app version used to stamp every event. Resolved lazily on first use
     * — which happens on the IO channel-consumer coroutine, never the Main
     * thread — so the `packageManager` read stays off the cold-launch path. A
     * test may pin [appVersionOverride] before the first record to assert the
     * stamp deterministically.
     */
    private val appVersion: AppVersion by lazy { appVersionOverride ?: readAppVersion() }

    @Volatile
    @VisibleForTesting
    internal var appVersionOverride: AppVersion? = null

    private fun readAppVersion(): AppVersion =
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val name = info.versionName?.takeIf { it.isNotBlank() } ?: "unknown"
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            AppVersion(name = name, code = code)
        }.getOrDefault(AppVersion(name = "unknown", code = 0L))

    private fun appVersionLabel(): String = appVersion.let { "${it.name} (${it.code})" }

    private class PendingEvent(
        val category: String,
        val name: String,
        val wallClockTime: Instant,
        val monotonicTimestampNanos: Long,
        val metadata: Map<String, Any?>,
    )

    /** The app version stamped onto every event. */
    @VisibleForTesting
    internal data class AppVersion(val name: String, val code: Long)

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
