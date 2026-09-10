package com.pocketshell.next.nav

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The app2 navigation graph, as a sealed class of destinations.
 *
 * Style follows the old client's `AppDestination` (one sealed hierarchy that
 * enumerates every screen, documented per destination) — but not its content or
 * its mechanism. The old hierarchy carried fully-resolved SSH credentials on
 * ~20 destinations and was driven by a hand-rolled
 * `remember { mutableStateOf(...) }` navigator, which meant deep links and
 * saved-state restoration were app code. app2 hosts a real
 * `androidx.navigation` `NavHost`, so each destination here is a *route
 * template* plus a typed builder for it:
 *
 * - [pattern] is the string handed to `composable(route = ...)`.
 * - `route(...)` builds a concrete, encoded instance to `navigate(...)` to.
 * - Arguments are ids and names only. Nothing here carries a connection, a key
 *   path, or a passphrase; a screen resolves those from `hostId` through the
 *   connections registry (task M-3). That is the deliberate break from the old
 *   graph, where a credential-carrying destination was the norm.
 *
 * Route set is fixed by plan §A.1: Hosts, Workspaces, Workspace, Session, Files, Settings, Usage,
 * plus [Ports] (task P-4 — see its own doc for why forwarding is a host-scoped
 * route rather than a tab inside [Session]) and the host-management routes
 * task P-6 adds ([HostForm], [SshKeys]), plus the categorized
 * Settings/support routes from issue #2610. A new screen is a new object here,
 * never an ad-hoc string at a call site.
 */
sealed class Destination(val pattern: String) {

    /** Landing destination — the saved-host list. */
    data object Hosts : Destination("hosts") {
        fun route(): String = pattern
    }

    /** App settings. */
    data object Settings : Destination("settings") {
        fun route(): String = pattern
    }

    /** Terminal reading settings. */
    data object TerminalSettings : Destination("settings/terminal") {
        fun route(): String = pattern
    }

    /** Dictation settings. */
    data object VoiceSettings : Destination("settings/voice") {
        fun route(): String = pattern
    }

    /** Focused dictation-language choice page. */
    data object VoiceLanguage : Destination("settings/voice/language") {
        fun route(): String = pattern
    }

    /** App-switching and connection-lifetime settings. */
    data object ConnectionSettings : Destination("settings/connections") {
        fun route(): String = pattern
    }

    /** Focused background-grace choice page. */
    data object GraceSettings : Destination("settings/connections/grace") {
        fun route(): String = pattern
    }

    /** Timing and compatibility settings. */
    data object AdvancedSettings : Destination("settings/advanced") {
        fun route(): String = pattern
    }

    /** Local diagnostics index. */
    data object Diagnostics : Destination("diagnostics") {
        fun route(): String = pattern
    }

    /** One report, loaded from the on-device crash-report store. */
    data object DiagnosticReport : Destination("diagnostics/report/{$ARG_REPORT_ID}") {
        fun route(reportId: String): String =
            "diagnostics/report/${encodeSegment(reportId)}"
    }

    /** Installed build identity and update entry point. */
    data object About : Destination("settings/about") {
        fun route(): String = pattern
    }

    /** Real GitHub release-check state and native release handoffs. */
    data object Update : Destination("settings/about/update") {
        fun route(): String = pattern
    }

    /** Provider quota / usage panel. */
    data object Usage : Destination("usage") {
        fun route(): String = pattern
    }

    /** Host-scoped quota panel opened from a workspace or terminal. */
    data object HostUsage : Destination("usage/{$ARG_HOST_ID}") {
        fun route(hostId: Long): String = "usage/$hostId"
    }

    /** Host-scoped Quiet root: durable workspace navigation plus root sessions. */
    data object Workspaces : Destination("workspaces/{$ARG_HOST_ID}") {
        fun route(hostId: Long): String = "workspaces/$hostId"
    }

    /** Host workspace screen with one root action opened on entry. */
    data object WorkspaceRootAction : Destination(
        "workspaces-action/{$ARG_HOST_ID}?$ARG_ROOT_PATH={$ARG_ROOT_PATH}&$ARG_ROOT_ACTION={$ARG_ROOT_ACTION}",
    ) {
        fun route(hostId: Long, rootPath: String, action: String): String =
            "workspaces-action/$hostId?$ARG_ROOT_PATH=${encodeSegment(rootPath)}&$ARG_ROOT_ACTION=${encodeSegment(action)}"
    }

    /**
     * One persistent workspace on a host. The canonical absolute path is a
     * query argument because it contains `/`; route restoration therefore
     * carries the workspace identity without relying on in-memory selection.
     */
    data object Workspace : Destination("workspace/{$ARG_HOST_ID}?$ARG_WORKSPACE_PATH={$ARG_WORKSPACE_PATH}") {
        fun route(hostId: Long, path: String): String =
            "workspace/$hostId?$ARG_WORKSPACE_PATH=${encodeSegment(path)}"
    }

    /** The same workspace route with the new-session sheet already open. */
    data object WorkspaceStart :
        Destination("workspace-start/{$ARG_HOST_ID}?$ARG_WORKSPACE_PATH={$ARG_WORKSPACE_PATH}") {
        fun route(hostId: Long, path: String): String =
            "workspace-start/$hostId?$ARG_WORKSPACE_PATH=${encodeSegment(path)}"
    }

    /** Host-scoped page for changing the persistent root/workspace order. */
    data object ReorderWorkspaces : Destination("reorder-workspaces/{$ARG_HOST_ID}") {
        fun route(hostId: Long): String = "reorder-workspaces/$hostId"
    }

    /**
     * Compatibility name for existing callers while the destination migrates
     * from the legacy session-tree vocabulary. It resolves to the Quiet route;
     * production navigation uses [Workspaces] directly.
     */
    @Deprecated("Use Destination.Workspaces")
    data object Tree : Destination(Workspaces.pattern) {
        fun route(hostId: Long): String = Workspaces.route(hostId)
    }

    /**
     * A live session on [ARG_HOST_ID], identified by its server-side
     * [ARG_SESSION_NAME] (aplexer session name, or aplexer `workspace:tag`).
     * The name is the identity the host CLI speaks — the client never
     * carries sockets or UUIDs (plan §B.0).
     */
    data object Session : Destination(
        "session/{$ARG_HOST_ID}/{$ARG_SESSION_NAME}?$ARG_WORKSPACE_PATH={$ARG_WORKSPACE_PATH}",
    ) {
        fun route(hostId: Long, sessionName: String, workspacePath: String? = null): String =
            buildString {
                append("session/$hostId/${encodeSegment(sessionName)}")
                workspacePath?.takeIf { it.isNotBlank() }?.let {
                    append("?$ARG_WORKSPACE_PATH=${encodeSegment(it)}")
                }
            }
    }

    /**
     * Remote file browser/viewer for [ARG_HOST_ID].
     *
     * [ARG_PATH] is optional: absent means "open at the host's default
     * location", present means "open this absolute remote path". It is a query
     * argument rather than a path segment precisely because a filesystem path
     * contains `/`; percent-encoding it into a segment would work but reads
     * badly in logs and back-stack dumps.
     */
    data object Files : Destination("files/{$ARG_HOST_ID}?$ARG_PATH={$ARG_PATH}") {
        fun route(hostId: Long, path: String? = null): String =
            if (path == null) "files/$hostId" else "files/$hostId?$ARG_PATH=${encodeSegment(path)}"
    }

    /**
     * One file open in the viewer/editor on [ARG_HOST_ID] (task P-3b).
     *
     * A separate destination from [Files] rather than a mode inside it, so the
     * system back gesture does what the user means: from a file, back returns to
     * the directory that file was opened from, with its scroll position and its
     * own path argument intact. Folding both into one route would mean
     * reimplementing that with in-screen state, which is exactly the hand-rolled
     * navigation the rewrite deleted.
     *
     * [ARG_PATH] is a query argument here for the same reason it is on [Files]
     * (a filesystem path contains `/`), but unlike [Files] it is REQUIRED —
     * "the viewer with no file" is not a state.
     */
    data object FileViewer : Destination("file/{$ARG_HOST_ID}?$ARG_PATH={$ARG_PATH}") {
        fun route(hostId: Long, path: String): String =
            "file/$hostId?$ARG_PATH=${encodeSegment(path)}"
    }

    /**
     * Port forwarding for one host (task P-4).
     *
     * A standalone route rather than a tab inside [Session] on purpose: the
     * session screen's chrome is still moving, and forwarding is deliberately
     * host-scoped, not session-scoped — a forward outlives any session on that
     * host. When the session chrome settles it should gain an entry point that
     * navigates HERE; this destination is where that link will point, so nothing
     * has to move then.
     */
    data object Ports : Destination("ports/{$ARG_HOST_ID}") {
        fun route(hostId: Long): String = "ports/$hostId"
    }

    /** Quiet Services & tunnels detail for one remote port. */
    data object TunnelDetail : Destination("tunnel/{$ARG_HOST_ID}/{$ARG_REMOTE_PORT}") {
        fun route(hostId: Long, remotePort: Int): String = "tunnel/$hostId/$remotePort"
    }

    /** Quiet manual tunnel form; a missing port opens a blank form. */
    data object AddTunnel : Destination("add-tunnel/{$ARG_HOST_ID}?$ARG_REMOTE_PORT={$ARG_REMOTE_PORT}") {
        fun route(hostId: Long, remotePort: Int? = null): String =
            if (remotePort == null) {
                "add-tunnel/$hostId?$ARG_REMOTE_PORT=$NO_REMOTE_PORT"
            } else {
                "add-tunnel/$hostId?$ARG_REMOTE_PORT=$remotePort"
            }
    }

    /**
     * The add/edit host form (task P-6).
     *
     * [ARG_HOST_ID] is a query argument with a `-1` default rather than a path
     * segment, because "add" and "edit" are the same screen and Add has no id.
     * That default is load-bearing: it means the form's identity is always
     * present in its `SavedStateHandle`, so `AddEditHostViewModel` never has to
     * keep a mutable id field — which is exactly what made the audit's F1
     * edit-then-add overwrite possible in the old client.
     */
    data object HostForm : Destination("host-form?$ARG_HOST_ID={$ARG_HOST_ID}") {
        /** [hostId] `null` opens a blank Add form; an id opens that host for editing. */
        fun route(hostId: Long? = null): String =
            "host-form?$ARG_HOST_ID=${hostId ?: NO_HOST_ID}"
    }

    /** Manage registered SSH keys: generate, import, delete (task P-6). */
    data object SshKeys : Destination("ssh-keys") {
        fun route(): String = pattern
    }

    /**
     * Per-host workspace-root shortcuts (task P-6), opened from the Settings →
     * Workspace section for one saved host.
     *
     * [ARG_HOST_ID] is a path segment, not a query argument, because — unlike
     * [Files]/[FileViewer] — nothing here ever carries a value containing `/`:
     * it identifies which host's `project_roots` rows this screen manages, the
     * same shape as [Tree].
     */
    data object WorkspaceRoots : Destination("workspace-roots/{$ARG_HOST_ID}") {
        fun route(hostId: Long): String = "workspace-roots/$hostId"
    }

    /** Focused form for registering one project root on a host. */
    data object AddWorkspaceRoot : Destination("add-workspace-root/{$ARG_HOST_ID}") {
        fun route(hostId: Long): String = "add-workspace-root/$hostId"
    }

    companion object {
        const val ARG_HOST_ID: String = "hostId"
        const val ARG_REPORT_ID: String = "reportId"

        /**
         * "No host" for [HostForm]. `NavType.LongType` has no null, so Add
         * carries this sentinel rather than an absent argument.
         */
        const val NO_HOST_ID: Long = -1L
        const val ARG_SESSION_NAME: String = "sessionName"
        const val ARG_PATH: String = "path"
        const val ARG_REMOTE_PORT: String = "remotePort"
        const val NO_REMOTE_PORT: Int = -1

        /**
         * Compatibility alias for the pre-#2610 name. The route itself is now
         * [Diagnostics]; retaining the alias avoids creating a second
         * crash-report destination for older callers.
         */
        @Deprecated("Use Destination.Diagnostics")
        val CrashReports: Diagnostics
            get() = Diagnostics
        const val ARG_WORKSPACE_PATH: String = "workspacePath"
        const val ARG_ROOT_PATH: String = "rootPath"
        const val ARG_ROOT_ACTION: String = "rootAction"

        /**
         * Every destination, in graph order.
         *
         * Computed on each read, NOT stored in a `val` initializer. A companion
         * property is compiled to a static field on [Destination], so an eager
         * `val all = listOf(Hosts, ...)` runs inside `Destination.<clinit>` —
         * and `Destination.<clinit>` is itself triggered *from* a nested
         * object's initializer (the objects extend [Destination]). Whichever
         * destination is touched first therefore sees its own `INSTANCE` still
         * null while the list is being built, and `all` silently contains a
         * null forever after. The unit test caught exactly that; a getter has
         * no such window.
         */
        val all: List<Destination>
            get() = listOf(
                Hosts, Workspaces, Workspace, Session, Files, FileViewer, Ports, Settings,
                TerminalSettings, VoiceSettings, VoiceLanguage, ConnectionSettings,
                GraceSettings, AdvancedSettings, Diagnostics, DiagnosticReport,
                About, Update, Usage, HostUsage, TunnelDetail, AddTunnel,
                HostForm, SshKeys, WorkspaceRoots, AddWorkspaceRoot,
                WorkspaceStart, ReorderWorkspaces, WorkspaceRootAction,
            )

        /** The graph's start destination. Getter, for the same reason as [all]. */
        val start: Destination
            get() = Hosts

        /**
         * Percent-encodes one route component.
         *
         * `URLEncoder` is form encoding, which spells a space `+`; navigation
         * decodes route components with URI rules, where `+` stays a literal
         * plus. Rewriting `+` to `%20` makes the two sides agree — a session
         * named `my project` must arrive at the screen with its space intact.
         * Kept off `android.net.Uri` on purpose so route construction is
         * testable on the plain JVM.
         */
        internal fun encodeSegment(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}
