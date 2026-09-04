package com.pocketshell.core.portfwd

import com.pocketshell.core.transport.HostConnection

/**
 * One listening TCP port discovered on the remote.
 */
public data class RemotePort(
    public val port: Int,
    public val processName: String,
)

/**
 * Scans a remote host for TCP ports in `LISTEN` state.
 *
 * Tries three strategies in order, falling back when one fails or returns
 * nothing:
 *
 * 1. `ss -tlnp` — modern, gives port + owning process. iproute2 standard.
 * 2. `netstat -tlnp` — older but still present on most distros (incl. Alpine
 *    busybox, which has no `ss`). Different process-field format than `ss`.
 * 3. `ss -tln` (no `-p`) — last resort; loses the process name but is the
 *    only thing guaranteed to work without root on stripped containers.
 *
 * Ported from `ssh-auto-forward-android/.../ssh/PortScanner.kt`; rewired in task
 * P-4 from the deleted `core-ssh` session onto [HostConnection.exec]. The awk
 * pipelines and the regex extraction are unchanged — only the thing that runs
 * the command moved, so the parsing behaviour stays exactly as tested.
 */
public object PortScanner {

    /**
     * Run a scan over [connection] and return one [RemotePort] per discovered
     * listening port. Returns an empty list if every strategy fails — the
     * caller (the AutoForwarder loop) treats this as "scan failed, try
     * again next tick" rather than "no ports listening".
     */
    public suspend fun scan(connection: HostConnection): List<RemotePort> {
        return tryPrimary(connection)
            ?: tryFallback(connection)
            ?: tryLastResort(connection)
            ?: emptyList()
    }

    private suspend fun tryPrimary(connection: HostConnection): List<RemotePort>? {
        val out = runOrNull(connection, "ss -tlnp 2>/dev/null | awk 'NR>1 {print \$4, \$7}'")
            ?: return null
        if (out.isBlank()) return null
        return parseSsOutput(out)
    }

    private suspend fun tryFallback(connection: HostConnection): List<RemotePort>? {
        val out = runOrNull(
            connection,
            "netstat -tlnp 2>/dev/null | awk 'NR>1 && /LISTEN/ {print \$4, \$7}'",
        ) ?: return null
        if (out.isBlank()) return null
        return parseNetstatOutput(out)
    }

    private suspend fun tryLastResort(connection: HostConnection): List<RemotePort>? {
        val out = runOrNull(connection, "ss -tln 2>/dev/null | awk 'NR>1 {print \$4}'")
            ?: return null
        if (out.isBlank()) return null
        return parsePortsOnly(out)
    }

    private suspend fun runOrNull(connection: HostConnection, command: String): String? {
        // exec doesn't throw on non-zero exits — we treat "command not found"
        // (non-zero exit, empty stdout), an exec wall-clock timeout, and a
        // transport-level IOException alike: this strategy didn't work, fall
        // through to the next one.
        return try {
            connection.exec(command).stdout
        } catch (_: Throwable) {
            null
        }
    }

    internal fun parseSsOutput(output: String): List<RemotePort> {
        return output.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.trim().split(WHITESPACE, limit = 2)
                if (parts.isEmpty()) return@mapNotNull null
                val port = extractPort(parts[0]) ?: return@mapNotNull null
                val processName = if (parts.size > 1) extractSsProcessName(parts[1]) else ""
                RemotePort(port, processName)
            }
            .toList()
    }

    internal fun parseNetstatOutput(output: String): List<RemotePort> {
        return output.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.trim().split(WHITESPACE, limit = 2)
                if (parts.isEmpty()) return@mapNotNull null
                val port = extractPort(parts[0]) ?: return@mapNotNull null
                val processName = if (parts.size > 1) extractNetstatProcess(parts[1]) else ""
                RemotePort(port, processName)
            }
            .toList()
    }

    internal fun parsePortsOnly(output: String): List<RemotePort> {
        return output.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> extractPort(line.trim())?.let { RemotePort(it, "") } }
            .toList()
    }

    private fun extractPort(addressField: String): Int? {
        val colonIndex = addressField.lastIndexOf(':')
        if (colonIndex < 0) return null
        return addressField.substring(colonIndex + 1).toIntOrNull()
    }

    private fun extractSsProcessName(processField: String): String {
        // `ss -p` emits e.g. `users:(("sshd",pid=1,fd=3))`. Older versions
        // drop the outer parens. Try both shapes.
        for (pattern in SS_PROCESS_PATTERNS) {
            pattern.find(processField)?.groupValues?.get(1)?.let { return it }
        }
        return ""
    }

    private fun extractNetstatProcess(processField: String): String {
        // netstat's process field looks like `1234/sshd` (PID/name). Some
        // busybox builds appended `: ...` extras; strip on the first comma.
        val parts = processField.split("/")
        if (parts.size >= 2) {
            val name = parts[1].split(",")[0].trim()
            if (name.isNotBlank()) return name
        }
        return ""
    }

    private val WHITESPACE = "\\s+".toRegex()
    private val SS_PROCESS_PATTERNS = listOf(
        Regex("""users:\(\("([^"]+)""""),
        Regex("""users:\("([^"]+)""""),
    )
}
