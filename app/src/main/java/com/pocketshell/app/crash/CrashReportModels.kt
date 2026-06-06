package com.pocketshell.app.crash

import java.io.File
import java.time.Instant

data class CrashReport(
    val id: String,
    val timestamp: Instant,
    val file: File,
    val summary: String,
    val contextSummary: String,
    val appVersion: String?,
    val topFrame: String?,
)

data class CrashReportMetadata(
    val appVersion: String,
    val androidRelease: String,
    val sdkInt: Int,
    val device: String,
)

data class CrashReportContext(
    val screen: String,
    val hostName: String? = null,
    val hostname: String? = null,
    val username: String? = null,
    val sessionName: String? = null,
    val startDirectory: String? = null,
    val action: String? = null,
) {
    fun summary(): String {
        val parts = mutableListOf(screen)
        val host = hostName?.takeIf { it.isNotBlank() }
            ?: hostname?.takeIf { it.isNotBlank() }
        if (host != null) parts += "host=$host"
        if (!sessionName.isNullOrBlank()) parts += "session=$sessionName"
        if (!startDirectory.isNullOrBlank()) parts += "cwd=$startDirectory"
        if (!action.isNullOrBlank()) parts += "action=$action"
        return parts.joinToString(" · ")
    }

    companion object {
        val Unknown = CrashReportContext(screen = "Unknown screen")
    }
}
