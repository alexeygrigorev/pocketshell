package com.pocketshell.app.voice

import android.content.Context
import com.pocketshell.core.storage.dao.PendingTranscriptionDao
import com.pocketshell.core.storage.entity.PendingTranscriptionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-disk + DB queue for failed / offline Whisper transcriptions —
 * issue #180.
 *
 * Each queued entry pairs:
 *
 *  1. A row in [PendingTranscriptionDao] indexed by a stable UUID string.
 *  2. An audio file at `filesDir/voice-pending/<uuid>.wav` containing the
 *     bytes the recorder produced.
 *
 * The store enforces three invariants:
 *
 *  - **Persist-before-Whisper.** Callers MUST [enqueueAudio] before they
 *    attempt the Whisper round-trip. On success they call
 *    [markSucceeded] which deletes both the file and the row. On failure
 *    they call [markFailure] which bumps the retry counter and stamps
 *    the error message. If the process is killed between the Whisper
 *    request finishing and the success/failure call, the row stays put;
 *    the user sees a stale "pending" entry but the audio is not lost.
 *  - **10 MB cap per recording.** Audio larger than [MAX_AUDIO_BYTES] is
 *    silently dropped by [enqueueAudio] (returns `null`); the
 *    recording is then not queued for retry. A 10 MB cap allows
 *    ≥ 5 minutes of 16 kHz mono 16-bit speech, which already far
 *    exceeds the cognitive-effort ceiling a single voice prompt
 *    represents.
 *  - **Orphan reconciliation.** [reconcile] is called once per
 *    `ViewModelScope` startup. It deletes file-without-row leftovers
 *    (from a crash between file write and DB insert) and row-without-
 *    file rows (from a manual file deletion). Without this, the
 *    `voice-pending/` directory and the table would drift forever.
 *
 * Threading: all suspend methods hop to [Dispatchers.IO] internally so
 * the call site (the composer ViewModel) does not have to do its own
 * dispatcher dance. The store is `@Singleton` because the DAO and the
 * filesystem reference must agree across recompositions.
 */
@Singleton
class PendingTranscriptionStore @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val dao: PendingTranscriptionDao,
) {
    /**
     * Test seam: production wires the system clock; tests substitute a
     * virtual one so timestamps are deterministic. Lives as a mutable
     * field (rather than a constructor parameter) so the Hilt graph
     * doesn't have to bind `() -> Long` — function types confuse Dagger
     * because every other consumer would also have to provide one.
     */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * Test seam: production uses real UUIDs; tests can supply a
     * deterministic generator so audio filenames are predictable.
     * Mutable field for the same reason as [clock].
     */
    internal var idGenerator: () -> String = { UUID.randomUUID().toString() }

    /**
     * Stream the current queue, newest recording first. Maps the entity
     * shape into the UI-facing [PendingTranscriptionItem] so consumers
     * never need to import the storage module's entity class.
     */
    val items: Flow<List<PendingTranscriptionItem>> = kotlinx.coroutines.flow.flow {
        dao.getAll().collect { rows ->
            emit(rows.map { it.toUiItem() })
        }
    }

    /**
     * Persist [audio] to `filesDir/voice-pending/<uuid>.wav` and insert a
     * matching DB row. Returns the new [PendingTranscriptionItem] on
     * success.
     *
     * Returns `null` when:
     *  - [audio] is empty (nothing to retry).
     *  - [audio] is larger than [MAX_AUDIO_BYTES] (cap-violation; the
     *    recording is then not queued for retry).
     *
     * The file is fsync-equivalent (Java `FileOutputStream.close()` does
     * not force a fsync, but the kernel's writeback is observable within
     * a few hundred milliseconds; the row insert is the durability
     * marker — if it succeeds, the file is committed).
     */
    suspend fun enqueueAudio(
        audio: ByteArray,
        destinationContext: String,
        recordingTimestampMs: Long = clock(),
        initialError: String? = null,
    ): PendingTranscriptionItem? = withContext(Dispatchers.IO) {
        if (audio.isEmpty()) return@withContext null
        if (audio.size.toLong() > MAX_AUDIO_BYTES) return@withContext null

        val id = idGenerator()
        val dir = ensureDir()
        val file = File(dir, "$id.wav")
        try {
            file.outputStream().use { out -> out.write(audio) }
        } catch (e: java.io.IOException) {
            // Disk full / permission error / IO surface — best-effort
            // delete the partial file then bail. The recording is
            // then not queued for retry.
            runCatching { file.delete() }
            return@withContext null
        }

        val entity = PendingTranscriptionEntity(
            id = id,
            audioPath = file.absolutePath,
            recordingTimestampMs = recordingTimestampMs,
            destinationContext = destinationContext,
            retryCount = 0,
            lastErrorMessage = initialError,
            audioByteSize = audio.size.toLong(),
            createdAtMs = clock(),
        )
        runCatching { dao.insert(entity) }.onFailure {
            // Row insert failed — the file is now an orphan. The next
            // reconcile() will sweep it up but we can also clean here.
            runCatching { file.delete() }
            return@withContext null
        }
        entity.toUiItem()
    }

    /**
     * Load the persisted audio bytes for [id]. Returns `null` if the
     * file is missing (orphaned row), the row is missing, or any IO
     * error occurs.
     */
    suspend fun loadAudio(id: String): ByteArray? = withContext(Dispatchers.IO) {
        val row = dao.getById(id) ?: return@withContext null
        val file = File(row.audioPath)
        if (!file.exists()) return@withContext null
        runCatching { file.readBytes() }.getOrNull()
    }

    /** Snapshot fetch, used by retry-on-foreground orchestration. */
    suspend fun snapshot(): List<PendingTranscriptionItem> = withContext(Dispatchers.IO) {
        dao.getAllOnce().map { it.toUiItem() }
    }

    /**
     * Delete the row + audio file for [id]. Idempotent: a missing row /
     * file is treated as success.
     */
    suspend fun markSucceeded(id: String) = withContext(Dispatchers.IO) {
        val row = dao.getById(id)
        if (row != null) {
            runCatching { File(row.audioPath).delete() }
        }
        dao.deleteById(id)
    }

    /**
     * Bump the retry counter and stamp the new error message. Returns the
     * updated row (or `null` if the row is already gone). Caller uses the
     * returned `retryCount` to decide whether the UI still offers Retry
     * or switches to Delete / Save-as-audio.
     */
    suspend fun markFailure(
        id: String,
        errorMessage: String,
    ): PendingTranscriptionItem? = withContext(Dispatchers.IO) {
        val current = dao.getById(id) ?: return@withContext null
        val updated = current.copy(
            retryCount = current.retryCount + 1,
            lastErrorMessage = errorMessage,
        )
        runCatching { dao.update(updated) }
        updated.toUiItem()
    }

    /**
     * Caller-driven discard. Same as [markSucceeded] (delete row + file)
     * but conceptually distinct so call sites can log a separate
     * "user-discarded" signal in future.
     */
    suspend fun discard(id: String) = markSucceeded(id)

    /**
     * Copy the persisted audio into a user-visible "exports" directory
     * so the user can pick it up via a file manager. Returns the
     * absolute path on success, `null` on failure. The original queue
     * entry is then deleted — saving is the "give up" path after the
     * 3-retry cap.
     */
    suspend fun saveAsAudioFile(id: String): String? = withContext(Dispatchers.IO) {
        val row = dao.getById(id) ?: return@withContext null
        val src = File(row.audioPath)
        if (!src.exists()) {
            // Orphaned row — sweep it.
            dao.deleteById(id)
            return@withContext null
        }
        val exportsDir = File(appContext.filesDir, EXPORTS_DIR).apply { mkdirs() }
        val dst = File(exportsDir, "voice-${row.recordingTimestampMs}-$id.wav")
        val ok = runCatching {
            src.inputStream().use { input -> dst.outputStream().use { output -> input.copyTo(output) } }
        }.isSuccess
        if (!ok) return@withContext null
        // After exporting we delete the queue row (and the source file)
        // so the banner clears.
        runCatching { src.delete() }
        dao.deleteById(id)
        dst.absolutePath
    }

    /**
     * Sweep orphans:
     *
     *  - Files in `voice-pending/` without a matching DB row are deleted
     *    (crash between file write and row insert).
     *  - Rows whose audio file is missing are deleted (manual file
     *    deletion or external sweep).
     *
     * Cheap enough to call on every ViewModel startup; the queue is
     * expected to hold a handful of entries at most.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val dir = ensureDir()
        val rows = dao.getAllOnce()
        val rowsById = rows.associateBy { it.id }
        // Delete orphan files.
        dir.listFiles()?.forEach { f ->
            val id = f.nameWithoutExtension
            if (!rowsById.containsKey(id)) {
                runCatching { f.delete() }
            }
        }
        // Delete row-without-file entries.
        rows.forEach { row ->
            if (!File(row.audioPath).exists()) {
                dao.deleteById(row.id)
            }
        }
    }

    /**
     * Wipe every queued row + audio file. Called when the user toggles
     * the feature off in Settings → Voice. Idempotent.
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        val dir = ensureDir()
        dir.listFiles()?.forEach { f -> runCatching { f.delete() } }
        dao.deleteAll()
    }

    private fun ensureDir(): File {
        val dir = File(appContext.filesDir, VOICE_PENDING_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun PendingTranscriptionEntity.toUiItem(): PendingTranscriptionItem =
        PendingTranscriptionItem(
            id = id,
            recordingTimestampMs = recordingTimestampMs,
            destinationContext = destinationContext,
            retryCount = retryCount,
            lastErrorMessage = lastErrorMessage,
            audioByteSize = audioByteSize,
        )

    companion object {
        /**
         * Folder under `filesDir` where audio files are persisted. The
         * standard Android app sandbox keeps the directory private to
         * the app uid — explicit at-rest encryption is a follow-up.
         */
        const val VOICE_PENDING_DIR: String = "voice-pending"

        /**
         * Folder under `filesDir` where the "save as audio" affordance
         * copies recordings the user wants to keep after the 3-retry
         * cap. The path is surfaced in the UI so the user knows where
         * to look.
         */
        const val EXPORTS_DIR: String = "voice-exports"

        /**
         * Hard cap per recording — issue #180 calls out 10 MB. A 60s
         * 16 kHz mono 16-bit WAV is ~1.9 MB, so 10 MB lets a >5 min
         * recording survive. Larger recordings are unusual enough that
         * dropping them is preferable to filling the disk.
         */
        const val MAX_AUDIO_BYTES: Long = 10L * 1024L * 1024L
    }
}

/**
 * UI-facing snapshot of a queued transcription. Lives at file scope so
 * the composer (and any other surface) can consume it without importing
 * the storage entity class.
 *
 * @property id stable UUID; passed back to [PendingTranscriptionStore]
 *   for retry / discard / save.
 * @property recordingTimestampMs epoch-millis at which the user stopped
 *   speaking. Used for the "X minutes ago" hint.
 * @property destinationContext where the transcript was meant to go
 *   (composer / inline-dictation / share-target). Today only the
 *   composer surface consumes the queue; reading the field is for
 *   future routing.
 * @property retryCount how many Whisper attempts have already failed.
 *   `0` means the row was queued offline before any attempt; the UI
 *   surfaces "waiting for network" rather than an error.
 * @property lastErrorMessage the user-facing string from the most recent
 *   Whisper failure, or `null` for offline-only rows.
 * @property audioByteSize cached size in bytes, used so the UI does not
 *   have to stat() every file.
 */
data class PendingTranscriptionItem(
    val id: String,
    val recordingTimestampMs: Long,
    val destinationContext: String,
    val retryCount: Int,
    val lastErrorMessage: String?,
    val audioByteSize: Long,
) {
    /**
     * True once the row has exhausted the [PendingTranscriptionEntity.MAX_RETRY_ATTEMPTS]
     * cap. The UI hides Retry in that case and offers Delete /
     * Save-as-audio instead.
     */
    val atRetryCap: Boolean
        get() = retryCount >= PendingTranscriptionEntity.MAX_RETRY_ATTEMPTS

    /**
     * True when the row was queued because the device had no network at
     * recording-stop time — distinct from a row that hit a Whisper
     * failure. The UI surfaces "waiting for network" vs the error text.
     */
    val isWaitingForNetwork: Boolean
        get() = retryCount == 0 && lastErrorMessage == NETWORK_WAITING_MESSAGE

    companion object {
        /**
         * Sentinel message stamped on a row that was queued because the
         * device was offline at the time the recording stopped. The UI
         * uses this string verbatim to render "waiting for network"
         * affordances; matching against the constant means the
         * recognisably-different surface can render even after a
         * process restart (the row's [lastErrorMessage] survives).
         */
        const val NETWORK_WAITING_MESSAGE: String = "Waiting for network — will retry when online."
    }
}
