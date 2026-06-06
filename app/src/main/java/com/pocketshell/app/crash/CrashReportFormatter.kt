package com.pocketshell.app.crash

import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

object CrashReportFormatter {

    fun format(
        throwable: Throwable,
        threadName: String,
        timestamp: Instant,
        metadata: CrashReportMetadata,
        context: CrashReportContext,
    ): String = buildString {
        appendLine("PocketShell crash report")
        appendLine("Generated: $timestamp")
        appendLine("App version: ${metadata.appVersion}")
        appendLine("Android: ${metadata.androidRelease} (SDK ${metadata.sdkInt})")
        appendLine("Device: ${metadata.device}")
        appendLine("Thread: $threadName")
        appendLine()
        appendLine("Context")
        appendLine("Screen: ${context.screen}")
        context.hostName?.takeIf { it.isNotBlank() }?.let { appendLine("Host: $it") }
        context.hostname?.takeIf { it.isNotBlank() }?.let { appendLine("Hostname: $it") }
        context.username?.takeIf { it.isNotBlank() }?.let { appendLine("User: $it") }
        context.sessionName?.takeIf { it.isNotBlank() }?.let { appendLine("Session: $it") }
        context.startDirectory?.takeIf { it.isNotBlank() }?.let { appendLine("Directory: $it") }
        context.action?.takeIf { it.isNotBlank() }?.let { appendLine("Action: $it") }
        appendLine("Exception summary: ${summary(throwable)}")
        appendLine("Top frame: ${topFrame(throwable) ?: "unknown"}")
        appendLine()
        appendLine("Privacy note: this local report contains crash stack trace, active")
        appendLine("screen/session context, and coarse app/device metadata. It is not uploaded automatically.")
        appendLine()
        appendLine("Exception")
        appendLine(stackTraceOf(throwable))
    }

    internal fun summary(throwable: Throwable): String {
        val name = throwable::class.java.simpleName.ifBlank { "Throwable" }
        val message = throwable.message?.takeIf { it.isNotBlank() }
        return if (message == null) name else "$name: $message"
    }

    internal fun topFrame(throwable: Throwable): String? =
        throwable.stackTrace.firstOrNull()?.let { frame ->
            buildString {
                append(frame.className)
                append('.')
                append(frame.methodName)
                val fileName = frame.fileName
                if (fileName != null) {
                    append('(')
                    append(fileName)
                    if (frame.lineNumber >= 0) {
                        append(':')
                        append(frame.lineNumber)
                    }
                    append(')')
                }
            }
        }

    private fun stackTraceOf(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString().trimEnd()
    }
}
