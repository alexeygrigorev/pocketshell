package com.pocketshell.app.projects

import com.pocketshell.app.bootstrap.BootstrapTool
import com.pocketshell.app.bootstrap.uvToolInstallCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Issue #1492 (widens #779) — REPRODUCE-FIRST, class-covering (D31/D32 G1/G2/G6,
 * D33/G10) regression for the in-app host-updater silently no-opping whenever a
 * new `pocketshell` release bumps a pinned in-house sibling (`quse`, `tmuxctl`,
 * …) past the host's `uv` `exclude-newer` date cap.
 *
 * ## The real failure (hetzner, 2026-07-12)
 *
 * The host bakes `exclude-newer = "7 days"` (`~/.config/uv/uv.toml`, receipt cap
 * ≈ 2026-07-05). `pocketshell 0.4.27` pins `quse==0.0.9`, published AFTER that
 * cap. The old generated command
 * `uv tool install --upgrade --exclude-newer-package pocketshell=2099-12-31 pocketshell`
 * lifts the cap for `pocketshell` ONLY, so `quse==0.0.9` stays filtered out →
 * `uv` reports "No solution found … pocketshell==0.4.27 depends on quse==0.0.9"
 * and keeps the already-installed 0.4.24, exiting 0 (a silent no-op; the
 * version-mismatch banner never clears).
 *
 * The maintainer's manual fix that worked lifted the cap for the WHOLE
 * resolution. The fix here does that generally via a global `--exclude-newer`.
 *
 * ## What this test proves
 *
 * It runs a faithful model of `uv`'s exclude-newer resolver against the EXACT
 * failing fixture (a sibling pin `quse==0.0.9` uploaded after the host cap) and
 * asserts that EVERY host-update command PocketShell can emit (all four build
 * sites — [AgentLaunchVersionCheck], [PayloadVersionCheck], the bootstrap
 * [uvToolInstallCommand], and [HostPocketshellUpgrade]) resolves the host to the
 * target version. On the OLD per-package override this resolves to a no-op (the
 * host stays 0.4.24) → RED; with the global `--exclude-newer` cap lift it
 * resolves to 0.4.27 → GREEN.
 *
 * The resolver model is self-guarded ([modelReproducesTheBugForTheNarrowOverride])
 * so it is not a vacuous pass: the narrow override is shown to yield the no-op.
 */
class HostUpdateCommandExcludeNewerTest {

    // --- The fixture that reproduces the bug (non-happy host state, G10) ------

    /** The host's baked `exclude-newer` cutoff (uv.toml + tool receipt). */
    private val hostCap = "2026-07-05"

    /** The currently-installed (stale) host version the no-op leaves in place. */
    private val installedVersion = "0.4.24"

    /** The release we want the host to land on. */
    private val targetVersion = "0.4.27"

    /**
     * PyPI upload dates for the packages in the target's resolution. `quse==0.0.9`
     * is the in-house sibling pinned by `pocketshell 0.4.27`, published AFTER the
     * host cap — this is the exact state that makes the narrow override no-op.
     */
    private val uploadDates = mapOf(
        "pocketshell:$targetVersion" to "2026-07-11",
        "quse:0.0.9" to "2026-07-08", // > hostCap 2026-07-05 → filtered unless the cap is lifted for quse
    )

    /** `pocketshell 0.4.27` pins exactly `quse==0.0.9`. */
    private val targetDependencies = mapOf("quse" to "0.0.9")

    // --- The four command build sites (class coverage, G2) --------------------

    private fun allEmittedUpdateCommands(): Map<String, String> = mapOf(
        "AgentLaunchVersionCheck.UPDATE_COMMAND" to AgentLaunchVersionCheck.UPDATE_COMMAND,
        "PayloadVersionCheck.UPDATE_COMMAND" to PayloadVersionCheck.UPDATE_COMMAND,
        "HostBootstrapper.uvToolInstallCommand(upgrade)" to
            uvToolInstallCommand(BootstrapTool.Pocketshell, upgrade = true),
        "HostBootstrapper.uvToolInstallCommand(install)" to
            uvToolInstallCommand(BootstrapTool.Pocketshell, upgrade = false),
        "HostPocketshellUpgrade.UPGRADE_COMMAND (uv arm)" to HostPocketshellUpgrade.UPGRADE_COMMAND,
    )

    // --- Load-bearing assertion (GREEN only with the whole-resolution fix) ----

    @Test
    fun everyHostUpdateCommand_updatesTheHost_whenASiblingPinIsPastTheHostCap() {
        for ((site, command) in allEmittedUpdateCommands()) {
            val resolved = resolveInstalledVersionAfter(command)
            assertEquals(
                "$site must lift the exclude-newer cap for the WHOLE resolution so a " +
                    "release pinning quse==0.0.9 (past the host cap) still updates the host — " +
                    "instead it resolved to a silent no-op (host stayed $installedVersion). " +
                    "Command: $command",
                targetVersion,
                resolved,
            )
        }
    }

    @Test
    fun noHostUpdateCommand_usesTheNarrowPerPackageOverride() {
        // The narrow `--exclude-newer-package pocketshell=…` override is the bug.
        // Guard the whole class so a future edit can't reintroduce it at any site.
        for ((site, command) in allEmittedUpdateCommands()) {
            assertFalse(
                "$site must NOT use the narrow per-package override that broke on " +
                    "sibling pins (#1492) — command: $command",
                command.contains("--exclude-newer-package"),
            )
        }
    }

    // --- Self-guard: the model actually reproduces the bug (not vacuous, G3) ---

    @Test
    fun modelReproducesTheBugForTheNarrowOverride() {
        // The OLD command shape resolves to a no-op (host stuck at the stale
        // version) — proving the resolver model is faithful and the GREEN
        // assertion above is load-bearing, not vacuous.
        val narrow =
            "uv tool install --upgrade --exclude-newer-package pocketshell=2099-12-31 pocketshell"
        assertEquals(
            "the narrow per-package override must reproduce the silent no-op",
            installedVersion,
            resolveInstalledVersionAfter(narrow),
        )

        // And the whole-resolution override resolves to the target — the fix.
        val global = "uv tool install --upgrade --exclude-newer 2099-12-31 pocketshell"
        assertEquals(targetVersion, resolveInstalledVersionAfter(global))
    }

    // --- The uv exclude-newer resolver model ----------------------------------

    /**
     * Model `uv tool install`'s outcome under the host's `exclude-newer` cap.
     * Returns the version the host ends up on after running [command]:
     *  - [targetVersion] when the whole target resolution is satisfiable, or
     *  - [installedVersion] when any pinned dependency is filtered by the cap
     *    (unsatisfiable → uv keeps the installed version and exits 0: the no-op).
     */
    private fun resolveInstalledVersionAfter(command: String): String {
        val spec = parseExcludeNewer(command)
        // Effective cap for a package: per-package CLI override > global CLI
        // override > the host's baked uv.toml cutoff.
        fun capFor(pkg: String): String = spec.perPackage[pkg] ?: spec.global ?: hostCap

        // pocketshell itself must be a candidate (uploaded on/before its cap).
        val pocketshellUpload = uploadDates.getValue("pocketshell:$targetVersion")
        if (pocketshellUpload > capFor("pocketshell")) return installedVersion

        // Every pinned dependency must be a candidate too, else the resolution is
        // unsatisfiable and uv keeps the installed version (the reported no-op).
        for ((dep, ver) in targetDependencies) {
            val depUpload = uploadDates.getValue("$dep:$ver")
            if (depUpload > capFor(dep)) return installedVersion
        }
        return targetVersion
    }

    private data class ExcludeNewerSpec(
        val global: String?,
        val perPackage: Map<String, String>,
    )

    /** Extract global `--exclude-newer <date>` and `--exclude-newer-package P=<date>` flags. */
    private fun parseExcludeNewer(command: String): ExcludeNewerSpec {
        val tokens = command.split(Regex("\\s+")).filter { it.isNotBlank() }
        var global: String? = null
        val perPackage = mutableMapOf<String, String>()
        var i = 0
        while (i < tokens.size) {
            when (tokens[i]) {
                "--exclude-newer" -> {
                    global = tokens.getOrNull(i + 1)
                    i += 2
                }
                "--exclude-newer-package" -> {
                    val spec = tokens.getOrNull(i + 1)
                    if (spec != null && spec.contains('=')) {
                        val parts = spec.split('=', limit = 2)
                        perPackage[parts[0]] = parts[1]
                    }
                    i += 2
                }
                else -> i++
            }
        }
        return ExcludeNewerSpec(global, perPackage)
    }
}
