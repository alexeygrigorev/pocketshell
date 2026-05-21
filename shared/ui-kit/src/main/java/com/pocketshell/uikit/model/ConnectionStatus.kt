package com.pocketshell.uikit.model

/**
 * Connection state rendered by `StatusDot`. Matches `.status-dot`
 * variants in `docs/mockups/styles.css`.
 *
 * Distinct from `HostStatus` — host-list rows have a "Disconnected"
 * (known-but-offline) state; status dots used elsewhere (breadcrumb
 * live dot, sheet headers, etc.) only know four conditions.
 *
 * - [Idle] — muted grey, no animation.
 * - [Connecting] — amber, pulses (1.4s linear infinite per the CSS).
 * - [Connected] — green with a soft outer glow.
 * - [Error] — red, no animation.
 */
enum class ConnectionStatus {
    Idle,
    Connecting,
    Connected,
    Error,
}
