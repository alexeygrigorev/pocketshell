package com.pocketshell.core.ssh

import java.io.File

/**
 * How the SSH client should treat the server's host key.
 *
 * App production code persists and supplies a [VerifiedFingerprint]. The file
 * policy remains available to command-line/integration consumers. The sole
 * permissive implementation is Kotlin-internal and reachable only through
 * explicitly suppressed test-fixture bridges; production consumers cannot
 * name or construct it.
 */
public sealed interface KnownHostsPolicy {

    /** Fail closed without presenting a trust prompt. Safe constructor default. */
    public data object RejectAll : KnownHostsPolicy

    /**
     * Accept any host key without verifying. Equivalent to
     * `StrictHostKeyChecking=no` and `UserKnownHostsFile=/dev/null`. Use only
     * in tests or behind a "trust on first use" UI prompt.
     */
    /**
     * Require the exact OpenSSH SHA-256 fingerprint. A null expected value is
     * a first-use probe: the handshake is rejected with the presented
     * fingerprint so UI can request explicit confirmation.
     */
    public data class VerifiedFingerprint(
        public val expectedSha256: String?,
    ) : KnownHostsPolicy

    /**
     * Verify the server's host key against the given known_hosts file. The
     * file is consulted in OpenSSH format. Unknown keys cause connect() to fail.
     */
    public data class KnownHostsFile(public val file: File) : KnownHostsPolicy
}

/** Internal test-fixture implementation; no production consumer can name it. */
internal data object TestOnlyAcceptAll : KnownHostsPolicy
