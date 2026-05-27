package com.pocketshell.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pocketshell.core.storage.entity.PendingTranscriptionEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the queue of audio recordings awaiting Whisper transcription —
 * issue #180.
 *
 * Reads stream as a [Flow] so the composer banner / list updates without
 * polling. Inserts and updates are suspend so the call site (composer
 * `stopAndTranscribe`) can persist the audio file first, then write the
 * row in the same coroutine, without hopping threads. The window between
 * "audio file on disk" and "row inserted" is closed by the orphan
 * reconciliation step in
 * [com.pocketshell.app.voice.PendingTranscriptionStore].
 */
@Dao
interface PendingTranscriptionDao {

    /**
     * Stream every queued row, newest recording first. The composer's
     * banner subscribes via this; an empty list means there is nothing to
     * retry and the banner hides.
     */
    @Query("SELECT * FROM pending_transcriptions ORDER BY recordingTimestampMs DESC")
    fun getAll(): Flow<List<PendingTranscriptionEntity>>

    /**
     * One-shot snapshot for paths that cannot wait for the next emission
     * (orphan-file reconciliation at app start, retry workflows that need
     * to read the row before deleting it).
     */
    @Query("SELECT * FROM pending_transcriptions ORDER BY recordingTimestampMs DESC")
    suspend fun getAllOnce(): List<PendingTranscriptionEntity>

    /** Fetch a single row by stable id, or null if it has been deleted. */
    @Query("SELECT * FROM pending_transcriptions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PendingTranscriptionEntity?

    /**
     * Insert a new pending row. The primary key is the caller-supplied
     * UUID, so `REPLACE` is harmless — it lets the store re-queue an
     * existing row with the same audio file without an UPSERT dance.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: PendingTranscriptionEntity)

    /**
     * Update an existing row in place. Used by the retry path to bump
     * `retryCount` and refresh `lastErrorMessage` without recreating the
     * row (which would change `createdAtMs` and confuse the list order).
     */
    @Update
    suspend fun update(entry: PendingTranscriptionEntity)

    /** Delete a single row by id. The audio file is deleted by the store. */
    @Query("DELETE FROM pending_transcriptions WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Wipe every queued row (e.g. when the user toggles the feature off). */
    @Query("DELETE FROM pending_transcriptions")
    suspend fun deleteAll()
}
