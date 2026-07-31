package com.pocketshell.core.ssh

import net.schmizz.sshj.Config
import net.schmizz.sshj.SSHClient
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException

/** One concrete network endpoint in a bounded sequential SSH dial plan. */
internal data class SshDialTarget(
    val address: InetAddress?,
    val diagnosticLabel: String,
) {
    companion object {
        /** Test/fake connector default: retain the legacy unresolved-host call. */
        fun unresolved(host: String): SshDialTarget = SshDialTarget(
            address = null,
            diagnosticLabel = "resolver[$host]",
        )
    }
}

/**
 * Resolve every candidate once and choose a deliberate, stable family order.
 *
 * IPv6 is attempted first. Android carriers increasingly expose an IPv6-only
 * network with DNS64/NAT64; preferring the resolver-provided IPv6 candidate
 * uses that native path instead of depending on optional CLAT. A broken AAAA
 * cannot strand the connection because each address has a bounded turn and the
 * IPv4 candidates follow sequentially. Resolver order is retained within each
 * family, and duplicate addresses are removed without changing that order.
 */
internal object SshDialPlanner {
    fun resolve(host: String): List<SshDialTarget> =
        plan(host, InetAddress.getAllByName(host).toList())

    internal fun plan(host: String, resolved: List<InetAddress>): List<SshDialTarget> {
        if (resolved.isEmpty()) throw UnknownHostException("No addresses resolved for $host")

        val unique = LinkedHashMap<String, InetAddress>()
        resolved.forEach { address ->
            val key = "${address.javaClass.name}:${address.hostAddress}"
            unique.putIfAbsent(key, address)
        }
        val ordered = unique.values.sortedBy { address ->
            when (address) {
                is Inet6Address -> 0
                is Inet4Address -> 1
                else -> 2
            }
        }
        return ordered.map { address ->
            val family = when (address) {
                is Inet6Address -> "IPv6"
                is Inet4Address -> "IPv4"
                else -> address.javaClass.simpleName
            }
            SshDialTarget(
                address = address,
                diagnosticLabel = "$family[${address.hostAddress}]",
            )
        }
    }
}

/**
 * sshj client whose TCP socket is pinned to one already-resolved address while
 * sshj still records the ORIGINAL hostname for host-key verification.
 *
 * Calling sshj's `connect(InetAddress, port)` loses its private `hostname`
 * field, so `KnownHostsPolicy.KnownHostsFile` would verify the numeric address
 * instead of the configured host. Overriding only the protected address factory
 * lets `connect(originalHost, port)` retain trust semantics while preventing a
 * second hidden resolver lookup from choosing a different endpoint.
 */
internal class AddressPinnedSshClient(
    config: Config,
    private val address: InetAddress,
) : SSHClient(config) {
    override fun makeInetSocketAddress(hostname: String, port: Int): InetSocketAddress =
        InetSocketAddress(address, port)
}

internal class AllSshAddressesFailedException(
    host: String,
    port: Int,
    failures: List<Pair<SshDialTarget, Throwable>>,
) : java.io.IOException(
    buildString {
        append("All ")
        append(failures.size)
        append(" resolved SSH address attempt(s) failed for ")
        append(host)
        append(':')
        append(port)
        append(": ")
        append(
            failures.joinToString("; ") { (target, failure) ->
                "${target.diagnosticLabel}=${failure.javaClass.simpleName}: ${failure.message ?: "no message"}"
            },
        )
    },
    failures.lastOrNull()?.second,
)
