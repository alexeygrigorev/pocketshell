package com.pocketshell.app.crash

import android.content.Context
import android.os.Build
import java.io.File

object CrashReporter {
    private const val DirectoryName = "crash-reports"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is ReportingUncaughtExceptionHandler) return

        val store = CrashReportStore(directory(appContext))
        Thread.setDefaultUncaughtExceptionHandler(
            ReportingUncaughtExceptionHandler(
                store = store,
                metadataProvider = { appContext.crashReportMetadata() },
                delegate = previous,
            ),
        )
    }

    fun store(context: Context): CrashReportStore =
        CrashReportStore(directory(context.applicationContext))

    private fun directory(context: Context): File =
        File(context.filesDir, DirectoryName)
}

class ReportingUncaughtExceptionHandler(
    private val store: CrashReportStore,
    private val metadataProvider: () -> CrashReportMetadata,
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            store.save(
                throwable = throwable,
                threadName = thread.name,
                metadata = metadataProvider(),
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
