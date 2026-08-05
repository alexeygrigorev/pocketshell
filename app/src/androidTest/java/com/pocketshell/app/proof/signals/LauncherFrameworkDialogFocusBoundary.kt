package com.pocketshell.app.proof.signals

import android.app.Activity
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

private val FRAMEWORK_ERROR_FOCUS_PATTERN = Regex(
    "mCurrentFocus=.*(?:Application Not Responding|Application Error): ([A-Za-z0-9._]+)",
)

/**
 * Restores a journey from the one environment-owned focus window proven by
 * issue #1985: a framework crash/ANR dialog for the device's HOME app.
 *
 * This is deliberately narrower than "package android". Framework dialogs for
 * PocketShell and app-owned dialogs remain hard failures. Only a currently
 * focused framework error whose subject is the dynamically resolved HOME
 * package is closed, and the caller's activity must then regain focus.
 */
fun requirePocketShellFocusAfterLauncherDialogCleanup(
    scenario: ActivityScenario<out Activity>,
    context: String,
    timeoutMs: Long = WINDOW_FOCUS_DEFAULT_TIMEOUT_MS,
) {
    if (waitForActivityWindowFocused(scenario, timeoutMs = 250)) return

    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val focusedFrameworkPackage = focusedFrameworkErrorPackage()
    val homePackage = resolveHomePackage()

    if (focusedFrameworkPackage == homePackage) {
        dismissFocusedLauncherFrameworkDialog()
        if (waitForActivityWindowFocused(scenario, timeoutMs)) return
    }

    val outcome = awaitActivityWindowFocus(scenario, timeoutMs = 0)
    throw AssertionError(
        "$FOREIGN_WINDOW_FOCUS_SIGNATURE $context; ${outcome.diagnosis}; " +
            "focused_framework_package=${focusedFrameworkPackage ?: "<none>"} " +
            "home_package=$homePackage",
    )
}

internal fun resolveHomePackage(): String {
    val component = executeShellCommand(
        "cmd package resolve-activity --brief " +
            "-a android.intent.action.MAIN -c android.intent.category.HOME",
    ).lineSequence().lastOrNull { '/' in it }.orEmpty().trim()
    val pkg = component.substringBefore('/')
    if (pkg.isBlank()) {
        throw AssertionError("issue #1985 could not resolve the device HOME package: '$component'")
    }
    return pkg
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
