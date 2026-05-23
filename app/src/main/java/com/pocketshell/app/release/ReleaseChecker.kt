package com.pocketshell.app.release

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Metadata about a GitHub Release that PocketShell can offer the user as
 * a download. Returned by [ReleaseChecker.check] when a newer release is
 * available than the currently installed [versionName][android.content.pm.PackageInfo.versionName].
 *
 * - [tagName] is the upstream tag (e.g. `v0.2.0`) — shown verbatim in
 *   the update banner.
 * - [htmlUrl] is the GitHub web page for the release — useful as a
 *   secondary "release notes" link.
 * - [apkUrl] is the direct download URL for the `-debug.apk` asset; the
 *   app fires `Intent.ACTION_VIEW` against this so the system browser
 *   (or download manager) handles the actual download. We deliberately
 *   do NOT trigger an in-process silent install — sideloading is the
 *   user's choice.
 */
data class ReleaseInfo(
    val tagName: String,
    val htmlUrl: String,
    val apkUrl: String,
)

/**
 * Hits the GitHub Releases API for the public PocketShell repo and
 * returns a [ReleaseInfo] when a newer build than [currentVersion] is
 * available. Adapted from the canonical pattern in
 * `ssh-auto-forward-android` (`com.sshautoforward.util.ReleaseChecker`).
 *
 * Implementation choices:
 *
 * - `java.net.HttpURLConnection` + `org.json` — both Android-platform
 *   bundled. We deliberately avoid bringing OkHttp / Moshi into the app
 *   module purely for this one-shot API call (see issue #40's
 *   "no new libs.versions.toml entries").
 * - All network IO runs on [Dispatchers.IO]; the public surface is a
 *   single `suspend` function so the caller (a `ViewModel`) can launch
 *   it within `viewModelScope`.
 * - Any failure (network down, 404 for a fresh repo with no releases,
 *   JSON shape unexpected) silently returns `null`. The update banner
 *   is a courtesy; we never want a transient network blip to surface
 *   an error to the user.
 *
 * The check picks the exact dotted-version debug APK asset, e.g.
 * `pocketshell-0.2.1-debug.apk`, so a tagged release points the browser
 * at the stable downloadable filename rather than a CI artifact name.
 */
open class ReleaseChecker(
    private val latestReleaseUrl: String = API_URL,
) {
    companion object {
        private const val REPO = "alexeygrigorev/pocketshell"
        private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private const val USER_AGENT = "pocketshell"
        private const val TIMEOUT_MS = 10_000
    }

    /**
     * Query the GitHub Releases API. Returns the asset metadata only
     * when the remote tag is strictly newer than [currentVersion]; if
     * the remote tag is equal or older, returns `null`.
     *
     * Marked `open` so unit tests can subclass and short-circuit the
     * network call — see `HostListViewModelTest.FakeReleaseChecker`.
     */
    open suspend fun check(currentVersion: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(latestReleaseUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", USER_AGENT)
            }

            if (conn.responseCode != 200) return@withContext null

            parseRelease(conn.inputStream.bufferedReader().readText(), currentVersion)
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseRelease(body: String, currentVersion: String): ReleaseInfo? {
        val json = JSONObject(body)
        val tagName = json.getString("tag_name")
        val remoteVersion = ParsedVersion.from(tagName) ?: return null

        if (!isNewer(currentVersion, tagName)) return null

        val htmlUrl = json.getString("html_url")
        val assets = json.optJSONArray("assets") ?: return null
        val expectedApkName = "pocketshell-${remoteVersion.toDottedString()}-debug.apk"

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name") == expectedApkName) {
                return ReleaseInfo(tagName, htmlUrl, asset.getString("browser_download_url"))
            }
        }

        return null
    }

    /**
     * Semver-ish comparison. Accepts an optional leading `v`, optional
     * missing minor/patch components (treated as zero), and trailing
     * pre-release/build qualifiers (e.g. `0.1.0-debug`), then compares
     * major/minor/patch as numbers.
     *
     * Visible (rather than `private`) so the unit tests can pin the
     * comparison semantics — see issue #40's acceptance criteria.
     */
    internal fun isNewer(current: String, remote: String): Boolean {
        val currentVersion = ParsedVersion.from(current) ?: return false
        val remoteVersion = ParsedVersion.from(remote) ?: return false
        return remoteVersion.compareTo(currentVersion) > 0
    }

    internal fun renderDottedVersionLabel(versionName: String): String {
        val parsed = ParsedVersion.from(versionName)
        return "v${parsed?.toDottedString() ?: versionName.trim().removePrefix("v")}"
    }

    private data class ParsedVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
    ) : Comparable<ParsedVersion> {
        override fun compareTo(other: ParsedVersion): Int =
            compareValuesBy(this, other, ParsedVersion::major, ParsedVersion::minor, ParsedVersion::patch)

        fun toDottedString(): String = "$major.$minor.$patch"

        companion object {
            private val VERSION_PATTERN = Regex("""^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:[-+].*)?$""")

            fun from(raw: String): ParsedVersion? {
                val match = VERSION_PATTERN.matchEntire(raw.trim()) ?: return null
                return ParsedVersion(
                    major = match.groupValues[1].toIntOrNull() ?: return null,
                    minor = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0,
                    patch = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0,
                )
            }
        }
    }
}
