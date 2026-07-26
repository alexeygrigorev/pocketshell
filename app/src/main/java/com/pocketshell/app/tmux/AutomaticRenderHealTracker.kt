package com.pocketshell.app.tmux

/** Synchronous ownership ledger for queued or running automatic render-heal jobs. */
internal class AutomaticRenderHealTracker(
    initialActivityEpoch: Long = 1L,
) {
    private val lock = Any()
    private val activeTokens = linkedSetOf<Long>()
    private var activityEpoch = initialActivityEpoch

    init {
        require(initialActivityEpoch > 0L) { "automatic render-heal activity epoch must be positive" }
    }

    fun begin(): Long = synchronized(lock) {
        val token = Math.incrementExact(activityEpoch)
        check(token > 0L && activeTokens.add(token)) {
            "automatic render-heal activity epoch/token must advance uniquely: $token"
        }
        activityEpoch = token
        token
    }

    fun complete(token: Long) {
        synchronized(lock) {
            check(activeTokens.remove(token)) { "automatic render-heal owner completed twice: $token" }
        }
    }

    fun snapshot(): Activity = synchronized(lock) {
        Activity(activeCount = activeTokens.size, activityEpoch = activityEpoch)
    }

    internal data class Activity(val activeCount: Int, val activityEpoch: Long)
}
