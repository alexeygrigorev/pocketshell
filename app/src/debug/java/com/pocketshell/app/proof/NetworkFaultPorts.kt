package com.pocketshell.app.proof

/**
 * Host-port map for the network-fault / packet-loss / Toxiproxy fixtures.
 *
 * Issue #2128: `NetworkFaultProofBase` used to hardcode 2228 / 2229 / 8474, so
 * a `connected-test.sh --pool` lane that had claimed agents port 2243 still
 * attached through the shared `pocketshell-test-network-fault-proxy` (upstream
 * `agents:22` = the shared 2222 fixture). Sibling lanes wiping that fixture
 * presented as an empty session list — the #1842 class, in a place #1842's
 * agents-port lock does not reach.
 *
 * Single-lane / CI / nightly keep the historical identity (2222 → 2228/2229/
 * 8474). A pool agents port derives a disjoint triple so two fault-class lanes
 * cannot share a proxy. There is no unset-fallback to the shared ports: a
 * pool lane that only has `agentsPort` still isolates, because the formula
 * is a function of that port (D22).
 *
 * Keep the offsets in lockstep with `scripts/lib/agents-pool.sh`
 * (`FAULT_SSH_OFFSET` / `PACKET_LOSS_OFFSET`).
 */
object NetworkFaultPorts {
    const val SINGLE_LANE_AGENTS_PORT: Int = 2222
    const val SINGLE_LANE_FAULT_SSH_PORT: Int = 2228
    const val SINGLE_LANE_PACKET_LOSS_PORT: Int = 2229
    const val SINGLE_LANE_TOXIPROXY_API_PORT: Int = 8474

    /** Container-internal Toxiproxy listen. Host publish is [faultSshPort]. */
    const val CONTAINER_LISTEN: String = "0.0.0.0:2228"

    /**
     * Toxiproxy upstream. Per-lane compose projects still name their agents
     * service `agents`, so this hostname is correct on an isolated network;
     * it is only a pin when every lane shares one proxy on the default
     * compose network.
     */
    const val CONTAINER_UPSTREAM: String = "agents:22"

    const val POOL_FAULT_SSH_OFFSET: Int = 10
    const val POOL_PACKET_LOSS_OFFSET: Int = 20

    fun faultSshPort(agentsPort: Int): Int =
        if (agentsPort == SINGLE_LANE_AGENTS_PORT) {
            SINGLE_LANE_FAULT_SSH_PORT
        } else {
            agentsPort + POOL_FAULT_SSH_OFFSET
        }

    fun packetLossSshPort(agentsPort: Int): Int =
        if (agentsPort == SINGLE_LANE_AGENTS_PORT) {
            SINGLE_LANE_PACKET_LOSS_PORT
        } else {
            agentsPort + POOL_PACKET_LOSS_OFFSET
        }

    fun toxiproxyApiPort(agentsPort: Int): Int =
        if (agentsPort == SINGLE_LANE_AGENTS_PORT) {
            SINGLE_LANE_TOXIPROXY_API_PORT
        } else {
            SINGLE_LANE_TOXIPROXY_API_PORT + (agentsPort - SINGLE_LANE_AGENTS_PORT)
        }

    fun isPinnedToSharedFixture(
        agentsPort: Int,
        faultSshPort: Int = faultSshPort(agentsPort),
        apiPort: Int = toxiproxyApiPort(agentsPort),
    ): Boolean =
        agentsPort != SINGLE_LANE_AGENTS_PORT &&
            (
                faultSshPort == SINGLE_LANE_FAULT_SSH_PORT ||
                    apiPort == SINGLE_LANE_TOXIPROXY_API_PORT
                )

    fun checkNotPinnedToSharedFixture(agentsPort: Int) {
        val fault = faultSshPort(agentsPort)
        val api = toxiproxyApiPort(agentsPort)
        check(!isPinnedToSharedFixture(agentsPort, fault, api)) {
            "FAIL: network-fault class is pinned to the shared 2228/8474 " +
                "fixture while agentsPort=$agentsPort (faultSsh=$fault " +
                "api=$api). A --pool lane must use its own toxiproxy, never " +
                "pocketshell-test-network-fault-proxy. Issue #2128."
        }
    }
}
