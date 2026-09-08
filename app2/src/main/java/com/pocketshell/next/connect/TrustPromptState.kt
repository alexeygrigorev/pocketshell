package com.pocketshell.next.connect

import com.pocketshell.core.transport.TrustDecision

/**
 * What the trust prompt shows (rewrite task M-3).
 *
 * A plain data holder, not a ViewModel: [ConnectionsRegistry] surfaces a
 * [com.pocketshell.core.transport.ConnectResult.NeedsTrust], the caller maps it
 * with [from], and the U-2 sheet renders it. Keeping the mapping here (and unit
 * tested) means the "first contact" vs "the key CHANGED" distinction — the one
 * thing a user must never see collapsed into a generic "accept?" — is decided
 * once, not re-derived per screen.
 */
data class TrustPromptState(
    val hostId: Long,
    /** The fingerprint the server just presented, e.g. `SHA256:abc...`. */
    val fingerprintSha256: String,
    /** True when a DIFFERENT key was already trusted for this host. */
    val isMismatch: Boolean,
    /** The previously trusted fingerprint; non-null exactly when [isMismatch]. */
    val previousFingerprintSha256: String?,
) {
    /**
     * The digest algorithm named by the transport fingerprint. The transport
     * contract carries `SHA256:<base64>` and deliberately does not carry the
     * server key type, so this label describes the value the user is copying.
     */
    val fingerprintAlgorithm: String
        get() = fingerprintAlgorithmLabel(fingerprintSha256)

    /** Same digest label for the old value in a changed-key comparison. */
    val previousFingerprintAlgorithm: String?
        get() = previousFingerprintSha256?.let(::fingerprintAlgorithmLabel)

    companion object {
        /**
         * Maps a [TrustDecision] to a prompt, or null for
         * [TrustDecision.Trusted] — an already-trusted key must never raise a
         * prompt, so "nothing to ask" is a first-class result rather than an
         * empty-string prompt.
         */
        fun from(hostId: Long, decision: TrustDecision): TrustPromptState? = when (decision) {
            is TrustDecision.Trusted -> null

            is TrustDecision.Unknown -> TrustPromptState(
                hostId = hostId,
                fingerprintSha256 = decision.fingerprintSha256,
                isMismatch = false,
                previousFingerprintSha256 = null,
            )

            is TrustDecision.Mismatch -> TrustPromptState(
                hostId = hostId,
                fingerprintSha256 = decision.presentedSha256,
                isMismatch = true,
                previousFingerprintSha256 = decision.storedSha256,
            )
        }
    }
}

internal fun fingerprintAlgorithmLabel(value: String): String {
    return when (value.substringBefore(':').uppercase()) {
        "SHA256" -> "SHA-256"
        "SHA512" -> "SHA-512"
        "SHA1" -> "SHA-1"
        else -> "Fingerprint digest"
    }
}
