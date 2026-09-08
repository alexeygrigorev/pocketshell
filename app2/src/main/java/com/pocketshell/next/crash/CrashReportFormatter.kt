package com.pocketshell.next.crash

import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Ported unchanged (besides package) from the old app's
 * `com.pocketshell.app.crash.CrashReportFormatter` (rewrite task P-10).
 */
object CrashReportFormatter {

    /**
     * Removes identity and location data before a report leaves the device.
     * The local file remains complete for on-device review; this is the default
     * representation used by both the single-report and archive share paths.
     */
    fun redactForSharing(report: String): String = buildString {
        var inException = false
        report.lineSequence().forEach { line ->
            when {
                line == "Exception" -> {
                    inException = true
                    appendLine(line)
                }
                inException -> appendLine(redactSensitiveText(line))
                line.startsWith("Host:") ||
                    line.startsWith("Hostname:") ||
                    line.startsWith("User:") ||
                    line.startsWith("Session:") ||
                    line.startsWith("Directory:") ||
                    line.startsWith("Action:") -> appendLine(line.substringBefore(':') + ": [redacted]")
                line.startsWith("Exception summary:") -> {
                    val value = line.removePrefix("Exception summary:").trim()
                    appendLine("Exception summary: ${value.substringBefore(':')}")
                }
                line.startsWith("Top frame:") -> appendLine("Top frame: [redacted]")
                else -> appendLine(redactSensitiveText(line))
            }
        }
    }.trimEnd() + "\n"

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

    private fun redactSensitiveText(value: String): String = value
        .replace(Regex("(?i)(/home/|/users/|/var/home/)[^\\s:)]+"), "<path>")
        .replace(Regex("(?i)~/[^\\s:)]+"), "<path>")
        .replace(Regex("(?i)(authorization|bearer|password|passphrase|token|secret|private key)\\s*[=:]\\s*[^\\s]+"), "$1=[redacted]")
        .replace(Regex("-----BEGIN [^-]+ PRIVATE KEY-----.*", RegexOption.IGNORE_CASE), "<private-key-redacted>")
}
