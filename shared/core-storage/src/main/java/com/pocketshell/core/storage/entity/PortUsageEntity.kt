package com.pocketshell.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Per-(host, remote port) usage counters for the auto-forward panel
 * (issue #203).
 *
 * Ported unchanged from `ssh-auto-forward-android` (`data/db/entity/
 * PortUsageEntity.kt`). The composite primary key on
 * `(hostId, remotePort)` is intentional — there is exactly one row per
 * (host, port) pair, so [PortUsageDao.insertIfMissing] is idempotent
 * and the `incrementClick` / `addBytes` update verbs can be safe upserts
 * via "INSERT OR IGNORE" semantics on the DAO.
 *
 * Two counters live here:
 *
 * - [clickCount] — how many times the user has tapped the "open in
 *   browser" affordance for this tunnel. Drives the "frequent ports"
 *   indicator on the panel (a star next to ports the user keeps coming
 *   back to).
 * - [totalBytes] — accumulated bytes-in + bytes-out across every
 *   forward instance for this remote port. Per-tunnel byte counts are
 *   ephemeral (they reset every time the SshSession reconnects); this
 *   column is what lets the panel report "12 MB total since you added
 *   this port" across reconnects.
 *
 * The `lastUsedAt` epoch-millis column is updated by both verbs so a
 * future "recently used" sort key has a single column to range-scan.
 *
 * The FK ON DELETE CASCADE matches the rest of the host-scoped tables
 * ([HostEntity], [PortRemappingEntity], [SnippetEntity], ...): deleting
 * a host wipes its usage history without leaving orphaned rows. The
 * `hostId` index supports the `getByHostId` panel query.
 */
@Entity(
    tableName = "port_usage",
    primaryKeys = ["hostId", "remotePort"],
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
data class PortUsageEntity(
    val hostId: Long,
    val remotePort: Int,
    val clickCount: Int = 0,
    val totalBytes: Long = 0,
    val lastUsedAt: Long = 0,
)
