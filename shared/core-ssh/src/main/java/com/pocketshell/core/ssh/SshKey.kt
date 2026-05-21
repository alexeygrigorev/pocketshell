package com.pocketshell.core.ssh

import java.io.File

/**
 * Source of an SSH private key.
 *
 * Two supported shapes: on-disk path or in-memory PEM blob (useful for
 * tests and for "paste key" import flows that never persist to disk).
 * Both routes are auto-detected by sshj — it picks the right reader for
 * classic PEM, the newer "OPENSSH PRIVATE KEY" format ed25519 uses by
 * default, PKCS8, and PuTTY.
 */
public sealed interface SshKey {

    /** Key stored on disk. The file must exist and be readable. */
    public data class Path(public val file: File) : SshKey

    /** Key supplied as a PEM (or OpenSSH-format) string in memory. */
    public data class Pem(public val content: String) : SshKey
}
