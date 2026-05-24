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
 *
 * Issue #49 added [tmuxInstalled] and [lastBootstrapAt]: the host-bootstrap
 * flow probes for `tmux` on first connect and caches the outcome here so we
 * only re-check after 24h or on explicit user trigger. Both columns are
 * nullable; `null` means "never checked", `true` / `false` mean
 * "verified at [lastBootstrapAt]". Stored as epoch-millis `Long?` to match
 * the existing `createdAt` / `lastConnectedAt` convention — the issue body
 * mentioned `Instant?` but the codebase has no `kotlinx-datetime` dependency
 * and the brief forbids adding new catalog entries.
 *
 * Issue #117 (usage-panel Fix C) added [heruInstalled],
 * [heruLastDetectedAt], and [usageCommandOverride]: the same bootstrap
 * probe that fills [tmuxInstalled] also reports whether `heru` (the
 * usage CLI) is present on the host. The detected result is cached the
 * same way so the periodic usage scheduler can skip hosts that don't
 * have heru without re-probing on every poll. [usageCommandOverride] is
 * an optional per-host override for the usage command (e.g. a corporate
 * wrapper that emits the same JSON shape) — `null` falls back to
 * `heru usage --json`.
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
    val tmuxInstalled: Boolean? = null,
    val lastBootstrapAt: Long? = null,
    val heruInstalled: Boolean? = null,
    val heruLastDetectedAt: Long? = null,
    val usageCommandOverride: String? = null,
)
