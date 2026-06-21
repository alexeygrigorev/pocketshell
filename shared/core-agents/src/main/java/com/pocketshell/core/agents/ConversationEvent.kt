package com.pocketshell.core.agents

public enum class AgentKind(public val displayName: String) {
    ClaudeCode("Claude Code"),
    Codex("Codex"),
    OpenCode("OpenCode"),
}

public enum class ConversationRole {
    User,
    Assistant,
}

/**
 * Issue #494: delivery state of a locally-composed user [ConversationEvent.Message].
 *
 * Only optimistic (locally-echoed) user turns ever carry a non-[Confirmed]
 * state. The instant the user taps Send, the message is shown as [Pending]
 * ("sending…") so there is no "did it send?" gap before the agent's JSONL
 * transcript round-trips. When the real transcript entry arrives it is a
 * [Confirmed] message and the reconcile pass replaces the optimistic
 * [Pending] turn with it. If the send fails (no live session, write error)
 * the optimistic turn flips to [Failed] with a retry affordance instead of
 * being silently dropped or left pending forever.
 *
 * Parsed transcript events are always [Confirmed] — they came from the
 * authoritative agent log.
 */
/**
 * Issue #842: an image referenced by a transcript event.
 *
 * Agent transcripts surface an image in a few shapes:
 *  - an `image` content block carrying a host file PATH (a screenshot the agent
 *    saved, e.g. `/home/me/shot.png`), and/or
 *  - an `image` content block carrying a content-block id / `source` URL (an
 *    `https://…` URL or a Claude `file_id`), and/or
 *  - inline base64 in a `source.data` block.
 *
 * The renderer prefers [path] (loaded over the warm SSH session — D21, the same
 * file-viewer load path) and falls back to [url] (an `http(s)` URL opened/loaded
 * directly). When neither can be fetched, the renderer shows the [path] or [url]
 * as plain text so the reference is never silently dropped. Exactly one of
 * [path]/[url]/[base64Data] is normally populated; [path] wins when several are.
 */
public data class ConversationImage(
    /** An absolute (or `~`-relative) host file path to load over SSH, when present. */
    val path: String? = null,
    /** An `http(s)://` URL (or opaque source ref) to load/open directly, when present. */
    val url: String? = null,
    /** Inline base64-encoded image bytes (no data-URI prefix), when the block carried them. */
    val base64Data: String? = null,
    /** The image MIME type when the transcript declared it (e.g. `image/png`). */
    val mediaType: String? = null,
) {
    /** The best single text reference for the fallback/path-text rendering. */
    public val displayReference: String
        get() = path ?: url ?: (mediaType?.let { "[$it image]" }) ?: "[image]"

    public companion object {
        /** True when this image carries at least one fetchable/representable reference. */
        public fun ConversationImage.hasReference(): Boolean =
            !path.isNullOrBlank() || !url.isNullOrBlank() || !base64Data.isNullOrBlank()
    }
}

public enum class MessageSendState {
    /** Confirmed by the agent's transcript (the authoritative record). */
    Confirmed,

    /** Optimistically shown locally; awaiting transcript confirmation. */
    Pending,

    /** The send failed; the user can retry. */
    Failed,
}

public sealed interface ConversationEvent {
    public val id: String
    public val agent: AgentKind
    public val atMillis: Long?

    public data class Message(
        override val id: String,
        override val agent: AgentKind,
        override val atMillis: Long? = null,
        val role: ConversationRole,
        val text: String,
        val streaming: Boolean = false,
        // Issue #494: delivery state for locally-composed user turns. Parsed
        // transcript events keep the default [MessageSendState.Confirmed];
        // an optimistic echo is inserted as [MessageSendState.Pending] and
        // flips to [MessageSendState.Failed] when its send fails.
        val sendState: MessageSendState = MessageSendState.Confirmed,
        // Issue #842: image content blocks / pasted-image-by-path references
        // carried by this message turn. Empty for the common text-only turn;
        // populated when the parser saw an `image` content block or a
        // pasted-image path. The renderer shows each inline under the text.
        val images: List<ConversationImage> = emptyList(),
    ) : ConversationEvent

    public data class ToolCall(
        override val id: String,
        override val agent: AgentKind,
        override val atMillis: Long? = null,
        val name: String,
        val input: String,
    ) : ConversationEvent

    public data class ToolResult(
        override val id: String,
        override val agent: AgentKind,
        override val atMillis: Long? = null,
        val toolCallId: String? = null,
        val output: String,
        val isError: Boolean = false,
        // Issue #842: image(s) returned in a tool result (e.g. a screenshot
        // tool whose result content is an `image` block). Empty for the common
        // text result; populated when the result content carried an image.
        val images: List<ConversationImage> = emptyList(),
    ) : ConversationEvent

    /**
     * A structured metadata block emitted by an agent's JSONL feed inside
     * what would otherwise be a `Message` text payload. Claude Code in
     * particular wraps non-user-authored notes (date-change reminders,
     * slash-command echoes, captured stdout) in XML-style tags such as
     * `<system-reminder>`, `<command-name>`, `<command-args>`,
     * `<command-stdout>`, and `<local-command-stdout>`. Issue #176 lifts
     * them out of the surrounding markdown so the conversation pane can
     * render them in a muted, collapsible style without competing with
     * the user/assistant prose.
     *
     * The [tag] is the raw XML element name (without `<>`); [content] is
     * the inner text exactly as the agent emitted it (no trim, no markdown
     * processing — the renderer treats it as preformatted text).
     */
    public data class SystemNote(
        override val id: String,
        override val agent: AgentKind,
        override val atMillis: Long? = null,
        val tag: String,
        val content: String,
    ) : ConversationEvent
}

public interface ConversationParser {
    public fun parseLine(line: String): List<ConversationEvent>
}
