package com.pocketshell.app.diagnostics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.StrictMode
import android.util.Log
import java.util.concurrent.Executor

/**
 * Issue #933 (#928 D9 / P1) — the process-wide main-thread blocking-IO
 * DETECTOR.
 *
 * The recurring meta-problem the maintainer hits is freezes/crashes that CI
 * did NOT catch. The D9 audit established the single biggest detection hole:
 * there was NO process-wide [StrictMode] (the only one anywhere was a single
 * per-test `detectNetwork()` policy that guards exactly one SSH-close path).
 * The #926/#928-D1 freezes are unbounded `runBlocking { … }` **disk reads +
 * mutex waits** on the Main thread (e.g. `MainActivity.onCreate`'s
 * `runBlocking { resolveDefaultHostLaunchDestination }` Room read), and
 * `detectNetwork()` does not flag disk IO — only a socket write. So the exact
 * class the maintainer hit had no automated tripwire.
 *
 * This installer wires a Main-thread [StrictMode.ThreadPolicy] that detects
 * disk reads, disk writes, network use, and custom slow calls, and — on API 28+
 * — routes every violation into [DiagnosticEvents] as a `strictmode.violation`
 * event so the load-bearing journeys can HARD-assert zero violations over the
 * connect/switch/close hot windows. A blocked Main thread = a violation = a
 * recorded diagnostic = a RED journey.
 *
 * **Release safety (D9 mandate).** This is DEBUG/TEST-scoped: it is a no-op
 * unless the app is running as a debuggable build (`FLAG_DEBUGGABLE`, which is
 * true for `:app:assembleDebug` and the androidTest APK, false for the signed
 * release APK). It NEVER calls `penaltyDeath()` — legitimate startup disk reads
 * exist, so the *journey* is the gate, not a process kill. The penalty is
 * `penaltyLog()` plus the diagnostic listener; the app keeps running.
 */
object StrictModeInstaller {

    private const val LOG_TAG = "PsStrictMode"

    /**
     * The diagnostic category/event the [StrictMode] violation listener emits.
     * Journeys assert `DiagnosticEvents` carries ZERO of these over the hot
     * windows (see the connected `StrictModeMainThreadIoDetectorE2eTest`).
     */
    const val DIAGNOSTIC_CATEGORY: String = "strictmode"
    const val DIAGNOSTIC_EVENT: String = "violation"

    /**
     * Install the process-wide Main-thread policy when (and only when) the app
     * is a debuggable build. A no-op otherwise, so the signed release APK never
     * pays the StrictMode cost and can never trip `penaltyDeath` (there is
     * none).
     *
     * Returns `true` if the policy was installed (debuggable), `false` if it
     * was skipped (release).
     */
    fun installIfDebuggable(context: Context): Boolean {
        if (!isDebuggable(context)) return false
        StrictMode.setThreadPolicy(buildThreadPolicy { runnable -> runnable.run() })
        Log.i(LOG_TAG, "process-wide main-thread StrictMode installed (debug/test build)")
        return true
    }

    /**
     * True when [context] belongs to a debuggable build (debug variant or the
     * androidTest APK). The signed release APK is NOT debuggable, so this is
     * the seam that keeps the detector — and any future `penaltyDeath` — out of
     * release.
     */
    fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Build the Main-thread [StrictMode.ThreadPolicy] used in debug/test.
     *
     * Detects disk reads, disk writes, network, and custom slow calls — the
     * union of the #926/#928-D1 freeze ingredients (the `detectNetwork()`-only
     * policy that shipped before this missed the disk-IO majority). On API 28+
     * a [StrictMode.OnThreadViolationListener] routes each violation into
     * [DiagnosticEvents] via [recordViolation] AND logs it; below API 28 (no
     * listener API) it falls back to `penaltyLog()` only.
     *
     * Deliberately NO `penaltyDeath()` — see the class doc. Exposed for the JVM
     * detector test so the listener wiring is exercised without a device.
     */
    fun buildThreadPolicy(listenerExecutor: Executor): StrictMode.ThreadPolicy {
        val builder = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .detectCustomSlowCalls()
            .penaltyLog()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder
                .penaltyListener(
                    listenerExecutor,
                    StrictMode.OnThreadViolationListener { violation ->
                        recordViolation(violation)
                    },
                )
                .build()
        } else {
            builder.build()
        }
    }

    /**
     * Route a single StrictMode violation into [DiagnosticEvents] as a
     * `strictmode.violation` event so journeys can assert on it, and log it.
     *
     * Pulled out as its own function (taking a plain [Throwable]) so the JVM
     * detector test can drive it directly and prove the detector actually
     * fires a recorded diagnostic — without needing a real on-device StrictMode
     * trip.
     */
    fun recordViolation(violation: Throwable) {
        val kind = violationKind(violation)
        Log.w(LOG_TAG, "main-thread StrictMode violation kind=$kind", violation)
        DiagnosticEvents.record(
            DIAGNOSTIC_CATEGORY,
            DIAGNOSTIC_EVENT,
            "kind" to kind,
            "detail" to (violation.message ?: violation::class.java.simpleName),
            "topFrame" to (violation.stackTrace.firstOrNull()?.toString() ?: "unknown"),
        )
    }

    /**
     * Map the concrete violation subclass name to a stable, SDK-robust kind
     * token (`disk_read`, `disk_write`, `network`, `custom_slow_call`, or the
     * raw simple name). The platform subclass names have wobbled across SDK
     * versions, so match on substrings rather than exact classes.
     */
    internal fun violationKind(violation: Throwable): String {
        val name = violation::class.java.simpleName
        return when {
            name.contains("DiskRead", ignoreCase = true) -> "disk_read"
            name.contains("DiskWrite", ignoreCase = true) -> "disk_write"
            name.contains("Network", ignoreCase = true) -> "network"
            name.contains("CustomSlowCall", ignoreCase = true) -> "custom_slow_call"
            name.contains("Disk", ignoreCase = true) -> "disk"
            else -> name.ifBlank { "unknown" }
        }
    }
}
