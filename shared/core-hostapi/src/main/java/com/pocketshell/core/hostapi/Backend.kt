package com.pocketshell.core.hostapi

/**
 * Which session manager on the host owns a session.
 *
 * [UNKNOWN] is the forward-compatibility escape hatch: if the host CLI grows a
 * third manager, an older phone build still lists those sessions (greyed out /
 * read-only at the UI's discretion) instead of silently hiding rows. Dropping
 * unparseable rows is exactly the "empty-but-broken looks like empty-and-fine"
 * failure the schema-2 `errors[]` list exists to prevent, so the parser never
 * does it.
 */
enum class Backend {
    TMUX,
    APLEXER,
    UNKNOWN,
    ;

    companion object {
        /** Wire value of [TMUX], as emitted by `pocketshell sessions list --json`. */
        const val WIRE_TMUX: String = "tmux"

        /** Wire value of [APLEXER]. */
        const val WIRE_APLEXER: String = "aplexer"

        /**
         * Maps a wire `manager` string. Anything unrecognised becomes
         * [UNKNOWN] — never an error, never a dropped row.
         */
        fun fromWire(wire: String): Backend = when (wire) {
            WIRE_TMUX -> TMUX
            WIRE_APLEXER -> APLEXER
            else -> UNKNOWN
        }
    }
}
