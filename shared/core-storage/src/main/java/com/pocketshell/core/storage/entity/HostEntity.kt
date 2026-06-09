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
 * `pocketshell` CLI. Those pre-#386 hard cuts happened before Room-backed
 * data preservation became required for normal APK updates.
 *
 * Issue #294 removed the manual PATH override column. Bootstrap probes now
 * derive PATH from the remote user's shell rc and prepend PocketShell's
 * default user-bin locations automatically.
 *
 * Issue #315 adds [pocketshellCliVersion],
 * [pocketshellExpectedCliVersion], and [pocketshellVersionCompatible] so
 * a host with an installed but app-incompatible helper is not collapsed
 * into the generic "needs setup" state.
 *
 * Issue #328 adds [pocketshellDaemonRunning] and
 * [pocketshellDaemonEnabled] so optional jobs-daemon capability can be shown
 * separately from the required tmux + compatible CLI setup cache.
 *
 * Issue #627 adds [claudeProfilesJson] for per-host Claude Code profile
 * configuration. Each profile has a name and an optional config directory
 * path on the remote host that maps to `CLAUDE_CONFIG_DIR`. The JSON list
 * is parsed by `ClaudeProfile.fromJson()` in the app module. `null` means
 * "only the default profile" (no config dir override) — the common case
 * for hosts with a single Claude Code installation.
 *
 * Issue #631 adds [codexProfilesJson] for per-host Codex profile
 * configuration, mirroring [claudeProfilesJson]. Each Codex profile has a
 * name and an optional config directory path that maps to `CODEX_HOME`.
 * `null` means "only the default profile" — the common case for hosts
 * with a single Codex installation.
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
    val pocketshellDaemonRunning: Boolean? = null,
    val pocketshellDaemonEnabled: Boolean? = null,
    val usageCommandOverride: String? = null,
    /**
     * JSON-encoded list of Claude Code profiles (issue #627). Each entry
     * has `name` (display label) and `configDir` (remote path for
     * `CLAUDE_CONFIG_DIR`; empty/missing for the default profile).
     * `null` means "only the default profile exists" — no profile
     * selector is shown in the session type picker.
     */
    val claudeProfilesJson: String? = null,
    /**
     * JSON-encoded list of Codex profiles (issue #631). Each entry has
     * `name` (display label) and `configDir` (remote path for `CODEX_HOME`;
     * empty/missing for the default profile). `null` means "only the
     * default profile exists" — no profile selector is shown in the
     * session type picker.
     */
    val codexProfilesJson: String? = null,
)
