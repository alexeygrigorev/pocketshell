package com.pocketshell.app.proof.signals

import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

private val FRAMEWORK_ERROR_FOCUS_PATTERN = Regex(
    "mCurrentFocus=.*(?:Application Not Responding|Application Error): ([A-Za-z0-9._]+)",
)

/*
 * Issue #1985's narrow, evidence-based cleanup of the one environment-owned
 * focus window proven safe to close: a framework crash/ANR dialog for the
 * device's HOME app.
 *
 * This is deliberately narrower than "package android". Framework dialogs for
 * PocketShell and app-owned dialogs are never touched.
 *
 * Issue #2021 removed the `requirePocketShellFocusAfterLauncherDialogCleanup`
 * wrapper that used to sit on top of these primitives (D22 hard cut — no shim).
 * That wrapper hard-failed a journey at its `@Before`/`@After` boundary for ANY
 * unfocused reading, including one the journey inherited from whatever ran
 * before it on the shard, which is how two of the seven #1994 reopen-proof arms
 * produced no verdict at all on the 2026-08-06 nightly. The boundary rule now
 * lives in `recordJourneyEntryFocus` / `requireNoJourneyOwnedFocusRegression`
 * (SyntheticFocusOwnerHarness.kt), which call the primitives below.
 */

internal fun parseHomePackage(resolveActivityOutput: String): String {
    val component = resolveActivityOutput.lineSequence()
        .lastOrNull { '/' in it }
        .orEmpty()
        .trim()
    return component.substringBefore('/')
}

internal fun tryResolveHomePackage(): String? {
    val pkg = parseHomePackage(
        executeShellCommand(
            "cmd package resolve-activity --brief " +
                "-a android.intent.action.MAIN -c android.intent.category.HOME",
        ),
    )
    return pkg.takeIf { it.isNotBlank() }
}

/**
 * Issue #2021 leftover self-check: on a cold AVD the HOME resolver can return
 * empty for a beat. Wait, then throw — never proceed with a null launcher
 * package (that voided EnvironmentFocusOwnerCleanup's arrange step).
 */
internal fun resolveHomePackage(
    timeoutMs: Long = 15_000L,
): String {
    val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
    var last = ""
    while (android.os.SystemClock.elapsedRealtime() < deadline) {
        last = tryResolveHomePackage().orEmpty()
        if (last.isNotBlank()) return last
        android.os.SystemClock.sleep(50)
    }
    throw AssertionError("issue #1985 could not resolve the device HOME package: '$last'")
}

/** Wait until [homePackage] owns the focused window (HOME keyevent settled). */
internal fun awaitHomePackageOwnsDisplay(
    homePackage: String,
    timeoutMs: Long = 15_000L,
): Boolean {
    val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
    while (android.os.SystemClock.elapsedRealtime() < deadline) {
        val dump = executeShellCommand("dumpsys window")
        val currentFocus = dump.lineSequence()
            .firstOrNull { it.contains("mCurrentFocus") }
            .orEmpty()
        if (currentFocus.contains(homePackage)) return true
        android.os.SystemClock.sleep(50)
    }
    return executeShellCommand("dumpsys window")
        .lineSequence()
        .firstOrNull { it.contains("mCurrentFocus") }
        .orEmpty()
        .contains(homePackage)
}

internal fun focusedFrameworkErrorPackage(): String? = FRAMEWORK_ERROR_FOCUS_PATTERN
    .find(executeShellCommand("dumpsys window"))
    ?.groupValues
    ?.get(1)

internal fun executeFocusShellCommand(command: String): String = executeShellCommand(command)

/** Closes only a currently focused framework error for the resolved HOME app. */
internal fun dismissFocusedLauncherFrameworkDialog(): Boolean {
    val focusedPackage = focusedFrameworkErrorPackage() ?: return false
    val homePackage = resolveHomePackage()
    if (focusedPackage != homePackage) return false
    val closeButton = waitForUniqueFrameworkCloseButton(timeoutMs = 5_000)
        ?: throw AssertionError(
            "issue #1985 recognized the environment-owned launcher framework " +
                "dialog but could not find its unique aerr_close action; " +
                "home_package=$homePackage",
        )
    if (!closeButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        throw AssertionError(
            "issue #1985 could not invoke the environment-owned launcher " +
                "framework dialog's aerr_close action; home_package=$homePackage",
        )
    }
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    return true
}

private fun waitForUniqueFrameworkCloseButton(timeoutMs: Long): AccessibilityNodeInfo? {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
    do {
        val buttons = instrumentation.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId("android:id/aerr_close")
            .orEmpty()
        if (buttons.size == 1) return buttons.single()
        android.os.SystemClock.sleep(50)
    } while (android.os.SystemClock.elapsedRealtime() < deadline)
    return null
}

private fun executeShellCommand(command: String): String {
    val descriptor = InstrumentationRegistry.getInstrumentation()
        .uiAutomation
        .executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        .bufferedReader()
        .use { it.readText() }
}
