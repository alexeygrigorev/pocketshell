package com.pocketshell.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A configured SSH host. Owns connection metadata + auto-forward defaults.
 *
 * Extracted unchanged from `ssh-auto-forward-android`. The auto-forward
 * fields ([maxAutoPort], [skipPortsBelow], [scanIntervalSec]) live here for
 * now; if PocketShell ends up with a different port-forwarding model they
 * can migrate out, but keeping them avoids a schema split today.
 */
@Entity(
    tableName = "hosts",
    foreignKeys = [
        ForeignKey(
            entity = SshKeyEntity::class,
            parentColumns = ["id"],
            childColumns = ["keyId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("keyId")],
)
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val keyId: Long,
    val maxAutoPort: Int = 10000,
    val skipPortsBelow: Int = 1000,
    val scanIntervalSec: Int = 5,
    val enabled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long? = null,
)
