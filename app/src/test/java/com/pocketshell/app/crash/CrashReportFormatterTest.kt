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
            context = CrashReportContext(
                screen = "Tmux session",
                hostName = "devbox",
                hostname = "dev.example",
                username = "alexey",
                sessionName = "agent-main",
                startDirectory = "/home/alexey/git/pocketshell",
            ),
        )

        assertTrue(report.contains("PocketShell crash report"))
        assertTrue(report.contains("Generated: 2026-05-22T10:15:30Z"))
        assertTrue(report.contains("App version: 0.1.0"))
        assertTrue(report.contains("Android: 15 (SDK 35)"))
        assertTrue(report.contains("Device: Google Pixel"))
        assertTrue(report.contains("Thread: main"))
        assertTrue(report.contains("Context"))
        assertTrue(report.contains("Screen: Tmux session"))
        assertTrue(report.contains("Host: devbox"))
        assertTrue(report.contains("Hostname: dev.example"))
        assertTrue(report.contains("User: alexey"))
        assertTrue(report.contains("Session: agent-main"))
        assertTrue(report.contains("Directory: /home/alexey/git/pocketshell"))
        assertTrue(report.contains("Exception summary: IllegalStateException: session failed"))
        assertTrue(report.contains("Top frame:"))
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
