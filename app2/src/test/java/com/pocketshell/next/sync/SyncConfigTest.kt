package com.pocketshell.next.sync

import android.content.Intent
import java.security.KeyStore
import java.security.MessageDigest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The constants that make this client and the desktop client ONE account, and
 * the one that is deliberately different (issue #2633).
 *
 * The manifest check is the important one. The redirect scheme lives in two
 * places by necessity — an `<intent-filter>` needs a literal, and the OAuth
 * request needs the same string at runtime — and a mismatch between them is
 * invisible at build time: the browser simply never comes back, with no error
 * anywhere. So when the maintainer replaces the placeholder client ID, this
 * test is what says "…and the manifest too".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncConfigTest {

    @Test
    fun `points at the same backend and slot as the desktop app`() {
        // Both from pocketshell-electron/src/shared/syncConfig.ts. Changing
        // either splits the account in two.
        assertEquals("https://a7sota2qic.execute-api.eu-west-1.amazonaws.com", SyncConfig.SYNC_API_URL)
        assertEquals("main", SyncConfig.SYNC_SLOT)
    }

    @Test
    fun `uses Google's documented OAuth endpoints`() {
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth", SyncConfig.AUTH_ENDPOINT)
        assertEquals("https://oauth2.googleapis.com/token", SyncConfig.TOKEN_ENDPOINT)
        assertEquals("openid email profile", SyncConfig.SCOPE)
    }

    @Test
    fun `derives the reversed-client-ID redirect Google requires for Android`() {
        assertEquals(
            "com.googleusercontent.apps.1035162854462-abc",
            SyncConfig.redirectScheme("1035162854462-abc.apps.googleusercontent.com"),
        )
        assertEquals(
            "com.googleusercontent.apps.1035162854462-abc:/oauth2redirect",
            SyncConfig.redirectUri("1035162854462-abc.apps.googleusercontent.com"),
        )
    }

    /**
     * The client is REGISTERED (maintainer, 2026-09-10, Google Cloud project
     * `pocketshell-508120`), so this build is configured and the settings-sync
     * screen must offer a working Sign in rather than a "not configured yet"
     * banner.
     *
     * Asserted as a hard `true`, not as a restatement of the implementation:
     * the whole point is that a revert to the placeholder — a bad merge, a
     * copy-paste from an older branch — turns sign-in back into a dead button
     * with nothing in the UI to explain why, and this is what catches it.
     */
    @Test
    fun `ships a real registered Google client, not the placeholder`() {
        assertTrue(
            "#2633/#2635: GOOGLE_ANDROID_CLIENT_ID is back to the placeholder — " +
                "sign-in would open a browser at a 400",
            SyncConfig.isGoogleClientConfigured,
        )
        assertFalse(
            SyncConfig.GOOGLE_ANDROID_CLIENT_ID.contains(SyncConfig.UNCONFIGURED_CLIENT_ID_MARKER),
        )
        // The shape Google issues for an installed/public Android client.
        assertTrue(
            "a Google client ID ends in .apps.googleusercontent.com, was " +
                SyncConfig.GOOGLE_ANDROID_CLIENT_ID,
            SyncConfig.GOOGLE_ANDROID_CLIENT_ID.endsWith(".apps.googleusercontent.com"),
        )
        assertTrue(
            "the redirect scheme must be a legal URI scheme, or the manifest cannot declare it",
            SyncConfig.redirectScheme().matches(Regex("[a-zA-Z][a-zA-Z0-9+.-]*")),
        )
    }

    /**
     * The fingerprint recorded as "the key that signs this app" must be the one
     * the build actually signs with (#2635).
     *
     * Google authenticates an Android OAuth client by package name PLUS signing
     * certificate, so this pairing is a live credential, not documentation. It
     * already went wrong once: the client was registered against
     * `~/.android/debug.keystore` (`A0:4C:74:…`), while issue #42 pins every
     * build — laptop, CI and release — to the `debug.keystore` COMMITTED at the
     * repo root. Sign-in fails for a mismatch, and it fails invisibly: the
     * browser comes back and nothing happens.
     *
     * Reading the keystore rather than restating a constant is the whole point.
     * A regenerated or rotated keystore is a silent break otherwise; here it is
     * a red test with the new fingerprint printed in the message, ready to hand
     * to the Cloud Console.
     */
    @Test
    fun `the recorded signing fingerprint is the key that actually signs the app`() {
        val keystore = repoFile("debug.keystore")
        assertTrue(
            "issue #42's committed debug keystore is missing at ${keystore.absolutePath}",
            keystore.isFile,
        )

        val store = KeyStore.getInstance("PKCS12").runCatching {
            keystore.inputStream().use { load(it, "android".toCharArray()) }
            this
        }.recoverCatching {
            KeyStore.getInstance("JKS").apply {
                keystore.inputStream().use { load(it, "android".toCharArray()) }
            }
        }.getOrThrow()

        val certificate = requireNotNull(store.getCertificate("androiddebugkey")) {
            "the committed keystore has no `androiddebugkey` alias"
        }
        val actual = MessageDigest.getInstance("SHA-1")
            .digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it) }

        assertEquals(
            "the APK's signing certificate changed. Google authenticates this " +
                "app by package name + certificate, so sign-in is now broken " +
                "until $actual is added to the OAuth client " +
                "(${SyncConfig.GOOGLE_ANDROID_CLIENT_ID}) in Google Cloud " +
                "project pocketshell-508120.",
            SyncConfig.SIGNING_SHA1,
            actual,
        )
    }

    /** Walks up to the repo root, so the test works from any module dir. */
    private fun repoFile(relative: String): java.io.File {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return java.io.File(relative)
    }

    /**
     * The manifest's `<intent-filter>` must resolve the URI the OAuth request
     * asks Google to redirect to. Asked of the real PackageManager over the
     * real merged manifest, so it fails if either half moves without the other.
     */
    @Test
    fun `the manifest captures the redirect URI this build asks Google for`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SyncConfig.redirectUri())).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        assertEquals(
            "exactly one activity must claim ${SyncConfig.redirectUri()} — " +
                "update app2/src/main/AndroidManifest.xml's sync redirect scheme " +
                "when SyncConfig.GOOGLE_ANDROID_CLIENT_ID changes",
            1,
            matches.size,
        )
        assertEquals(
            SyncOAuthRedirectActivity::class.java.name,
            matches.single().activityInfo.name,
        )
    }
}
