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
 * Outcome of one [PortScanner.scan].
 *
 * The two cases are deliberately distinct types rather than "a list that may
 * be empty" (issue #2489): the caller tears down forwards whose remote port
 * has vanished, and a failed scan is NOT evidence that anything vanished. A
 * list-shaped return let one transient exec failure look like "every port
 * disappeared" and close every auto-forwarded tunnel.
 */
public sealed interface PortScanResult {

    /**
     * A strategy ran and produced at least one listening port. This is the
     * only outcome the vanished-port sweep may act on.
     */
    public data class Ports(public val ports: List<RemotePort>) : PortScanResult

    /**
     * Every strategy failed: the command errored, timed out, produced no
     * output, or produced output no parser recognised. The caller must leave
     * its existing tunnels alone and retry on the next tick.
     *
     * Note there is no "succeeded with zero ports" outcome: every host we can
     * scan is reached over SSH, so its own sshd is always listening. A scan
     * that finds literally nothing has failed, it has not observed an idle
     * host.
     */
    public data object Failed : PortScanResult
}

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
     * Run a scan over [connection]. Returns [PortScanResult.Ports] with one
     * [RemotePort] per discovered listening port, or [PortScanResult.Failed]
     * when every strategy fails — the caller (the AutoForwarder loop) treats
     * that as "scan failed, try again next tick" rather than "no ports
     * listening", so a transient exec failure never tears down live tunnels
     * (issue #2489).
     */
    public suspend fun scan(connection: HostConnection): PortScanResult {
        val ports = tryPrimary(connection)
            ?: tryFallback(connection)
            ?: tryLastResort(connection)
            ?: return PortScanResult.Failed
        return PortScanResult.Ports(ports)
    }

    private suspend fun tryPrimary(connection: HostConnection): List<RemotePort>? {
        val out = runOrNull(connection, "ss -tlnp 2>/dev/null | awk 'NR>1 {print \$4, \$7}'")
            ?: return null
        if (out.isBlank()) return null
        return parseSsOutput(out).ifEmptyStrategyFailed()
    }

    private suspend fun tryFallback(connection: HostConnection): List<RemotePort>? {
        val out = runOrNull(
            connection,
            "netstat -tlnp 2>/dev/null | awk 'NR>1 && /LISTEN/ {print \$4, \$7}'",
        ) ?: return null
        if (out.isBlank()) return null
        return parseNetstatOutput(out).ifEmptyStrategyFailed()
    }

    private suspend fun tryLastResort(connection: HostConnection): List<RemotePort>? {
        val out = runOrNull(connection, "ss -tln 2>/dev/null | awk 'NR>1 {print \$4}'")
            ?: return null
        if (out.isBlank()) return null
        return parsePortsOnly(out).ifEmptyStrategyFailed()
    }

    /**
     * A strategy that produced output no parser recognised didn't work either
     * — fall through to the next one rather than reporting "zero ports
     * listening", which the AutoForwarder would read as "everything vanished"
     * (issue #2489). Together with the blank-output checks above this makes
     * `Ports(emptyList())` unreachable: no ports found is always [Failed].
     */
    private fun List<RemotePort>.ifEmptyStrategyFailed(): List<RemotePort>? =
        takeIf { it.isNotEmpty() }

    private suspend fun runOrNull(connection: HostConnection, command: String): String? {
        // exec doesn't throw on non-zero exits — we treat "command not found"
        // (non-zero exit, empty stdout), an exec wall-clock timeout, and a
        // transport-level IOException alike: this strategy didn't work, fall
        // through to the next one.
        return try {
            val result = connection.exec(command)
            // A wall-clock overrun does NOT throw: exec returns timedOut=true
            // plus whatever partial stdout it captured. Parsing that truncated
            // listing would silently report a short port list as authoritative
            // (issue #2489), so a timeout is a failed strategy, not output.
            if (result.timedOut) null else result.stdout
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
