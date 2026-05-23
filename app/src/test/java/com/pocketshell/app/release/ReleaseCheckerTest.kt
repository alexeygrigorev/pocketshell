package com.pocketshell.app.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the semver comparison semantics surfaced by [ReleaseChecker.isNewer].
 *
 * The acceptance criteria for issue #40 enumerate these cases verbatim:
 *
 * - `("v0.0.1", "v0.0.2")` -> true  (patch bump)
 * - `("v0.0.1", "v0.0.1")` -> false (equal)
 * - `("v0.0.1", "v0.1.0")` -> true  (minor bump dominates patch)
 * - `("v0.1.0", "v0.0.9")` -> false (minor regression — not "newer")
 *
 * We also lock down the `v`-prefix tolerance and the `-debug` suffix
 * stripping on the local side, since both come up in PocketShell's
 * actual `versionName` strings.
 */
class ReleaseCheckerTest {

    private val checker = ReleaseChecker()

    @Test
    fun isNewer_returnsTrue_forPatchBump() {
        assertTrue(checker.isNewer("v0.0.1", "v0.0.2"))
    }

    @Test
    fun isNewer_returnsFalse_forEqualVersions() {
        assertFalse(checker.isNewer("v0.0.1", "v0.0.1"))
    }

    @Test
    fun isNewer_returnsFalse_forEqualVersionsWithMissingPatch() {
        assertFalse(checker.isNewer("0.2", "v0.2.0"))
        assertFalse(checker.isNewer("0", "v0.0.0"))
    }

    @Test
    fun isNewer_returnsTrue_forMinorBump() {
        assertTrue(checker.isNewer("v0.0.1", "v0.1.0"))
    }

    @Test
    fun isNewer_returnsFalse_forMinorRegression() {
        assertFalse(checker.isNewer("v0.1.0", "v0.0.9"))
    }

    @Test
    fun isNewer_toleratesMissingVPrefix_onLocalSide() {
        // PackageInfo.versionName ships without a `v` prefix — make sure
        // the comparison still works.
        assertTrue(checker.isNewer("0.1.0", "v0.2.0"))
        assertFalse(checker.isNewer("0.2.0", "v0.1.0"))
    }

    @Test
    fun isNewer_returnsFalse_forEqualVersionsWithDifferentVPrefixes() {
        assertFalse(checker.isNewer("0.2.0", "v0.2.0"))
        assertFalse(checker.isNewer("v0.2.0", "0.2.0"))
    }

    @Test
    fun isNewer_stripsLocalQualifierSuffix() {
        // Debug builds sometimes carry a `-debug` qualifier; the remote
        // tag is plain semver. The comparison should drop the qualifier
        // before splitting.
        assertTrue(checker.isNewer("0.1.0-debug", "v0.2.0"))
        assertFalse(checker.isNewer("0.2.0-debug", "v0.1.0"))
    }

    @Test
    fun isNewer_handlesRemoteQualifierSuffix() {
        assertTrue(checker.isNewer("0.1.0", "v0.2.0-beta.1"))
        assertFalse(checker.isNewer("0.2.0", "v0.2.0-beta.1"))
    }

    @Test
    fun isNewer_returnsTrue_forMajorBump() {
        assertTrue(checker.isNewer("v0.9.9", "v1.0.0"))
    }

    @Test
    fun isNewer_returnsFalse_forMajorRegression() {
        assertFalse(checker.isNewer("v1.0.0", "v0.9.9"))
    }

    @Test
    fun isNewer_returnsFalse_forUnknownLocalVersion() {
        assertFalse(checker.isNewer("", "v0.2.0"))
        assertFalse(checker.isNewer("unknown", "v0.2.0"))
    }

    @Test
    fun isNewer_returnsFalse_forUnknownRemoteVersion() {
        assertFalse(checker.isNewer("0.2.0", "latest"))
        assertFalse(checker.isNewer("0.2.0", "v0.2.x"))
    }

    @Test
    fun renderDottedVersionLabel_normalizesVersionNames() {
        assertEquals("v0.2.1", checker.renderDottedVersionLabel("0.2.1"))
        assertEquals("v0.2.1", checker.renderDottedVersionLabel("v0.2.1"))
        assertEquals("v0.2.1", checker.renderDottedVersionLabel("0.2.1-debug"))
        assertEquals("v0.2.0", checker.renderDottedVersionLabel("0.2"))
    }

    @Test
    fun parseRelease_returnsExactDottedApkAsset_forNewerVersion() {
        val result = checker.parseRelease(
            body = releaseJson(
                tagName = "v0.2.1",
                assets = """
                    {"name":"pocketshell-v0.2.1-20260523-abcdef0-debug.apk","browser_download_url":"https://example.com/old.apk"},
                    {"name":"pocketshell-0.2.1-debug.apk","browser_download_url":"https://example.com/dotted.apk"}
                """.trimIndent(),
            ),
            currentVersion = "0.2.0",
        )

        assertEquals(
            ReleaseInfo(
                tagName = "v0.2.1",
                htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v0.2.1",
                apkUrl = "https://example.com/dotted.apk",
            ),
            result,
        )
    }

    @Test
    fun parseRelease_returnsNull_forNewerVersionWithoutExactDottedApkAsset() {
        val result = checker.parseRelease(
            body = releaseJson(
                tagName = "v0.2.1",
                assets = """
                    {"name":"pocketshell-v0.2.1-20260523-abcdef0-debug.apk","browser_download_url":"https://example.com/old.apk"},
                    {"name":"app-debug.apk","browser_download_url":"https://example.com/app-debug.apk"}
                """.trimIndent(),
            ),
            currentVersion = "0.2.0",
        )

        assertNull(result)
    }

    @Test
    fun parseRelease_returnsNull_forEqualOrOlderRelease() {
        assertNull(
            checker.parseRelease(
                body = releaseJson(tagName = "v0.2.1"),
                currentVersion = "0.2.1",
            ),
        )
        assertNull(
            checker.parseRelease(
                body = releaseJson(tagName = "v0.2.0"),
                currentVersion = "0.2.1",
            ),
        )
    }

    private fun releaseJson(
        tagName: String,
        assets: String = """
            {"name":"pocketshell-0.2.1-debug.apk","browser_download_url":"https://example.com/dotted.apk"}
        """.trimIndent(),
    ): String = """
        {
          "tag_name": "$tagName",
          "html_url": "https://github.com/alexeygrigorev/pocketshell/releases/tag/$tagName",
          "assets": [
            $assets
          ]
        }
    """.trimIndent()
}
