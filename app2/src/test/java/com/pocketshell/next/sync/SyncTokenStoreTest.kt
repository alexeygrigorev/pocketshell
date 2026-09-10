package com.pocketshell.next.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AndroidKeystoreSyncTokenStore] (issue #2633) — the Android answer to the
 * desktop app's Electron `safeStorage`.
 *
 * The load-bearing assertion is [on_disk_record_is_not_plaintext]. Everything
 * else here is a round-trip that would pass just as happily against plain
 * `SharedPreferences`; only reading the backing XML proves the OAuth refresh
 * token — a credential that stays valid until it is revoked — is not sitting
 * in app-private storage in the clear, where any device with an unlocked
 * bootloader or a backup extraction can read it.
 *
 * Same Robolectric shape as `:shared:core-voice`'s `ApiKeyStorageTest`, which
 * covers the identical mechanism for the Whisper API key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SyncTokenStoreTest {

    private lateinit var context: Context
    private val prefsFile = "test-sync-auth"

    private val sample = StoredAuth(
        sub = "108374652910",
        email = "person@example.com",
        idToken = "eyJhbGciOiJSUzI1NiIsImtpZCI6ImFiYyJ9.payload.signature",
        refreshToken = "1//0gRefreshTokenThatStaysValidUntilRevoked",
        obtainedAtMs = 1_700_000_000_000,
        expiresInS = 3600,
    )

    @Before
    fun setUp() {
        // Robolectric ships no "AndroidKeyStore" JCA provider; the shim lets
        // EncryptedSharedPreferences/Tink complete setup on the host JVM.
        FakeAndroidKeyStore.install()
        context = ApplicationProvider.getApplicationContext()
        clearPrefs()
    }

    @After
    fun tearDown() = clearPrefs()

    @Test
    fun `load returns null before anything is stored`() {
        assertNull(store().load())
    }

    @Test
    fun `save then load round-trips every field`() {
        store().save(sample)
        assertEquals(sample, store().load())
    }

    @Test
    fun `a sign-in survives a fresh store instance`() {
        // The user-visible contract: still signed in after a process restart.
        store().save(sample)
        assertEquals(sample, AndroidKeystoreSyncTokenStore(context, prefsFile).load())
    }

    @Test
    fun `an absent refresh token stays absent rather than becoming empty`() {
        // Google does not always issue one; "no refresh token" must mean
        // "sign in again at expiry", not "refresh with the empty string".
        val noRefresh = sample.copy(refreshToken = null)
        store().save(noRefresh)
        assertNull(store().load()!!.refreshToken)
    }

    @Test
    fun `clear removes the sign-in`() {
        val store = store()
        store.save(sample)
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `a corrupt record degrades to signed out rather than crashing`() {
        // A truncated write must put the user at "signed out" — a state the
        // settings screen can act on — not throw on the first read of the
        // screen, which would be a crash loop with no way out.
        val store = store()
        store.save(sample)
        writeRawRecord("{not json")
        assertNull(store.load())
    }

    @Test
    fun `on_disk_record_is_not_plaintext`() {
        store().save(sample)

        val xml = sharedPrefsFile(prefsFile)
        assertNotNull("prefs file should exist after save", xml)
        val text = xml!!.readBytes().toString(Charsets.UTF_8)

        assertFalse("ID token leaked to disk:\n$text", text.contains(sample.idToken))
        assertFalse("refresh token leaked to disk:\n$text", text.contains(sample.refreshToken!!))
        assertFalse("email leaked to disk:\n$text", text.contains(sample.email!!))
        assertFalse("subject leaked to disk:\n$text", text.contains(sample.sub))
        // EncryptedSharedPreferences SIV-encrypts the field NAME too, so even
        // "there is a sync token here" is not readable off the file.
        assertFalse(
            "field name leaked to disk:\n$text",
            text.contains(AndroidKeystoreSyncTokenStore.KEY_AUTH_RECORD),
        )
        assertTrue("the file should still hold something", text.isNotEmpty())
    }

    @Test
    fun `sync tokens do not share a preferences file with the voice API key`() {
        // Separate keysets, separate blast radius: clearing or corrupting one
        // secret store must not take the other with it.
        assertEquals("pocketshell-sync-auth", AndroidKeystoreSyncTokenStore.DEFAULT_PREFERENCES_FILE)
    }

    private fun store() = AndroidKeystoreSyncTokenStore(context, prefsFile)

    /**
     * Replace the stored record with something that decrypts fine but is not a
     * record — the shape a truncated/interrupted write leaves behind. Written
     * through an independently-built handle on the same encrypted file, so the
     * corruption lands in OUR JSON rather than in Tink's envelope.
     */
    @Suppress("DEPRECATION")
    private fun writeRawRecord(value: String) {
        val masterKey = androidx.security.crypto.MasterKey.Builder(context)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()
        androidx.security.crypto.EncryptedSharedPreferences.create(
            context,
            prefsFile,
            masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).edit().putString(AndroidKeystoreSyncTokenStore.KEY_AUTH_RECORD, value).commit()
    }

    private fun clearPrefs() {
        context.deleteSharedPreferences(prefsFile)
        context.deleteSharedPreferences("master_key")
    }

    private fun sharedPrefsFile(name: String): File? {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!dir.isDirectory) return null
        return File(dir, "$name.xml").takeIf { it.exists() }
    }
}
