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
 * Route set is fixed by plan §A.1: Hosts, Tree, Session, Files, Settings, Usage,
 * plus [Ports] (task P-4 — see its own doc for why forwarding is a host-scoped
 * route rather than a tab inside [Session]) and the three host-management
 * routes task P-6 adds ([HostForm], [SshKeys], [HostQr], [QrScan]). A new screen
 * is a new object here, never an ad-hoc string at a call site.
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

    /** Provider quota / usage panel. */
    data object Usage : Destination("usage") {
        fun route(): String = pattern
    }

    /** Workspace + session tree for one host. */
    data object Tree : Destination("tree/{$ARG_HOST_ID}") {
        fun route(hostId: Long): String = "tree/$hostId"
    }

    /**
     * A live session on [ARG_HOST_ID], identified by its server-side
     * [ARG_SESSION_NAME] (tmux session name, or aplexer `workspace:tag`).
     * The name is the identity the host CLI speaks — the client never
     * carries sockets or UUIDs (plan §B.0).
     */
    data object Session : Destination("session/{$ARG_HOST_ID}/{$ARG_SESSION_NAME}") {
        fun route(hostId: Long, sessionName: String): String =
            "session/$hostId/${encodeSegment(sessionName)}"
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

    /** Render one host as a QR code for another device to scan (task P-6). */
    data object HostQr : Destination("host-qr/{$ARG_HOST_ID}") {
        fun route(hostId: Long): String = "host-qr/$hostId"
    }

    /** Scan a QR to import a host (task P-6). */
    data object QrScan : Destination("qr-scan") {
        fun route(): String = pattern
    }

    companion object {
        const val ARG_HOST_ID: String = "hostId"

        /**
         * "No host" for [HostForm]. `NavType.LongType` has no null, so Add
         * carries this sentinel rather than an absent argument.
         */
        const val NO_HOST_ID: Long = -1L
        const val ARG_SESSION_NAME: String = "sessionName"
        const val ARG_PATH: String = "path"

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
                Hosts, Tree, Session, Files, FileViewer, Ports, Settings, Usage,
                HostForm, SshKeys, HostQr, QrScan,
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
