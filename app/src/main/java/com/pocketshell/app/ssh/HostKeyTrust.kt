package com.pocketshell.app.ssh

import com.pocketshell.core.ssh.ChangedHostKeyException
import com.pocketshell.core.ssh.HostKeyVerificationException
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.storage.entity.HostEntity

internal data class HostKeyTrustBinding(
    val policy: KnownHostsPolicy,
    val leaseIdentity: String,
)

internal fun HostEntity.hostKeyTrustBinding(): HostKeyTrustBinding {
    val fingerprint = trustedHostKeySha256?.trim()?.takeIf { it.isNotEmpty() }
    return HostKeyTrustBinding(
        policy = KnownHostsPolicy.VerifiedFingerprint(fingerprint),
        leaseIdentity = fingerprint?.let { "host-key:$it" } ?: "host-key:unconfirmed",
    )
}

internal fun HostEntity.acceptPresentedHostKey(
    failure: HostKeyVerificationException,
): HostEntity {
    require(hostname.equals(failure.host, ignoreCase = true) && port == failure.port) {
        "presented SSH host key belongs to a different endpoint"
    }
    return copy(
        trustedHostKeyAlgorithm = failure.algorithm,
        trustedHostKeySha256 = failure.presentedSha256,
    )
}

internal fun Throwable.findHostKeyVerificationFailure(): HostKeyVerificationException? {
    val seen = HashSet<Throwable>()
    var cursor: Throwable? = this
    while (cursor != null && seen.add(cursor)) {
        if (cursor is HostKeyVerificationException) return cursor
        cursor = cursor.cause
    }
    return null
}

internal fun HostKeyVerificationException.actionableMessage(): String = when (this) {
    is ChangedHostKeyException ->
        "The SSH server identity changed. Verify the new fingerprint before replacing trust.\n\n" +
            "Expected: $expectedSha256\nReceived: $presentedSha256"
    else ->
        "Confirm this SSH server fingerprint before connecting:\n\n$algorithm $presentedSha256"
}
