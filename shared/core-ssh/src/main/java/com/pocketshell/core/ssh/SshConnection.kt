package com.pocketshell.core.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
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

    /** Default keep-alive interval in seconds. */
    internal const val DEFAULT_KEEP_ALIVE_SECONDS: Int = 15

    /**
     * Number of consecutive unanswered keep-alive requests sshj tolerates
     * before it declares the peer lost and tears the transport down with
     * `CONNECTION_LOST`.
     *
     * With the [KeepAliveProvider.KEEP_ALIVE] provider ([KeepAliveRunner])
     * every interval sends a `keepalive@openssh.com` global request and
     * expects a reply; an unanswered request increments the miss counter,
     * and any reply resets it. The session is only dropped after this many
     * *consecutive* misses.
     *
     * Tolerance window = [DEFAULT_KEEP_ALIVE_SECONDS] x this count.
     * At 15s x 4 = 60s: a brief blip (metro tunnel / dead spot / quick
     * wifi<->cellular gap) of up to ~60s of no replies is ridden through
     * (the session freezes, then resumes when connectivity returns) instead
     * of dropping on the first missed packet. Only a sustained outage past
     * the window counts as a real disconnect. This mirrors the existing
     * 60s background-grace window (#450) so the two tolerances agree.
     */
    internal const val DEFAULT_MAX_ALIVE_COUNT: Int = 4

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
        keepAliveSeconds: Int = DEFAULT_KEEP_ALIVE_SECONDS,
    ): Result<SshSession> = connect(
        host = host,
        port = port,
        user = user,
        key = key,
        passphrase = passphrase,
        knownHosts = knownHosts,
        timeoutMs = timeoutMs,
        keepAliveSeconds = keepAliveSeconds,
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
        keepAliveSeconds: Int = DEFAULT_KEEP_ALIVE_SECONDS,
        connector: SshConnector<C>,
    ): Result<SshSession> = coroutineScope {
        // Issue #173 round-2: install the process-wide
        // UncaughtExceptionHandler guard BEFORE we spawn any sshj
        // background threads. sshj's `SSHClient.connect` starts the
        // `sshj-Reader` JVM thread (and `KeepAlive` once auth lands);
        // if those threads die with a transport-level exception (the
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
                        keepAliveSeconds,
                        DEFAULT_MAX_ALIVE_COUNT,
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
        return DefaultConfig().apply {
            // sshj's DefaultConfig ships KeepAliveProvider.HEARTBEAT, which
            // only *writes* SSH_MSG_IGNORE packets and never waits for a
            // reply — so it keeps a NAT mapping warm but can never detect a
            // dead peer. Switch to KeepAliveProvider.KEEP_ALIVE
            // (KeepAliveRunner): it sends keepalive@openssh.com global
            // requests, counts unanswered ones, and tears the transport down
            // with CONNECTION_LOST after `maxAliveCount` consecutive misses.
            //
            // This MUST be set on the Config before the SSHClient is
            // constructed: SSHClient's constructor builds its ConnectionImpl
            // using Config.getKeepAliveProvider(), so the KeepAlive instance
            // is fixed at construction time and can't be swapped afterwards.
            keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
        }
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
            keepAliveSeconds: Int,
            maxAliveCount: Int,
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
            keepAliveSeconds: Int,
            maxAliveCount: Int,
        ) {
            client.connectTimeout = timeoutMs
            client.timeout = timeoutMs

            // Configure keep-alive BEFORE connect(). sshj starts its
            // KeepAlive thread inside SSHClient.onConnect() — which runs
            // synchronously *during* client.connect() — and only if
            // KeepAlive.isEnabled() is already true at that moment.
            // isEnabled() returns (keepAliveInterval > 0), so the interval
            // has to be set before connect() runs. The previous code set it
            // *after* connect() returned, which meant onConnect() saw
            // interval == 0, isEnabled() == false, and the keep-alive thread
            // was NEVER started for the whole session. With no SSH-level
            // keep-alives, an idle NAT/server reaped the TCP and the
            // connection silently died (issue #548). The KeepAlive instance
            // already exists here: SSHClient's constructor built the
            // ConnectionImpl (and its KeepAlive) from the config provider, so
            // client.connection.keepAlive is reachable pre-connect.
            val keepAlive = client.connection.keepAlive
            keepAlive.keepAliveInterval = keepAliveSeconds
            // KeepAliveRunner adds maxAliveCount: how many consecutive
            // unanswered keepalive@openssh.com requests to tolerate before
            // declaring CONNECTION_LOST. The default provider (HEARTBEAT)
            // ignores this; KEEP_ALIVE (set in createSshConfig) honours it.
            if (keepAlive is KeepAliveRunner) {
                keepAlive.maxAliveCount = maxAliveCount
            }

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
        }

        override fun toSession(client: SSHClient): SshSession = RealSshSession(client)

        override fun disconnect(client: SSHClient) {
            client.disconnect()
        }
    }
}
