package com.pocketshell.core.tmux.protocol

/**
 * One event emitted by tmux running in control mode (`tmux -CC`).
 *
 * tmux's control-mode protocol is line-oriented: every notification or
 * command-response delimiter starts with `%` and is terminated by `\n`. See
 * `man tmux` (the CONTROL MODE section) and `control-notify.c` in the tmux
 * sources for the authoritative list.
 *
 * Per [D5](../../../../../../../../../docs/decisions.md) we consume this
 * protocol directly rather than screen-scraping a regular tmux PTY — the
 * structured events are what make per-pane rendering and tmux-native UX
 * possible at all.
 *
 * The set of variants below covers everything PocketShell needs in Phase 2.
 * Unknown / future event types fall through as null from
 * [ControlModeParser.parse] (rather than as a catch-all `Unknown` variant)
 * because the consumer ([ControlEventStream]) filters nulls anyway and we
 * want a compile error if we ever forget to handle a variant we said we'd
 * care about.
 *
 * Field naming convention: tmux session IDs are `$N`, window IDs are `@N`,
 * and pane IDs are `%N`. We preserve the prefixes in the string fields so
 * callers can pass them straight back to tmux in `send-keys`, `kill-window`,
 * etc. without rebuilding the syntax.
 */
public sealed interface ControlEvent {

    /**
     * Raw bytes written to a pane's tty. Emitted as `%output %<paneId> <data>`
     * where `<data>` is the literal bytes with non-printables octal-escaped
     * (`\NNN`) and backslashes doubled. [ControlModeParser] decodes those
     * escapes and hands us the original bytes.
     *
     * `ByteArray` (not `String`) because tmux emits arbitrary 8-bit data —
     * CSI sequences, mouse reports, UTF-8 fragments split across writes,
     * etc. The terminal emulator decodes; we just transport.
     */
    public data class Output(
        val paneId: String,
        val data: ByteArray,
    ) : ControlEvent {
        // ByteArray's identity-based equals/hashCode is the wrong default for
        // a value type — override so tests can compare events directly.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Output) return false
            return paneId == other.paneId && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            return 31 * paneId.hashCode() + data.contentHashCode()
        }
    }

    /**
     * The attached client switched to a different session. Emitted as
     * `%session-changed $<sessionId> <name>`.
     */
    public data class SessionChanged(
        val sessionId: String,
        val name: String,
    ) : ControlEvent

    /**
     * A new window was created. Emitted as `%window-add @<windowId>`.
     *
     * tmux does NOT carry the parent session or window name on this
     * notification — the consumer must look those up via `list-windows`
     * (a command-response cycle, not an event). Fields are kept on the
     * data class so a future tmux change that does include them is a
     * non-breaking addition, but today [sessionId] and [name] are populated
     * as empty strings by [ControlModeParser].
     */
    public data class WindowAdd(
        val sessionId: String,
        val windowId: String,
        val name: String,
    ) : ControlEvent

    /** A window was closed. Emitted as `%window-close @<windowId>`. */
    public data class WindowClose(
        val sessionId: String,
        val windowId: String,
    ) : ControlEvent

    /**
     * A window was renamed. Emitted as `%window-renamed @<windowId> <name>`.
     * tmux does not include the session ID on this event — see [WindowAdd]
     * for the same rationale.
     */
    public data class WindowRenamed(
        val sessionId: String,
        val windowId: String,
        val name: String,
    ) : ControlEvent

    /**
     * A window's pane layout changed. Emitted as
     * `%layout-change @<windowId> <layout>` (older tmux) or
     * `%layout-change @<windowId> <layout> <visible-layout> <window-flags>`
     * (tmux 2.2+). We capture the first layout token verbatim; the visible
     * layout / flags suffixes are dropped because PocketShell renders one
     * pane at a time (per [D6](../../../../../../../../../docs/decisions.md))
     * and doesn't need them.
     */
    public data class LayoutChange(
        val sessionId: String,
        val windowId: String,
        val layout: String,
    ) : ControlEvent

    /**
     * A pane entered or left a special mode (copy, view, choose). Emitted as
     * `%pane-mode-changed %<paneId>`. tmux does not include the new mode on
     * the event — callers re-read it via `display-message` if they care.
     */
    public data class PaneModeChanged(
        val paneId: String,
    ) : ControlEvent

    /**
     * Opens a command-response block. Emitted as
     * `%begin <unix-time> <command-number> <flags>`.
     *
     * Every command sent over the control-mode channel produces a matching
     * `%begin` / (`%end` or [Error]) pair with the same `<command-number>`,
     * so callers can correlate responses to their requests.
     * [ControlEventStream] tracks this framing so the response payload lines
     * (which are NOT `%`-prefixed) don't leak out as events.
     */
    public data class Begin(
        val time: Long,
        val number: Long,
        val flags: Int,
    ) : ControlEvent

    /** Closes a [Begin] block on success. */
    public data class End(
        val time: Long,
        val number: Long,
        val flags: Int,
    ) : ControlEvent

    /**
     * Closes a [Begin] block on failure. The payload lines between [Begin]
     * and [Error] are the error message body.
     */
    public data class Error(
        val time: Long,
        val number: Long,
        val flags: Int,
    ) : ControlEvent

    /** The global session list changed. Emitted as `%sessions-changed`. */
    public data object SessionsChanged : ControlEvent

    /**
     * A client detached from the server. Emitted as `%client-detached
     * <clientName>` in tmux >= 3.2; older versions emit it with no payload.
     * The client name is intentionally not captured here — PocketShell is
     * always the only client, so the distinction doesn't matter for the
     * UI yet. Kept as a data object for now; promote to a data class if a
     * caller ever needs the client name.
     */
    public data object ClientDetached : ControlEvent

    /**
     * The tmux server is shutting down. Emitted as `%exit` optionally
     * followed by a human-readable reason (e.g. `%exit server exited`).
     * After this, the control-mode channel will close.
     */
    public data class Exit(
        val reason: String?,
    ) : ControlEvent
}
