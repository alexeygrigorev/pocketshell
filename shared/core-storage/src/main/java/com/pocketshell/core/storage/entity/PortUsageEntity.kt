package com.pocketshell.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Historical per-(host, remote port) counters retained only as Room schema.
 *
 * The unused DAO was removed in issue #2432. Keeping this entity, its table,
 * foreign key, index, and migrations lets existing installs upgrade without a
 * destructive schema change; production has no reader or writer for the rows.
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
