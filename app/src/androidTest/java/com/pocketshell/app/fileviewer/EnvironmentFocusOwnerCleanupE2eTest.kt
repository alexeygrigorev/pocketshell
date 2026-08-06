package com.pocketshell.app.fileviewer

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.app.proof.signals.executeFocusShellCommand
import com.pocketshell.app.proof.signals.dismissFocusedLauncherFrameworkDialog
import com.pocketshell.app.proof.signals.focusedFrameworkErrorPackage
import com.pocketshell.app.proof.signals.recordJourneyEntryFocus
import com.pocketshell.app.proof.signals.resolveHomePackage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exact Android-owned focus-cascade regression for reopened issue #1985. */
@RunWith(AndroidJUnit4::class)
class EnvironmentFocusOwnerCleanupE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun startFromPocketShellFocus() {
        recordJourneyEntryFocus(
            scenario = compose.activityRule.scenario,
            context = "before issue #1985 launcher framework-dialog fixture",
        )
    }

    @After
    fun restoreFocusEvenWhenTheJourneyBodyExits() {
        val ownerAtCleanup = focusedFrameworkErrorPackage()
        if (ownerAtCleanup != null) {
            assertTrue(
                "the assertion-exit boundary must dismiss the exact launcher framework dialog",
                dismissFocusedLauncherFrameworkDialog(),
            )
        }
        val cleanupDeadline = SystemClock.elapsedRealtime() + 5_000
        var escapedOwner: String?
        do {
            escapedOwner = focusedFrameworkErrorPackage()
            if (escapedOwner == null) break
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < cleanupDeadline)
        assertNull(
            "no Android launcher framework focus owner may escape into the next IME journey",
            escapedOwner,
        )
        WalkthroughScreenshotArtifacts.capture("issue1985-launcher-dialog-focus-restored")
    }

    @Test
    fun launcherFrameworkDialogAtPriorJourneyExitCannotPoisonNextImeJourney() {
        val homePackage = resolveHomePackage()
        val oldShowFirst = executeFocusShellCommand(
            "settings get global show_first_crash_dialog",
        ).trim()
        try {
            executeFocusShellCommand("settings put global show_first_crash_dialog 1")
            executeFocusShellCommand("input keyevent HOME")
            SystemClock.sleep(1_500)
            executeFocusShellCommand("am crash $homePackage")
        } finally {
            if (oldShowFirst == "null" || oldShowFirst.isBlank()) {
                executeFocusShellCommand("settings delete global show_first_crash_dialog")
            } else {
                executeFocusShellCommand(
                    "settings put global show_first_crash_dialog $oldShowFirst",
                )
            }
        }

        val deadline = SystemClock.elapsedRealtime() + 5_000
        var focusedPackage: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            focusedPackage = focusedFrameworkErrorPackage()
            if (focusedPackage != null) break
            SystemClock.sleep(50)
        }
        assertEquals(
            "the fixture must raise a genuine package-android framework error for HOME",
            homePackage,
            focusedPackage,
        )
        WalkthroughScreenshotArtifacts.capture("issue1985-launcher-framework-focus-owner")
        // No body cleanup. The @After boundary is the load-bearing finally
        // path; without it this Android-owned window poisons the next journey.
    }
}
