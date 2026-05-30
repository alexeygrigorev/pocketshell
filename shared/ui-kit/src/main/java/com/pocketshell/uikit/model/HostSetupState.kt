package com.pocketshell.uikit.model

/**
 * Per-host bootstrap / setup readiness as displayed on `HostCard`.
 *
 * Issue #120: the host list previously had no visible signal for whether
 * a saved host already had the server-side tooling (`tmux`, the
 * `pocketshell` CLI, the jobs daemon) installed. The card now renders a
 * small badge to the right of the host name with three states:
 *
 * - [Ready] — the most recent bootstrap probe reported every required
 *   tool installed and the pocketshell jobs daemon active. Tapping the
 *   badge is a no-op (informational).
 * - [NeedsSetup] — the most recent probe reported one or more required
 *   tools missing (or the daemon disabled). Tapping the badge opens the
 *   existing bootstrap sheet so the user can fix it without first
 *   touching the host row (which would otherwise re-trigger a session
 *   open).
 * - [CliUpdateNeeded] — the remote `pocketshell` CLI exists, but its
 *   version does not match the app-compatible helper version. Tapping
 *   the badge opens the bootstrap sheet with an upgrade action.
 * - [Unknown] — there is no cached probe result yet (cold launch with
 *   saved hosts) OR the cache is stale and a probe has not landed. The
 *   ViewModel triggers a background re-probe on first composition; the
 *   badge stays `Unknown` only while we lack a verified answer.
 *
 * Kept separate from [HostStatus] (which tracks live SSH connection
 * state) because "do I need to install tools?" is a deployment-time
 * question, not a per-session connection question.
 */
enum class HostSetupState {
    Ready,
    NeedsSetup,
    CliUpdateNeeded,
    Unknown,
}
