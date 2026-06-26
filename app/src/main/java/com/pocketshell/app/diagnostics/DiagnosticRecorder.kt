package com.pocketshell.app.diagnostics

import android.content.Context
import com.pocketshell.app.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : DiagnosticEventSink {
    private val store = DiagnosticLogStore(
        logFile = File(context.filesDir, "diagnostics/pocketshell-diagnostics.jsonl"),
        exportDirectory = File(context.cacheDir, DIAGNOSTICS_EXPORT_CACHE_DIR),
    )
    private val clock: Clock = Clock.systemUTC()
    private val sequence = AtomicLong(store.lastSequence())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<RecorderCommand>(capacity = RECORDER_BUFFER_CAPACITY)

    init {
        scope.launch {
            for (command in commands) {
                when (command) {
                    is RecorderCommand.Line -> store.appendLine(command.line)
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
                            store.appendLine(eventLine(command.category, command.event, command.fields))
                        }
                        command.done.complete(Unit)
                    }
                }
            }
        }
    }

    override fun record(category: String, event: String, fields: Map<String, Any?>) {
        if (!settingsRepository.settings.value.diagnosticsRecordingEnabled) return
        val line = eventLine(category, event, fields)
        if (commands.trySend(RecorderCommand.Line(line)).isFailure) {
            val overflow = DiagnosticsEvent(
                sequence = sequence.incrementAndGet(),
                wallClockTime = Instant.now(clock),
                monotonicTimestampNanos = android.os.SystemClock.elapsedRealtimeNanos(),
                category = "diagnostics",
                name = "recorder_overflow",
            )
            commands.trySend(
                RecorderCommand.Line(DiagnosticEventJson.encode(overflow)),
            )
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
            store.exportSnapshot(deviceLabel(), appVersion(), filter)
        }
    }

    suspend fun readEvents(filter: DiagnosticEventFilter = DiagnosticEventFilter.All): List<DiagnosticsEvent> {
        flush()
        return withContext(Dispatchers.IO) {
            store.readEvents(filter)
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

    private suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        commands.send(RecorderCommand.Flush(done))
        done.await()
    }

    private fun eventLine(category: String, event: String, fields: Map<String, Any?>): String =
        DiagnosticEventJson.encode(
            DiagnosticsEvent(
                sequence = sequence.incrementAndGet(),
                wallClockTime = Instant.now(clock),
                monotonicTimestampNanos = android.os.SystemClock.elapsedRealtimeNanos(),
                category = category,
                name = event,
                metadata = DiagnosticRedactor.redact(fields),
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

    private sealed interface RecorderCommand {
        data class Line(val line: String) : RecorderCommand
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
