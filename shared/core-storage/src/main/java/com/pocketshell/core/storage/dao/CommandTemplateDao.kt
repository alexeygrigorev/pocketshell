package com.pocketshell.core.storage.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pocketshell.core.storage.entity.CommandTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandTemplateDao {
    @Query("SELECT * FROM command_templates WHERE hostId = :hostId ORDER BY label COLLATE NOCASE")
    fun getByHostId(hostId: Long): Flow<List<CommandTemplateEntity>>

    @Query("SELECT * FROM command_templates WHERE id = :id")
    suspend fun getById(id: Long): CommandTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: CommandTemplateEntity): Long

    @Update
    suspend fun update(template: CommandTemplateEntity)

    @Delete
    suspend fun delete(template: CommandTemplateEntity)

    @Query("DELETE FROM command_templates WHERE hostId = :hostId")
    suspend fun deleteByHostId(hostId: Long)
}
