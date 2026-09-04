package com.pocketshell.next.ports

import com.pocketshell.core.portfwd.AutoForwarderSupervisor.ConnectionState

/**
 * The words on the forwarding notification, derived purely from a controller
 * snapshot.
 *
 * Split out from the service so the copy is unit-testable without Android: the
 * old client's notification bugs were never about `NotificationCompat`, they were
 * about which snapshot won a race, and a pure `snapshot -> text` function makes
 * "does rapid toggling ever produce nonsense?" an ordinary assertion.
 */
internal object ForwardNotificationText {

    fun title(snapshot: List<ForwardingController.HostForwarding>): String {
        val forwards = snapshot.sumOf { it.forwardingCount }
        return when {
            snapshot.isEmpty() -> "Port forwarding stopping"
            forwards == 0 -> "Port forwarding starting"
            forwards == 1 -> "1 port forwarded"
            else -> "$forwards ports forwarded"
        }
    }

    /**
     * One clause per host: the host name plus either its forwarded local ports or
     * why it has none yet. Deliberately lists the LOCAL ports — that is the number
     * the user types into a browser.
     */
    fun body(snapshot: List<ForwardingController.HostForwarding>): String {
        if (snapshot.isEmpty()) return "No hosts are forwarding."
        return snapshot.joinToString(" · ") { host ->
            val ports = host.tunnels
                .filter { it.status == com.pocketshell.core.portfwd.TunnelInfo.Status.FORWARDING }
                .map { it.localPort }
                .sorted()
            when {
                ports.isNotEmpty() -> "${host.hostName}: ${ports.joinToString(", ")}"
                else -> "${host.hostName}: ${host.connection.label}"
            }
        }
    }

    private val ConnectionState.label: String
        get() = when (this) {
            ConnectionState.Idle -> "idle"
            ConnectionState.Connecting -> "connecting"
            ConnectionState.Connected -> "scanning"
            ConnectionState.Reconnecting -> "reconnecting"
            ConnectionState.Lost -> "unreachable"
        }
}
