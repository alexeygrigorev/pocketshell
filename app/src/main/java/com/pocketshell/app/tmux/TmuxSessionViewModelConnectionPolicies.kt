package com.pocketshell.app.tmux

/**
 * Issue #666: only a genuine foreground cold-restore attaches attach-only.
 * Every other trigger (explicit user tap/create, fast switch, all reconnect
 * variants, within-grace lifecycle reattach to a session we just had live)
 * keeps attach-OR-create so it can create or reattach as before — #634's
 * warm-open and the reconnect journeys are untouched.
 */
internal fun tmuxCreateIfMissingForTrigger(trigger: TmuxConnectTrigger): Boolean =
    trigger != TmuxConnectTrigger.ColdRestore
