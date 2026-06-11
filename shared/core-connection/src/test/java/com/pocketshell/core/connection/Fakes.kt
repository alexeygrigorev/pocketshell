package com.pocketshell.core.connection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A controllable virtual clock — advance time explicitly in tests. */
class FakeClock(private var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now

    fun advanceBy(deltaMs: Long) {
        now += deltaMs
    }

    fun set(ms: Long) {
        now = ms
    }
}

/** A fake [TransportPort] whose warmth is set per-host in tests. */
class FakeTransportPort : TransportPort {
    private val warmHosts = mutableSetOf<HostKey>()
    var ensureLeaseCount = 0
        private set
    var evictStaleCount = 0
        private set

    private val _transportEvents = MutableSharedFlow<TransportUpDown>(extraBufferCapacity = 16)
    override val transportEvents: Flow<TransportUpDown> = _transportEvents.asSharedFlow()

    fun setWarm(host: HostKey, warm: Boolean) {
        if (warm) warmHosts.add(host) else warmHosts.remove(host)
    }

    override suspend fun ensureLease(host: HostKey): LeaseHandle {
        ensureLeaseCount++
        warmHosts.add(host)
        return object : LeaseHandle {
            override val host: HostKey = host
        }
    }

    override fun isWarm(host: HostKey): Boolean = host in warmHosts

    override suspend fun evictStale(host: HostKey) {
        evictStaleCount++
        warmHosts.remove(host)
    }
}
