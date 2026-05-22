package com.pocketshell.app.crash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CrashReportFormatterTest {
    @Test
    fun formatIncludesCrashMetadataAndStackTrace() {
        val report = CrashReportFormatter.format(
            throwable = IllegalStateException("session failed"),
            threadName = "main",
            timestamp = Instant.parse("2026-05-22T10:15:30Z"),
            metadata = CrashReportMetadata(
                appVersion = "0.1.0",
                androidRelease = "15",
                sdkInt = 35,
                device = "Google Pixel",
            ),
        )

        assertTrue(report.contains("PocketShell crash report"))
        assertTrue(report.contains("Generated: 2026-05-22T10:15:30Z"))
        assertTrue(report.contains("App version: 0.1.0"))
        assertTrue(report.contains("Android: 15 (SDK 35)"))
        assertTrue(report.contains("Device: Google Pixel"))
        assertTrue(report.contains("Thread: main"))
        assertTrue(report.contains("IllegalStateException: session failed"))
        assertTrue(report.contains("not uploaded automatically"))
        assertFalse(report.contains("logcat"))
    }

    @Test
    fun summaryFallsBackToExceptionClassName() {
        val summary = CrashReportFormatter.summary(NullPointerException())

        assertTrue(summary == "NullPointerException")
    }
}
