package com.pocketshell.app.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests over the `git show` diff parser — issue #1242.
 *
 * The gateway pipes `git show --no-color <ref>` through `head -c cap+1` and hands
 * the raw output to [GitHistoryGateway.parseDiff]. These pin the line
 * classification (the `+`/`-` gutter + syntax-neutral kind) and — the
 * load-bearing safety property — the truncation/windowing boundary that keeps a
 * giant commit from producing an unbounded list.
 */
class GitCommitDiffParseTest {

    private val sampleDiff = buildString {
        appendLine("commit a1b2c3d4e5f6")
        appendLine("Author: Ada Lovelace <ada@example.com>")
        appendLine("Date:   Mon Jul 6 10:00:00 2026 +0000")
        appendLine()
        appendLine("    Add timeline view")
        appendLine()
        appendLine("diff --git a/app/Foo.kt b/app/Foo.kt")
        appendLine("index 1111111..2222222 100644")
        appendLine("--- a/app/Foo.kt")
        appendLine("+++ b/app/Foo.kt")
        appendLine("@@ -1,3 +1,4 @@")
        appendLine(" fun foo() {")
        appendLine("-    val old = 1")
        appendLine("+    val new = 2")
        appendLine("+    val added = 3")
        appendLine(" }")
    }

    @Test
    fun `classifies commit metadata file headers hunk headers and add remove context`() {
        val diff = GitHistoryGateway.parseDiff("a1b2c3d", sampleDiff, byteCapExceeded = false)

        // Commit header block.
        assertEquals(DiffLineKind.CommitMeta, lineByContent(diff, "commit a1b2c3d4e5f6").kind)
        assertEquals(
            DiffLineKind.CommitMeta,
            lineByContent(diff, "Author: Ada Lovelace <ada@example.com>").kind,
        )
        // File header block.
        assertEquals(
            DiffLineKind.FileHeader,
            lineByContent(diff, "diff --git a/app/Foo.kt b/app/Foo.kt").kind,
        )
        assertEquals(DiffLineKind.FileHeader, lineByContent(diff, "--- a/app/Foo.kt").kind)
        assertEquals(DiffLineKind.FileHeader, lineByContent(diff, "+++ b/app/Foo.kt").kind)
        // Hunk header.
        assertEquals(DiffLineKind.HunkHeader, lineByContent(diff, "@@ -1,3 +1,4 @@").kind)
    }

    @Test
    fun `plus and minus lines get a gutter and stripped content but the file headers do not`() {
        val diff = GitHistoryGateway.parseDiff("a1b2c3d", sampleDiff, byteCapExceeded = false)

        val added = diff.lines.first { it.kind == DiffLineKind.Added && it.content.contains("new") }
        assertEquals("+", added.gutter)
        assertEquals("    val new = 2", added.content)

        val removed = diff.lines.first { it.kind == DiffLineKind.Removed }
        assertEquals("-", removed.gutter)
        assertEquals("    val old = 1", removed.content)

        val context = diff.lines.first { it.kind == DiffLineKind.Context }
        assertEquals(" ", context.gutter)
        assertEquals("fun foo() {", context.content)

        // The `+++`/`---` file headers must NOT be misclassified as add/remove.
        assertTrue(
            diff.lines.none { it.kind == DiffLineKind.Added && it.content.startsWith("+ b/") },
        )
        assertFalse(diff.truncated)
    }

    @Test
    fun `not truncated when under both caps`() {
        val diff = GitHistoryGateway.parseDiff("a1b2c3d", sampleDiff, byteCapExceeded = false)
        assertFalse(diff.truncated)
    }

    @Test
    fun `byte-cap-exceeded flag propagates to truncated`() {
        val diff = GitHistoryGateway.parseDiff("a1b2c3d", sampleDiff, byteCapExceeded = true)
        assertTrue(diff.truncated)
    }

    @Test
    fun `line cap windows the list and marks truncated at the boundary`() {
        // 10 lines of content but a cap of 4 → exactly 4 kept, truncated=true.
        val raw = (1..10).joinToString("\n") { "+line $it" }
        val diff = GitHistoryGateway.parseDiff("a1b2c3d", raw, byteCapExceeded = false, maxLines = 4)
        assertEquals(4, diff.lines.size)
        assertTrue("more lines than the cap must mark truncated", diff.truncated)
    }

    @Test
    fun `exactly at the line cap is not truncated`() {
        val raw = (1..4).joinToString("\n") { "+line $it" }
        val diff = GitHistoryGateway.parseDiff("a1b2c3d", raw, byteCapExceeded = false, maxLines = 4)
        assertEquals(4, diff.lines.size)
        assertFalse("exactly at the cap is complete, not truncated", diff.truncated)
    }

    @Test
    fun `trailing newline does not create a spurious empty line`() {
        val diff = GitHistoryGateway.parseDiff("a1b2c3d", "+one\n+two\n", byteCapExceeded = false)
        assertEquals(2, diff.lines.size)
    }

    @Test
    fun `interior blank context line is preserved`() {
        // A blank context line in unified diff is a single space; keep it. Inside
        // a hunk (after @@) so it classifies as context, not commit metadata.
        val diff = GitHistoryGateway.parseDiff(
            "a1b2c3d",
            "@@ -1,3 +1,3 @@\n a\n \n b",
            byteCapExceeded = false,
        )
        assertEquals(4, diff.lines.size)
        assertEquals(DiffLineKind.Context, diff.lines[2].kind)
    }

    @Test
    fun `no newline at end of file marker is neutral metadata not a removal`() {
        val diff = GitHistoryGateway.parseDiff(
            "a1b2c3d",
            "@@ -1 +1 @@\n-old\n+new\n\\ No newline at end of file",
            byteCapExceeded = false,
        )
        val marker = lineByContent(diff, "\\ No newline at end of file")
        assertEquals(DiffLineKind.CommitMeta, marker.kind)
    }

    private fun lineByContent(diff: GitCommitDiff, content: String): DiffLine =
        diff.lines.first { it.content == content }
}
