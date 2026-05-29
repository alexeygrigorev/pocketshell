package com.pocketshell.core.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An SSH private key registered with the app. Hosts reference this by id.
 *
 * Key material lives on disk at [privateKeyPath]; only metadata is stored in
 * the database. [fingerprint] is a content hash of the trimmed private-key
 * payload so import paths can reuse an existing row for byte-identical keys.
 */
@Entity(tableName = "ssh_keys")
data class SshKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val privateKeyPath: String,
    val fingerprint: String = "",
    val hasPassphrase: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)
