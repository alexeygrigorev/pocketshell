package com.pocketshell.app.release

import android.util.Log
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
 * Outcome of a single GitHub-Releases poll. Issue #515: the old API
 * collapsed "no newer release" and "the check itself failed" into the
 * same `null`, so a cold-launch network blip / GitHub rate-limit produced
 * exactly the same silent no-banner as a genuinely up-to-date install.
 * Splitting them lets the caller log + surface a failure (with a retry)
 * while staying silent when the app really is current.
 *
 * - [UpdateAvailable] — a strictly-newer release with a downloadable APK
 *   asset was found.
 * - [UpToDate] — the check succeeded and the installed build is current
 *   (or the newer tag had no matching dotted-APK asset). No banner.
 * - [Failed] — the check could not complete (non-200, rate-limit,
 *   network error, unparseable body). Carries a human-readable [reason]
 *   for logging and an optional "tap to retry" affordance. NOT a silent
 *   null.
 */
sealed interface ReleaseCheckResult {
    data class UpdateAvailable(val info: ReleaseInfo) : ReleaseCheckResult
    data object UpToDate : ReleaseCheckResult
    data class Failed(val reason: String) : ReleaseCheckResult

    /** The [ReleaseInfo] when an update is available, else `null`. */
    fun infoOrNull(): ReleaseInfo? = (this as? UpdateAvailable)?.info
}

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
        private const val TAG = "PsReleaseCheck"
    }

    /**
     * Query the GitHub Releases API and classify the outcome (issue
     * #515). Unlike the legacy [check], this distinguishes three states:
     *
     *  - [ReleaseCheckResult.UpdateAvailable] when the remote tag is
     *    strictly newer than [currentVersion] and ships the dotted-APK
     *    asset;
     *  - [ReleaseCheckResult.UpToDate] when the install is current (or
     *    the newer tag has no matching APK);
     *  - [ReleaseCheckResult.Failed] when the request itself failed
     *    (non-200 / rate-limit / network error / unparseable body).
     *
     * Every failure is logged with its concrete reason via [Log] so the
     * "no banner appeared" case is diagnosable in logcat instead of
     * vanishing into a silent `null`.
     *
     * Marked `open` so unit tests can subclass and short-circuit the
     * network call — see `HostListViewModelTest.FakeReleaseChecker`.
     */
    open suspend fun checkForUpdate(currentVersion: String): ReleaseCheckResult =
        withContext(Dispatchers.IO) {
            try {
                val conn = (URL(latestReleaseUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", USER_AGENT)
                }

                val code = conn.responseCode
                if (code != 200) {
                    // GitHub returns 403 with a rate-limit body for
                    // unauthenticated bursts — the single most likely cause
                    // of "the banner never showed at cold launch". Naming the
                    // code makes that diagnosable.
                    val reason = "GitHub returned HTTP $code"
                    Log.w(TAG, "release check failed: $reason (current=$currentVersion)")
                    return@withContext ReleaseCheckResult.Failed(reason)
                }

                val body = conn.inputStream.bufferedReader().readText()
                val info = parseRelease(body, currentVersion)
                if (info != null) {
                    ReleaseCheckResult.UpdateAvailable(info)
                } else {
                    ReleaseCheckResult.UpToDate
                }
            } catch (e: Exception) {
                val reason = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                Log.w(TAG, "release check failed: $reason (current=$currentVersion)", e)
                ReleaseCheckResult.Failed(reason)
            }
        }

    /**
     * Legacy convenience wrapper that collapses the [checkForUpdate]
     * outcome back to "the [ReleaseInfo] or `null`". Kept for callers
     * (e.g. the update notifier's version labeler) that only care about
     * the available-release case. New callers that need to surface a
     * failed check should use [checkForUpdate] directly.
     */
    suspend fun check(currentVersion: String): ReleaseInfo? =
        checkForUpdate(currentVersion).infoOrNull()

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
