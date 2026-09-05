package com.pocketshell.next.release

import java.net.SocketException
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ReleaseChecker] against an injected fetcher — no live GitHub.
 *
 * Pins compare, the three-way result, APK-asset picking (including drifted
 * filenames), and `published_at` → date-only (no clock time).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ReleaseCheckerTest {

    private val utcChecker = ReleaseChecker(zoneId = ZoneOffset.UTC)

    @Test
    fun isNewer_returnsTrue_forPatchBump() {
        assertTrue(utcChecker.isNewer("v0.0.1", "v0.0.2"))
    }

    @Test
    fun isNewer_returnsFalse_forEqualVersions() {
        assertFalse(utcChecker.isNewer("v0.0.1", "v0.0.1"))
        assertFalse(utcChecker.isNewer("0.5.0", "v0.5.0"))
    }

    @Test
    fun isNewer_returnsFalse_forOlderRemote() {
        assertFalse(utcChecker.isNewer("v0.1.0", "v0.0.9"))
        assertFalse(utcChecker.isNewer("0.5.1", "v0.5.0"))
    }

    @Test
    fun isNewer_toleratesMissingVPrefix_andDebugSuffix() {
        assertTrue(utcChecker.isNewer("0.5.0-debug", "v0.5.1"))
        assertTrue(utcChecker.isNewer("0.5.0", "v0.5.1"))
    }

    @Test
    fun formatPublishedDate_isCalendarDate_withNoClockTime() {
        val label = formatPublishedDate("2026-09-05T14:30:00Z", ZoneOffset.UTC)
        assertEquals("5 Sep 2026", label)
        assertFalse("date label leaked a clock time: $label", label.contains(":"))
        assertFalse(
            "date label looks like HH:mm: $label",
            Regex("""\d{1,2}:\d{2}""").containsMatchIn(label),
        )
    }

    @Test
    fun formatPublishedDate_emptyOnBlank() {
        assertEquals("", formatPublishedDate("", ZoneOffset.UTC))
        assertEquals("", formatPublishedDate("   ", ZoneOffset.UTC))
    }

    @Test
    fun newerTagWithApk_isUpdateAvailable_andDateHasNoTime() = runBlocking {
        val checker = ReleaseChecker(
            http = { ReleaseHttpResponse(200, releaseJson(tagName = "v0.5.1")) },
            zoneId = ZoneOffset.UTC,
        )
        val result = checker.checkForUpdate("0.5.0")
        assertTrue(result is ReleaseCheckResult.UpdateAvailable)
        val info = (result as ReleaseCheckResult.UpdateAvailable).info
        assertEquals("v0.5.1", info.tagName)
        assertEquals("https://example.com/pocketshell-0.5.1.apk", info.apkUrl)
        assertEquals("5 Sep 2026", info.publishedDateLabel)
        assertFalse(info.publishedDateLabel.contains(":"))
        assertEquals(
            "v0.5.1 is available — you are on v0.5.0 · 5 Sep 2026",
            updateAvailableBannerText(info, "v0.5.0"),
        )
    }

    @Test
    fun sameVersion_isUpToDate_notFailed() = runBlocking {
        val checker = ReleaseChecker(
            http = { ReleaseHttpResponse(200, releaseJson(tagName = "v0.5.0")) },
            zoneId = ZoneOffset.UTC,
        )
        val result = checker.checkForUpdate("0.5.0")
        assertEquals(ReleaseCheckResult.UpToDate, result)
        assertFalse(result is ReleaseCheckResult.Failed)
    }

    @Test
    fun olderTag_isUpToDate() = runBlocking {
        val checker = ReleaseChecker(
            http = { ReleaseHttpResponse(200, releaseJson(tagName = "v0.4.0")) },
            zoneId = ZoneOffset.UTC,
        )
        assertEquals(ReleaseCheckResult.UpToDate, checker.checkForUpdate("0.5.0"))
    }

    @Test
    fun non200_isFailed_notUpToDate() = runBlocking {
        val checker = ReleaseChecker(
            http = { ReleaseHttpResponse(500, """{"message":"oops"}""") },
        )
        val result = checker.checkForUpdate("0.5.0")
        assertTrue(result is ReleaseCheckResult.Failed)
        assertEquals("server error (HTTP 500)", (result as ReleaseCheckResult.Failed).reason)
        assertFalse(result is ReleaseCheckResult.UpToDate)
    }

    @Test
    fun github403_isFailed_andNotRetried() = runBlocking {
        val calls = AtomicInteger(0)
        val checker = ReleaseChecker(
            retryBackoffMs = 0,
            http = {
                calls.incrementAndGet()
                ReleaseHttpResponse(403, """{"message":"API rate limit exceeded"}""")
            },
        )
        val result = checker.checkForUpdate("0.5.0")
        assertTrue(result is ReleaseCheckResult.Failed)
        assertEquals("rate-limited, try again later", (result as ReleaseCheckResult.Failed).reason)
        assertEquals(1, calls.get())
    }

    @Test
    fun missingApk_isFailed() = runBlocking {
        val checker = ReleaseChecker(
            http = {
                ReleaseHttpResponse(
                    200,
                    releaseJson(
                        tagName = "v0.5.1",
                        assets = """{"name":"notes.txt","browser_download_url":"https://example.com/notes.txt"}""",
                    ),
                )
            },
        )
        val result = checker.checkForUpdate("0.5.0")
        assertTrue(result is ReleaseCheckResult.Failed)
        assertTrue(
            (result as ReleaseCheckResult.Failed).reason.contains("no downloadable APK"),
        )
    }

    @Test
    fun driftedApkFilename_isStillAnOffer() = runBlocking {
        val checker = ReleaseChecker(
            http = {
                ReleaseHttpResponse(
                    200,
                    releaseJson(
                        tagName = "v0.5.1",
                        assets = """{"name":"PocketShell-0.5.1.apk","browser_download_url":"https://example.com/drifted.apk"}""",
                    ),
                )
            },
            zoneId = ZoneOffset.UTC,
        )
        val result = checker.checkForUpdate("0.5.0")
        assertTrue(result is ReleaseCheckResult.UpdateAvailable)
        assertEquals(
            "https://example.com/drifted.apk",
            (result as ReleaseCheckResult.UpdateAvailable).info.apkUrl,
        )
    }

    @Test
    fun transientFailure_retriesOnce_andSucceeds() = runBlocking {
        val calls = AtomicInteger(0)
        val checker = ReleaseChecker(
            retryBackoffMs = 0,
            http = {
                if (calls.getAndIncrement() == 0) throw SocketException("connection reset")
                ReleaseHttpResponse(200, releaseJson(tagName = "v0.5.0"))
            },
        )
        assertEquals(ReleaseCheckResult.UpToDate, checker.checkForUpdate("0.5.0"))
        assertEquals(2, calls.get())
    }

    @Test
    fun unknownHost_isFailed_notRetried() = runBlocking {
        val calls = AtomicInteger(0)
        val checker = ReleaseChecker(
            retryBackoffMs = 0,
            http = {
                calls.incrementAndGet()
                throw java.net.UnknownHostException("api.github.com")
            },
        )
        val result = checker.checkForUpdate("0.5.0")
        assertEquals("no network connection", (result as ReleaseCheckResult.Failed).reason)
        assertEquals(1, calls.get())
    }

    @Test
    fun checkHitsTheGithubLatestEndpoint() = runBlocking {
        var seenUrl: String? = null
        val checker = ReleaseChecker(
            http = { url ->
                seenUrl = url
                ReleaseHttpResponse(200, releaseJson(tagName = "v0.5.0"))
            },
        )
        checker.checkForUpdate("0.5.0")
        assertEquals(ReleaseChecker.API_URL, seenUrl)
    }

    private fun releaseJson(
        tagName: String,
        assets: String = """{"name":"pocketshell-0.5.1-debug.apk","browser_download_url":"https://example.com/pocketshell-0.5.1.apk"}""",
        publishedAt: String = "2026-09-05T14:30:00Z",
    ): String = """
        {
          "tag_name": "$tagName",
          "html_url": "https://github.com/alexeygrigorev/pocketshell/releases/tag/$tagName",
          "published_at": "$publishedAt",
          "assets": [ $assets ]
        }
    """.trimIndent()
}
