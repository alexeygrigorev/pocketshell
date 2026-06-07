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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : DiagnosticEventSink {
    private val store = DiagnosticLogStore(
        logFile = File(context.filesDir, "diagnostics/pocketshell-diagnostics.log"),
        exportDirectory = File(context.cacheDir, DIAGNOSTICS_EXPORT_CACHE_DIR),
    )
    private val clock: Clock = Clock.systemUTC()
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
                        command.done.complete(Unit)
                    }
                }
            }
        }
    }

    override fun record(category: String, event: String, fields: Map<String, Any?>) {
        if (!settingsRepository.settings.value.diagnosticsRecordingEnabled) return
        val line = DiagnosticLogFormatter.format(
            timestamp = Instant.now(clock),
            category = category,
            event = event,
            fields = fields,
        )
        if (commands.trySend(RecorderCommand.Line(line)).isFailure) {
            commands.trySend(
                RecorderCommand.Line(
                    DiagnosticLogFormatter.format(
                        timestamp = Instant.now(clock),
                        category = "diagnostics",
                        event = "recorder_overflow",
                    ),
                ),
            )
        }
    }

    suspend fun clear() {
        val done = CompletableDeferred<Unit>()
        commands.send(RecorderCommand.Clear(done))
        done.await()
    }

    suspend fun exportSnapshot(): File? {
        flush()
        return withContext(Dispatchers.IO) {
            store.exportSnapshot(deviceLabel())
        }
    }

    private suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        commands.send(RecorderCommand.Flush(done))
        done.await()
    }

    private fun deviceLabel(): String =
        listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(separator = " ")
            .ifBlank { "device" }

    private sealed interface RecorderCommand {
        data class Line(val line: String) : RecorderCommand
        data class Flush(val done: CompletableDeferred<Unit>) : RecorderCommand
        data class Clear(val done: CompletableDeferred<Unit>) : RecorderCommand
    }

    private companion object {
        const val RECORDER_BUFFER_CAPACITY = 256
    }
}

internal const val DIAGNOSTICS_EXPORT_CACHE_DIR = "diagnostics-export"
