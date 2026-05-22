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
    ): String = buildString {
        appendLine("PocketShell crash report")
        appendLine("Generated: $timestamp")
        appendLine("App version: ${metadata.appVersion}")
        appendLine("Android: ${metadata.androidRelease} (SDK ${metadata.sdkInt})")
        appendLine("Device: ${metadata.device}")
        appendLine("Thread: $threadName")
        appendLine()
        appendLine("Privacy note: this local report contains crash stack trace and coarse")
        appendLine("app/device metadata only. It is not uploaded automatically.")
        appendLine()
        appendLine("Exception")
        appendLine(stackTraceOf(throwable))
    }

    internal fun summary(throwable: Throwable): String {
        val name = throwable::class.java.simpleName.ifBlank { "Throwable" }
        val message = throwable.message?.takeIf { it.isNotBlank() }
        return if (message == null) name else "$name: $message"
    }

    private fun stackTraceOf(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString().trimEnd()
    }
}
