package com.pocketshell.uikit.model

/**
 * A single segment in a `Breadcrumb`, including the current segment in the
 * `host > session > pane` chain.
 *
 * Each crumb is independently tappable so the user can jump to any
 * ancestor (e.g. host root from a deep pane). The terminal crumb has
 * `isCurrent = true` and renders in the bright text colour; the rest
 * render as muted secondary text.
 */
data class Crumb(
    val label: String,
    val isCurrent: Boolean,
    val onClick: () -> Unit,
)
