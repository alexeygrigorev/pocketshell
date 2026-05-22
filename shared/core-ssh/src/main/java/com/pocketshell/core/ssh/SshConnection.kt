package com.pocketshell.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.IOException
import java.security.Security

/**
 * Entry point for the `core-ssh` module.
 *
 * Holds the connect-and-authenticate logic in one place. Exposes a stateless
 * [connect] factory:
 *
 * ```
 * SshConnection.connect(host, port, user, key, passphrase): Result<SshSession>
 * ```
 */
public object SshConnection {

    /** Default TCP + auth timeouts, in milliseconds. */
    internal const val DEFAULT_TIMEOUT_MS: Int = 30_000

    /** Default keep-alive interval in seconds. */
    internal const val DEFAULT_KEEP_ALIVE_SECONDS: Int = 15

    /**
     * Connect to `[host]:[port]` as [user] and authenticate with [key]
     * (optionally encrypted with [passphrase]).
     *
     * Returns a [Result] wrapping either a live [SshSession] or an
     * [SshException] explaining the failure (DNS, transport, auth, ...).
     * Never throws — all paths land in `Result`.
     *
     * The host key policy defaults to [KnownHostsPolicy.AcceptAll]. Production
     * callers should pass [KnownHostsPolicy.KnownHostsFile] explicitly.
     */
    @JvmOverloads
    @JvmStatic
    public suspend fun connect(
        host: String,
        port: Int,
        user: String,
        key: SshKey,
        passphrase: CharArray? = null,
        knownHosts: KnownHostsPolicy = KnownHostsPolicy.AcceptAll,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        keepAliveSeconds: Int = DEFAULT_KEEP_ALIVE_SECONDS,
    ): Result<SshSession> = withContext(Dispatchers.IO) {
        val client = SSHClient(createSshConfig())
        try {
            applyKnownHostsPolicy(client, knownHosts)
            client.connectTimeout = timeoutMs
            client.timeout = timeoutMs
            client.connect(host, port)
            client.connection.keepAlive.keepAliveInterval = keepAliveSeconds

            val keyProvider = loadKeyProvider(client, key, passphrase)
            client.authPublickey(user, keyProvider)

            Result.success(RealSshSession(client) as SshSession)
        } catch (e: Throwable) {
            // Best-effort cleanup on the partially-initialised client.
            runCatching { client.disconnect() }
            Result.failure(wrap(e, host, port, user))
        }
    }

    private fun createSshConfig(): DefaultConfig {
        ensureBouncyCastleProvider()
        return DefaultConfig()
    }

    private fun ensureBouncyCastleProvider() {
        synchronized(Security::class.java) {
            val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            if (provider?.javaClass?.name == BouncyCastleProvider::class.java.name) {
                return
            }

            // Android ships a stripped provider named "BC" that can miss
            // algorithms sshj negotiates with OpenSSH, notably X25519/EC.
            // Replace it with the bundled BouncyCastle provider before
            // sshj builds its algorithm list.
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    private fun applyKnownHostsPolicy(client: SSHClient, policy: KnownHostsPolicy) {
        when (policy) {
            is KnownHostsPolicy.AcceptAll ->
                client.addHostKeyVerifier(PromiscuousVerifier())
            is KnownHostsPolicy.KnownHostsFile ->
                client.loadKnownHosts(policy.file)
        }
    }

    private fun loadKeyProvider(
        client: SSHClient,
        key: SshKey,
        passphrase: CharArray?,
    ): KeyProvider {
        val passwordFinder = passphrase?.let { PasswordUtils.createOneOff(it) }
        // SSHClient.loadKeys uses sshj's KeyProviderUtil to detect the file
        // format (classic PEM "BEGIN RSA PRIVATE KEY", new OpenSSH
        // "BEGIN OPENSSH PRIVATE KEY", PKCS8, PuTTY) and pick the right
        // provider implementation. Doing it manually with OpenSSHKeyFile
        // works for legacy keys but trips on the v1 "OPENSSH PRIVATE KEY"
        // format ed25519 uses by default — so always go through loadKeys.
        val provider = when (key) {
            is SshKey.Path -> {
                if (!key.file.exists()) {
                    throw IOException("Private key file not found: ${key.file.absolutePath}")
                }
                if (passwordFinder != null) {
                    client.loadKeys(key.file.absolutePath, passwordFinder)
                } else {
                    client.loadKeys(key.file.absolutePath)
                }
            }
            is SshKey.Pem -> {
                // Three-arg loadKeys takes private PEM, optional public PEM,
                // and an optional password finder. We don't carry a separate
                // public key — sshj derives it from the private one for the
                // formats we support.
                client.loadKeys(key.content, null, passwordFinder)
            }
        }
        // Touch the provider so an unreadable/encrypted-without-passphrase
        // key fails here, not deep inside sshj's auth handshake.
        provider.public
        return provider
    }

    private fun wrap(t: Throwable, host: String, port: Int, user: String): SshException = when (t) {
        is SshException -> t
        else -> SshException(
            "SSH connect to $user@$host:$port failed: ${t.javaClass.simpleName}: ${t.message}",
            t,
        )
    }
}
