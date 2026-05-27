package com.pocketshell.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-host snippet library (one-tap "send command" or "send prompt"
 * shortcuts surfaced from the quick-send panel).
 *
 * - [hostId] references [HostEntity.id]. ON DELETE CASCADE.
 * - [label] is the optional user-facing name shown in the panel. When
 *   `null`, the UI derives a label from the first line of [body] truncated
 *   to ~40 chars (see issue #190). Pre-#190 rows had a non-null label
 *   filled in at creation; those rows survive the v7 -> v8 migration with
 *   their explicit label preserved, while new snippets default to `null`
 *   and let the UI render the derived label so the user is not asked to
 *   type the same content twice.
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
    val label: String?,
    val body: String,
    val kind: String,
)
