package com.pocketshell.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #1089 — install-order guard for the process-wide Main-thread
 * [com.pocketshell.app.diagnostics.StrictModeInstaller].
 *
 * The StrictMode thread policy MUST be armed as the first statement of
 * `App.onCreate()`, **before** `super.onCreate()` runs Hilt field injection.
 * Hilt injects the App-level singletons (settingsRepository / usageScheduler /
 * diagnosticRecorder / …) inside `super.onCreate()`, so any launch-path
 * main-thread disk read that happens DURING injection (the #1124
 * DiagnosticRecorder and #1088 SettingsRepository launch-freeze class) is
 * invisible to StrictMode — and undercounts the cold-launch freeze measurement —
 * when the policy is armed AFTER `super.onCreate()` (where it sat ~17 lines too
 * late).
 *
 * This is the reproduce-first red→green guard: it FAILS on the pre-fix code
 * where `StrictModeInstaller.installIfDebuggable(this)` sits below
 * `super.onCreate()`, and PASSES once arming is the first statement before
 * `super.onCreate()`. It is a pure-JVM source-structure check (no emulator), so
 * it runs for free in the Unit job and catches the exact placement regression
 * that hid #1124 / #1088.
 */
class AppStrictModeArmOrderTest {

    // Code only — comment lines are stripped so a prose mention of
    // `super.onCreate()` in the explanatory comment can't be matched as the call.
    private val source: String = locate("App.kt")
        .lineSequence()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    @Test
    fun strictModeIsArmedBeforeSuperOnCreate() {
        val installIdx = source.indexOf(STRICT_MODE_INSTALL_CALL)
        assertTrue(
            "App.onCreate must call $STRICT_MODE_INSTALL_CALL",
            installIdx >= 0,
        )
        val superIdx = source.indexOf(SUPER_ON_CREATE_CALL)
        assertTrue("App.onCreate must call $SUPER_ON_CREATE_CALL", superIdx >= 0)
        assertTrue(
            "StrictMode thread policy must be armed BEFORE super.onCreate() (Hilt field " +
                "injection), so injection-time main-thread disk IO (the #1124 / #1088 " +
                "launch-freeze class) is detected. installIdx=$installIdx superIdx=$superIdx",
            installIdx < superIdx,
        )
    }

    @Test
    fun noStrictModeInstallRunsAfterSuperOnCreate() {
        // A future edit that re-adds a post-injection arm (the regression that
        // hid #1124 / #1088) is caught here: the LAST install call must still
        // precede super.onCreate(), so the entire injection window is observed.
        val superIdx = source.indexOf(SUPER_ON_CREATE_CALL)
        assertTrue("App.onCreate must call $SUPER_ON_CREATE_CALL", superIdx >= 0)
        val lastInstallIdx = source.lastIndexOf(STRICT_MODE_INSTALL_CALL)
        assertTrue(
            "No StrictMode install may run after super.onCreate(); the injection window must " +
                "be observed. lastInstallIdx=$lastInstallIdx superIdx=$superIdx",
            lastInstallIdx in 0 until superIdx,
        )
    }

    private fun locate(name: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/pocketshell/app/$name"),
            File("src/main/java/com/pocketshell/app/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $name from ${File(".").absolutePath}")
        return file.readText()
    }

    private companion object {
        const val STRICT_MODE_INSTALL_CALL = "StrictModeInstaller.installIfDebuggable(this)"
        const val SUPER_ON_CREATE_CALL = "super.onCreate()"
    }
}
