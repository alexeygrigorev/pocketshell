package com.pocketshell.app.portfwd

/**
 * Generation fence between the aggregate notification Stop action and
 * foreground resume.
 *
 * All methods are intentionally lock-free: [ForwardingController] calls them
 * only while holding its one `notificationStopLock`, so beginning Stop, final
 * resume adoption, durable completion and runtime teardown form one ordering.
 *
 * State transitions:
 *
 * ```text
 * idle(g) --begin Stop--> stopping(g+1)
 * stopping(g+1) --persist fails--> idle(g+1)       (runtime stays truthful/live)
 * stopping(g+1) --persist succeeds + teardown--> idle(g+1)
 * ```
 *
 * A resume captures `g` before reading Room. It may adopt only while the
 * authority is idle at the same generation. Thus:
 *
 * - adoption first: Stop subsequently tears that adopted transport down;
 * - Stop first: the old resume permit loses and its late transport is closed;
 * - Stop in flight: a new resume sweep cannot start;
 * - persistence failure: existing runtime remains live, but pre-Stop in-flight
 *   connects are still invalidated rather than racing the failed user action.
 */
internal class ForwardingNotificationStopAuthority {

    class ResumePermit internal constructor(internal val generation: Long)

    sealed interface BeginResult {
        data class Started(val generation: Long) : BeginResult
        data object Coalesced : BeginResult
    }

    private var generation: Long = 0L
    private var stoppingGeneration: Long? = null

    fun beginStop(): BeginResult {
        if (stoppingGeneration != null) return BeginResult.Coalesced
        generation += 1L
        stoppingGeneration = generation
        return BeginResult.Started(generation)
    }

    fun captureResumePermit(): ResumePermit? =
        if (stoppingGeneration == null) ResumePermit(generation) else null

    fun permitsAdoption(permit: ResumePermit): Boolean =
        stoppingGeneration == null && permit.generation == generation

    fun isCurrentStop(stopGeneration: Long): Boolean =
        stoppingGeneration == stopGeneration

    fun finishStop(stopGeneration: Long): Boolean {
        if (stoppingGeneration != stopGeneration) return false
        stoppingGeneration = null
        return true
    }
}
