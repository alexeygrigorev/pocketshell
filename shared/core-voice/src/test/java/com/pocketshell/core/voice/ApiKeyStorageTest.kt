package com.pocketshell.core.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.io.File

/**
 * Verifies [AndroidKeystoreApiKeyStorage] round-trips and — critically —
 * that the value on disk is encrypted (not plaintext).
 *
 * Robolectric supplies an Android Context, a stub Keystore, and the
 * SharedPreferences XML on the host JVM. `AndroidJUnit4` would also work,
 * but RobolectricTestRunner is explicit about which engine drives the test
 * (the same choice the core-storage module makes).
 *
 * `@Config(sdk = [33])` pins to a Robolectric-supported SDK level. The
 * `security-crypto` library uses Keystore APIs that require SDK 23+, so this
 * comfortably exercises the production path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ApiKeyStorageTest {

    private lateinit var context: Context
    private val prefsFile = "test-voice-secrets"

    @Before
    fun setUp() {
        // Robolectric doesn't ship an `"AndroidKeyStore"` JCA provider.
        // Install a software-backed stand-in so EncryptedSharedPreferences /
        // Tink can complete their setup on the host JVM.
        FakeAndroidKeyStore.install()
        context = ApplicationProvider.getApplicationContext()
        // Start each test with a fresh prefs file so prior state can't leak.
        clearPrefs()
    }

    @After
    fun tearDown() {
        clearPrefs()
    }

    @Test
    fun load_returns_null_before_first_save() {
        val storage = AndroidKeystoreApiKeyStorage(context, prefsFile)
        assertNull(storage.load())
    }

    @Test
    fun save_then_load_round_trips() {
        val storage = AndroidKeystoreApiKeyStorage(context, prefsFile)
        storage.save("sk-secret-123")
        assertEquals("sk-secret-123", storage.load())
    }

    @Test
    fun save_overwrites_existing_value() {
        val storage = AndroidKeystoreApiKeyStorage(context, prefsFile)
        storage.save("first")
        storage.save("second")
        assertEquals("second", storage.load())
    }

    @Test
    fun clear_removes_the_key() {
        val storage = AndroidKeystoreApiKeyStorage(context, prefsFile)
        storage.save("sk-bye")
        storage.clear()
        assertNull(storage.load())
    }

    @Test
    fun load_survives_a_fresh_storage_instance() {
        // The first instance writes; the second instance — pointing at the
        // same file — must be able to read it. This is the user-visible
        // contract (config saved across app restarts).
        AndroidKeystoreApiKeyStorage(context, prefsFile).save("sk-persistent")
        val reloaded = AndroidKeystoreApiKeyStorage(context, prefsFile)
        assertEquals("sk-persistent", reloaded.load())
    }

    @Test
    fun on_disk_value_is_not_plaintext() {
        val plaintext = "sk-very-secret-bytes-abc123"
        AndroidKeystoreApiKeyStorage(context, prefsFile).save(plaintext)

        val xml = sharedPrefsFile(prefsFile)
        assertNotNull("prefs file should exist after save", xml)
        assertTrue("prefs file should exist after save", xml!!.exists())

        val onDisk = xml.readBytes()
        // The crucial property: the plaintext value MUST NOT appear in the
        // backing file. Both as raw bytes and as a substring of any UTF
        // representation. EncryptedSharedPreferences also encrypts the field
        // *name*, so we additionally assert the key identifier is opaque.
        val text = onDisk.toString(Charsets.UTF_8)
        assertFalse(
            "plaintext value leaked to disk:\n$text",
            text.contains(plaintext),
        )
        assertFalse(
            "field name leaked to disk (should be SIV-encrypted):\n$text",
            text.contains(AndroidKeystoreApiKeyStorage.KEY_API_KEY),
        )
    }

    private fun clearPrefs() {
        context.deleteSharedPreferences(prefsFile)
        // Also try to remove the master-key prefs file Jetpack uses, so the
        // round-trip test can re-create it from scratch on a fresh fixture.
        context.deleteSharedPreferences("master_key")
    }

    private fun sharedPrefsFile(name: String): File? {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!dir.isDirectory) return null
        return File(dir, "$name.xml").takeIf { it.exists() }
    }
}
