package com.pocketshell.app.crash

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.Instant

class CrashReportDisplayTest {
    @Test
    fun rowSubtitleCombinesContextAppVersionAndTopFrame() {
        val report = CrashReport(
            id = "20260606-120000-000",
            timestamp = Instant.parse("2026-06-06T12:00:00Z"),
            file = File("unused.txt"),
            summary = "IllegalStateException: session failed",
            contextSummary = "Tmux session · host=devbox · session=agent-main",
            appVersion = "0.2.8",
            topFrame = "com.pocketshell.app.tmux.TmuxSessionScreenKt.render(TmuxSessionScreen.kt:540)",
        )

        assertEquals("IllegalStateException: session failed", crashReportRowTitle(report))
        assertEquals(
            "Tmux session · host=devbox · session=agent-main · app=0.2.8 · " +
                "top=com.pocketshell.app.tmux.TmuxSessionScreenKt.render(TmuxSessionScreen.kt:540)",
            crashReportRowSubtitle(report),
        )
    }
}
