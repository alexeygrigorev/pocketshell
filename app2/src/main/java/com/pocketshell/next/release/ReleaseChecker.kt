package com.pocketshell.next.release

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * Metadata about a GitHub Release PocketShell can offer as a download.
 *
 * [publishedDateLabel] is a local calendar date only (`d MMM yyyy`) — never a
 * clock time. Empty when GitHub omitted `published_at` or it could not be
 * parsed; the rest of the offer still stands.
 */
data class ReleaseInfo(
    val tagName: String,
    val htmlUrl: String,
    val apkUrl: String,
    val publishedDateLabel: String = "",
)

/**
 * Outcome of one GitHub-Releases poll (issue #515).
 *
 * "No newer release" and "the check itself failed" must never collapse into
 * the same silence: a rate-limit or network blip is not "up to date".
 */
sealed interface ReleaseCheckResult {
    data class UpdateAvailable(val info: ReleaseInfo) : ReleaseCheckResult
    data object UpToDate : ReleaseCheckResult
    data class Failed(val reason: String) : ReleaseCheckResult

    fun infoOrNull(): ReleaseInfo? = (this as? UpdateAvailable)?.info
}

/** One GET against the latest-release URL. Throws on transport/TLS failure. */
fun interface ReleaseHttpClient {
    fun get(url: String): ReleaseHttpResponse
}

data class ReleaseHttpResponse(val code: Int, val body: String)

/**
 * Hits the GitHub Releases API and classifies the outcome against the
 * installed [android.content.pm.PackageInfo.versionName].
 *
 * `HttpURLConnection` + `org.json` — no OkHttp for this one-shot call. The
 * [http] client is injectable so JVM tests never touch live GitHub.
 *
 * One auto-retry on a transient TLS/network blip; a GitHub 403 (rate-limit)
 * is a returned [ReleaseCheckResult.Failed] and is NOT retried (#1456).
 */
open class ReleaseChecker(
    private val latestReleaseUrl: String = API_URL,
    private val retryBackoffMs: Long = RETRY_BACKOFF_MS,
    private val http: ReleaseHttpClient = HttpUrlConnectionReleaseClient(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    companion object {
        private const val REPO = "alexeygrigorev/pocketshell"
        internal const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private const val USER_AGENT = "pocketshell"
        private const val TIMEOUT_MS = 10_000
        private const val RETRY_BACKOFF_MS = 400L
        private const val TAG = "PsReleaseCheck"
    }

    open suspend fun checkForUpdate(currentVersion: String): ReleaseCheckResult =
        withContext(Dispatchers.IO) {
            try {
                fetchRelease(currentVersion)
            } catch (e: Exception) {
                val failure = classifyFailure(e)
                Log.w(
                    TAG,
                    "release check failed: ${e.javaClass.simpleName}: ${e.message ?: "no message"} " +
                        "-> \"${failure.message}\" (transient=${failure.transient}, current=$currentVersion)",
                    e,
                )
                if (failure.transient) {
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

    internal fun fetchRelease(currentVersion: String): ReleaseCheckResult {
        val response = http.get(latestReleaseUrl)
        if (response.code != 200) {
            val userReason = if (response.code == HttpURLConnection.HTTP_FORBIDDEN) {
                "rate-limited, try again later"
            } else {
                "server error (HTTP ${response.code})"
            }
            Log.w(TAG, "release check failed: GitHub returned HTTP ${response.code} (current=$currentVersion)")
            return ReleaseCheckResult.Failed(userReason)
        }
        return when (val outcome = parseReleaseOutcome(response.body, currentVersion)) {
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

    internal fun classifyFailure(e: Throwable): FailureClassification {
        var cause: Throwable? = e
        val seen = HashSet<Throwable>()
        while (cause != null && seen.add(cause)) {
            when (cause) {
                is SocketTimeoutException ->
                    return FailureClassification("timed out", transient = true)
                is UnknownHostException ->
                    return FailureClassification("no network connection", transient = false)
                is SSLException, is GeneralSecurityException ->
                    return FailureClassification("connection problem", transient = true)
                is SocketException ->
                    return FailureClassification("connection problem", transient = true)
            }
            val message = cause.message ?: ""
            if (message.contains("DefaultSSLContextImpl") || message.contains("NoSuchAlgorithmException")) {
                return FailureClassification("connection problem", transient = true)
            }
            cause = cause.cause
        }
        return FailureClassification("connection problem", transient = false)
    }

    internal data class FailureClassification(
        val message: String,
        val transient: Boolean,
    )

    internal fun isNewer(current: String, remote: String): Boolean {
        val currentVersion = ParsedVersion.from(current) ?: return false
        val remoteVersion = ParsedVersion.from(remote) ?: return false
        return remoteVersion.compareTo(currentVersion) > 0
    }

    internal fun renderDottedVersionLabel(versionName: String): String {
        val parsed = ParsedVersion.from(versionName)
        return "v${parsed?.toDottedString() ?: versionName.trim().removePrefix("v")}"
    }

    private fun parseReleaseOutcome(body: String, currentVersion: String): ParsedReleaseOutcome {
        val json = try {
            JSONObject(body)
        } catch (_: JSONException) {
            return ParsedReleaseOutcome.Failed("unparseable release body")
        }
        val tagName = json.optString("tag_name").takeIf { it.isNotBlank() }
            ?: return ParsedReleaseOutcome.Failed("Latest release has no tag_name")
        ParsedVersion.from(tagName)
            ?: return ParsedReleaseOutcome.Failed("Latest release tag is not parseable: $tagName")

        if (!isNewer(currentVersion, tagName)) return ParsedReleaseOutcome.UpToDate

        val htmlUrl = json.optString("html_url").takeIf { it.isNotBlank() }
            ?: return ParsedReleaseOutcome.Failed("Release $tagName has no html_url")
        val assets = json.optJSONArray("assets")
            ?: return ParsedReleaseOutcome.Failed("Release $tagName has no downloadable APK assets")
        val apkUrl = pickApkUrl(assets)
            ?: return ParsedReleaseOutcome.Failed("Release $tagName has no downloadable APK assets")
        val publishedDateLabel = formatPublishedDate(json.optString("published_at"), zoneId)
        return ParsedReleaseOutcome.UpdateAvailable(
            ReleaseInfo(
                tagName = tagName,
                htmlUrl = htmlUrl,
                apkUrl = apkUrl,
                publishedDateLabel = publishedDateLabel,
            ),
        )
    }

    /**
     * Prefer the historical `pocketshell-<ver>-debug.apk` name when it is
     * present, otherwise the first `.apk` asset. Release asset names have
     * drifted; a tagged release with *any* APK is still an offer.
     */
    private fun pickApkUrl(assets: org.json.JSONArray): String? {
        var firstApk: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (!name.endsWith(".apk", ignoreCase = true) || url.isBlank()) continue
            if (name.contains("pocketshell", ignoreCase = true) && name.contains("-debug.apk")) {
                return url
            }
            if (firstApk == null) firstApk = url
        }
        return firstApk
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

    /**
     * Production GET: always `disconnect()`, drain the error stream on a
     * non-200 so a GitHub 403 does not leak the keep-alive socket.
     */
    private class HttpUrlConnectionReleaseClient : ReleaseHttpClient {
        override fun get(url: String): ReleaseHttpResponse {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            return try {
                val code = conn.responseCode
                val stream = if (code == 200) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                ReleaseHttpResponse(code, body)
            } finally {
                conn.disconnect()
            }
        }
    }
}

private val PUBLISHED_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

/**
 * GitHub `published_at` → local calendar date. Never includes a clock time
 * (`HH:mm` / a `:`). Unparseable or blank input yields an empty string.
 */
internal fun formatPublishedDate(publishedAt: String, zoneId: ZoneId): String {
    val raw = publishedAt.trim()
    if (raw.isEmpty()) return ""
    val instant = try {
        Instant.parse(raw)
    } catch (_: DateTimeException) {
        return raw.takeWhile { it != 'T' }
            .takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) }
            .orEmpty()
    }
    return instant.atZone(zoneId).toLocalDate().format(PUBLISHED_DATE)
}

/** Host-list banner copy: `v0.5.1 is available — you are on v0.5.0 · 5 Sep 2026`. */
internal fun updateAvailableBannerText(info: ReleaseInfo, currentVersionLabel: String): String {
    val head = "${info.tagName} is available — you are on $currentVersionLabel"
    return if (info.publishedDateLabel.isBlank()) head else "$head · ${info.publishedDateLabel}"
}
