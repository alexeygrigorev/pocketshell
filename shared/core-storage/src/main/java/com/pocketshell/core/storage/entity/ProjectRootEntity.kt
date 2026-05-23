package com.pocketshell.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-host project root shortcut used by the terminal navigation lane.
 */
@Entity(
    tableName = "project_roots",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("hostId"),
        Index(value = ["hostId", "path"], unique = true),
    ],
)
data class ProjectRootEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: Long,
    val label: String,
    val path: String,
    val createdAt: Long = System.currentTimeMillis(),
)
