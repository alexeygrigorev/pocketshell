package com.pocketshell.app.diagnostics

import android.content.Context
import com.pocketshell.app.crash.CrashReport
import com.pocketshell.app.crash.CrashReporter

/**
 * Issue #933 (#928 D9 / P3) — the post-journey ZERO-CRASH gate.
 *
 * `CrashReportStore` already persists EVERY crash to disk (the uncaught
 * handler) AND every NON-fatal recorded via [CrashReporter.recordNonFatal]
 * (the #896 net), with a `list()` API. The D9 audit found this is the
 * highest-value-per-line detector available today but it was wired NOWHERE — no
 * journey asserted the store was empty. So a crash that landed during a journey
 * (the D2 process-death signature: "it minimized then cold-started from the host
 * list") sailed through a green journey.
 *
 * This gate converts the persisted store into a CI-visible failure: after each
 * load-bearing journey, assert the store holds ZERO reports. The result type
 * carries the offending report text so a failing journey can attach it as an
 * artifact rather than just failing with a count.
 *
 * The pure [evaluate] entry point takes the already-read reports + their bodies
 * so it is JVM-unit-testable (red→green: a present report ⇒ FAIL, an empty list
 * ⇒ PASS) without a device; [evaluate(Context)] is the on-device convenience
 * that reads `CrashReporter.store(context).list()` first.
 */
object CrashReportGate {

    /**
     * The verdict of the zero-crash gate.
     *
     * [clean] is the load-bearing property the journey asserts. [failureMessage]
     * is a ready-to-use assertion message that names the count and inlines each
     * report's summary + body so the failure is self-describing in CI logs.
     */
    data class Result(
        val clean: Boolean,
        val reportCount: Int,
        val failureMessage: String,
        val reportBodies: List<String>,
    )

    /**
     * Pure evaluation over an already-read snapshot of the crash store.
     *
     * @param reports the reports from `CrashReportStore.list()`.
     * @param readBody reads a report's full body for the failure message; a
     *   failure to read is tolerated (the summary still names the crash).
     */
    fun evaluate(
        reports: List<CrashReport>,
        readBody: (CrashReport) -> String = { "<body unavailable>" },
    ): Result {
        if (reports.isEmpty()) {
            return Result(
                clean = true,
                reportCount = 0,
                failureMessage = "no crash reports — journey clean",
                reportBodies = emptyList(),
            )
        }
        val bodies = reports.map { report ->
            val body = runCatching { readBody(report) }.getOrElse { "<body read failed: ${it.message}>" }
            "----- crash ${report.id} (${report.summary}) -----\n$body"
        }
        val message = buildString {
            append("Zero-crash gate FAILED: ")
            append(reports.size)
            append(" crash report(s) were persisted during the journey ")
            append("(uncaught crash and/or recordNonFatal). ")
            append("This is the #928-D2 process-death / non-fatal signature surfacing as a CI failure.\n")
            append(bodies.joinToString("\n\n"))
        }
        return Result(
            clean = false,
            reportCount = reports.size,
            failureMessage = message,
            reportBodies = bodies,
        )
    }

    /**
     * On-device convenience: read the persistent crash store for [context] and
     * evaluate it. Used by the journey teardown rule.
     */
    fun evaluate(context: Context): Result {
        val store = CrashReporter.store(context)
        val reports = store.list()
        return evaluate(reports, readBody = { store.read(it) })
    }
}
