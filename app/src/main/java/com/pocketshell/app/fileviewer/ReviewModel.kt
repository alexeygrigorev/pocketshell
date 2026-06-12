package com.pocketshell.app.fileviewer

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer

/**
 * Review-comments state for the file viewer (issue #714, slice 1).
 *
 * In review mode the maintainer leaves comments on individual lines and/or a
 * single file-level comment, reviews the pending set, then submits. The state
 * lives in [FileViewerViewModel] (not Compose `remember`) so it survives scroll
 * and config change — the issue's "don't lose them on a config change / scroll"
 * criterion.
 *
 *  - [active]       — review mode on/off. When on, the text panel swaps from the
 *                     plain blob view to the per-line commentable panel.
 *  - [lineComments] — 1-based line number → comment text. One comment per line
 *                     in v1 (the YAML schema keys comments by line; the issue's
 *                     "multiple comments allowed" is satisfied across lines, and
 *                     re-commenting a line overwrites it via the edit sheet).
 *  - [fileComment]  — the optional whole-file comment ("File note").
 *  - [submitting]   — true while a submit is in flight (slice 2 wires the SSH
 *                     write; slice 1 only builds the YAML).
 *
 * All edit/delete/clear ops are pure and unit-tested.
 */
data class ReviewState(
    val active: Boolean = false,
    val lineComments: Map<Int, String> = emptyMap(),
    val fileComment: String? = null,
    val submitting: Boolean = false,
) {
    /** Number of pending comments — line comments plus the file comment if set. */
    val pendingCount: Int
        get() = lineComments.size + (if (fileComment.isNullOrEmpty()) 0 else 1)

    /** True when there is at least one comment to submit. */
    val hasPending: Boolean
        get() = pendingCount > 0

    /** Whether [line] (1-based) currently carries a comment. */
    fun hasLineComment(line: Int): Boolean = lineComments.containsKey(line)

    /**
     * Set or overwrite the comment on [line] (1-based). A blank/empty [text]
     * removes the comment instead (so an emptied edit sheet clears it), keeping
     * the pending set free of empty entries.
     */
    fun withLineComment(line: Int, text: String): ReviewState {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) {
            withoutLineComment(line)
        } else {
            copy(lineComments = lineComments + (line to trimmed))
        }
    }

    /** Remove the comment on [line] (1-based); a no-op if none exists. */
    fun withoutLineComment(line: Int): ReviewState =
        if (lineComments.containsKey(line)) copy(lineComments = lineComments - line) else this

    /**
     * Set or overwrite the whole-file comment. A blank/empty [text] clears it
     * (nulls the field) so the pending count drops back.
     */
    fun withFileComment(text: String): ReviewState {
        val trimmed = text.trim()
        return copy(fileComment = trimmed.ifEmpty { null })
    }

    /** Clear the whole-file comment. */
    fun withoutFileComment(): ReviewState = copy(fileComment = null)

    /**
     * Clear every pending comment but keep review mode active — used after a
     * successful submit so the user can keep reviewing the same file.
     */
    fun cleared(): ReviewState = copy(lineComments = emptyMap(), fileComment = null, submitting = false)
}

/**
 * One comment in the `pocketshell_review` export — either anchored to a line
 * (with the verbatim [code] of that line so an agent can re-locate it if its
 * copy drifted) or scoped to the whole file.
 */
sealed interface ReviewComment {
    val text: String

    data class Line(val line: Int, val code: String, override val text: String) : ReviewComment

    data class File(override val text: String) : ReviewComment
}

/**
 * Pure assembly + serialization of a review into the `pocketshell_review` YAML
 * schema (issue #714). Kept separate from [FileViewerViewModel] so it is unit-
 * testable with no Android / SSH dependency.
 */
object ReviewExport {

    const val TYPE: String = "pocketshell_review"
    const val SCHEMA: Int = 1

    /**
     * Order the pending comments into the export list: line comments sorted by
     * ascending line number first, then the file comment last (matching the
     * format decision's example). [lines] is the file body already split on
     * `"\n"` so a line comment carries the verbatim source of `lines[line - 1]`
     * as its [ReviewComment.Line.code] anchor. A line index outside [lines]
     * (defensive) yields an empty code string rather than throwing.
     */
    fun orderedComments(state: ReviewState, lines: List<String>): List<ReviewComment> {
        val lineComments = state.lineComments.entries
            .sortedBy { it.key }
            .map { (line, text) ->
                val code = lines.getOrNull(line - 1) ?: ""
                ReviewComment.Line(line = line, code = code, text = text)
            }
        val fileComment = state.fileComment
            ?.takeIf { it.isNotEmpty() }
            ?.let { ReviewComment.File(text = it) }
        return lineComments + listOfNotNull(fileComment)
    }

    /**
     * Build the canonical `pocketshell_review` YAML for [state] over the file at
     * [file] on [host], with each line comment's verbatim source pulled from
     * [lines] (the body split on `"\n"`). [submittedAt] is supplied by the
     * caller (ISO-8601 UTC) — this builder is pure and never reads the clock.
     *
     * A REAL YAML serializer (snakeyaml) does the encoding, NOT string concat:
     * comment text and code lines contain colons, quotes, `#`, and newlines, and
     * multi-line `text` must emit as a literal block scalar (`|`). The custom
     * [Representer] routes any multi-line string to [DumperOptions.ScalarStyle.LITERAL]
     * so it round-trips cleanly, while single-line strings stay compact.
     */
    fun buildReviewYaml(
        state: ReviewState,
        host: String,
        file: String,
        lines: List<String>,
        submittedAt: String,
    ): String {
        // LinkedHashMap preserves key order so the document reads
        // type/schema/host/file/submitted_at/comments top-to-bottom.
        val root = LinkedHashMap<String, Any>()
        root["type"] = TYPE
        root["schema"] = SCHEMA
        root["host"] = host
        root["file"] = file
        root["submitted_at"] = submittedAt
        root["comments"] = orderedComments(state, lines).map { it.toYamlMap() }

        return yaml().dump(root)
    }

    private fun ReviewComment.toYamlMap(): Map<String, Any> = when (this) {
        is ReviewComment.Line -> linkedMapOf(
            "line" to line,
            "code" to code,
            "text" to text,
        )
        is ReviewComment.File -> linkedMapOf(
            "scope" to "file",
            "text" to text,
        )
    }

    /**
     * A snakeyaml [Yaml] tuned for the review export: block style, no document
     * start marker, and a representer that emits multi-line strings as literal
     * block scalars (`|`) so a multi-paragraph comment is readable and escape-
     * free instead of a `"…\n…"` double-quoted blob.
     */
    private fun yaml(): Yaml {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = false
            // Keep insertion order from the LinkedHashMaps above.
            isAllowUnicode = true
            // Wide line width so single-line code/text isn't folded mid-string;
            // multi-line strings are handled by the LITERAL representer below.
            width = Int.MAX_VALUE
        }
        return Yaml(LiteralBlockRepresenter(options), options)
    }

    /**
     * Represents any [String] that contains a newline as a LITERAL block scalar
     * (`|`). Single-line strings fall back to snakeyaml's default scalar choice
     * (plain, or quoted when the content needs it — e.g. a leading space or a
     * `:`), so we never hand-escape anything.
     */
    private class LiteralBlockRepresenter(options: DumperOptions) : Representer(options) {
        init {
            representers[String::class.java] = RepresentString()
        }

        private inner class RepresentString : org.yaml.snakeyaml.representer.Represent {
            override fun representData(data: Any): Node {
                val value = data as String
                return if (value.contains('\n')) {
                    representScalar(Tag.STR, value, DumperOptions.ScalarStyle.LITERAL)
                } else {
                    representScalar(Tag.STR, value)
                }
            }
        }
    }
}
