package com.pocketshell.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stub: per-host snippet library (one-tap "send command" or "send prompt"
 * shortcuts surfaced from the quick-send panel). Populated in a later issue
 * when the input-methods work lands (see docs/input-methods.md). The entity
 * exists today so the schema doesn't need a v2 migration when that lands.
 *
 * - [hostId] references [HostEntity.id]. ON DELETE CASCADE.
 * - [label] is the user-facing name shown in the panel.
 * - [body] is the literal text sent over tmux when the snippet fires.
 * - [kind] discriminates command (`"command"`) from agent prompt
 *   (`"prompt"`). Stored as String to keep schema migrations open-ended;
 *   the parser layer above maps to a Kotlin enum.
 */
@Entity(
    tableName = "snippets",
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
data class SnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: Long,
    val label: String,
    val body: String,
    val kind: String,
)
