package com.pocketshell.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-host user-authored command sequence template.
 *
 * [commands] stores one shell submission per line. The app layer expands
 * `{{placeholder}}` values and dispatches each non-empty line as an Enter
 * separated command sequence.
 */
@Entity(
    tableName = "command_templates",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("hostId")],
)
data class CommandTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: Long,
    val label: String,
    val commands: String,
)
