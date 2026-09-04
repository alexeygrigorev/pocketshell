package com.pocketshell.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordFinder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * Resolves an [AuthMaterial] *reference* to actual credential material.
 *
 * Deferred seam (rewrite task T-2): `core-storage`'s key table / encrypted
 * preference store are not wired to the rewrite yet, so the factory does not
 * know how to turn a row id into a private key. The app layer injects an
 * implementation backed by the real secret store when that task lands;
 * integration tests inject one backed by the disposable Docker fixture key.
 * Keeping this a constructor dependency (instead of an inline TODO) means the
 * dial path is complete and testable today and only this seam swaps later.
 */
interface AuthSecretResolver {
    /**
     * Returns the private key for `ssh_keys` row [keyId] as OpenSSH/PEM text,
     * already decrypted (passphrase handling belongs to the secret store, not
     * the transport).
     */
    suspend fun resolvePrivateKeyPem(keyId: Long): String

    /** Returns the password behind an [AuthMaterial.Password.secretRef] handle. */
    suspend fun resolvePassword(secretRef: String): CharArray
}

/**
 * The single production dial site (rewrite task T-2): dials sshj, decides
 * host-key trust through the injected [TrustStore], authenticates, and hands
 * back a live [RealHostConnection].
 *
 * ## Bounded, cancellable dial
 *
 * sshj's `SSHClient.connect` parks in a blocking JDK socket read during the
 * handshake, which a plain coroutine `withTimeout` CANNOT interrupt — the
 * timeout would cancel the waiting coroutine while the half-open socket (and
 * the dial thread) stayed wedged. So the blocking dial runs as a job on the
 * factory-owned [dialScope], and the caller waits on it under the wall-clock
 * bound; on timeout or caller cancellation, [abandonDial] DISCONNECTS the
 * half-open client from a separate dispatcher thread, which closes the socket
 * and unparks the blocked read. (Same idea as the old core-ssh
 * `SshLeaseManager` connect bound — idea only; no lease/refcount machinery
 * here, every [connect] call dials a fresh client.)
 *
 * The wall-clock wait runs on [ioDispatcher] so the timeout uses REAL time
 * even when the caller is a virtual-time test scheduler (a virtual clock
 * auto-advancing past the bound while a real handshake is in flight would trip
 * it spuriously).
 *
 * ## Host-key trust
 *
 * Every client gets exactly one verifier, which computes the presented key's
 * `SHA256:...` fingerprint and asks [TrustStore.evaluate]. Anything but
 * [TrustDecision.Trusted] fails the handshake (sshj aborts with
 * HOST_KEY_NOT_VERIFIABLE — the dial can never silently proceed) and surfaces
 * as [ConnectResult.NeedsTrust] whose `retry` re-runs the FULL dial with a
 * fresh client after the caller has recorded a decision.
 *
 * ## Keep-alive
 *
 * Per the rewrite plan (§C.1), sshj's `KeepAliveProvider.KEEP_ALIVE` heartbeat
 * is enabled at [keepAliveIntervalSec] (default 15 s). NOTE the history: old
 * issue #847 removed exactly this provider because its background writer
 * thread could race a rekey window and corrupt the transport against OpenSSH
 * (upstream sshj #910). The plan re-adopts it for the rewrite's much simpler
 * single-connection model; the integration suite carries an idle-past-the-
 * interval canary test so a resurrection of #847 turns red in CI, not
 * on-device. The interval is injectable so it can be tuned (or disabled with
 * 0) without touching this file.
 */
class RealHostConnectionFactory(
    private val secrets: AuthSecretResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val keepAliveIntervalSec: Int = DEFAULT_KEEP_ALIVE_INTERVAL_SEC,
) : HostConnectionFactory {

    /**
     * Owns in-flight blocking dials. Supervisor: an abandoned dial that later
     * fails (because we yanked its socket) completes its async exceptionally
     * without cancelling the scope or crashing anything.
     */
    private val dialScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    override suspend fun connect(target: HostTarget, trust: TrustStore): ConnectResult {
        val resolvedAuth = try {
            resolveAuth(target.auth)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return ConnectResult.Failed(
                "Could not resolve credentials for ${target.username}@${target.hostname}: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }

        val client = newClient()
        val verifier = TrustDecisionVerifier(target, trust)
        client.addHostKeyVerifier(verifier)

        val dial = dialScope.async { blockingDial(client, target, resolvedAuth) }
        val outcome: DialOutcome = try {
            withContext(ioDispatcher) {
                withTimeoutOrNull(connectTimeoutMs) {
                    dial.await()
                    DialOutcome.Success
                } ?: DialOutcome.TimedOut
            }
        } catch (cancelled: CancellationException) {
            abandonDial(client, dial)
            throw cancelled
        } catch (failure: Throwable) {
            DialOutcome.Failed(failure)
        }

        return when (outcome) {
            DialOutcome.Success ->
                ConnectResult.Connected(RealHostConnection(target, client, ioDispatcher))

            DialOutcome.TimedOut -> {
                abandonDial(client, dial)
                ConnectResult.Failed(
                    "Connect to ${target.username}@${target.hostname}:${target.port} " +
                        "timed out after ${connectTimeoutMs}ms",
                    null,
                )
            }

            is DialOutcome.Failed -> {
                // blockingDial already disconnected its client best-effort.
                val decision = verifier.decision()
                if (decision != null && decision !is TrustDecision.Trusted) {
                    // Not a failure: the host key needs a user decision. The
                    // retry dials from scratch (fresh SSHClient, fresh
                    // verifier) so a recordTrusted() in between is re-evaluated.
                    ConnectResult.NeedsTrust(
                        decision = decision,
                        retry = { connect(target, trust) },
                    )
                } else {
                    ConnectResult.Failed(
                        "Connect to ${target.username}@${target.hostname}:${target.port} failed: " +
                            (outcome.cause.message ?: outcome.cause.javaClass.simpleName),
                        outcome.cause,
                    )
                }
            }
        }
    }

    /** Runs on [dialScope]: the blocking sshj connect + auth. Cleans up its own failures. */
    private fun blockingDial(client: SSHClient, target: HostTarget, auth: ResolvedAuth) {
        try {
            client.connectTimeout = connectTimeoutMs.toInt()
            // Connect-phase SO_TIMEOUT: bounds every blocking handshake/auth
            // read. Cleared once live (below) so the long-lived transport is
            // never governed by a connect-phase read deadline (old #927 lesson).
            client.timeout = connectTimeoutMs.toInt()
            client.connect(target.hostname, target.port)
            when (auth) {
                is ResolvedAuth.PrivateKey ->
                    client.authPublickey(
                        target.username,
                        client.loadKeys(auth.pem, null as String?, null as PasswordFinder?),
                    )

                is ResolvedAuth.Password ->
                    client.authPassword(target.username, auth.password)
            }
            client.timeout = 0
        } catch (failure: Throwable) {
            runCatching { client.disconnect() }
            throw failure
        }
    }

    /**
     * Gives up on an in-flight dial: disconnecting the client closes the
     * socket, which unparks the blocking handshake read so the dial job dies
     * promptly instead of leaking a wedged thread. Runs on [dialScope] so the
     * (blocking) disconnect never runs on the caller's resume path.
     */
    private fun abandonDial(client: SSHClient, dial: Job) {
        dialScope.launch {
            runCatching { client.disconnect() }
            dial.cancel()
        }
    }

    private suspend fun resolveAuth(auth: AuthMaterial): ResolvedAuth = when (auth) {
        is AuthMaterial.KeyRef -> ResolvedAuth.PrivateKey(secrets.resolvePrivateKeyPem(auth.keyId))
        is AuthMaterial.Password -> ResolvedAuth.Password(secrets.resolvePassword(auth.secretRef))
    }

    private fun newClient(): SSHClient {
        ensureBouncyCastleProvider()
        val config = DefaultConfig()
        config.keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
        val client = SSHClient(config)
        // Must be set BEFORE connect: SSHClient.onConnect() starts the
        // keep-alive thread only when the interval is already > 0.
        client.connection.keepAlive.keepAliveInterval = keepAliveIntervalSec
        return client
    }

    private sealed interface ResolvedAuth {
        data class PrivateKey(val pem: String) : ResolvedAuth
        data class Password(val password: CharArray) : ResolvedAuth {
            override fun equals(other: Any?): Boolean =
                other is Password && password.contentEquals(other.password)

            override fun hashCode(): Int = password.contentHashCode()
        }
    }

    private sealed interface DialOutcome {
        data object Success : DialOutcome
        data object TimedOut : DialOutcome
        data class Failed(val cause: Throwable) : DialOutcome
    }

    /**
     * sshj [HostKeyVerifier] bridging to the suspend [TrustStore]. sshj calls
     * [verify] synchronously on its transport thread mid-handshake, so the
     * suspend evaluate runs under [runBlocking] — acceptable because evaluate
     * is a local lookup (in-memory/Room), never network.
     *
     * Returning `false` makes sshj abort the handshake with
     * HOST_KEY_NOT_VERIFIABLE; the recorded decision then drives
     * [ConnectResult.NeedsTrust].
     */
    private class TrustDecisionVerifier(
        private val target: HostTarget,
        private val trust: TrustStore,
    ) : HostKeyVerifier {

        private val lastDecision = AtomicReference<TrustDecision?>(null)

        fun decision(): TrustDecision? = lastDecision.get()

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val presented = sha256HostKeyFingerprint(key)
            val decision = runBlocking { trust.evaluate(target, presented) }
            lastDecision.set(decision)
            return decision is TrustDecision.Trusted
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 30_000
        const val DEFAULT_KEEP_ALIVE_INTERVAL_SEC: Int = 15

        /**
         * OpenSSH-style `SHA256:<base64-no-padding>` fingerprint of a host
         * key's wire encoding — the exact format [TrustStore] stores/compares.
         */
        internal fun sha256HostKeyFingerprint(key: PublicKey): String {
            val wireKey = Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = MessageDigest.getInstance("SHA-256").digest(wireKey)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }

        /**
         * Android ships a stripped "BC" provider that can miss algorithms sshj
         * negotiates with OpenSSH (notably X25519/EC); replace it with the
         * bundled full BouncyCastle before sshj builds its algorithm list.
         * No-op on a plain JVM where the full provider is already installed.
         */
        private fun ensureBouncyCastleProvider() {
            synchronized(Security::class.java) {
                val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
                if (provider?.javaClass?.name == BouncyCastleProvider::class.java.name) {
                    return
                }
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
        }
    }
}
