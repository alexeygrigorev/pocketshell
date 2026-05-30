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
 * Issue #117 (usage-panel Fix C) added the usage-tool cache columns +
 * the optional per-host command override. The same bootstrap probe that
 * fills [tmuxInstalled] also reports whether the unified
 * [pocketshell](https://github.com/alexeygrigorev/pocketshell) CLI is
 * present on the host. The detected result is cached in
 * [pocketshellInstalled] / [pocketshellLastDetectedAt] so the periodic
 * usage scheduler can skip hosts that don't have pocketshell without
 * re-probing on every poll. `null` means "never probed", `true` / `false`
 * mean "verified at [pocketshellLastDetectedAt]". [usageCommandOverride]
 * is an optional per-host override for the usage command (e.g. a corporate
 * wrapper that emits the same JSON shape) — `null` falls back to the
 * default `pocketshell usage --json`.
 *
 * Issue #231 (parity swap, #170 follow-up) renamed these detection columns
 * from the legacy `quse*` naming to `pocketshell*` as the Android side cut
 * over from the separate `quse` / `tmuxctl` utilities to the unified
 * `pocketshell` CLI. Per D22 this is a hard cut: there is no legacy column,
 * no compatibility shim, and no Room migration — the destructive rebuild in
 * `StorageModule` (`fallbackToDestructiveMigration(dropAllTables = true)`)
 * is sufficient.
 *
 * Issue #294 removed the manual PATH override column. Bootstrap probes now
 * derive PATH from the remote user's shell rc and prepend PocketShell's
 * default user-bin locations automatically. Per D22 this is a hard cut;
 * the destructive rebuild handles the schema change.
 *
 * Issue #315 adds [pocketshellCliVersion],
 * [pocketshellExpectedCliVersion], and [pocketshellVersionCompatible] so
 * a host with an installed but app-incompatible helper is not collapsed
 * into the generic "needs setup" state.
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
    val pocketshellInstalled: Boolean? = null,
    val pocketshellLastDetectedAt: Long? = null,
    val pocketshellCliVersion: String? = null,
    val pocketshellExpectedCliVersion: String? = null,
    val pocketshellVersionCompatible: Boolean? = null,
    val usageCommandOverride: String? = null,
)
