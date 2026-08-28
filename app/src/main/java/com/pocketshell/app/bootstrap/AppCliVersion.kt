package com.pocketshell.app.bootstrap

/**
 * The RELEASE CORE (`MAJOR.MINOR.PATCH`, any number of dotted numeric
 * components) of a version string, with any semver pre-release qualifier
 * (`-…`) and any build metadata (`+…`) stripped.
 *
 * ## Why this exists (issue #2381)
 *
 * Until issue #2356 the app's `versionName` was a hand-maintained
 * `versionName = "0.4.44"` literal, so "the pocketshell CLI version this app
 * build expects on the host" was trivially a clean dotted release version:
 * exactly the `tools/pocketshell` release published in lockstep with the APK.
 * Every consumer — [compareSemver], the `pocketshell==<version>` install pin in
 * `HostPocketshellUpgrade` — silently relied on that shape.
 *
 * #2356 replaced the literal with `scripts/derive-version.sh`, which produces
 * four shapes:
 *
 * | build                       | derived `versionName`      |
 * |-----------------------------|----------------------------|
 * | exact release tag           | `0.4.45`                   |
 * | commit after a tag (dev/CI) | `0.4.45-4-g9b1d784e`       |
 * | tagless checkout (CI)       | `0.0.0-dev+525c87a`        |
 * | no git at all               | `0.0.0-dev`                |
 *
 * Three of the four are NOT dotted-numeric, and the app compared them against
 * the host's reported CLI version with a comparator that only understood the
 * first shape. The consequences were all user-visible:
 *
 *  - A correctly set-up host reported `VersionMismatch`, so the bootstrap
 *    "Host setup needed" sheet took over and never cleared — the app could not
 *    navigate past setup at all. This is what collapsed the nightly fault
 *    gate's bootstrap phase on 2026-08-28 (8 of 10
 *    `HostBootstrapScenarioSuiteTest` methods), where the CI APK reported
 *    `0.0.0-dev+525c87a` while `pocketshell --version` on the fixture reported
 *    the very same string: the app's own `VERSION_PATTERN` truncated the
 *    host's answer at the `+` (to `0.0.0-dev`) and then declared the two
 *    unequal.
 *  - The remote-CLI-is-newer path (#514) degraded into `VersionMismatch` too,
 *    so a genuinely newer host CLI raised a takeover setup sheet instead of the
 *    soft, dismissible "consider updating the app" banner.
 *  - `HostPocketshellUpgrade` pinned `pocketshell==0.4.45-4-g9b1d784e`, a
 *    version that exists on no index, so the offered fix could never succeed.
 *
 * The release core is the right expectation in every case: a build made N
 * commits after `v0.4.45` still ships against the `0.4.45` CLI, and semver
 * §10 says build metadata never participates in precedence.
 *
 * Returns `null` when [raw] has no parseable dotted-numeric core (callers keep
 * their existing conservative fallback rather than guessing).
 */
internal fun releaseVersionCore(raw: String): String? =
    releaseVersionCoreComponents(raw)?.joinToString(".")

/** [releaseVersionCore] as numeric components, for ordering comparisons. */
internal fun releaseVersionCoreComponents(raw: String): List<Int>? {
    val trimmed = raw.trim().removePrefix("v")
    if (trimmed.isEmpty()) return null
    // Semver §10: build metadata (`+…`) is not part of precedence. Strip it
    // first so `0.0.0-dev+525c87a` and `0.0.0-dev` are the same release.
    val withoutBuild = trimmed.substringBefore('+')
    // Then the pre-release / git-describe qualifier: `0.4.45-4-g9b1d784e` is
    // "4 commits after v0.4.45", i.e. still the 0.4.45 release line.
    val core = withoutBuild.substringBefore('-')
    if (core.isEmpty()) return null
    val parts = core.split('.')
    val components = ArrayList<Int>(parts.size)
    for (part in parts) {
        if (part.isEmpty()) return null
        val value = part.toIntOrNull() ?: return null
        if (value < 0) return null
        components += value
    }
    return components
}

/**
 * The version this app build expects the host `pocketshell` CLI to be at, from
 * the installed APK's [versionName]. Empty when it cannot be resolved (callers
 * then skip the version check entirely, exactly as before #2356).
 */
internal fun expectedHostCliVersion(versionName: String?): String =
    versionName?.let { releaseVersionCore(it) }.orEmpty()
