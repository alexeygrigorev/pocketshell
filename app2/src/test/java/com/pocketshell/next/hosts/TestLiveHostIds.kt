package com.pocketshell.next.hosts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The `@LiveHostIds` seam for tests that do not care about connection status.
 *
 * Production wires `ConnectionsRegistry.liveHostIds()` here (#2635 2a). A test
 * that only wants a host LIST gets an empty set — no transport factory, no
 * trust store, no dial — while a test that cares about the dot passes
 * [liveHosts] instead.
 */
fun noLiveHosts(): Flow<Set<Long>> = flowOf(emptySet())

/** The same seam with [ids] reported as connected. */
fun liveHosts(vararg ids: Long): Flow<Set<Long>> = flowOf(ids.toSet())
