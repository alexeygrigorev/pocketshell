package com.pocketshell.uikit.model

/**
 * Connection state of a remote host as displayed in `HostCard`.
 *
 * Maps to the CSS `.status-dot` modifiers in `docs/mockups/styles.css`:
 *
 * - [Connected] -> `.status-dot.connected` (green glow)
 * - [Connecting] -> `.status-dot.connecting` (amber, pulses)
 * - [Disconnected] -> bare `.status-dot` (muted grey)
 * - [Error] -> `.status-dot.error` (red)
 *
 * Kept separate from the more general [ConnectionStatus] used by
 * `StatusDot` itself because the host list distinguishes "disconnected"
 * (a known host the user has used before) from "idle" (no session yet,
 * default state).
 */
enum class HostStatus {
    Connected,
    Connecting,
    Disconnected,
    Error,
}
