package com.pocketshell.app.fileviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

/**
 * Issue #764 — the `pocketshell_annotation` YAML sidecar (mirrors
 * [ReviewExportTest]). Asserts the schema fields, that a multi-line note emits
 * as a literal block scalar that round-trips through a real parser, and that a
 * blank note is omitted.
 */
class AnnotationExportTest {

    @Test
    fun buildsTheCanonicalSchemaWithProvenanceFields() {
        val yaml = AnnotationExport.buildAnnotationYaml(
            host = "hetzner",
            sourceFile = "/srv/screenshot.png",
            image = "/home/alexey/inbox/pocketshell/annotations/screenshot-20260614-101010.png",
            submittedAt = "2026-06-14T10:10:10Z",
            note = "the circled button is misaligned",
        )

        assertTrue(yaml.contains("type: pocketshell_annotation"))
        assertTrue(yaml.contains("schema: 1"))
        assertTrue(yaml.contains("host: hetzner"))
        assertTrue(yaml.contains("source_file: /srv/screenshot.png"))
        assertTrue(yaml.contains("image: /home/alexey/inbox/pocketshell/annotations/screenshot-20260614-101010.png"))
        assertTrue(yaml.contains("the circled button is misaligned"))

        // Round-trips through a real YAML parser — assert structured values
        // (snakeyaml may quote the ISO timestamp, so check the parsed map).
        @Suppress("UNCHECKED_CAST")
        val parsed = Yaml().load<Map<String, Any>>(yaml)
        assertTrue(parsed["type"] == "pocketshell_annotation")
        assertEquals(1, parsed["schema"])
        assertEquals("hetzner", parsed["host"])
        assertEquals("/srv/screenshot.png", parsed["source_file"])
        assertEquals("2026-06-14T10:10:10Z", parsed["submitted_at"])
        assertTrue(parsed["image"].toString().endsWith(".png"))
    }

    @Test
    fun multiLineNoteEmitsAsLiteralBlockAndRoundTrips() {
        val note = "first line\nsecond line: with a colon\nthird"
        val yaml = AnnotationExport.buildAnnotationYaml(
            host = "h",
            sourceFile = "/a.png",
            image = "/b.png",
            submittedAt = "2026-06-14T00:00:00Z",
            note = note,
        )
        // Literal block scalar marker.
        assertTrue("multi-line note must use a literal block (|), was:\n$yaml", yaml.contains("note: |"))

        @Suppress("UNCHECKED_CAST")
        val parsed = Yaml().load<Map<String, Any>>(yaml)
        assertTrue("note must round-trip intact", (parsed["note"] as String).trimEnd() == note)
    }

    @Test
    fun blankNoteIsOmitted() {
        val yaml = AnnotationExport.buildAnnotationYaml(
            host = "h",
            sourceFile = "/a.png",
            image = "/b.png",
            submittedAt = "2026-06-14T00:00:00Z",
            note = null,
        )
        assertFalse("no note key when note is null", yaml.contains("note:"))

        val blank = AnnotationExport.buildAnnotationYaml(
            host = "h",
            sourceFile = "/a.png",
            image = "/b.png",
            submittedAt = "2026-06-14T00:00:00Z",
            note = "   ",
        )
        assertFalse("no note key when note is blank", blank.contains("note:"))
    }
}
