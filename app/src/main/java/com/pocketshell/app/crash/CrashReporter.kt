package com.pocketshell.app.crash

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.atomic.AtomicReference

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
     * This is the visibility side of issue #896's scope-level
     * [kotlinx.coroutines.CoroutineExceptionHandler] safety net: a stray
     * teardown-race throw on the SSH/tmux close cascade is converted from
     * process death into a logged, recoverable event, but it is still
     * saved here so a swallowed throw never becomes a silent black hole
     * (the #896 anti-masking requirement). Returns false (and records
     * nothing) if [context] is unavailable.
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
