package com.pocketshell.app.crash

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock
import javax.inject.Inject

internal const val REPORT_ARCHIVES_CACHE_DIR = "report-archives"
private const val REPORT_ARCHIVE_RETENTION_MS = 24L * 60L * 60L * 1000L

/**
 * Owns the "Share all reports" + "Delete all reports" actions on the
 * crash/diagnostics screen.
 *
 * Responsibilities:
 *
 * - Expose the current report count so the screen can show "Share all (N)".
 * - **Share all**: pack every local report file into ONE zip (via
 *   [ReportsArchive]) and hand the prepared file back to the screen so it
 *   can launch Android's native share sheet through a content URI. This
 *   keeps the potentially large report contents out of intent extras and
 *   works even before a host is configured.
 * - **Delete all**: clear every local report file. Surfaced behind an
 *   explicit confirm in the UI; the ViewModel only ever deletes when asked.
 */
@HiltViewModel
internal class CrashReportsViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
) : ViewModel() {

    private val store: CrashReportStore = CrashReporter.store(applicationContext)

    /** Clock for the deterministic archive filename; overridable in tests. */
    @androidx.annotation.VisibleForTesting
    internal var clock: Clock = Clock.systemUTC()

    private val _reports = MutableStateFlow(store.list())
    val reports: StateFlow<List<CrashReport>> = _reports.asStateFlow()

    private val _shareAllState = MutableStateFlow<ShareAllState>(ShareAllState.Idle)
    val shareAllState: StateFlow<ShareAllState> = _shareAllState.asStateFlow()

    fun reload() {
        _reports.value = store.list()
    }

    fun read(report: CrashReport): String = store.read(report)

    fun deleteOne(report: CrashReport) {
        store.delete(report)
        reload()
    }

    /** Confirmed "Delete all": clear every local report file. */
    fun deleteAll() {
        _reports.value.forEach { store.delete(it) }
        reload()
    }

    /**
     * Begin the Share-all flow by preparing a zip in cache. The composable
     * observes [ShareAllState.Prepared] and launches the platform chooser.
     */
    fun shareAll() {
        if (_reports.value.isEmpty()) return
        if (_shareAllState.value is ShareAllState.Preparing) return
        val reportFiles = _reports.value.map { it.file }.filter { it.isFile }
        if (reportFiles.isEmpty()) {
            _shareAllState.value = ShareAllState.Failed("No reports to share.")
            return
        }
        _shareAllState.value = ShareAllState.Preparing
        viewModelScope.launch {
            val archive = runCatching {
                val dir = reportArchivesDir()
                pruneOldReportArchives(dir)
                val name = ReportsArchive.archiveFileName(deviceLabel(), clock)
                ReportsArchive.packInto(reportFiles, File(dir, name))
            }.getOrElse { error ->
                _shareAllState.value = ShareAllState.Failed(
                    error.message ?: "Could not build the reports archive.",
                )
                return@launch
            }

            _shareAllState.value = ShareAllState.Prepared(
                archive = archive,
                reportCount = reportFiles.size,
            )
        }
    }

    fun markShareAllLaunched() {
        if (_shareAllState.value is ShareAllState.Prepared) {
            _shareAllState.value = ShareAllState.Idle
        }
    }

    fun shareAllLaunchFailed(message: String) {
        _shareAllState.value = ShareAllState.Failed(message)
    }

    fun clearShareAllState() {
        _shareAllState.value = ShareAllState.Idle
    }

    private fun reportArchivesDir(): File =
        File(applicationContext.cacheDir, REPORT_ARCHIVES_CACHE_DIR).also { it.mkdirs() }

    private fun pruneOldReportArchives(dir: File) {
        val cutoff = System.currentTimeMillis() - REPORT_ARCHIVE_RETENTION_MS
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("pocketshell-reports-") }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    private fun deviceLabel(): String =
        listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(separator = " ")
            .ifBlank { "device" }
}

/** State machine for the Share-all action. */
internal sealed interface ShareAllState {
    data object Idle : ShareAllState

    /** Packing the zip in cache. */
    data object Preparing : ShareAllState

    /** The bundle is ready for Android's native share sheet. */
    data class Prepared(
        val archive: File,
        val reportCount: Int,
    ) : ShareAllState

    /** Share failed; reports were preserved. */
    data class Failed(val message: String) : ShareAllState
}
