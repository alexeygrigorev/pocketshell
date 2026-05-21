package com.pocketshell.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stub: cached tmux session metadata for the dashboard's "all sessions"
 * sort-by-recency view. Populated in a later Phase 1 / Phase 2 issue once
 * the tmux control-mode client lands; today this entity only exists so the
 * initial schema is forward-compatible.
 *
 * - [hostId] references [HostEntity.id]. ON DELETE CASCADE so removing a
 *   host drops its session cache.
 * - [name] is the tmux session name as reported by `tmux ls`.
 * - [lastSeenAt] is the most recent moment PocketShell observed the session
 *   on this host (epoch millis).
 * - [tags] is a free-form comma-separated label list — denormalised on
 *   purpose because v1 has no tag-management UI yet. A future migration can
 *   normalise into a join table if needed.
 *
 * See docs/architecture.md and docs/decisions.md (D5/D6) for context.
 */
@Entity(
    tableName = "sessions",
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
        Index(value = ["hostId", "name"], unique = true),
    ],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: Long,
    val name: String,
    val lastSeenAt: Long = 0L,
    val tags: String? = null,
)
