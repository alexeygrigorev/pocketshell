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

    private val _transportEvents = MutableSharedFlow<TransportUpDown>(extraBufferCapacity = 16)
    override val transportEvents: Flow<TransportUpDown> = _transportEvents.asSharedFlow()

    fun setWarm(host: HostKey, warm: Boolean) {
        if (warm) warmHosts.add(host) else warmHosts.remove(host)
    }

    override fun isWarm(host: HostKey): Boolean = host in warmHosts
}

class FakeLivenessPort(private var provenAlive: Boolean = false) : LivenessPort {
    var queryCount: Int = 0
        private set

    fun setProvenAlive(value: Boolean) {
        provenAlive = value
    }

    override fun transportProvenAliveRecently(): Boolean {
        queryCount++
        return provenAlive
    }
}
