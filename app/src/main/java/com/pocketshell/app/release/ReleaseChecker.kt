package com.pocketshell.app.release

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLException

/**
 * Metadata about a GitHub Release that PocketShell can offer the user as
 * a download. Returned by [ReleaseChecker.checkForUpdate] when a newer release is
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
 * - [UpToDate] — the check succeeded and the installed build is current.
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
     * - The [checkForUpdate] API classifies failures so callers can log and
     *   surface a retry.
 *
 * The check picks the exact dotted-version debug APK asset, e.g.
 * `pocketshell-0.2.1-debug.apk`, so a tagged release points the browser
 * at the stable downloadable filename rather than a CI artifact name.
 */
open class ReleaseChecker(
    private val latestReleaseUrl: String = API_URL,
    /**
     * Backoff before the single auto-retry of a transient network/TLS blip
     * (issue #1456). Injectable so unit tests can drive the retry path with
     * zero wall-clock cost.
     */
    private val retryBackoffMs: Long = RETRY_BACKOFF_MS,
) {
    companion object {
        private const val REPO = "alexeygrigorev/pocketshell"
        private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private const val USER_AGENT = "pocketshell"
        private const val TIMEOUT_MS = 10_000
        private const val RETRY_BACKOFF_MS = 400L
        private const val TAG = "PsReleaseCheck"
    }

    /**
     * Query the GitHub Releases API and classify the outcome (issue
     * #515). It distinguishes three states:
     *
     *  - [ReleaseCheckResult.UpdateAvailable] when the remote tag is
     *    strictly newer than [currentVersion] and ships the dotted-APK
     *    asset;
     *  - [ReleaseCheckResult.UpToDate] when the install is current;
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
                // A returned non-200 (e.g. the GitHub 403 rate-limit) never throws
                // — it comes back as a [ReleaseCheckResult.Failed] here and is NOT
                // retried (issue #1456: don't hammer GitHub on a rate-limit).
                fetchRelease(currentVersion)
            } catch (e: Exception) {
                // Issue #1456: the raw exception chain (e.g. the cryptic conscrypt
                // `SocketException: NoSuchAlgorithmException ... DefaultSSLContextImpl`)
                // stays in logcat for diagnosis, but the user only ever sees the
                // classified human category — never a class name or stack.
                val failure = classifyFailure(e)
                Log.w(
                    TAG,
                    "release check failed: ${e.javaClass.simpleName}: ${e.message ?: "no message"} " +
                        "-> \"${failure.message}\" (transient=${failure.transient}, current=$currentVersion)",
                    e,
                )
                if (failure.transient) {
                    // The conscrypt SSLContext-construction failure and other
                    // transient network/TLS blips commonly succeed on a fresh
                    // connection. Retry ONCE before surfacing the banner; only a
                    // both-attempts-failed result nags the user (issue #515's
                    // visible-failure contract is preserved for a persistent fault).
                    delay(retryBackoffMs)
                    try {
                        fetchRelease(currentVersion)
                    } catch (retry: Exception) {
                        val retryFailure = classifyFailure(retry)
                        Log.w(
                            TAG,
                            "release check retry failed: ${retry.javaClass.simpleName}: " +
                                "${retry.message ?: "no message"} -> \"${retryFailure.message}\" " +
                                "(current=$currentVersion)",
                            retry,
                        )
                        ReleaseCheckResult.Failed(retryFailure.message)
                    }
                } else {
                    ReleaseCheckResult.Failed(failure.message)
                }
            }
        }

    /**
     * One network attempt against the GitHub Releases API. Returns the outcome
     * for the non-throwing cases (update / up-to-date / non-200 / unparseable
     * body) and lets a genuine network/TLS exception PROPAGATE so
     * [checkForUpdate] can classify + retry it (issue #1456).
     *
     * `internal open` so unit tests can subclass and inject a throw-then-succeed
     * sequence to exercise the auto-retry orchestration without real sockets.
     */
    internal open fun fetchRelease(currentVersion: String): ReleaseCheckResult {
        val conn = (URL(latestReleaseUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        // A single `disconnect()` in the finally releases the socket no
        // matter which branch runs (or throws). Without it the keep-alive
        // socket leaks — repeated cold-launch polls pile up half-open
        // connections. The input/error streams are additionally closed
        // (via `use`) so the body is drained; on a non-200 (the common
        // GitHub 403 rate-limit) an unread + unclosed `errorStream`
        // otherwise leaks the connection back-pressure too.
        return try {
            val code = conn.responseCode
            if (code != 200) {
                // GitHub returns 403 with a rate-limit body for unauthenticated
                // bursts — the single most likely cause of "the banner never
                // showed at cold launch". Drain + close the error stream so the
                // socket is reusable rather than leaked. The concrete code is
                // logged; the user sees a human category (issue #1456).
                conn.errorStream?.use { runCatching { it.readBytes() } }
                val userReason = if (code == HttpURLConnection.HTTP_FORBIDDEN) {
                    "rate-limited, try again later"
                } else {
                    "server error (HTTP $code)"
                }
                Log.w(TAG, "release check failed: GitHub returned HTTP $code (current=$currentVersion)")
                ReleaseCheckResult.Failed(userReason)
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                when (val outcome = parseReleaseOutcome(body, currentVersion)) {
                    is ParsedReleaseOutcome.UpdateAvailable ->
                        ReleaseCheckResult.UpdateAvailable(outcome.info)
                    ParsedReleaseOutcome.UpToDate ->
                        ReleaseCheckResult.UpToDate
                    is ParsedReleaseOutcome.Failed -> {
                        Log.w(TAG, "release check failed: ${outcome.reason} (current=$currentVersion)")
                        ReleaseCheckResult.Failed(outcome.reason)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Maps a caught network/TLS exception to a short, human-readable banner
     * category and whether it is worth an automatic retry (issue #1456). The
     * user NEVER sees the class name or `message`; the raw exception stays in
     * logcat. The whole cause chain is inspected because conscrypt surfaces its
     * default-`SSLContext` construction failure as a plain [SocketException]
     * *caused by* a `NoSuchAlgorithmException` mentioning `DefaultSSLContextImpl`.
     */
    internal fun classifyFailure(e: Throwable): FailureClassification {
        var cause: Throwable? = e
        val seen = HashSet<Throwable>()
        while (cause != null && seen.add(cause)) {
            when (cause) {
                // A connect/read timeout — a transient slow-network blip.
                is SocketTimeoutException ->
                    return FailureClassification("timed out", transient = true)
                // DNS/connectivity failure: retrying immediately won't help.
                is UnknownHostException ->
                    return FailureClassification("no network connection", transient = false)
                // TLS handshake / SSLContext construction faults (incl. the
                // conscrypt NoSuchAlgorithmException, a GeneralSecurityException)
                // are the classic transient case this fix heals.
                is SSLException, is GeneralSecurityException ->
                    return FailureClassification("connection problem", transient = true)
                // Covers ConnectException / the conscrypt-wrapped SocketException.
                is SocketException ->
                    return FailureClassification("connection problem", transient = true)
            }
            val message = cause.message ?: ""
            if (message.contains("DefaultSSLContextImpl") || message.contains("NoSuchAlgorithmException")) {
                return FailureClassification("connection problem", transient = true)
            }
            cause = cause.cause
        }
        // Anything unclassified: show a generic connection category and do NOT
        // retry blindly (avoid hammering on an unknown persistent fault).
        return FailureClassification("connection problem", transient = false)
    }

    /**
     * The user-facing banner category ([message]) plus whether [checkForUpdate]
     * should auto-retry once before surfacing it ([transient]). Issue #1456.
     */
    internal data class FailureClassification(
        val message: String,
        val transient: Boolean,
    )

    internal fun parseRelease(body: String, currentVersion: String): ReleaseInfo? =
        (parseReleaseOutcome(body, currentVersion) as? ParsedReleaseOutcome.UpdateAvailable)?.info

    private fun parseReleaseOutcome(body: String, currentVersion: String): ParsedReleaseOutcome {
        val json = JSONObject(body)
        val tagName = json.getString("tag_name")
        val remoteVersion = ParsedVersion.from(tagName)
            ?: return ParsedReleaseOutcome.Failed("Latest release tag is not parseable: $tagName")

        if (!isNewer(currentVersion, tagName)) return ParsedReleaseOutcome.UpToDate

        val htmlUrl = json.getString("html_url")
        val assets = json.optJSONArray("assets")
            ?: return ParsedReleaseOutcome.Failed("Release $tagName has no downloadable APK assets")
        val expectedApkName = "pocketshell-${remoteVersion.toDottedString()}-debug.apk"

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name") == expectedApkName) {
                return ParsedReleaseOutcome.UpdateAvailable(
                    ReleaseInfo(tagName, htmlUrl, asset.getString("browser_download_url")),
                )
            }
        }

        return ParsedReleaseOutcome.Failed("Release $tagName is missing $expectedApkName")
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

    private sealed interface ParsedReleaseOutcome {
        data class UpdateAvailable(val info: ReleaseInfo) : ParsedReleaseOutcome
        data object UpToDate : ParsedReleaseOutcome
        data class Failed(val reason: String) : ParsedReleaseOutcome
    }
}
