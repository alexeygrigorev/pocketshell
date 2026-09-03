package com.pocketshell.core.hostapi

/**
 * How the host arrived at a session's [AgentState].
 *
 * [REPORTED] came from the agent multiplexer's own bookkeeping (authoritative);
 * [HEURISTIC] was inferred from the pane's output (best effort). The UI is
 * expected to present a heuristic state more tentatively than a reported one.
 *
 * `null` means the host gave no state at all. An unrecognised wire value also
 * parses to `null`, for the same forward-compatibility reason as [AgentState].
 */
enum class AgentStateSource {
    REPORTED,
    HEURISTIC,
    ;

    companion object {
        /** Maps a wire `agent_state_source` value; unknown or `null` → `null`. */
        fun fromWire(wire: String?): AgentStateSource? = when (wire) {
            "reported" -> REPORTED
            "heuristic" -> HEURISTIC
            else -> null
        }
    }
}
