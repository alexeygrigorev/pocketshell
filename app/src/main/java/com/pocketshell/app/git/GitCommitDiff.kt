package com.pocketshell.app.git

/**
 * A parsed unified diff for a single commit (or working-tree change) — issue
 * #1242. Read-only: reviewing a change from the phone without leaving the app.
 *
 * The raw `git show --no-color <ref>` output is classified line-by-line into
 * [DiffLine]s so the UI can render `+`/`-` gutters and syntax-neutral coloring
 * over the shared monospace renderer. Large diffs are bounded — see
 * [GitHistoryGateway.commitDiff] (server-side byte cap) and the render line cap —
 * so a huge commit never triggers an unbounded read or an unbounded list. When a
 * bound trips, [truncated] is set so the UI shows a visible truncation marker.
 */
data class GitCommitDiff(
    /** The commit ref this diff was fetched for (short hash from the log row). */
    val ref: String,
    /** Classified diff lines, newest cap first, in file order. */
    val lines: List<DiffLine>,
    /**
     * True when the output was cut off by the server-side byte cap or the render
     * line cap — the UI appends a truncation marker so the user knows there is
     * more that isn't shown (and can fall back to "Open on GitHub").
     */
    val truncated: Boolean,
)

/**
 * One rendered line of a unified diff. [gutter] is the `+`/`-`/space sign shown
 * in a fixed-width column; [content] is the code (with the leading diff prefix
 * already stripped for add/remove/context lines). [kind] drives the color.
 */
data class DiffLine(
    val gutter: String,
    val content: String,
    val kind: DiffLineKind,
)

/**
 * Syntax-neutral classification of a diff line — enough to color the `+`/`-`
 * gutters and set apart hunk headers, file headers, and the commit metadata
 * that `git show` prints above the patch.
 */
enum class DiffLineKind {
    /** Commit metadata git show prints first: `commit …`, `Author:`, `Date:`, subject. */
    CommitMeta,

    /** Per-file header lines: `diff --git`, `index`, `--- a/…`, `+++ b/…`, mode/rename/binary. */
    FileHeader,

    /** A `@@ -a,b +c,d @@` hunk header. */
    HunkHeader,

    /** An added line (`+…`). */
    Added,

    /** A removed line (`-…`). */
    Removed,

    /** An unchanged context line. */
    Context,
}
