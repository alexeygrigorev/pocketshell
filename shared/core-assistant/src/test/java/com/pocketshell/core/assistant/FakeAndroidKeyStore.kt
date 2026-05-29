package com.pocketshell.core.assistant

import java.io.InputStream
import java.io.OutputStream
import java.security.AlgorithmParameters
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Test-only JCA provider that registers a software-backed `"AndroidKeyStore"`
 * KeyStore so [androidx.security.crypto.MasterKey] / Tink can call
 *
 *   `KeyStore.getInstance("AndroidKeyStore")`
 *   `KeyGenerator.getInstance("AES", "AndroidKeyStore")`
 *
 * without throwing `NoSuchAlgorithmException` on the host JVM (Robolectric).
 *
 * The implementation is intentionally minimal — symmetric AES keys are
 * generated via the default JDK provider and stored in an in-memory map.
 * Cryptographically this is just standard AES; the *point* of the shim is
 * the provider lookup, not a hardware-backed key. The production code path
 * runs against the real Android Keystore on device — that's what users
 * actually depend on.
 *
 * Borrowed in shape from the public `FakeAndroidKeyStore` recipe used by
 * various open-source Android projects (e.g. test infrastructure for the
 * `androidx.security` library itself).
 */
internal object FakeAndroidKeyStore {

    private const val PROVIDER_NAME = "AndroidKeyStore"

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            if (Security.getProvider(PROVIDER_NAME) != null) {
                installed = true
                return
            }
            Security.insertProviderAt(FakeProvider(), 1)
            installed = true
        }
    }

    private class FakeProvider : Provider(PROVIDER_NAME, 1.0, "Test stub for Robolectric") {
        init {
            put("KeyStore.AndroidKeyStore", KeyStoreImpl::class.java.name)
            put("KeyGenerator.AES", AesKeyGenerator::class.java.name)
        }
    }

    /** Shared in-memory key table across the test process. */
    private object KeyTable {
        private val keys = mutableMapOf<String, Key>()
        @Synchronized fun put(alias: String, key: Key) { keys[alias] = key }
        @Synchronized fun get(alias: String): Key? = keys[alias]
        @Synchronized fun contains(alias: String): Boolean = keys.containsKey(alias)
        @Synchronized fun remove(alias: String) { keys.remove(alias) }
        @Synchronized fun aliases(): List<String> = keys.keys.toList()
        @Synchronized fun size(): Int = keys.size
    }

    /**
     * Java AES KeyGenerator routed under the "AndroidKeyStore" provider.
     *
     * Tink calls `KeyGenerator.getInstance("AES", "AndroidKeyStore")` then
     * `init(KeyGenParameterSpec)` with an alias embedded, then
     * `generateKey()`. Our implementation:
     *  - ignores the KeyGenParameterSpec details (block mode, padding, ...)
     *    — they don't matter for storage testing
     *  - generates a real AES-256 key via the default JDK provider
     *  - stores it under the spec's keystore alias so a later
     *    `KeyStore.getKey(alias, null)` returns it.
     */
    class AesKeyGenerator : KeyGeneratorSpi() {
        private var alias: String? = null
        private var keySize: Int = 256
        private val random = SecureRandom()

        override fun engineInit(random: SecureRandom?) {
            // No alias path; caller will fetch the key directly. Nothing to do.
        }

        override fun engineInit(params: java.security.spec.AlgorithmParameterSpec?, random: SecureRandom?) {
            // KeyGenParameterSpec is Android-only and not accessible from
            // the host JVM as a typed parameter. Reflectively pull out the
            // alias if it's there; otherwise just generate without
            // registering under any alias.
            if (params != null) {
                alias = runCatching {
                    val getKs = params::class.java.getMethod("getKeystoreAlias")
                    getKs.invoke(params) as? String
                }.getOrNull()
                keySize = runCatching {
                    val getSize = params::class.java.getMethod("getKeySize")
                    (getSize.invoke(params) as? Int) ?: 256
                }.getOrNull() ?: 256
            }
        }

        override fun engineInit(keysize: Int, random: SecureRandom?) {
            keySize = keysize
        }

        override fun engineGenerateKey(): SecretKey {
            val raw = ByteArray(keySize / 8)
            random.nextBytes(raw)
            val key: SecretKey = SecretKeySpec(raw, "AES")
            alias?.let { KeyTable.put(it, key) }
            return key
        }
    }

    /** Minimal in-memory KeyStoreSpi. */
    class KeyStoreImpl : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = KeyTable.get(alias)
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date? = Date()
        override fun engineSetKeyEntry(
            alias: String,
            key: Key,
            password: CharArray?,
            chain: Array<out Certificate>?,
        ) {
            KeyTable.put(alias, key)
        }
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = Unit
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit
        override fun engineDeleteEntry(alias: String) { KeyTable.remove(alias) }
        override fun engineAliases(): Enumeration<String> =
            java.util.Collections.enumeration(KeyTable.aliases())
        override fun engineContainsAlias(alias: String): Boolean = KeyTable.contains(alias)
        override fun engineSize(): Int = KeyTable.size()
        override fun engineIsKeyEntry(alias: String): Boolean = KeyTable.contains(alias)
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }
}
