package com.pocketshell.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * User-defined remote→local port remapping for a host.
 *
 * Extracted unchanged from `ssh-auto-forward-android`. The unique index on
 * `(hostId, remotePort)` enforces "one local port per remote port per host".
 */
@Entity(
    tableName = "port_remappings",
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
        Index(value = ["hostId", "remotePort"], unique = true),
    ],
)
data class PortRemappingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: Long,
    val remotePort: Int,
    val localPort: Int,
)
