package com.pocketshell.uikit.model

/**
 * Variant for the `ProgressBar` component: normal, warning, or danger.
 *
 * Used by the usage panel cards to colour the fill bar according to how close
 * the user is to their limit:
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
