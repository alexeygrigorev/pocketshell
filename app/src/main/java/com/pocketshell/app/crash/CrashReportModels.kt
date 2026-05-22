package com.pocketshell.app.crash

import java.io.File
import java.time.Instant

data class CrashReport(
    val id: String,
    val timestamp: Instant,
    val file: File,
    val summary: String,
)

data class CrashReportMetadata(
    val appVersion: String,
    val androidRelease: String,
    val sdkInt: Int,
    val device: String,
)
