package com.pocketshell.app.nav

/**
 * Issue #1831 — the structural parent of a destination, used as the back-stack
 * fallback when the hand-rolled navigator has nothing to pop.
 *
 * ## Why this exists
 *
 * `MainActivity.AppNavigator` keeps its back stack in a plain
 * `remember { mutableListOf<AppDestination>() }` and `back()` used to fall back
 * to [AppDestination.HostList] whenever that stack was empty. The stack is
 * empty for every destination the app lands on WITHOUT a user navigation, and
 * there are four such producers for a live session:
 *
 *  1. the #177 process-death restore (`onCreate(savedInstanceState != null)`
 *     routes straight into the persisted tmux session),
 *  2. a share-into-session launch (`ShareActivity` -> `EXTRA_OPEN_SESSION_*`),
 *  3. the home-screen active-sessions widget tap (same extras shape),
 *  4. the #859 agent-card push deep-link (resolved off-Main, then applied
 *     directly because the user is still on the cold-launch host list).
 *
 * ...plus ANY of them followed by a configuration change, which discards the
 * `remember`ed stack outright. In every one of those the maintainer's first
 * Back jumped from the agent session to the HOST list instead of the session
 * list — the #1831 report.
 *
 * Rather than patch each producer to synthesise its own stack (four sites
 * today, one more with every new entry point), the navigator asks the
 * destination where it belongs. `TmuxSession` -> its host's [AppDestination
 * .FolderList] (the "sessions screen" the maintainer expects) -> [AppDestination
 * .HostList] -> `null`, at which point Back on the root host list keeps the
 * Activity default and exits the app (`shouldTrapSystemBack`, issue #520 — the
 * behaviour `ColdInstallE2eTest` / `EmulatorWorkflowE2eTest` assert).
 *
 * This is only ever consulted when the real stack is empty; a genuine
 * navigation history always wins, so no existing journey changes shape.
 *
 * ## Why the `when` is exhaustive
 *
 * Deliberately no `else` branch: adding a destination to [AppDestination] must
 * be a compile error here, so nobody can add a new screen that silently
 * inherits "Back goes to the host list" — the exact defect this file fixes.
 *
 * @return the destination Back should reach when the navigator has nothing to
 *   pop, or `null` for the root [AppDestination.HostList] (Back exits the app).
 */
internal fun AppDestination.parentDestination(): AppDestination? = when (this) {
    // Root: Back keeps the Activity default and exits the app (#520).
    AppDestination.HostList -> null

    // Host-scoped screens whose natural parent is that host's session/folder
    // list — they all carry the full SSH connection tuple, so the parent is
    // reconstructible exactly.
    is AppDestination.TmuxSession -> hostSessionTree()

    is AppDestination.RepoBrowser -> folderListFor(
        hostId = hostId,
        hostName = hostName,
        hostname = hostname,
        port = port,
        username = username,
        keyPath = keyPath,
        passphrase = passphrase,
    )

    is AppDestination.EnvFiles -> folderListFor(
        hostId = hostId,
        hostName = hostName,
        hostname = hostname,
        port = port,
        username = username,
        keyPath = keyPath,
        passphrase = passphrase,
    )

    is AppDestination.FileViewer -> folderListFor(
        hostId = hostId,
        hostName = hostName,
        hostname = hostname,
        port = port,
        username = username,
        keyPath = keyPath,
        passphrase = passphrase,
    )

    is AppDestination.FileExplorer -> folderListFor(
        hostId = hostId,
        hostName = hostName,
        hostname = hostname,
        port = port,
        username = username,
        keyPath = keyPath,
        passphrase = passphrase,
    )

    is AppDestination.GitHistory -> folderListFor(
        hostId = hostId,
        hostName = hostName,
        hostname = hostname,
        port = port,
        username = username,
        keyPath = keyPath,
        passphrase = passphrase,
    )

    is AppDestination.RecurringJobs -> folderListFor(
        hostId = hostId,
        hostName = hostName,
        hostname = hostname,
        port = port,
        username = username,
        keyPath = keyPath,
        passphrase = passphrase,
    )

    // Host-scoped, but the SSH tuple is OPTIONAL on this destination (the
    // Settings host-picker route arrives without credentials). Without them a
    // folder list could not connect, so those instances fall back to the host
    // list.
    is AppDestination.WatchedFolders -> {
        val resolvedHostname = hostname
        val resolvedPort = port
        val resolvedUsername = username
        val resolvedKeyPath = keyPath
        if (
            resolvedHostname != null &&
            resolvedPort != null &&
            resolvedUsername != null &&
            resolvedKeyPath != null
        ) {
            folderListFor(
                hostId = hostId,
                hostName = hostName,
                hostname = resolvedHostname,
                port = resolvedPort,
                username = resolvedUsername,
                keyPath = resolvedKeyPath,
                passphrase = passphrase,
            )
        } else {
            AppDestination.HostList
        }
    }

    // Reached FROM the host list, so the host list is the parent. The
    // port-forward panel is host-scoped but carries no hostname/user/port, so
    // it cannot rebuild a folder list either.
    is AppDestination.FolderList -> AppDestination.HostList
    is AppDestination.PortForwardPanel -> AppDestination.HostList
    AppDestination.AddHost -> AppDestination.HostList
    AppDestination.AddFirstHost -> AppDestination.HostList
    is AppDestination.FirstHostTestConnect -> AppDestination.HostList
    is AppDestination.EditFirstHost -> AppDestination.HostList
    is AppDestination.EditHost -> AppDestination.HostList
    AppDestination.Scan -> AppDestination.HostList
    AppDestination.CrashReports -> AppDestination.HostList
    AppDestination.Settings -> AppDestination.HostList
    AppDestination.Usage -> AppDestination.HostList
    AppDestination.AiCosts -> AppDestination.HostList
    AppDestination.PortForwardChooser -> AppDestination.HostList
}

/**
 * Issue #2237 — this session's host session/folder tree, as a non-null
 * [AppDestination.FolderList].
 *
 * [parentDestination] already resolves a `TmuxSession` to exactly this screen for
 * the #1831 empty-back-stack Back fallback, but it returns a nullable
 * [AppDestination] because the root host list has no parent. The stale-session
 * recovery dialog (`MainActivity`) needs the same destination as a hard fact — a
 * dismissed "this session is gone" prompt lands the user back on THIS host's
 * sessions, never on the unrelated list of all hosts — so it takes this typed
 * accessor rather than re-building the seven-field constructor call by hand or
 * null-handling a value that cannot be null.
 *
 * One builder, one shape: both callers go through [folderListFor], so the
 * recovery landing and the Back fallback can never drift apart.
 */
internal fun AppDestination.TmuxSession.hostSessionTree(): AppDestination.FolderList =
    folderListFor(
        hostId = hostId,
        hostName = hostName,
        hostname = hostname,
        port = port,
        username = username,
        keyPath = keyPath,
        passphrase = passphrase,
    )

private fun folderListFor(
    hostId: Long,
    hostName: String,
    hostname: String,
    port: Int,
    username: String,
    keyPath: String,
    passphrase: CharArray?,
): AppDestination.FolderList = AppDestination.FolderList(
    hostId = hostId,
    hostName = hostName,
    hostname = hostname,
    port = port,
    username = username,
    keyPath = keyPath,
    passphrase = passphrase,
)
