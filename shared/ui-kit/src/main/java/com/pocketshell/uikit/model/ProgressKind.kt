package com.pocketshell.uikit.model

/**
 * Variant for the `ProgressBar` component. Matches `.progress-fill` /
 * `.progress-fill.warn` / `.progress-fill.danger` in
 * `docs/mockups/styles.css`.
 *
 * Used by the usage panel cards (`docs/mockups/usage.html`) to colour
 * the fill bar according to how close the user is to their limit:
 *
 * - [Default] — accent (cyan). Plenty of headroom.
 * - [Warn] — amber. Approaching the limit.
 * - [Danger] — red. At or over the limit.
 */
enum class ProgressKind {
    Default,
    Warn,
    Danger,
}
