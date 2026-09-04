package com.pocketshell.core.hostapi

/**
 * What the agent running in a session is doing, as the host reports it.
 *
 * `null` (rather than a fourth enum constant) means "the host has no opinion" —
 * a plain shell session, or a manager that does not track agent state. The UI
 * must render that as "no badge", not as [IDLE].
 *
 * An unrecognised wire value also parses to `null`: a newer host CLI inventing
 * a fourth state must not make an older phone fail the whole listing.
 */
enum class AgentState {
    IDLE,
    WAITING,
    WORKING,
    ;

    companion object {
        /** Maps a wire `agent_state` value; unknown or `null` → `null`. */
        fun fromWire(wire: String?): AgentState? = when (wire) {
            "idle" -> IDLE
            "waiting" -> WAITING
            "working" -> WORKING
            else -> null
        }
    }
}
