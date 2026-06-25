package com.pocketshell.core.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.IOException
import java.security.Security
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

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

    /**
     * Connect to `[host]:[port]` as [user] and authenticate with [key]
     * (optionally encrypted with [passphrase]).
     *
     * Returns a [Result] wrapping either a live [SshSession] or an
     * [SshException] explaining the failure (DNS, transport, auth, ...).
     * Ordinary connect/auth failures land in `Result.failure`. Coroutine
     * cancellation is preserved as cancellation; if the caller cancels before
     * the live [SshSession] is delivered, the underlying client is
     * disconnected.
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
    ): Result<SshSession> = connect(
        host = host,
        port = port,
        user = user,
        key = key,
        passphrase = passphrase,
        knownHosts = knownHosts,
        timeoutMs = timeoutMs,
        connector = RealSshConnector,
    )

    internal suspend fun <C : Any> connect(
        host: String,
        port: Int,
        user: String,
        key: SshKey,
        passphrase: CharArray? = null,
        knownHosts: KnownHostsPolicy = KnownHostsPolicy.AcceptAll,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        connector: SshConnector<C>,
    ): Result<SshSession> = coroutineScope {
        // Issue #173 round-2: install the process-wide
        // UncaughtExceptionHandler guard BEFORE we spawn any sshj
        // background threads. sshj's `SSHClient.connect` starts the
        // `sshj-Reader` JVM thread (the keepalive thread is no longer
        // started — issue #847 removed the racing background writer);
        // if that thread dies with a transport-level exception (the
        // CI-reproducible "Broken transport; encountered EOF" path
        // triggered when the OS tears the TCP socket down underneath
        // a backgrounded app) the JVM default handler would terminate
        // the whole process. The guard intercepts only sshj-named
        // threads with transport-family exceptions and routes the
        // observable signal through the existing Kotlin coroutine
        // disconnect machinery instead. Idempotent — only the first
        // call wraps a real handler. See [SshjTransportThreadGuard].
        SshjTransportThreadGuard.installIfNecessary()
        suspendCancellableCoroutine { continuation ->
            val liveClient = AtomicReference<C?>(null)
            var worker: Job? = null

            fun disconnectClient() {
                liveClient.getAndSet(null)?.let { client ->
                    runCatching { connector.disconnect(client) }
                }
            }

            continuation.invokeOnCancellation {
                disconnectClient()
                worker?.cancel()
            }

            worker = launch(Dispatchers.IO) {
                try {
                    val client = connector.createClient()
                    liveClient.set(client)
                    connector.applyKnownHostsPolicy(client, knownHosts)
                    connector.connect(
                        client,
                        host,
                        port,
                        timeoutMs,
                    )
                    connector.authenticate(client, user, key, passphrase)

                    val session = connector.toSession(client)
                    val result = Result.success(session)
                    if (continuation.isActive) {
                        liveClient.set(null)
                        continuation.resume(result) { _, undeliveredResult, _ ->
                            undeliveredResult.getOrNull()?.close()
                        }
                    } else {
                        session.close()
                    }
                } catch (e: CancellationException) {
                    disconnectClient()
                    continuation.cancel(e)
                } catch (e: Throwable) {
                    disconnectClient()
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(wrap(e, host, port, user)))
                    }
                }
            }
        }
    }

    /**
     * Create an [SSHClient] with the same Android-compatible crypto provider
     * setup used by [connect]. Callers that need sshj primitives outside the
     * public [SshSession] surface still get the core transport fixes.
     */
    @JvmStatic
    public fun createClient(): SSHClient {
        // Issue #173 round-2: same rationale as [connect] — install
        // the guard before any sshj thread can start.
        SshjTransportThreadGuard.installIfNecessary()
        return SSHClient(createSshConfig())
    }

    private fun createSshConfig(): DefaultConfig {
        ensureBouncyCastleProvider()
        // Issue #847 / #766 slice 1 — NO background keepalive writer.
        //
        // PocketShell used to switch the provider to
        // `KeepAliveProvider.KEEP_ALIVE` (#548) so a `sshj-KeepAliveRunner`
        // thread sent `keepalive@openssh.com` every 15s for dead-peer
        // detection. That background thread is a SECOND writer on the live
        // transport: its periodic global-request could land in a KEX/rekey
        // window and desync the encoder sequence counter, so the server logged
        // `ssh_dispatch_run_fatal: ... Connection corrupted` ~one keepalive
        // interval after the handshake — the real cause of the v0.4.10/v0.4.11
        // "loading tree" connect hang (upstream sshj #910). The
        // single-transport-writer rule (see [TransportDispatcher]) cannot
        // tolerate an un-ownable background writer, so the keepalive is removed
        // entirely (D22 hard-cut).
        //
        // Dead-peer detection is preserved by the FOREGROUND single-writer
        // `LivenessProbe` (core-connection, #792 slice D), which pings the live
        // `-CC` control channel through the same serialised dispatch path and
        // drives the existing reconnect machinery — no second transport writer.
        // NAT warmth is moot under D21 (no background work: the app backgrounds
        // and tmux holds state remotely, reconnecting on next foreground).
        //
        // We leave sshj's DefaultConfig keepalive provider untouched and simply
        // never enable an interval: `KeepAlive.isEnabled()` is
        // `keepAliveInterval > 0`, and `SSHClient.onConnect()` only `start()`s
        // the thread when enabled — so with no interval set, no keepalive thread
        // is ever started.
        return DefaultConfig()
    }

    /**
     * Issue #927: clear the connect-phase socket read timeout (`SO_TIMEOUT`) on a
     * now-live client so the long-lived `-CC` control channel is not governed by a
     * connect-phase read deadline. sshj's `SSHClient.timeout` maps straight to
     * `Socket.setSoTimeout`; `0` means an infinite read timeout (block until bytes
     * arrive), which is the correct posture for an idle-but-alive control channel.
     * Dead-peer detection is the foreground single-writer `LivenessProbe`'s job,
     * not a socket read deadline. Visible for the `KeepAliveConfigTest` sibling so
     * the scoping is asserted without opening a real socket.
     */
    @JvmStatic
    internal fun clearLiveChannelReadTimeout(client: SSHClient) {
        client.timeout = 0
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

    internal interface SshConnector<C : Any> {
        fun createClient(): C
        fun applyKnownHostsPolicy(client: C, policy: KnownHostsPolicy)
        suspend fun connect(
            client: C,
            host: String,
            port: Int,
            timeoutMs: Int,
        )
        suspend fun authenticate(client: C, user: String, key: SshKey, passphrase: CharArray?)
        fun toSession(client: C): SshSession
        fun disconnect(client: C)
    }

    private object RealSshConnector : SshConnector<SSHClient> {
        override fun createClient(): SSHClient = SSHClient(createSshConfig())

        override fun applyKnownHostsPolicy(client: SSHClient, policy: KnownHostsPolicy) {
            SshConnection.applyKnownHostsPolicy(client, policy)
        }

        override suspend fun connect(
            client: SSHClient,
            host: String,
            port: Int,
            timeoutMs: Int,
        ) {
            client.connectTimeout = timeoutMs
            // Issue #927: scope the socket read timeout (`SO_TIMEOUT`) to the
            // connect + auth phase ONLY. `SSHClient.timeout` maps to
            // `Socket.setSoTimeout`, i.e. the BLOCKING-READ timeout the sshj
            // `Reader` thread arms on every `InputStream.read`. Keeping it at the
            // connect timeout (30s) is fine during the bounded handshake, but on
            // the long-lived `-CC` control channel it re-arms a 30s read deadline
            // on an idle-but-alive link. (sshj's Reader loops on
            // `SocketTimeoutException` rather than dying, so this did not by
            // itself tear the transport — but a connect-phase read deadline has no
            // business governing the live channel, where dead-peer detection is
            // the foreground `LivenessProbe`'s job.) Set it for connect/auth here,
            // then clear it post-auth in [clearLiveChannelReadTimeout].
            client.timeout = timeoutMs

            // Issue #847: NO keepalive interval is set — the background
            // `sshj-KeepAliveRunner` writer is removed (see [createSshConfig]).
            // `SSHClient.onConnect()` (run synchronously inside `connect()`)
            // only starts the keepalive thread when `KeepAlive.isEnabled()` is
            // true, i.e. `keepAliveInterval > 0`; leaving it at the default 0
            // means no second transport writer is ever spawned. Dead-peer
            // detection is the foreground single-writer `LivenessProbe`'s job.
            client.connect(host, port)
        }

        override suspend fun authenticate(
            client: SSHClient,
            user: String,
            key: SshKey,
            passphrase: CharArray?,
        ) {
            val keyProvider = loadKeyProvider(client, key, passphrase)
            client.authPublickey(user, keyProvider)
            // Issue #927: auth is the last bounded connect-phase read. Now that
            // the transport is live and will carry the long-lived `-CC` channel,
            // clear the connect-phase socket read timeout so a normal idle gap on
            // an alive link never arms a `SocketTimeoutException` on the live
            // reader. Dead-peer detection is the foreground `LivenessProbe`'s job.
            clearLiveChannelReadTimeout(client)
        }

        override fun toSession(client: SSHClient): SshSession = RealSshSession(client)

        override fun disconnect(client: SSHClient) {
            client.disconnect()
        }
    }
}
