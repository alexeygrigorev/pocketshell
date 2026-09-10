package com.pocketshell.next.sync

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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

    @Test
    fun `reports the placeholder client ID as unconfigured`() {
        // Until the maintainer registers the Android OAuth client, the sign-in
        // button must say so instead of opening a browser at a 400.
        assertEquals(
            !SyncConfig.GOOGLE_ANDROID_CLIENT_ID.contains(SyncConfig.UNCONFIGURED_CLIENT_ID_MARKER),
            SyncConfig.isGoogleClientConfigured,
        )
        assertTrue(
            "the placeholder must still be a legal URI scheme, or the manifest cannot declare it",
            SyncConfig.redirectScheme().matches(Regex("[a-zA-Z][a-zA-Z0-9+.-]*")),
        )
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
