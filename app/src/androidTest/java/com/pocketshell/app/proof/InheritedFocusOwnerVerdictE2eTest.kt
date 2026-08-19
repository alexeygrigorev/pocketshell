package com.pocketshell.app.proof

import android.app.Dialog
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pocketshell.app.proof.signals.FOREIGN_WINDOW_FOCUS_SIGNATURE
import com.pocketshell.app.proof.signals.InheritedJourneyFocus
import com.pocketshell.app.proof.signals.SyntheticFocusOwnerHarness
import com.pocketshell.app.proof.signals.activityWindowFocused
import com.pocketshell.app.proof.signals.describeActiveWindow
import com.pocketshell.app.proof.signals.executeFocusShellCommand
import com.pocketshell.app.proof.signals.parseHomePackage
import com.pocketshell.app.proof.signals.recordJourneyEntryFocus
import com.pocketshell.app.proof.signals.requireNoJourneyOwnedFocusRegression
import com.pocketshell.app.proof.signals.waitForActivityWindowFocusLost
import com.pocketshell.app.proof.signals.waitForActivityWindowFocused
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2021 — an INHERITED focus owner must not void a downstream journey's
 * verdict, and the checks that ARE this journey's to make must keep their teeth.
 *
 * ## The reported problem, reproduced exactly
 *
 * On nightly run 31074120488 (2026-08-06, `main` 6892a313) two of the seven
 * #1994 reopen-proof arms produced **no verdict at all**. Both died at a focus
 * pre-condition before a single product assertion ran:
 *
 * ```
 * SessionSwipeSwitchE2eTest > foreignWindowOverTheViewportIsNamed… FAILED
 *   java.lang.AssertionError: The app window never held input focus, …
 *   before raising synthetic focus owner 'issue-1994 synthetic focus owner';
 *   app_window_focused=false active_window_pkg=com.pocketshell.app
 *   active_window_class=android.widget.FrameLayout
 *   at …SyntheticFocusOwnerHarnessKt.requirePocketShellFocusAtJourneyBoundary(
 *       SyntheticFocusOwnerHarness.kt:119)
 * ```
 *
 * `active_window_pkg=com.pocketshell.app` with `focused_framework_package=<none>`
 * is the load-bearing detail the issue did not have: this was **not** a foreign
 * window. It is an app-owned window owning the display while the scenario's
 * activity had no input focus — the state [inheritAppOwnedFocusWindow] below
 * creates directly, which is why this reproduction is faithful rather than a
 * proxy for the environment stall that produced it in the wild.
 *
 * ## Why the state is injected rather than waited for
 *
 * The hosted stall behind it (swiftshader frames up to 13.9 s across a splash
 * window handover the framework itself logged as
 * `Input channel object '… Splash Screen com.pocketshell.app (client)' was
 * disposed without first being removed with the input manager!`) cannot be
 * commanded on demand. D33: inject the failing state synthetically and HARD-fail
 * otherwise — [assertTrue] on `waitForActivityWindowFocusLost`, never a skip.
 *
 * ## Selectivity
 *
 * [aJourneyThatLeaksItsOwnSyntheticOwnerStillFails] is the anti-amnesty control:
 * relaxing the INHERITED case must not relax the OWNED one. Without it, "the
 * journey reached its body" could be bought by deleting the checks entirely.
 *
 * Leftover (Nightly 31147697950 / 31456819680, and the later
 * EnvironmentFocusOwnerCleanup self-check flake): an APP-OWNED splash/sheet
 * FrameLayout after a *focused* entry used to fail the exit boundary with the
 * same `active_window_pkg=<app>` reading. That is now classified as splash-
 * owner, not a journey regression. A genuine foreign package still fails
 * ([theJourneyBoundaryStillFailsOnAForeignFocusOwner]).
 */
@RunWith(AndroidJUnit4::class)
class InheritedFocusOwnerVerdictE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var inherited: Dialog? = null

    @After
    fun dismissInheritedOwner() {
        val dialog = inherited ?: return
        inherited = null
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Leave the device the way the next class expects it: this class's own
        // injected owner is gone and PocketShell owns the display again.
        assertTrue(
            "the inherited-owner fixture must not leak into the next journey",
            waitForActivityWindowFocused(compose.activityRule.scenario, timeoutMs = 10_000),
        )
    }

    /**
     * The reproduction. RED on base: [SyntheticFocusOwnerHarness.withOwner]
     * throws `$FOREIGN_WINDOW_FOCUS_SIGNATURE before raising synthetic focus
     * owner …` at SyntheticFocusOwnerHarness.kt:119 and `reachedBody` stays
     * false — exactly the nightly signature, and exactly "no verdict". GREEN
     * with the fix: the body runs and the injected state is proven.
     */
    @Test
    fun anInheritedAppOwnedFocusWindowStillLetsTheJourneyReachItsVerdict() {
        inheritAppOwnedFocusWindow()

        var reachedBody = false
        var ownerHadFocus = false
        var activityLostFocus = true
        SyntheticFocusOwnerHarness(
            scenario = compose.activityRule.scenario,
            label = "issue-2021 downstream synthetic owner",
            timeoutMs = 5_000,
        ).withOwner { dialog ->
            reachedBody = true
            ownerHadFocus = dialog.window?.decorView?.hasWindowFocus() == true
            activityLostFocus = !activityWindowFocused(compose.activityRule.scenario)
        }

        assertTrue(
            "a focus owner this journey INHERITED must not stop it reaching its own " +
                "verdict — that is the #2021 void the nightly recorded",
            reachedBody,
        )
        assertTrue(
            "the downstream harness must still hard-prove its own injection took: " +
                "the owner it raised owns the window focus",
            ownerHadFocus,
        )
        assertTrue(
            "the downstream harness must still hard-prove the ACTIVITY reading (the one " +
                "the #1994 capture oracle consumes) flipped to unfocused",
            activityLostFocus,
        )
    }

    /**
     * Selectivity control: the relaxation is scoped to the INHERITED state. A
     * synthetic owner this journey raised and failed to clean up is still a hard
     * failure, so #1985's no-leak guarantee is intact.
     */
    @Test
    fun aJourneyThatLeaksItsOwnSyntheticOwnerStillFails() {
        val harness = SyntheticFocusOwnerHarness(
            scenario = compose.activityRule.scenario,
            label = "issue-2021 leaked synthetic owner",
            timeoutMs = 5_000,
        )
        var owned: Dialog? = null
        val leaked = runCatching {
            harness.withOwner { dialog ->
                owned = dialog
                // Re-show on the way out so the harness's own dismiss cannot win:
                // this models a journey whose owner survives its cleanup.
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    dialog.setOnDismissListener { dialog.show() }
                }
            }
        }.exceptionOrNull()

        // Stop the re-show loop before asserting, so a failure here still leaves
        // the device usable for the next class.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owned?.setOnDismissListener(null)
            if (owned?.isShowing == true) owned?.dismiss()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertNotNull(
            "a synthetic owner that survives its own cleanup must still hard-fail — " +
                "relaxing the INHERITED case must not become blanket amnesty",
            leaked,
        )
        assertTrue(
            "the leak failure must name the surviving owner, got: ${leaked?.message}",
            leaked?.message.orEmpty().contains("survived its own cleanup"),
        )
        assertTrue(
            "the device must be left usable for the next journey",
            waitForActivityWindowFocused(compose.activityRule.scenario, timeoutMs = 10_000),
        )
    }

    /**
     * The class-boundary sibling of the leftover recurrence: an inherited
     * unfocused reading stays quiet, AND an APP-OWNED splash/sheet owner that
     * appears after a focused entry also stays quiet. A genuine foreign owner
     * is covered by [theJourneyBoundaryStillFailsOnAForeignFocusOwner].
     */
    @Test
    fun theJourneyBoundaryFailsOnAnOwnedRegressionAndNotOnAnInheritedOne() {
        val scenario = compose.activityRule.scenario

        // (a) inherited: entry already unfocused -> exit stays quiet.
        inheritAppOwnedFocusWindow()
        val inheritedEntry: InheritedJourneyFocus = recordJourneyEntryFocus(
            scenario = scenario,
            context = "issue #2021 inherited entry",
            timeoutMs = 2_000,
        )
        assertFalse(
            "the fixture must make the entry boundary observe an unfocused activity",
            inheritedEntry.focused,
        )
        assertTrue(
            "the inherited reading must still be REPORTED, not swallowed",
            inheritedEntry.diagnosis.contains("app_window_focused=false") &&
                inheritedEntry.diagnosis.contains("active_window_pkg="),
        )
        requireNoJourneyOwnedFocusRegression(
            scenario = scenario,
            entry = inheritedEntry,
            context = "issue #2021 inherited exit",
            timeoutMs = 2_000,
        )

        // (b) leftover recurrence: entry WAS focused, then an APP-OWNED splash /
        // sheet / FrameLayout owns the display (FileViewer teardown + both
        // #1994 arms on Nightly 31456819680). That reading is the hosted
        // splash-owner stall, not a foreign thief this journey leaked, so the
        // exit boundary must stay quiet. RED on current main: the boundary
        // still calls requirePocketShellFocusAtJourneyBoundary and throws
        // FOREIGN_WINDOW_FOCUS_SIGNATURE with active_window_pkg=<our package>.
        dismissInheritedOwner()
        val focusedEntry = recordJourneyEntryFocus(
            scenario = scenario,
            context = "issue #2021 splash-owner entry",
            timeoutMs = 10_000,
        )
        assertTrue("the splash-owner leftover needs a focused entry reading", focusedEntry.focused)
        inheritAppOwnedFocusWindow()
        requireNoJourneyOwnedFocusRegression(
            scenario = scenario,
            entry = focusedEntry,
            context = "issue #2021 splash-owner exit",
            timeoutMs = 2_000,
        )
    }

    /**
     * Selectivity for the leftover: relaxing the APP-OWNED splash-owner case
     * must not become blanket amnesty for a genuine foreign owner. A Settings
     * window in front is the named-other-package reading the class boundary
     * still exists to catch (#1985 / #1879).
     */
    @Test
    fun theJourneyBoundaryStillFailsOnAForeignFocusOwner() {
        val scenario = compose.activityRule.scenario
        val focusedEntry = recordJourneyEntryFocus(
            scenario = scenario,
            context = "issue #2021 foreign-owner entry",
            timeoutMs = 10_000,
        )
        assertTrue("the foreign-owner control needs a focused entry reading", focusedEntry.focused)

        val targetPackage = InstrumentationRegistry.getInstrumentation()
            .targetContext.packageName
        executeFocusShellCommand("am start -a android.settings.SETTINGS")
        assertTrue(
            "the fixture must actually put a foreign package in front, otherwise this " +
                "control proves nothing about the leftover classification",
            waitForForeignPackageInFront(targetPackage),
        )
        val regression = runCatching {
            requireNoJourneyOwnedFocusRegression(
                scenario = scenario,
                entry = focusedEntry,
                context = "issue #2021 foreign-owner exit",
                timeoutMs = 2_000,
            )
        }.exceptionOrNull()
        bringScenarioBackToFront()
        assertNotNull(
            "a genuine foreign focus owner must still hard-fail at the boundary — " +
                "excusing splash-owner must not become blanket amnesty",
            regression,
        )
        assertTrue(
            "the boundary failure must keep the #1879 signature, got: ${regression?.message}",
            regression?.message.orEmpty().contains(FOREIGN_WINDOW_FOCUS_SIGNATURE),
        )
        assertTrue(
            "the foreign-owner failure must name a package that is not this app, got: " +
                "${regression?.message}",
            regression?.message.orEmpty().contains("active_window_pkg=") &&
                !regression?.message.orEmpty().contains("active_window_pkg=$targetPackage"),
        )
    }

    /**
     * Issue #2021 leftover self-check flake: an empty HOME resolver output
     * must not be treated as a package. Mutation: returning a hardcoded
     * launcher package on blank input reddens the empty cases.
     */
    @Test
    fun homePackageParserDoesNotInventALauncherWhenTheResolverIsEmpty() {
        assertEquals(
            "empty dumpsys/resolver output is not a HOME package",
            "",
            parseHomePackage(""),
        )
        assertEquals(
            "a resolver miss is not a HOME package",
            "",
            parseHomePackage("No activity found"),
        )
        assertEquals(
            "the last component/line is the HOME package",
            "com.google.android.apps.nexuslauncher",
            parseHomePackage(
                "priority=0 preferredOrder=0 match=0x108000\n" +
                    "com.google.android.apps.nexuslauncher/" +
                    "com.google.android.apps.nexuslauncher.NexusLauncherActivity\n",
            ),
        )
    }

    /**
     * Create the exact nightly state: an app-owned window owns the display while
     * the scenario's activity holds no input focus. HARD-asserted, never assumed.
     */
    private fun inheritAppOwnedFocusWindow() {
        var shown: Dialog? = null
        compose.activityRule.scenario.onActivity { activity ->
            shown = Dialog(activity).also { dialog ->
                dialog.setContentView(
                    TextView(activity).apply {
                        text = "issue-2021 inherited focus owner"
                        isFocusable = true
                        isFocusableInTouchMode = true
                    },
                )
                dialog.setCancelable(false)
                dialog.show()
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        inherited = shown
        assertTrue(
            "the inherited-owner fixture must actually take the window focus, otherwise " +
                "this test proves nothing about the reported state",
            waitForInheritedOwnerFocus(requireNotNull(shown)),
        )
        assertTrue(
            "the inherited-owner fixture must leave the activity WITHOUT window focus — " +
                "that is the reported app_window_focused=false reading",
            waitForActivityWindowFocusLost(compose.activityRule.scenario, timeoutMs = 5_000),
        )
    }

    private fun waitForInheritedOwnerFocus(dialog: Dialog): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (dialog.window?.decorView?.hasWindowFocus() == true) return true
            SystemClock.sleep(50)
        }
        return dialog.window?.decorView?.hasWindowFocus() == true
    }

    private fun waitForForeignPackageInFront(appPackage: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val diagnosis = describeActiveWindow()
            val pkg = diagnosis.substringAfter("active_window_pkg=", "")
                .substringBefore(' ')
            if (pkg.isNotBlank() && pkg != appPackage && pkg != "<unavailable>") {
                return true
            }
            SystemClock.sleep(50)
        }
        return false
    }

    private fun bringScenarioBackToFront() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.getSystemService(android.app.ActivityManager::class.java)
                .appTasks
                .firstOrNull { it.taskInfo.taskId == activity.taskId }
                ?.moveToFront()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        waitForActivityWindowFocused(compose.activityRule.scenario, timeoutMs = 10_000)
    }
}
