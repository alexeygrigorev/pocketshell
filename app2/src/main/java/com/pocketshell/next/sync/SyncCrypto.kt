package com.pocketshell.next.sync

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONException
import org.json.JSONObject

/**
 * Client-side encryption for settings sync — the zero-knowledge half of the
 * feature (issue #2633). A faithful port of the desktop app's
 * `src/main/sync/SyncCrypto.ts`, byte-for-byte compatible with it: a blob
 * written on the laptop opens on the phone and vice versa, which is the whole
 * reason the feature exists.
 *
 * The sync backend stores an opaque envelope and never sees plaintext, the
 * passphrase, or the key. The passphrase is typed by the user at sync time and
 * lives only in the ViewModel's memory for that screen — never on disk — so
 * losing it loses the stored blob, which the settings screen says out loud.
 * What makes the same passphrase work on every device is the salt travelling
 * INSIDE the envelope: each write derives its key from that write's salt, so
 * the header is enough to re-derive anywhere.
 *
 * Format (v1, matched byte-for-byte by the desktop reader):
 *
 * ```
 * {
 *   "v": 1, "kdf": "pbkdf2-sha256", "iter": 600000,
 *   "salt": "<b64 16B>", "iv": "<b64 12B>", "ct": "<b64 ct+tag>"
 * }
 * ```
 *
 * The 16-byte GCM auth tag is appended to the ciphertext — the standard
 * compact spelling, and what makes a wrong passphrase or a corrupted blob fail
 * the tag check instead of yielding garbage.
 *
 * ## Why PBKDF2 is hand-rolled here
 *
 * Node derives the key over the passphrase's **UTF-8 bytes**. The JCE spells
 * PBKDF2 through `PBEKeySpec`, which takes a `char[]` and leaves the
 * char-to-byte conversion to the provider — SunJCE, Conscrypt and BouncyCastle
 * have historically disagreed (UTF-8 vs. low-8-bits vs. UTF-16). A passphrase
 * containing any non-ASCII character would then derive a different key on
 * Android than on the desktop, and the cross-device property would break for
 * exactly the users who would never guess why. PBKDF2-HMAC-SHA256 over an
 * explicit UTF-8 byte array is twenty lines (RFC 8018 §5.2) and removes the
 * question; [pbkdf2HmacSha256] is covered by published known-answer vectors in
 * `SyncCryptoTest` so it cannot silently drift.
 */

/** The serialized envelope: exactly the JSON string the server stores as `data`. */
typealias EnvelopeString = String

/**
 * Everything that can go wrong while encrypting or decrypting, as one error
 * type the UI layer can render as a message: not the passphrase, a malformed
 * envelope, or a blob that is not ours.
 */
class SyncCryptoError(message: String) : Exception(message)

object SyncCrypto {

    const val FORMAT_VERSION: Int = 1
    const val KDF_NAME: String = "pbkdf2-sha256"
    const val KDF_ITERATIONS: Int = 600_000
    const val SALT_BYTES: Int = 16
    const val IV_BYTES: Int = 12
    const val KEY_BYTES: Int = 32
    const val TAG_BYTES: Int = 16

    /** Bounds on the blob-controlled iteration count, same as the desktop reader. */
    private const val MIN_ITERATIONS = 1
    private const val MAX_ITERATIONS = 10_000_000

    private val random = SecureRandom()

    /**
     * Encrypt [plaintext] under [passphrase] into the serialized envelope.
     * Fresh salt and IV on every call, so encrypting the same settings twice
     * never produces the same blob.
     */
    fun encryptToEnvelope(
        plaintext: String,
        passphrase: String,
        randomSource: SecureRandom = random,
    ): EnvelopeString {
        val passwordBytes = passphraseBytes(passphrase)
        val salt = ByteArray(SALT_BYTES).also(randomSource::nextBytes)
        val iv = ByteArray(IV_BYTES).also(randomSource::nextBytes)
        val key = pbkdf2HmacSha256(passwordBytes, salt, KDF_ITERATIONS, KEY_BYTES)
        val ciphertext = try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, iv))
            // Java's GCM cipher already appends the auth tag to the output,
            // which is exactly Node's `concat(update, final, getAuthTag())`.
            cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            throw SyncCryptoError("could not encrypt the settings blob: ${e.message}")
        } finally {
            key.fill(0)
            passwordBytes.fill(0)
        }
        return JSONObject()
            .put("v", FORMAT_VERSION)
            .put("kdf", KDF_NAME)
            .put("iter", KDF_ITERATIONS)
            .put("salt", b64encode(salt))
            .put("iv", b64encode(iv))
            .put("ct", b64encode(ciphertext))
            .toString()
    }

    /**
     * Reverse [encryptToEnvelope]. Throws [SyncCryptoError] for a malformed
     * envelope and for ANY decryption failure — GCM cannot distinguish "wrong
     * passphrase" from "corrupted blob", and neither message should pretend to
     * know which.
     */
    fun decryptEnvelope(envelope: EnvelopeString, passphrase: String): String {
        val passwordBytes = passphraseBytes(passphrase)
        val fields = try {
            JSONObject(envelope)
        } catch (_: JSONException) {
            throw SyncCryptoError("stored blob is not a sync envelope")
        }
        // `JSONObject("[]")` throws, so an array lands above; a JSON scalar
        // ("3", "\"x\"") does too. Only an object reaches here.
        val version = fields.opt("v")
        val kdf = fields.opt("kdf")
        if (version != FORMAT_VERSION || kdf != KDF_NAME) {
            throw SyncCryptoError("envelope this app cannot read (v=$version, kdf=$kdf)")
        }
        if (fields.opt("iter") !is Number ||
            fields.opt("salt") !is String ||
            fields.opt("iv") !is String ||
            fields.opt("ct") !is String
        ) {
            throw SyncCryptoError("envelope is missing required fields")
        }
        val iterations = (fields.opt("iter") as Number).toLong()
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            // The iteration count is attacker-controlled input (it is in the
            // blob) and feeds PBKDF2's loop — bounded before use.
            throw SyncCryptoError("envelope iteration count is out of range")
        }
        val salt = b64decode(fields.getString("salt"), "salt")
        val iv = b64decode(fields.getString("iv"), "IV")
        val ciphertext = b64decode(fields.getString("ct"), "ciphertext")
        if (salt.size != SALT_BYTES) throw SyncCryptoError("envelope salt has the wrong length")
        if (iv.size != IV_BYTES) throw SyncCryptoError("envelope IV has the wrong length")
        if (ciphertext.size <= TAG_BYTES) throw SyncCryptoError("envelope ciphertext is truncated")

        val key = pbkdf2HmacSha256(passwordBytes, salt, iterations.toInt(), KEY_BYTES)
        try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, iv))
            return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            // Wrong passphrase and tampered ciphertext land here identically.
            throw SyncCryptoError("decryption failed — wrong passphrase or corrupted blob")
        } finally {
            key.fill(0)
            passwordBytes.fill(0)
        }
    }

    /**
     * PBKDF2-HMAC-SHA256 (RFC 8018 §5.2) over explicit UTF-8 password bytes.
     * Internal rather than private so the known-answer vectors can drive it.
     */
    internal fun pbkdf2HmacSha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyBytes: Int,
    ): ByteArray {
        require(password.isNotEmpty()) { "password must not be empty" }
        require(iterations > 0) { "iterations must be positive" }
        require(keyBytes > 0) { "keyBytes must be positive" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val blocks = (keyBytes + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)
        for (block in 1..blocks) {
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (block ushr 24).toByte(),
                    (block ushr 16).toByte(),
                    (block ushr 8).toByte(),
                    block.toByte(),
                ),
            )
            mac.doFinal(u, 0)
            System.arraycopy(u, 0, t, 0, hLen)
            for (round in 2..iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (i in 0 until hLen) {
                    t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
                }
            }
            System.arraycopy(t, 0, out, (block - 1) * hLen, hLen)
        }
        return out.copyOf(keyBytes)
    }

    /**
     * An empty passphrase is rejected rather than silently accepted: the JCE
     * refuses a zero-length HMAC key anyway, and "no passphrase" is not a
     * meaningful zero-knowledge state — it would be a blob anyone who can read
     * the account can open.
     */
    private fun passphraseBytes(passphrase: String): ByteArray {
        if (passphrase.isEmpty()) throw SyncCryptoError("a sync passphrase is required")
        return passphrase.toByteArray(StandardCharsets.UTF_8)
    }

    private fun b64encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun b64decode(value: String, what: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        throw SyncCryptoError("envelope $what is not valid base64")
    }

    private const val AES_GCM = "AES/GCM/NoPadding"
}
