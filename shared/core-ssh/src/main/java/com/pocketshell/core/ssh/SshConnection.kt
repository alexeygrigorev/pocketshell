package com.pocketshell.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.IOException

/**
 * Entry point for the `core-ssh` module.
 *
 * Holds the connect-and-authenticate logic in one place. The original
 * `ssh-auto-forward-android` had this as an instance class with a stored
 * config; we keep the same shape so the migration is a like-for-like swap,
 * but expose a stateless [connect] factory on the companion to match the
 * public API shape required by issue #4:
 *
 * ```
 * SshConnection.connect(host, port, user, key, passphrase): Result<SshSession>
 * ```
 *
 * Per [D3](../../../../../../../docs/decisions.md) we swap JSch for sshj
 * during the extraction. The JSch equivalents map as follows:
 *
 * - `JSch()` + `addIdentity(path)` → [SSHClient] + [SSHClient.loadKeys]
 * - `Session.connect()` → [SSHClient.connect] + [SSHClient.authPublickey]
 * - `Session.setConfig("StrictHostKeyChecking", "no")` → [KnownHostsPolicy.AcceptAll]
 * - `Session.openChannel("exec")` → [SSHClient.startSession] + `Session.exec`
 *   (wired in [RealSshSession.exec])
 */
public object SshConnection {

    /**
     * Default TCP + auth timeouts, in milliseconds. Same as the previous
     * `ssh-auto-forward-android` value to keep behavioural parity.
     */
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
     * The host key policy defaults to [KnownHostsPolicy.AcceptAll] for
     * backwards compatibility with `ssh-auto-forward-android`. Production
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
        val client = SSHClient(DefaultConfig())
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
        // format ed25519 uses by default — which is exactly the case the
        // D3 swap-to-sshj acceptance criterion calls out.
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
