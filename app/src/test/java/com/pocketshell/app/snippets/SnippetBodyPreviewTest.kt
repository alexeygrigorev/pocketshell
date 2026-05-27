package com.pocketshell.app.snippets

import com.pocketshell.core.storage.entity.SnippetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [snippetBodyPreview] (issue #198).
 *
 * The picker row renders the returned preview as a 12 sp monospace
 * `TextMuted` single line; these tests pin the helper's contract so a
 * future refactor cannot quietly regress the visible behaviour:
 *
 *  - Always returns a preview when the body carries content beyond the
 *    displayed label (single- or multi-line).
 *  - Suppresses the preview row entirely (returns `null`) for empty or
 *    whitespace-only bodies so the row does not render a blank padding
 *    band.
 *  - Suppresses the preview when the body collapses to exactly the
 *    label — repeating the primary text adds no information.
 *  - Collapses `\n`, `\r`, and `\r\n` to single spaces so multi-line
 *    bodies surface their hidden lines in the one-line preview.
 *
 * The function is pure (no Compose state, no DAO), so the tests are
 * plain JUnit and do not need a Robolectric / Compose runtime.
 */
class SnippetBodyPreviewTest {

    private fun snippet(label: String?, body: String): SnippetEntity =
        SnippetEntity(id = 1L, hostId = 1L, label = label, body = body, kind = "command")

    @Test
    fun returnsNull_whenBodyIsEmpty() {
        // Acceptance: empty bodies render no preview row (no blank
        // padding). Defensive — the editor blocks blank-body inserts.
        val s = snippet(label = "labelled", body = "")
        assertNull(snippetBodyPreview(s, s.displayLabel()))
    }

    @Test
    fun returnsNull_whenBodyIsWhitespaceOnly() {
        // Same rule as empty — a body of `"   \n  "` has no meaningful
        // content to preview.
        val s = snippet(label = "labelled", body = "   \n  \t ")
        assertNull(snippetBodyPreview(s, s.displayLabel()))
    }

    @Test
    fun returnsNull_whenBodyEqualsDerivedLabel_singleLine() {
        // The derived label IS the body's first (only) line, so showing
        // the body again would just duplicate the primary text.
        val s = snippet(label = null, body = "kubectl get pods -A")
        val displayLabel = s.displayLabel()
        assertEquals("kubectl get pods -A", displayLabel)
        assertNull(snippetBodyPreview(s, displayLabel))
    }

    @Test
    fun returnsNull_whenExplicitLabelMatchesBodyVerbatim() {
        // Dedup also fires when the user happens to type the body as
        // the explicit label — same "no information added" reasoning.
        val s = snippet(label = "echo done", body = "echo done")
        assertNull(snippetBodyPreview(s, s.displayLabel()))
    }

    @Test
    fun returnsPreview_whenExplicitLabelDiffersFromBody() {
        // Explicit-label rows are the most common case the preview was
        // added for — the user picked a human-readable label but the
        // body is what will actually be sent.
        val s = snippet(label = "list pods", body = "kubectl get pods -A")
        assertEquals("kubectl get pods -A", snippetBodyPreview(s, s.displayLabel()))
    }

    @Test
    fun returnsPreview_forMultiLineDerivedLabel_collapsingNewlines() {
        // The derived label only shows the first line; the picker
        // preview surfaces the rest by collapsing `\n` to a space.
        val s = snippet(
            label = null,
            body = "kubectl logs -f deploy/api\n  --since=10m --tail=200",
        )
        val displayLabel = s.displayLabel()
        assertEquals("kubectl logs -f deploy/api", displayLabel)
        val preview = snippetBodyPreview(s, displayLabel)
        assertNotNull(preview)
        // Newline collapsed to a single space; the leading whitespace
        // on the continuation line is preserved verbatim (shell bodies
        // rely on indentation).
        assertEquals(
            "kubectl logs -f deploy/api   --since=10m --tail=200",
            preview,
        )
    }

    @Test
    fun returnsPreview_forMultiLineExplicitLabel() {
        // Explicit-label multi-line body: preview shows the body
        // collapsed onto one line so the user sees what the snippet
        // will actually send.
        val s = snippet(
            label = "boot script",
            body = "echo first\necho second",
        )
        assertEquals("echo first echo second", snippetBodyPreview(s, s.displayLabel()))
    }

    @Test
    fun collapsesCarriageReturnAndCrlf() {
        // Defensive against legacy or copy-pasted line endings. All
        // three styles must collapse the same way so the preview reads
        // identically regardless of where the body came from.
        val crlf = snippet(label = "lab", body = "alpha\r\nbeta")
        val cr = snippet(label = "lab", body = "alpha\rbeta")
        val lf = snippet(label = "lab", body = "alpha\nbeta")
        assertEquals("alpha beta", snippetBodyPreview(crlf, crlf.displayLabel()))
        assertEquals("alpha beta", snippetBodyPreview(cr, cr.displayLabel()))
        assertEquals("alpha beta", snippetBodyPreview(lf, lf.displayLabel()))
    }

    @Test
    fun preservesInternalWhitespaceRuns() {
        // Shell bodies frequently rely on doubled spaces (here-doc
        // indentation, awk field separators); we must not collapse
        // internal whitespace beyond the newline normalisation.
        val s = snippet(label = "awk field", body = "awk  '{print  \$1}'")
        assertEquals("awk  '{print  \$1}'", snippetBodyPreview(s, s.displayLabel()))
    }

    @Test
    fun trimsLeadingAndTrailingWhitespace() {
        // The body itself might end in a trailing `\n` from the editor
        // — that should not surface as a trailing space in the
        // one-line preview.
        val s = snippet(label = "trailing", body = "echo done\n")
        assertEquals("echo done", snippetBodyPreview(s, s.displayLabel()))
    }

    @Test
    fun returnsNull_whenMultiLineBodyCollapsesToDerivedLabel() {
        // Edge case: the body is `"echo only\n"` — the derived label is
        // `"echo only"`, and once newlines are stripped and the result
        // is trimmed, the preview would just say `"echo only"`. Dedup
        // suppresses the duplicate so the picker stays clean.
        val s = snippet(label = null, body = "echo only\n")
        assertNull(snippetBodyPreview(s, s.displayLabel()))
    }

    @Test
    fun longSingleLineBody_isReturnedVerbatim() {
        // The Compose `Text(maxLines = 1, overflow = Ellipsis)` handles
        // visual truncation — the helper itself must not pre-truncate
        // or the test would lose track of what is being shown.
        val longBody =
            "kubectl logs --since=24h deploy/api-gateway -n production | grep ERROR | head -50"
        val s = snippet(label = "logs", body = longBody)
        assertEquals(longBody, snippetBodyPreview(s, s.displayLabel()))
    }
}
