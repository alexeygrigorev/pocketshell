package com.pocketshell.next.ports

import com.pocketshell.core.portfwd.AutoForwarderSupervisor.ConnectionState
import com.pocketshell.core.portfwd.TunnelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification copy, as a pure function of the controller snapshot.
 *
 * The old client's notification bugs were never about `NotificationCompat`; they
 * were about which snapshot won a race, which is why the copy lives in a pure
 * function and the serialisation lives in one collector coroutine. The
 * rapid-toggle case below is that old race class expressed as an ordinary
 * assertion: every snapshot in a burst must render text that describes THAT
 * snapshot, never a stale one.
 */
class ForwardNotificationTextTest {

    @Test
    fun `an empty snapshot reads as stopping, not as an empty list`() {
        assertEquals("Port forwarding stopping", ForwardNotificationText.title(emptyList()))
        assertEquals("No hosts are forwarding.", ForwardNotificationText.body(emptyList()))
    }

    @Test
    fun `a mounted host with no forwards yet reads as starting and shows its state`() {
        val snapshot = listOf(host("rmthz", ConnectionState.Connecting))

        assertEquals("Port forwarding starting", ForwardNotificationText.title(snapshot))
        assertEquals("rmthz: connecting", ForwardNotificationText.body(snapshot))
    }

    @Test
    fun `forwarded ports are counted and listed by LOCAL port`() {
        val snapshot = listOf(
            host(
                "rmthz",
                ConnectionState.Connected,
                forwarding(remotePort = 3_000, localPort = 3_000),
                forwarding(remotePort = 8_080, localPort = 8_081),
                // AVAILABLE rows are discovered, not forwarded — they must not
                // inflate the count or appear in the list.
                available(remotePort = 22),
            ),
        )

        assertEquals("2 ports forwarded", ForwardNotificationText.title(snapshot))
        // 8081, not 8080: the local port is the number the user types.
        assertEquals("rmthz: 3000, 8081", ForwardNotificationText.body(snapshot))
    }

    @Test
    fun `a single forward is singular`() {
        val snapshot = listOf(host("rmthz", ConnectionState.Connected, forwarding(3_000, 3_000)))
        assertEquals("1 port forwarded", ForwardNotificationText.title(snapshot))
    }

    @Test
    fun `multiple hosts are listed together`() {
        val snapshot = listOf(
            host("alpha", ConnectionState.Connected, forwarding(3_000, 3_000)),
            host("beta", ConnectionState.Reconnecting),
        )

        assertEquals("1 port forwarded", ForwardNotificationText.title(snapshot))
        assertEquals("alpha: 3000 · beta: reconnecting", ForwardNotificationText.body(snapshot))
    }

    @Test
    fun `every snapshot in a rapid toggle burst renders only its own hosts`() {
        // The old race class: a burst of enable/disable produced a notification
        // describing a snapshot that was no longer true. With the copy derived
        // purely from the snapshot handed in, that is impossible by construction
        // — assert it over the whole burst rather than one lucky frame.
        val burst = buildList {
            repeat(25) { index ->
                add(
                    if (index % 2 == 0) {
                        listOf(host("rmthz", ConnectionState.Connected, forwarding(3_000, 3_000)))
                    } else {
                        emptyList()
                    },
                )
            }
        }

        burst.forEach { snapshot ->
            val title = ForwardNotificationText.title(snapshot)
            val body = ForwardNotificationText.body(snapshot)
            assertTrue("title must never be blank, snapshot=$snapshot", title.isNotBlank())
            if (snapshot.isEmpty()) {
                assertFalse("an empty snapshot must not name a host: $body", body.contains("rmthz"))
            } else {
                assertTrue("a mounted host must be named: $body", body.contains("rmthz"))
            }
        }
    }

    private fun host(
        name: String,
        state: ConnectionState,
        vararg tunnels: TunnelInfo,
    ) = ForwardingController.HostForwarding(
        hostId = name.hashCode().toLong(),
        hostName = name,
        connection = state,
        tunnels = tunnels.toList(),
    )

    private fun forwarding(remotePort: Int, localPort: Int) = TunnelInfo(
        remotePort = remotePort,
        localPort = localPort,
        process = "app",
        status = TunnelInfo.Status.FORWARDING,
    )

    private fun available(remotePort: Int) = TunnelInfo(
        remotePort = remotePort,
        localPort = remotePort,
        process = "sshd",
        status = TunnelInfo.Status.AVAILABLE,
    )
}
