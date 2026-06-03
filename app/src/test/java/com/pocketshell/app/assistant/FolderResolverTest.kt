package com.pocketshell.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [FolderResolver] (issue #434). No emulator needed,
 * mirroring [CommandSafetyTest]. Covers the three resolution bands plus the
 * load-bearing safety invariant: every resolved/offered candidate is drawn
 * from the input set, so a hallucinated path can never reach start_session.
 */
class FolderResolverTest {

    private val candidates = listOf(
        FolderCandidate("/home/dev/ai-shipping-labs/workshops", "workshops", 1),
        FolderCandidate("/home/dev/rov/workshop", "ROV workshop", 0),
        FolderCandidate("/home/dev/notes/workshop", "workshop", 2),
        FolderCandidate("/home/dev/git/pocketshell", "pocketshell", 3),
        FolderCandidate("/home/dev/git/ssh-auto-forward", "ssh-auto-forward", 0),
    )

    @Test
    fun confident_singleClearMatch_resolvesToThatCwd() {
        val result = FolderResolver.resolve("ai shipping labs workshops", candidates)
        assertTrue("expected confident, got $result", result is FolderResolution.Confident)
        result as FolderResolution.Confident
        assertEquals("/home/dev/ai-shipping-labs/workshops", result.candidate.path)
    }

    @Test
    fun confident_pathTailMatch_evenWhenLabelDiffers() {
        val result = FolderResolver.resolve("pocketshell", candidates)
        assertTrue(result is FolderResolution.Confident)
        assertEquals(
            "/home/dev/git/pocketshell",
            (result as FolderResolution.Confident).candidate.path,
        )
    }

    @Test
    fun ambiguous_underspecifiedQuery_asksWhichOne() {
        // "workshop" matches three folders comparably → must ASK, not guess.
        val result = FolderResolver.resolve("workshop", candidates)
        assertTrue("expected ambiguous, got $result", result is FolderResolution.Ambiguous)
        result as FolderResolution.Ambiguous
        assertTrue("ambiguous set must have >1 candidate", result.candidates.size > 1)
        val paths = result.candidates.map { it.path }.toSet()
        assertTrue(paths.contains("/home/dev/rov/workshop"))
        assertTrue(paths.contains("/home/dev/notes/workshop"))
    }

    @Test
    fun noMatch_returnsNearestForDidYouMean() {
        val result = FolderResolver.resolve("kubernetes deployment manifests", candidates)
        assertTrue("expected no match, got $result", result is FolderResolution.NoMatch)
    }

    @Test
    fun noMatch_onEmptyCandidateSet() {
        val result = FolderResolver.resolve("anything", emptyList())
        assertTrue(result is FolderResolution.NoMatch)
    }

    @Test
    fun neverEmitsNonCandidateCwd_acrossManyQueries() {
        val knownPaths = candidates.map { it.path }.toSet()
        val queries = listOf(
            "workshops", "workshop", "rov workshop", "ai shipping labs", "shipping",
            "pocketshell", "ssh forward", "totally unknown thing", "labs", "git",
        )
        for (q in queries) {
            val emitted: List<FolderCandidate> = when (val r = FolderResolver.resolve(q, candidates)) {
                is FolderResolution.Confident -> listOf(r.candidate)
                is FolderResolution.Ambiguous -> r.candidates
                is FolderResolution.NoMatch -> r.nearest
            }
            emitted.forEach { c ->
                assertTrue(
                    "query \"$q\" produced non-candidate path ${c.path}",
                    c.path in knownPaths,
                )
            }
        }
    }

    @Test
    fun caseAndSeparatorInsensitive() {
        val result = FolderResolver.resolve("SSH_Auto_Forward", candidates)
        assertTrue(result is FolderResolution.Confident)
        assertEquals(
            "/home/dev/git/ssh-auto-forward",
            (result as FolderResolution.Confident).candidate.path,
        )
    }
}
