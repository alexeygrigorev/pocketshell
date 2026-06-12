package com.pocketshell.app.fileviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

/**
 * Unit tests for the pure `pocketshell_review` YAML export builder (issue #714).
 *
 * The format decision mandates a REAL YAML serializer, NOT string concat:
 * comment text + code lines contain colons, quotes, `#`, and newlines, and
 * multi-line `text` must emit as a literal block scalar (`|`). These tests feed
 * those exact tricky inputs and assert the output PARSES BACK to the same
 * structure via the YAML library — the round-trip proof that escaping is
 * correct.
 */
class ReviewExportTest {

    private val parser = Yaml()

    @Suppress("UNCHECKED_CAST")
    private fun parse(yaml: String): Map<String, Any?> =
        parser.load<Map<String, Any?>>(yaml)

    @Test
    fun `builds the documented schema scalars`() {
        val yaml = ReviewExport.buildReviewYaml(
            state = ReviewState(lineComments = mapOf(42 to "hoist this")),
            host = "hetzner",
            file = "/home/alexey/git/proj/Foo.kt",
            lines = List(50) { "line ${it + 1}" },
            submittedAt = "2026-06-12T05:30:00Z",
        )
        val root = parse(yaml)
        assertEquals("pocketshell_review", root["type"])
        assertEquals(1, root["schema"])
        assertEquals("hetzner", root["host"])
        assertEquals("/home/alexey/git/proj/Foo.kt", root["file"])
        assertEquals("2026-06-12T05:30:00Z", root["submitted_at"])
    }

    @Test
    fun `line comment carries the verbatim code anchor`() {
        val lines = listOf("def a():", "    val x = doThing(y)  # note", "    return null")
        val yaml = ReviewExport.buildReviewYaml(
            state = ReviewState(lineComments = mapOf(2 to "hot path")),
            host = "h",
            file = "/f.py",
            lines = lines,
            submittedAt = "2026-06-12T00:00:00Z",
        )
        val root = parse(yaml)
        val comments = root["comments"] as List<Map<String, Any?>>
        assertEquals(1, comments.size)
        assertEquals(2, comments[0]["line"])
        // The verbatim line — leading whitespace AND the trailing `#` survive.
        assertEquals("    val x = doThing(y)  # note", comments[0]["code"])
        assertEquals("hot path", comments[0]["text"])
    }

    @Test
    fun `tricky text with colons quotes and hashes round-trips`() {
        val tricky = """key: "value" # not a comment, just text with: colons"""
        val yaml = ReviewExport.buildReviewYaml(
            state = ReviewState(lineComments = mapOf(1 to tricky)),
            host = "h",
            file = "/f",
            lines = listOf("x"),
            submittedAt = "t",
        )
        val root = parse(yaml)
        val comments = root["comments"] as List<Map<String, Any?>>
        // Parsed back EXACTLY — the serializer quoted/escaped as needed, we
        // never hand-built the string.
        assertEquals(tricky, comments[0]["text"])
    }

    @Test
    fun `multi-line text emits as a literal block scalar and round-trips`() {
        val multi = "this allocation is on the hot path — can we hoist it?\nalso rename x to something clearer."
        val yaml = ReviewExport.buildReviewYaml(
            state = ReviewState(lineComments = mapOf(42 to multi)),
            host = "h",
            file = "/f",
            lines = List(42) { "line" },
            submittedAt = "t",
        )
        // The format decision wants a `|` block scalar for multi-line text, not
        // a `"…\n…"` double-quoted blob.
        assertTrue("expected a literal block scalar `|` in:\n$yaml", yaml.contains("text: |"))

        val root = parse(yaml)
        val comments = root["comments"] as List<Map<String, Any?>>
        assertEquals(multi, comments[0]["text"])
    }

    @Test
    fun `code with leading whitespace and special chars round-trips`() {
        val code = "\t  if (x == null) { return \"#nope\"; } // trailing: comment"
        val yaml = ReviewExport.buildReviewYaml(
            state = ReviewState(lineComments = mapOf(1 to "fix this")),
            host = "h",
            file = "/f",
            lines = listOf(code),
            submittedAt = "t",
        )
        val root = parse(yaml)
        val comments = root["comments"] as List<Map<String, Any?>>
        assertEquals(code, comments[0]["code"])
    }

    @Test
    fun `file comment uses scope file and no line or code`() {
        val yaml = ReviewExport.buildReviewYaml(
            state = ReviewState(fileComment = "overall the structure is good"),
            host = "h",
            file = "/f",
            lines = listOf("x"),
            submittedAt = "t",
        )
        val root = parse(yaml)
        val comments = root["comments"] as List<Map<String, Any?>>
        assertEquals(1, comments.size)
        assertEquals("file", comments[0]["scope"])
        assertEquals("overall the structure is good", comments[0]["text"])
        assertNull(comments[0]["line"])
        assertNull(comments[0]["code"])
    }

    @Test
    fun `comments ordered by line then file note last`() {
        val yaml = ReviewExport.buildReviewYaml(
            state = ReviewState(
                lineComments = mapOf(88 to "later", 42 to "earlier", 5 to "first"),
                fileComment = "overall",
            ),
            host = "h",
            file = "/f",
            lines = List(100) { "line ${it + 1}" },
            submittedAt = "t",
        )
        val root = parse(yaml)
        val comments = root["comments"] as List<Map<String, Any?>>
        assertEquals(listOf(5, 42, 88, null), comments.map { it["line"] })
        // The file note is last, with scope=file.
        assertEquals("file", comments.last()["scope"])
    }

    @Test
    fun `line index out of range yields empty code rather than throwing`() {
        val yaml = ReviewExport.buildReviewYaml(
            state = ReviewState(lineComments = mapOf(999 to "stray")),
            host = "h",
            file = "/f",
            lines = listOf("only one line"),
            submittedAt = "t",
        )
        val root = parse(yaml)
        val comments = root["comments"] as List<Map<String, Any?>>
        assertEquals(999, comments[0]["line"])
        assertEquals("", comments[0]["code"])
    }

    @Test
    fun `orderedComments builds Line and File comment models with anchors`() {
        val state = ReviewState(
            lineComments = mapOf(2 to "b", 1 to "a"),
            fileComment = "note",
        )
        val comments = ReviewExport.orderedComments(state, listOf("first", "second"))
        assertEquals(
            listOf(
                ReviewComment.Line(line = 1, code = "first", text = "a"),
                ReviewComment.Line(line = 2, code = "second", text = "b"),
                ReviewComment.File(text = "note"),
            ),
            comments,
        )
    }
}
