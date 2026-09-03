package com.pocketshell.next.crash

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Ported unchanged (besides package) from the old app's
 * `com.pocketshell.app.crash.CrashReporter` (rewrite task P-10) — the
 * uncaught-exception handler installer + non-fatal recording entry point.
 */
object CrashReporter {
    private const val DirectoryName = "crash-reports"
    private val currentContext = AtomicReference(CrashReportContext.Unknown)

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is ReportingUncaughtExceptionHandler) return

        val store = CrashReportStore(directory(appContext))
        Thread.setDefaultUncaughtExceptionHandler(
            ReportingUncaughtExceptionHandler(
                store = store,
                metadataProvider = { appContext.crashReportMetadata() },
                contextProvider = { currentContext.get() },
                delegate = previous,
            ),
        )
    }

    fun updateContext(next: CrashReportContext) {
        currentContext.set(next)
    }

    fun store(context: Context): CrashReportStore =
        CrashReportStore(directory(context.applicationContext))

    /**
     * Persist a NON-FATAL throwable to the same crash-report store the
     * uncaught handler uses, WITHOUT re-delegating to the platform
     * [Thread.UncaughtExceptionHandler] — so the process survives.
     *
     * Returns false (and records nothing) if [context] is unavailable.
     */
    fun recordNonFatal(
        context: Context?,
        throwable: Throwable,
        threadName: String = Thread.currentThread().name,
    ): Boolean {
        val appContext = context?.applicationContext ?: return false
        return runCatching {
            store(appContext).save(
                throwable = throwable,
                threadName = threadName,
                metadata = appContext.crashReportMetadata(),
                context = currentContext.get(),
            )
        }.isSuccess
    }

    private fun directory(context: Context): File =
        File(context.filesDir, DirectoryName)
}

class ReportingUncaughtExceptionHandler(
    private val store: CrashReportStore,
    private val metadataProvider: () -> CrashReportMetadata,
    private val contextProvider: () -> CrashReportContext,
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            store.save(
                throwable = throwable,
                threadName = thread.name,
                metadata = metadataProvider(),
                context = contextProvider(),
            )
        }

        delegate?.uncaughtException(thread, throwable)
    }
}

private fun Context.crashReportMetadata(): CrashReportMetadata {
    val versionName = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    return CrashReportMetadata(
        appVersion = versionName,
        androidRelease = Build.VERSION.RELEASE ?: "unknown",
        sdkInt = Build.VERSION.SDK_INT,
        device = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(separator = " ")
            .ifBlank { "unknown" },
    )
}
