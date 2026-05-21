package com.pocketshell.core.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An SSH private key registered with the app. Hosts reference this by id.
 *
 * Extracted unchanged from `ssh-auto-forward-android` (see project D2 in
 * docs/decisions.md). Key material lives on disk at [privateKeyPath]; only
 * metadata is stored in the database.
 */
@Entity(tableName = "ssh_keys")
data class SshKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val privateKeyPath: String,
    val hasPassphrase: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)
