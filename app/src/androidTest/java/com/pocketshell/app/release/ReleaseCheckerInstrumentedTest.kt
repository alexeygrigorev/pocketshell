package com.pocketshell.app.release

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseCheckerInstrumentedTest {

    @Test
    fun check_usesFakeReleaseFeedAndReturnsExactDottedApk() { runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "tag_name": "v0.2.1",
                      "html_url": "https://github.com/alexeygrigorev/pocketshell/releases/tag/v0.2.1",
                      "assets": [
                        {
                          "name": "pocketshell-v0.2.1-20260523-abcdef0-debug.apk",
                          "browser_download_url": "https://example.com/old.apk"
                        },
                        {
                          "name": "pocketshell-0.2.1-debug.apk",
                          "browser_download_url": "https://example.com/pocketshell-0.2.1-debug.apk"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        try {
            val checker = ReleaseChecker(server.url("/repos/latest").toString())

            val result = checker.checkForUpdate("0.2.0").infoOrNull()

            assertEquals(
                ReleaseInfo(
                    tagName = "v0.2.1",
                    htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v0.2.1",
                    apkUrl = "https://example.com/pocketshell-0.2.1-debug.apk",
                ),
                result,
            )
            assertEquals("/repos/latest", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    } }

    @Test
    fun check_returnsNullForEqualFakeRelease() { runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "tag_name": "v0.2.1",
                      "html_url": "https://github.com/alexeygrigorev/pocketshell/releases/tag/v0.2.1",
                      "assets": [
                        {
                          "name": "pocketshell-0.2.1-debug.apk",
                          "browser_download_url": "https://example.com/pocketshell-0.2.1-debug.apk"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        try {
            val checker = ReleaseChecker(server.url("/repos/latest").toString())

            assertNull(checker.checkForUpdate("0.2.1").infoOrNull())
        } finally {
            server.shutdown()
        }
    } }
}
