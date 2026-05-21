package com.pocketshell.core.ssh

import java.io.File

/**
 * How the SSH client should treat the server's host key.
 *
 * sshj defaults to strict verification against `~/.ssh/known_hosts`. Here we
 * expose the toggle explicitly so callers (and tests) have to opt in to laxer
 * policies. App code should usually use [KnownHostsFile] pointing at an
 * app-private file; tests use [AcceptAll].
 */
public sealed interface KnownHostsPolicy {

    /**
     * Accept any host key without verifying. Equivalent to
     * `StrictHostKeyChecking=no` and `UserKnownHostsFile=/dev/null`. Use only
     * in tests or behind a "trust on first use" UI prompt.
     */
    public data object AcceptAll : KnownHostsPolicy

    /**
     * Verify the server's host key against the given known_hosts file. The
     * file is consulted in OpenSSH format. Unknown keys cause connect() to
     * fail; this is the safe default for production code.
     */
    public data class KnownHostsFile(public val file: File) : KnownHostsPolicy
}
