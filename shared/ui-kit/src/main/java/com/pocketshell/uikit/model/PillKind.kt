package com.pocketshell.uikit.model

/**
 * Variant for the `Pill` component. Matches `.pill.ok` / `.pill.warn` /
 * `.pill.blocked` / `.pill.error` in `docs/mockups/styles.css`.
 *
 * - [Ok] — green text on green-12%-alpha background. Used for healthy
 *   usage windows.
 * - [Warn] — amber text on amber-12% background. Used for usage windows
 *   approaching their limit.
 * - [Blocked] — red text on red-12% background. Used when a provider
 *   hit its quota and is currently locked out.
 * - [Error] — muted text on surface-elev. Used when status couldn't be
 *   determined (provider unreachable, missing creds, etc.).
 */
enum class PillKind {
    Ok,
    Warn,
    Blocked,
    Error,
}
