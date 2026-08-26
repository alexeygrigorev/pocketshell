package com.pocketshell.app.proof.signals

import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey

/**
 * Read the server-owned tmux identity used to bind a route artifact.
 *
 * This deliberately does not derive identity from a Compose tag, a display
 * status, or a durable key string. The tmux server's name/id/creation tuple is
 * the authority for the session object that the emulator journey seeded.
 */
internal suspend fun readAuthoritativeTmuxSessionIdentity(
    host: String,
    port: Int,
    user: String,
    key: String,
    sessionName: String,
): SessionIdentity {
    val command =
        "tmux display-message -p -t ${shellQuote("=$sessionName:")} " +
            shellQuote("#{session_name}|#{session_id}|#{session_created}")
    val result = SshConnection.connect(
        host = host,
        port = port,
        user = user,
        key = SshKey.Pem(key),
        knownHosts = KnownHostsPolicy.AcceptAll,
        timeoutMs = 15_000,
    ).getOrThrow().use { it.exec(command) }
    check(result.exitCode == 0) {
        "tmux identity probe failed for '$sessionName': " +
            "exit=${result.exitCode} stderr=${result.stderr}"
    }
    val fields = result.stdout.trim().lineSequence().firstOrNull()?.split('|').orEmpty()
    check(fields.size == 3) {
        "tmux identity probe returned malformed output for '$sessionName': ${result.stdout}"
    }
    return SessionIdentity(
        name = fields[0],
        id = fields[1],
        createdEpochSeconds = fields[2].toLongOrNull()
            ?: error("tmux identity creation epoch is not numeric: ${fields[2]}"),
    )
}

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
