package com.pocketshell.app.hosts

import com.pocketshell.app.nav.AppDestination

/**
 * Issue #1239: the most-recently-attached session surfaced as a one-tap
 * "Resume last session" affordance on its host's card. Built from the persisted
 * [com.pocketshell.app.session.LastSessionStore] snapshot by
 * [HostListViewModel.refreshResumableSession].
 *
 * @property hostId the host the snapshot belongs to; the host card whose id
 *   matches renders the Resume affordance.
 * @property sessionName the tmux session name, shown on the affordance so the
 *   user knows exactly what they'll jump back into.
 * @property destination the pre-built live-session route the tap navigates to.
 *   Its passphrase is null — the reattach path resolves the key from disk by
 *   path, identical to a cold attach (same contract as
 *   [com.pocketshell.app.session.LastSessionStore.toDestination]).
 */
data class ResumableSession(
    val hostId: Long,
    val sessionName: String,
    val destination: AppDestination.TmuxSession,
)
