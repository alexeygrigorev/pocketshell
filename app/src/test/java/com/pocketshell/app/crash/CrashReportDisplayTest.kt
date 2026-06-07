package com.pocketshell.app.crash

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

class CrashReportDisplayTest {
    @Test
    fun rowDisplayCombinesTimestampContextAppVersionAndShortTopFrame() {
        val report = CrashReport(
            id = "20260606-120000-000",
            timestamp = Instant.parse("2026-06-06T12:00:00Z"),
            file = File("unused.txt"),
            summary = "IllegalStateException: session failed",
            contextSummary = "Tmux session · host=devbox · session=agent-main",
            appVersion = "0.2.8",
            topFrame = "com.pocketshell.app.tmux.TmuxSessionScreenKt.render(TmuxSessionScreen.kt:540)",
        )

        assertEquals(
            "2026-06-06 12:00:00 Z · IllegalStateException: session failed",
            crashReportRowTitle(report, ZoneOffset.UTC),
        )
        assertEquals(
            "Tmux session · host=devbox · session=agent-main · app=0.2.8 · " +
                "top=TmuxSessionScreen.kt:540",
            crashReportRowSubtitle(report),
        )
    }

    @Test
    fun shareSubjectIdentifiesTheSelectedReport() {
        val report = CrashReport(
            id = "20260606-120000-000",
            timestamp = Instant.parse("2026-06-06T12:00:00Z"),
            file = File("unused.txt"),
            summary = "IllegalStateException: session failed",
            contextSummary = "Tmux session · host=devbox · session=agent-main",
            appVersion = "0.2.8",
            topFrame = null,
        )

        assertEquals(
            "PocketShell crash report - 2026-06-06 12:00:00 Z - " +
                "Tmux session · host=devbox · session=agent-main - " +
                "IllegalStateException: session failed",
            crashReportShareSubject(report, ZoneOffset.UTC),
        )
    }
}
