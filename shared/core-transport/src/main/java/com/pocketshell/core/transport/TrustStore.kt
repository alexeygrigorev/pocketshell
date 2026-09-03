package com.pocketshell.core.transport

/** Verdict on the host key a server presented during a handshake. */
sealed interface TrustDecision {
    /** The presented key matches the one already stored for this host. */
    data object Trusted : TrustDecision

    /** No key is stored for this host yet (first contact — TOFU prompt). */
    data class Unknown(val fingerprintSha256: String) : TrustDecision

    /**
     * A key is stored and it is NOT the one presented. Never auto-accepted:
     * the user must be shown both fingerprints.
     */
    data class Mismatch(
        val storedSha256: String,
        val presentedSha256: String,
    ) : TrustDecision
}

/**
 * Trust-on-first-use host-key store. Implemented over the persisted host-key
 * table; the transport calls it from its host-key verifier, so no dial can skip
 * the check.
 */
interface TrustStore {
    /** Classifies [presentedSha256] (a `SHA256:...`-style fingerprint) for [target]. */
    suspend fun evaluate(target: HostTarget, presentedSha256: String): TrustDecision

    /** Persists [sha256] as the trusted key for [target], replacing any previous one. */
    suspend fun recordTrusted(target: HostTarget, sha256: String)
}
