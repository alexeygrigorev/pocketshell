package com.pocketshell.app.crash

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.share.ShareItemUploader
import com.pocketshell.app.share.ShareUploader
import com.pocketshell.app.share.ShareableItem
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock
import javax.inject.Inject

/**
 * Owns the "Share all reports" + "Delete all reports" actions on the
 * crash/diagnostics screen (issue #466).
 *
 * Responsibilities:
 *
 * - Expose the current report count so the screen can show "Share all (N)".
 * - **Share all**: pack every local report file into ONE zip (via
 *   [ReportsArchive]) and upload it to `~/inbox/pocketshell/` through the
 *   existing [ShareUploader] inbox transport. The uploader opens its OWN
 *   fresh, short-lived SSH session per call — it does NOT depend on the
 *   terminal's live session, so the #451 "no live SSH session after the
 *   file picker" failure mode does not apply here: tapping Share all is a
 *   connect-on-action that connects fresh and uploads on arrival.
 * - **Delete all**: clear every local report file. Surfaced behind an
 *   explicit confirm in the UI; the ViewModel only ever deletes when
 *   asked, and Share all NEVER deletes — so an upload that fails leaves
 *   every report intact for a retry.
 *
 * Host selection: if exactly one host is configured the share goes there
 * directly; with several hosts the screen surfaces a picker and calls
 * [shareAllTo] with the chosen host.
 */
@HiltViewModel
internal class CrashReportsViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val hostDao: HostDao,
    private val sshKeyDao: SshKeyDao,
) : ViewModel() {

    private val store: CrashReportStore = CrashReporter.store(applicationContext)

    /** Clock for the deterministic archive filename; overridable in tests. */
    @androidx.annotation.VisibleForTesting
    internal var clock: Clock = Clock.systemUTC()

    /**
     * The per-file uploader. A `var` so unit tests can substitute a fake
     * that records calls and drives success/failure without a live SSH
     * session (mirrors [com.pocketshell.app.share.ShareViewModel.uploader]).
     * Production never reassigns it.
     */
    @androidx.annotation.VisibleForTesting
    internal var uploader: ShareItemUploader = ShareUploader(applicationContext)

    private val _reports = MutableStateFlow(store.list())
    val reports: StateFlow<List<CrashReport>> = _reports.asStateFlow()

    val hosts: StateFlow<List<HostEntity>> = hostDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

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
     * Begin the Share-all flow. With no hosts there is nowhere to send;
     * with exactly one host we upload there directly; otherwise we ask the
     * screen to show a picker.
     */
    fun shareAll() {
        if (_reports.value.isEmpty()) return
        if (_shareAllState.value is ShareAllState.Uploading) return
        val configuredHosts = hosts.value
        when {
            configuredHosts.isEmpty() ->
                _shareAllState.value = ShareAllState.Failed(
                    "No host configured — add a host to share reports.",
                )
            configuredHosts.size == 1 -> shareAllTo(configuredHosts.single())
            else -> _shareAllState.value = ShareAllState.PickingHost(configuredHosts)
        }
    }

    /** User picked a host from the picker (or there was exactly one). */
    fun shareAllTo(host: HostEntity) {
        val reportFiles = _reports.value.map { it.file }.filter { it.isFile }
        if (reportFiles.isEmpty()) {
            _shareAllState.value = ShareAllState.Failed("No reports to share.")
            return
        }
        if (_shareAllState.value is ShareAllState.Uploading) return
        _shareAllState.value = ShareAllState.Uploading(host.name)
        viewModelScope.launch {
            val keyEntity = sshKeyDao.getById(host.keyId)
            if (keyEntity == null) {
                _shareAllState.value =
                    ShareAllState.Failed("No SSH key for host ${host.name}.")
                return@launch
            }

            val archive = runCatching {
                val dir = File(applicationContext.cacheDir, "report-archives")
                    .also { it.mkdirs() }
                val name = ReportsArchive.archiveFileName(deviceLabel(), clock)
                ReportsArchive.packInto(reportFiles, File(dir, name))
            }.getOrElse { error ->
                _shareAllState.value = ShareAllState.Failed(
                    error.message ?: "Could not build the reports archive.",
                )
                return@launch
            }

            val item = ShareableItem.FileItem(
                file = archive,
                displayName = archive.name,
            )
            val result = uploader.upload(host, keyEntity, item)
            // Always drop the temp archive; the source reports are untouched.
            archive.delete()

            result.fold(
                onSuccess = { remotePath ->
                    // Reports are intentionally preserved here. The user
                    // deletes them explicitly via Delete all once they have
                    // confirmed the bundle landed.
                    _shareAllState.value = ShareAllState.Success(
                        hostName = host.name,
                        remotePath = remotePath,
                        reportCount = reportFiles.size,
                    )
                },
                onFailure = { error ->
                    _shareAllState.value = ShareAllState.Failed(
                        error.message ?: "Upload failed — reports were kept.",
                    )
                },
            )
        }
    }

    fun clearShareAllState() {
        _shareAllState.value = ShareAllState.Idle
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

    /** Multiple hosts configured — the screen shows a picker. */
    data class PickingHost(val hosts: List<HostEntity>) : ShareAllState

    /** Packing + uploading the zip to [hostName]. */
    data class Uploading(val hostName: String) : ShareAllState

    /** The bundle landed at [remotePath]; [reportCount] reports were sent. */
    data class Success(
        val hostName: String,
        val remotePath: String,
        val reportCount: Int,
    ) : ShareAllState

    /** Share failed; reports were preserved. */
    data class Failed(val message: String) : ShareAllState
}
