package com.pocketshell.next.hosts

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo
import org.bouncycastle.asn1.pkcs.EncryptionScheme
import org.bouncycastle.asn1.pkcs.KeyDerivationFunc
import org.bouncycastle.asn1.pkcs.PBES2Parameters
import org.bouncycastle.asn1.pkcs.PBKDF2Params
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.crypto.PBEParametersGenerator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Pure private-key helpers: recognise a PEM, detect an encrypted one, hash one,
 * and generate a fresh one (rewrite task P-6).
 *
 * Deliberately free of Android and of Room — everything here is a function of a
 * `String`, so the key rules are unit-testable on the plain JVM and
 * [SshKeyStore] is left with nothing but IO. The logic is ported from the old
 * client's `SshKeyStorage`, which mixed the two.
 *
 * ## Passphrases
 *
 * Encryption is a property of the material, not a reason to discard it. The
 * encrypted PEM stays on app-private storage and the connection flow supplies
 * a one-use passphrase to sshj after the native unlock handoff. No passphrase
 * is written here or included in a QR payload.
 */
object SshKeyMaterial {

    /** Shape check: a PEM block whose type mentions a private key. */
    fun looksLikePrivateKey(content: String): Boolean {
        val trimmed = content.trim()
        return trimmed.startsWith("-----BEGIN") &&
            trimmed.contains("PRIVATE KEY") &&
            trimmed.lineSequence().any {
                it.trim().startsWith("-----END") && it.contains("PRIVATE KEY")
            }
    }

    /**
     * Parses enough of the supplied PEM to prove it is complete key material.
     *
     * Unencrypted keys are passed through sshj as well, so an apparently valid
     * PEM cannot be imported unless the local key reader can load it. Encrypted
     * keys cannot be fully opened without a passphrase, but their container,
     * ASN.1 envelope, or ciphertext block shape is still checked before the
     * secret reaches disk.
     */
    fun validatePrivateKey(content: String) {
        val pem = parsePem(content)
        when {
            pem.type == OPENSSH_PRIVATE_KEY -> {
                val decoded = pem.decodeBody()
                validateOpenSshContainer(decoded)
                if (!isEncrypted(content)) {
                    require(publicKeyLine(content).isNotBlank())
                }
            }

            pem.type == ENCRYPTED_PRIVATE_KEY -> {
                EncryptedPrivateKeyInfo.getInstance(pem.decodeBody())
            }

            isClassicEncrypted(pem.headers) -> {
                validateClassicCiphertext(pem.decodeBody(), pem.headers)
            }

            else -> {
                require(publicKeyLine(content).isNotBlank())
            }
        }
    }

    /**
     * `true` when the PEM is passphrase-protected.
     *
     * Three encodings have to be recognised, because the header alone is not
     * enough for the modern one:
     * - classic PEM encryption (`Proc-Type: 4,ENCRYPTED` / `DEK-Info:`),
     * - PKCS#8 (`-----BEGIN ENCRYPTED PRIVATE KEY-----`),
     * - OpenSSH's own container, whose header is identical whether or not the
     *   key is encrypted — the cipher/KDF names inside the base64 body are the
     *   only signal, which is why the body is parsed here.
     */
    fun isEncrypted(content: String): Boolean {
        val lines = content.lineSequence().map { it.trim() }.toList()
        return lines.any { it == "Proc-Type: 4,ENCRYPTED" } ||
            lines.any { it.startsWith("DEK-Info:", ignoreCase = true) } ||
            lines.any { it == "-----BEGIN ENCRYPTED PRIVATE KEY-----" } ||
            isEncryptedOpenSshKey(lines)
    }

    /**
     * Content hash of the trimmed PEM, used to reuse an existing `ssh_keys` row
     * for a byte-identical key instead of writing a second copy of the same
     * secret to disk (re-importing the same QR twice is the normal case).
     */
    fun fingerprint(content: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(content.trim().toByteArray(Charsets.UTF_8))
        return "sha256:" + bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Reads the public half through sshj without exposing the private half.
     * Unencrypted keys can be displayed immediately; encrypted keys are read
     * after the caller has completed the real unlock flow.
     */
    fun publicKeyLine(content: String, passphrase: CharArray? = null): String {
        unencryptedOpenSshPublicKeyLine(content)?.let { return it }
        // An unencrypted OpenSSH key carries its public half in clear text.
        // Read that record before installing the BC provider: Android may
        // already expose a platform provider under the same name, and the
        // private-key parser is unnecessary for this fast, deterministic path.
        ensureBouncyCastle()
        SSHClient().use { client ->
            val provider = loadKeyProvider(client, content, passphrase)
            val publicKey = provider.public
            val type = KeyType.fromKey(publicKey)
            val body = Buffer.PlainBuffer().apply {
                type.putPubKeyIntoBuffer(publicKey, this)
            }.getCompactData()
            return "${type} ${Base64.getEncoder().encodeToString(body)} pocketshell"
        }
    }

    /**
     * Generate the selected key pair and return the private half as PKCS#8 PEM.
     * The encrypted form is standard PKCS#8, which sshj's key-file
     * autodetection reads and [isEncrypted] can identify without guessing from
     * the selected UI option.
     */
    fun generatePrivateKeyPem(
        type: SshKeyGenerationType = SshKeyGenerationType.ED25519,
        passphrase: CharArray? = null,
    ): String {
        ensureBouncyCastle()
        val generator = KeyPairGenerator.getInstance(
            type.javaAlgorithm,
            BouncyCastleProvider.PROVIDER_NAME,
        )
        if (type == SshKeyGenerationType.RSA) {
            // 3072 bits matches the compatibility option shown in the dialog.
            generator.initialize(RSA_KEY_BITS)
        }
        val keyPair = generator.generateKeyPair()
        val privateKey = keyPair.private
        val publicKey = keyPair.public
        val protectedPassphrase = passphrase
            ?.takeIf { it.isNotEmpty() }
            ?.copyOf()
        return try {
            if (type == SshKeyGenerationType.ED25519) {
                generateOpenSshEd25519PrivateKeyPem(
                    privateKey = privateKey.encoded,
                    publicKey = publicKey.encoded,
                    passphrase = protectedPassphrase,
                )
            } else if (protectedPassphrase == null) {
                pemEncode("PRIVATE KEY", privateKey.encoded)
            } else {
                pemEncode(
                    "ENCRYPTED PRIVATE KEY",
                    encryptPkcs8(privateKey.encoded, protectedPassphrase),
                )
            }
        } finally {
            protectedPassphrase?.fill('\u0000')
        }
    }

    /** Kept for callers that still need to generate the legacy RSA shape. */
    fun generateRsaPrivateKeyPem(): String = generatePrivateKeyPem(SshKeyGenerationType.RSA)

    /** Human-readable SSH algorithm from a complete authorized-keys line. */
    fun keyAlgorithmLabel(publicKeyLine: String): String {
        return when (publicKeyLine.trim().substringBefore(' ')) {
            "ssh-ed25519" -> "ED25519"
            "ssh-rsa" -> "RSA"
            "ssh-dss" -> "DSA"
            "ecdsa-sha2-nistp256" -> "ECDSA P-256"
            "ecdsa-sha2-nistp384" -> "ECDSA P-384"
            "ecdsa-sha2-nistp521" -> "ECDSA P-521"
            else -> publicKeyLine.trim().substringBefore(' ').ifBlank { "Unknown" }
        }
    }

    /** OpenSSH SHA-256 fingerprint of the wire-encoded public key. */
    fun publicKeyFingerprint(publicKeyLine: String): String {
        val fields = publicKeyLine.trim().split(Regex("\\s+"))
        require(fields.size >= 2) { "That public key is incomplete" }
        val wireKey = Base64.getDecoder().decode(fields[1])
        val digest = MessageDigest.getInstance("SHA-256").digest(wireKey)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private const val RSA_KEY_BITS = 3072
    private const val PEM_LINE_LENGTH = 64
    private const val PKCS8_ITERATIONS = 100_000
    private const val PBKDF2_KEY_BITS = 256
    private const val PBKDF2_SALT_BYTES = 16
    private const val AES_IV_BYTES = 16
    private const val OPENSSH_BLOCK_BYTES = 8
    private const val OPENSSH_ENCRYPTED_BLOCK_BYTES = 16
    private const val OPENSSH_BCRYPT_ROUNDS = 16
    private const val ED25519_KEY_BYTES = 32
    private const val OPENSSH_SALT_BYTES = 16
    private const val OPENSSH_AES_KEY_BYTES = 32
    private const val OPENSSH_AES_IV_BYTES = 16
    private const val OPENSSH_PRIVATE_KEY = "OPENSSH PRIVATE KEY"
    private const val ENCRYPTED_PRIVATE_KEY = "ENCRYPTED PRIVATE KEY"

    private fun pemEncode(type: String, encoded: ByteArray): String {
        val body = Base64.getEncoder().encodeToString(encoded).chunked(PEM_LINE_LENGTH)
        return buildString {
            appendLine("-----BEGIN $type-----")
            body.forEach { appendLine(it) }
            appendLine("-----END $type-----")
        }
    }

    /**
     * sshj 0.40 reads Ed25519 from OpenSSH v1, while its PKCS#8 reader does not
     * recognise the Ed25519 OID emitted by the BC provider. Keep the modern key
     * in the format sshj itself uses for this algorithm. The encrypted branch
     * follows OpenSSH's bcrypt + AES-256-CTR container format.
     */
    private fun generateOpenSshEd25519PrivateKeyPem(
        privateKey: ByteArray,
        publicKey: ByteArray,
        passphrase: CharArray?,
    ): String {
        val seed = privateKey.takeLast(ED25519_KEY_BYTES).toByteArray()
        val public = publicKey.takeLast(ED25519_KEY_BYTES).toByteArray()
        val publicBlob = sshBlob(
            sshString("ssh-ed25519".toByteArray(Charsets.US_ASCII)),
            sshString(public),
        )
        val check = SecureRandom().nextInt()
        val privateBody = ByteArrayOutputStream().apply {
            writeInt(check)
            writeInt(check)
            write(sshString("ssh-ed25519".toByteArray(Charsets.US_ASCII)))
            write(sshString(public))
            write(sshString(seed + public))
            write(sshString("pocketshell".toByteArray(Charsets.UTF_8)))
        }
        val protectedPassphrase = passphrase?.takeIf { it.isNotEmpty() }
        val encrypted = protectedPassphrase != null
        val blockSize = if (encrypted) OPENSSH_ENCRYPTED_BLOCK_BYTES else OPENSSH_BLOCK_BYTES
        var padding = blockSize - (privateBody.size() % blockSize)
        if (padding == 0) padding = blockSize
        repeat(padding) { index -> privateBody.write(index + 1) }
        val encryption = protectedPassphrase?.let {
            encryptOpenSshPrivateBody(privateBody.toByteArray(), it)
        }
        val privatePayload = encryption?.second ?: privateBody.toByteArray()

        val outer = ByteArrayOutputStream().apply {
            write("openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII))
            write(sshString((if (encrypted) "aes256-ctr" else "none").toByteArray(Charsets.US_ASCII)))
            write(sshString((if (encrypted) "bcrypt" else "none").toByteArray(Charsets.US_ASCII)))
            write(
                sshString(
                    if (encrypted) {
                        val salt = requireNotNull(encryption).first
                        sshBlob(sshString(salt), uint32(OPENSSH_BCRYPT_ROUNDS))
                    } else {
                        ByteArray(0)
                    },
                ),
            )
            writeInt(1)
            write(sshString(publicBlob))
            write(sshString(privatePayload))
        }
        return pemEncode("OPENSSH PRIVATE KEY", outer.toByteArray())
    }

    private fun encryptOpenSshPrivateBody(
        body: ByteArray,
        passphrase: CharArray,
    ): Pair<ByteArray, ByteArray> {
        val salt = ByteArray(OPENSSH_SALT_BYTES).also(SecureRandom()::nextBytes)
        val password = PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(passphrase)
        val keyAndIv = ByteArray(OPENSSH_AES_KEY_BYTES + OPENSSH_AES_IV_BYTES)
        try {
            BCrypt().pbkdf(password, salt, OPENSSH_BCRYPT_ROUNDS, keyAndIv)
        } finally {
            password.fill(0)
        }
        return try {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding", BouncyCastleProvider.PROVIDER_NAME)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyAndIv.copyOfRange(0, OPENSSH_AES_KEY_BYTES), "AES"),
                IvParameterSpec(keyAndIv.copyOfRange(OPENSSH_AES_KEY_BYTES, keyAndIv.size)),
            )
            salt to cipher.doFinal(body)
        } finally {
            keyAndIv.fill(0)
        }
    }

    private fun sshBlob(vararg pieces: ByteArray): ByteArray =
        pieces.fold(ByteArrayOutputStream()) { output, piece ->
            output.write(piece)
            output
        }.toByteArray()

    private fun sshString(value: ByteArray): ByteArray =
        sshBlob(uint32(value.size), value)

    private fun uint32(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(uint32(value))
    }

    /**
     * Standard PBES2/PBKDF2/AES-256-CBC PKCS#8 encryption using bcprov APIs
     * available on the Android app classpath. This avoids making the app depend
     * on bcpkix's desktop-only PEM writer while retaining an sshj-readable file.
     */
    private fun encryptPkcs8(encodedPrivateKey: ByteArray, passphrase: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(PBKDF2_SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(AES_IV_BYTES).also(random::nextBytes)
        val passwordBytes = PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(passphrase)
        val generator = PKCS5S2ParametersGenerator(SHA256Digest())
        generator.init(passwordBytes, salt, PKCS8_ITERATIONS)
        val derived = (generator.generateDerivedParameters(PBKDF2_KEY_BITS) as KeyParameter).key
        passwordBytes.fill(0)
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding", BouncyCastleProvider.PROVIDER_NAME)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(derived, "AES"),
                IvParameterSpec(iv),
            )
            val encrypted = cipher.doFinal(encodedPrivateKey)
            val hmacSha256 = AlgorithmIdentifier(PKCSObjectIdentifiers.id_hmacWithSHA256, DERNull.INSTANCE)
            val parameters = PBES2Parameters(
                KeyDerivationFunc(
                    PKCSObjectIdentifiers.id_PBKDF2,
                    PBKDF2Params(salt, PKCS8_ITERATIONS, PBKDF2_KEY_BITS / 8, hmacSha256),
                ),
                EncryptionScheme(NISTObjectIdentifiers.id_aes256_CBC, DEROctetString(iv)),
            )
            EncryptedPrivateKeyInfo(
                AlgorithmIdentifier(PKCSObjectIdentifiers.id_PBES2, parameters),
                encrypted,
            ).encoded
        } finally {
            derived.fill(0)
        }
    }

    private fun isEncryptedOpenSshKey(lines: List<String>): Boolean {
        val begin = lines.indexOf("-----BEGIN OPENSSH PRIVATE KEY-----")
        val end = lines.indexOf("-----END OPENSSH PRIVATE KEY-----")
        if (begin < 0 || end <= begin) return false
        val body = lines.subList(begin + 1, end).joinToString("")
        val decoded = runCatching { Base64.getMimeDecoder().decode(body) }.getOrNull() ?: return false
        val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        if (decoded.size < magic.size || !decoded.copyOfRange(0, magic.size).contentEquals(magic)) {
            return false
        }
        val cipher = decoded.readOpenSshString(magic.size) ?: return false
        val kdf = decoded.readOpenSshString(cipher.nextOffset) ?: return false
        // An unencrypted OpenSSH key names both as "none"; anything else means
        // the private section is wrapped in a passphrase-derived cipher.
        return cipher.value != "none" || kdf.value != "none"
    }

    private fun isClassicEncrypted(headers: List<String>): Boolean =
        headers.any { it.equals("Proc-Type: 4,ENCRYPTED", ignoreCase = true) } ||
            headers.any { it.startsWith("DEK-Info:", ignoreCase = true) }

    private fun validateClassicCiphertext(decoded: ByteArray, headers: List<String>) {
        val cipher = headers.firstOrNull { it.startsWith("DEK-Info:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.substringBefore(',')
            ?.trim()
            ?.uppercase()
        val blockSize = when {
            cipher?.contains("AES") == true -> 16
            cipher?.contains("DES") == true || cipher?.contains("BLOWFISH") == true -> 8
            else -> 8
        }
        require(decoded.isNotEmpty() && decoded.size >= blockSize && decoded.size % blockSize == 0)
    }

    private fun validateOpenSshContainer(decoded: ByteArray) {
        val magic = "openssh-key-v1 ".toByteArray(Charsets.US_ASCII)
        require(decoded.size >= magic.size && decoded.copyOfRange(0, magic.size).contentEquals(magic))
        var offset = magic.size
        val cipher = decoded.readOpenSshBytes(offset).also { offset = it.nextOffset }
        val kdf = decoded.readOpenSshBytes(offset).also { offset = it.nextOffset }
        val options = decoded.readOpenSshBytes(offset).also { offset = it.nextOffset }
        require(offset + 4 <= decoded.size)
        val keyCount = ByteBuffer.wrap(decoded, offset, 4).int
        offset += 4
        require(keyCount > 0)
        repeat(keyCount) {
            val publicKey = decoded.readOpenSshBytes(offset)
            require(publicKey.bytes.isNotEmpty())
            offset = publicKey.nextOffset
        }
        val privatePayload = decoded.readOpenSshBytes(offset)
        require(privatePayload.bytes.isNotEmpty())
        offset = privatePayload.nextOffset
        require(offset == decoded.size)

        val cipherName = cipher.bytes.toString(Charsets.US_ASCII)
        val kdfName = kdf.bytes.toString(Charsets.US_ASCII)
        require((cipherName == "none") == (kdfName == "none"))
        if (cipherName == "none") {
            require(options.bytes.isEmpty())
            require(privatePayload.bytes.size % OPENSSH_BLOCK_BYTES == 0)
        } else {
            require(options.bytes.isNotEmpty())
            require(privatePayload.bytes.size % OPENSSH_ENCRYPTED_BLOCK_BYTES == 0)
        }
    }

    private data class PemBlock(
        val type: String,
        val headers: List<String>,
        val body: String,
    ) {
        fun decodeBody(): ByteArray = Base64.getDecoder().decode(body)
    }

    private fun parsePem(content: String): PemBlock {
        val lines = content.trim().lines().map(String::trim)
        require(lines.isNotEmpty())
        val begin = lines.first().removePrefix("-----BEGIN ").removeSuffix("-----")
        require(begin != lines.first() && begin.contains("PRIVATE KEY"))
        val end = "-----END $begin-----"
        require(lines.last() == end)
        val bodyLines = lines.subList(1, lines.lastIndex)
        val headers = bodyLines.filter {
            it.startsWith("Proc-Type:", ignoreCase = true) ||
                it.startsWith("DEK-Info:", ignoreCase = true)
        }
        val encoded = bodyLines.filterNot { it in headers }.joinToString("")
        require(encoded.isNotBlank())
        return PemBlock(type = begin, headers = headers, body = encoded)
    }

    private data class OpenSshString(val value: String, val nextOffset: Int)

    private fun loadKeyProvider(
        client: SSHClient,
        content: String,
        passphrase: CharArray?,
    ): KeyProvider = client.loadKeys(
        content,
        null as String?,
        passphrase?.let(PasswordUtils::createOneOff),
    )

    /**
     * OpenSSH v1 stores the public half in clear text before the private
     * payload. Reading that record directly avoids making an unencrypted key
     * detail wait on sshj's private-key parser on Android. Encrypted OpenSSH
     * keys and PEM/PKCS#8 shapes still use sshj below, after the caller has
     * supplied the passphrase where one is required.
     */
    private fun unencryptedOpenSshPublicKeyLine(content: String): String? {
        val pem = runCatching { parsePem(content) }.getOrNull()
            ?.takeIf { it.type == OPENSSH_PRIVATE_KEY }
            ?: return null
        val decoded = runCatching { pem.decodeBody() }.getOrNull() ?: return null
        val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        if (decoded.size < magic.size ||
            !decoded.copyOfRange(0, magic.size).contentEquals(magic)
        ) {
            return null
        }

        var offset = magic.size
        val cipher = decoded.readOpenSshBytesOrNull(offset) ?: return null
        offset = cipher.nextOffset
        val kdf = decoded.readOpenSshBytesOrNull(offset) ?: return null
        offset = kdf.nextOffset
        val options = decoded.readOpenSshBytesOrNull(offset) ?: return null
        offset = options.nextOffset
        if (cipher.bytes.toString(Charsets.US_ASCII) != "none" ||
            kdf.bytes.toString(Charsets.US_ASCII) != "none" ||
            options.bytes.isNotEmpty() ||
            offset + 4 > decoded.size
        ) {
            return null
        }

        val keyCount = ByteBuffer.wrap(decoded, offset, 4).int
        offset += 4
        if (keyCount <= 0) return null
        val publicKey = decoded.readOpenSshBytesOrNull(offset) ?: return null
        val type = publicKey.bytes.readOpenSshBytesOrNull(0) ?: return null
        val typeName = type.bytes.toString(Charsets.US_ASCII)
        if (typeName.isBlank()) return null
        return "$typeName ${Base64.getEncoder().encodeToString(publicKey.bytes)} pocketshell"
    }

    private fun ensureBouncyCastle() {
        synchronized(Security::class.java) {
            val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            if (provider?.javaClass?.name == BouncyCastleProvider::class.java.name) return
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    private fun ByteArray.readOpenSshString(offset: Int): OpenSshString? {
        val bytes = readOpenSshBytesOrNull(offset) ?: return null
        return OpenSshString(
            value = bytes.bytes.toString(Charsets.US_ASCII),
            nextOffset = bytes.nextOffset,
        )
    }

    private data class OpenSshBytes(val bytes: ByteArray, val nextOffset: Int)

    private fun ByteArray.readOpenSshBytes(offset: Int): OpenSshBytes =
        requireNotNull(readOpenSshBytesOrNull(offset))

    private fun ByteArray.readOpenSshBytesOrNull(offset: Int): OpenSshBytes? {
        if (offset < 0 || offset + 4 > size) return null
        val length = ByteBuffer.wrap(this, offset, 4).int
        if (length < 0 || offset + 4 + length > size) return null
        val start = offset + 4
        return OpenSshBytes(
            bytes = copyOfRange(start, start + length),
            nextOffset = start + length,
        )
    }
}
