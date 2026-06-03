package com.pocketshell.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootProjectFilterTest {

    private fun candidate(
        path: String,
        label: String,
        source: RootProjectSource = RootProjectSource.Scanned,
    ) = RootProjectCandidate(path = path, label = label, source = source)

    @Test
    fun blankQueryReturnsCandidatesUnchanged() {
        val candidates = listOf(
            candidate("/home/alexey/git/pocketshell", "pocketshell"),
            candidate("/home/alexey/git/llm-zoomcamp", "llm-zoomcamp"),
        )
        assertEquals(candidates, RootProjectFilter.filter(candidates, ""))
        assertEquals(candidates, RootProjectFilter.filter(candidates, "   "))
    }

    @Test
    fun subsequenceMatchesAbbreviation() {
        val candidates = listOf(
            candidate("/home/alexey/git/pocketshell", "pocketshell"),
            candidate("/home/alexey/git/llm-zoomcamp", "llm-zoomcamp"),
            candidate("/srv/tools/beta", "beta"),
        )
        // "psh" is a subsequence of "pocketshell" (p..s..h) but not of the others.
        assertEquals(
            listOf("pocketshell"),
            RootProjectFilter.filter(candidates, "psh").map { it.label },
        )
    }

    @Test
    fun matchingIsCaseInsensitive() {
        val candidates = listOf(
            candidate("/home/alexey/git/pocketshell", "pocketshell"),
            candidate("/home/alexey/git/llm-zoomcamp", "llm-zoomcamp"),
        )
        assertEquals(
            listOf("llm-zoomcamp"),
            RootProjectFilter.filter(candidates, "ZOOM").map { it.label },
        )
        assertEquals(
            listOf("pocketshell"),
            RootProjectFilter.filter(candidates, "POCKET").map { it.label },
        )
    }

    @Test
    fun exactPrefixRanksAboveSubsequence() {
        val candidates = listOf(
            // subsequence-only match for "po": p..o in "epic-tools"? no. Use a real subsequence case.
            candidate("/srv/a/pure-orbit", "pure-orbit"), // "po" word-initials p,o
            candidate("/srv/b/portal", "portal"), // exact prefix "po"
        )
        val result = RootProjectFilter.filter(candidates, "po").map { it.label }
        assertEquals("portal", result.first())
        assertTrue(result.contains("pure-orbit"))
    }

    @Test
    fun wordBoundaryMatchRanksAbovePlainSubsequence() {
        val candidates = listOf(
            // "zoom" is a subsequence of "z...o...o...m" path-wise, but a word-prefix in zoomcamp.
            candidate("/home/x/llm-zoomcamp", "llm-zoomcamp"), // word "zoomcamp" starts with "zoom"
            candidate("/home/x/zebra-room", "zebra-room"), // subsequence z,o,o,m across "zebra-room"? z-e-b-r-a-r-o-o-m -> z..o..o..m yes
        )
        val result = RootProjectFilter.filter(candidates, "zoom").map { it.label }
        assertEquals("llm-zoomcamp", result.first())
    }

    @Test
    fun wordInitialsMatch() {
        val candidates = listOf(
            candidate("/srv/a/pocket-shell-host", "pocket-shell-host"),
            candidate("/srv/b/other", "other"),
        )
        // initials p,s,h
        assertEquals(
            listOf("pocket-shell-host"),
            RootProjectFilter.filter(candidates, "psh").map { it.label },
        )
    }

    @Test
    fun historyRanksAheadOfScannedOnTie() {
        val candidates = listOf(
            candidate("/srv/scan/portal", "portal", RootProjectSource.Scanned),
            candidate("/srv/hist/portal-app", "portal-app", RootProjectSource.History),
        )
        // Both are exact-prefix matches for "portal"; history wins the tie.
        val result = RootProjectFilter.filter(candidates, "portal").map { it.label }
        assertEquals("portal-app", result.first())
    }

    @Test
    fun pathOnlyMatchIsIncludedButRanksLast() {
        val candidates = listOf(
            candidate("/home/alexey/git/pocketshell", "pocketshell"),
            candidate("/srv/tools/beta", "beta"),
        )
        // "/srv" matches only on the path of beta, not its label.
        val result = RootProjectFilter.filter(candidates, "/srv").map { it.label }
        assertEquals(listOf("beta"), result)
    }

    @Test
    fun nonMatchingQueryReturnsEmpty() {
        val candidates = listOf(
            candidate("/home/alexey/git/pocketshell", "pocketshell"),
        )
        assertTrue(RootProjectFilter.filter(candidates, "xyzq").isEmpty())
        assertFalse(RootProjectFilter.filter(candidates, "pocket").isEmpty())
    }
}
