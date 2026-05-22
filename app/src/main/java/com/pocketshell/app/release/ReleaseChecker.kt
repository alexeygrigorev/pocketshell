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
 * The check picks the asset whose filename ends with `-debug.apk` —
 * that's the only artifact #39 publishes (release builds are not signed
 * for distribution yet).
 */
open class ReleaseChecker {
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
            val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", USER_AGENT)
            }

            if (conn.responseCode != 200) return@withContext null

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val tagName = json.getString("tag_name")

            if (!isNewer(currentVersion, tagName)) return@withContext null

            val htmlUrl = json.getString("html_url")
            val assets = json.optJSONArray("assets") ?: return@withContext null

            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith("-debug.apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }

            if (apkUrl.isEmpty()) return@withContext null

            ReleaseInfo(tagName, htmlUrl, apkUrl)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Semver-ish comparison. Strips any leading `v` prefix and a
     * trailing `-<qualifier>` (e.g. `0.1.0-debug`) from the local
     * version, then compares each dot-separated number in turn.
     *
     * Visible (rather than `private`) so the unit tests can pin the
     * comparison semantics — see issue #40's acceptance criteria.
     */
    internal fun isNewer(current: String, remote: String): Boolean {
        val currentTag = current.trim().substringBefore("-").removePrefix("v")
        val remoteTag = remote.trim().substringBefore("-").removePrefix("v")
        val currentNums = currentTag.split(".").mapNotNull { it.toIntOrNull() }
        val remoteNums = remoteTag.split(".").mapNotNull { it.toIntOrNull() }

        if (currentNums.isEmpty() || remoteNums.isEmpty()) return false

        for (i in 0 until maxOf(currentNums.size, remoteNums.size)) {
            val c = currentNums.getOrElse(i) { 0 }
            val r = remoteNums.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
