package com.pocketshell.app.fileviewer

/**
 * Lightweight Markdown block + inline model for the in-app file viewer
 * (issue #696).
 *
 * ## Why a small in-house parser, not a library
 *
 * The viewer needs to render Markdown headers, fenced code blocks (monospaced),
 * lists, links, and bold/italic — a well-bounded subset. The two obvious
 * library routes each cost more than they buy here:
 *
 *  - **Markwon** is a *View*-based (`TextView`) renderer; wiring it into the
 *    Compose viewer means an `AndroidView` bridge and a styling stack that
 *    fights `PocketShellTheme`.
 *  - **Compose markdown renderers** (e.g. `multiplatform-markdown-renderer`)
 *    pull in `commonmark` + a multiplatform runtime — a new version-catalog
 *    entry and transitive weight for a subset we can express in ~2 small files.
 *
 * A focused parser keeps the APK lean (zero new deps, no `libs.versions.toml`
 * churn), composes straight into the existing `PocketShellTheme` text styling,
 * and — being pure Kotlin — is unit-tested without an emulator. This matches
 * the app's existing pattern of small in-house helpers (see
 * [FileTypeDetector], `RemotePathResolver`). Syntax highlighting inside code
 * blocks is explicitly out of scope (issue #696 non-goal); fenced code is shown
 * monospaced verbatim.
 */

/** A block-level Markdown element. */
internal sealed interface MarkdownBlock {

    /** `#`..`######` heading. [level] is 1..6; [spans] is the inline content. */
    data class Heading(val level: Int, val spans: List<InlineSpan>) : MarkdownBlock

    /** A normal paragraph of inline content. */
    data class Paragraph(val spans: List<InlineSpan>) : MarkdownBlock

    /**
     * A fenced (```` ``` ````) or indented code block. [language] is the info
     * string after the opening fence (may be blank); [content] is the verbatim
     * body with original line breaks, never inline-parsed.
     */
    data class CodeBlock(val language: String, val content: String) : MarkdownBlock

    /**
     * A list (ordered or unordered). Each item carries its inline spans and a
     * nesting [Item.indentLevel] (0 = top level).
     */
    data class ListBlock(val ordered: Boolean, val items: List<Item>) : MarkdownBlock {
        data class Item(val indentLevel: Int, val ordinal: Int?, val spans: List<InlineSpan>)
    }

    /** A `>` block quote; [spans] is the inline content of the quoted text. */
    data class BlockQuote(val spans: List<InlineSpan>) : MarkdownBlock

    /**
     * A GitHub-flavored pipe table (issue #921).
     *
     * [header] is the single header row; [rows] are the body rows. Each cell is
     * its own list of inline spans (so emphasis/code/links render inside cells).
     * [alignments] mirrors the delimiter row (one entry per column) so the
     * renderer can justify cells; columns with no explicit alignment are
     * [Alignment.NONE].
     */
    data class Table(
        val header: List<List<InlineSpan>>,
        val alignments: List<Alignment>,
        val rows: List<List<List<InlineSpan>>>,
    ) : MarkdownBlock {
        /** Per-column text justification derived from the `|---|:--:|` delimiter row. */
        enum class Alignment { NONE, LEFT, CENTER, RIGHT }
    }

    /** A thematic break (`---`, `***`, `___`). */
    data object HorizontalRule : MarkdownBlock
}

/** An inline-level Markdown run. */
internal sealed interface InlineSpan {

    /** Plain text with optional emphasis. */
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strikethrough: Boolean = false,
    ) : InlineSpan

    /** `` `code` `` inline span — rendered monospaced. */
    data class Code(val text: String) : InlineSpan

    /** `[label](url)` link. [label] is the visible text; [url] the target. */
    data class Link(val label: String, val url: String) : InlineSpan
}
