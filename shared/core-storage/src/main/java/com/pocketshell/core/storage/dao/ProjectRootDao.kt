package com.pocketshell.core.storage.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pocketshell.core.storage.entity.ProjectRootEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectRootDao {
    @Query("SELECT * FROM project_roots WHERE hostId = :hostId ORDER BY label, path")
    fun getByHostId(hostId: Long): Flow<List<ProjectRootEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(root: ProjectRootEntity): Long

    @Update
    suspend fun update(root: ProjectRootEntity)

    @Delete
    suspend fun delete(root: ProjectRootEntity)
}
