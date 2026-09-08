package com.pocketshell.core.storage.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.flow.Flow

/** Host labels shown before an SSH key deletion can cascade to those hosts. */
data class SshKeyHostReference(
    val keyId: Long,
    val hostName: String,
)

@Dao
interface SshKeyDao {
    @Query("SELECT * FROM ssh_keys ORDER BY name")
    fun getAll(): Flow<List<SshKeyEntity>>

    @Query("SELECT keyId, name AS hostName FROM hosts ORDER BY name")
    fun getHostReferences(): Flow<List<SshKeyHostReference>>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    suspend fun getById(id: Long): SshKeyEntity?

    @Query("SELECT * FROM ssh_keys WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): SshKeyEntity?

    @Query("SELECT * FROM ssh_keys WHERE fingerprint = :fingerprint ORDER BY id LIMIT 1")
    suspend fun getByFingerprint(fingerprint: String): SshKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(key: SshKeyEntity): Long

    @Delete
    suspend fun delete(key: SshKeyEntity)

    @Query("DELETE FROM ssh_keys WHERE id = :id")
    suspend fun deleteById(id: Long)
}
