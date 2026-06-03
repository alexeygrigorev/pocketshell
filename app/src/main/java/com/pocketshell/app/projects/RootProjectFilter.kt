package com.pocketshell.app.projects

/**
 * Fuzzy, ranked filtering for the root-project add sheet candidate list.
 *
 * Replaces the plain substring filter that used to live on
 * [FolderListViewModel] so that abbreviations like `psh` match `pocketshell`.
 * Matching is a case-insensitive subsequence test against the candidate label
 * (and, as a fallback, the path), and results are ranked so the strongest
 * match surfaces first while history candidates keep priority on ties.
 */
object RootProjectFilter {

    /** Match strength buckets, smaller is stronger. */
    private const val RANK_EXACT_PREFIX = 0
    private const val RANK_WORD_BOUNDARY = 1
    private const val RANK_SUBSEQUENCE = 2
    private const val RANK_PATH_ONLY = 3
    private const val RANK_NONE = Int.MAX_VALUE

    /**
     * Filter [candidates] by [query] using fuzzy subsequence matching and
     * return them ranked: exact-prefix > word-boundary > subsequence on the
     * label, then path-only subsequence matches. History candidates rank ahead
     * of scanned ones on ties, preserving the input order (which is already
     * history-first by recency) as the final tiebreak.
     *
     * A blank query returns [candidates] unchanged so the default sheet keeps
     * its existing history-first ordering.
     */
    fun filter(
        candidates: List<RootProjectCandidate>,
        query: String,
    ): List<RootProjectCandidate> {
        val clean = query.trim()
        if (clean.isEmpty()) return candidates

        return candidates
            .mapIndexedNotNull { index, candidate ->
                val rank = matchRank(candidate, clean)
                if (rank == RANK_NONE) null else Ranked(candidate, rank, index)
            }
            .sortedWith(
                compareBy<Ranked> { it.rank }
                    .thenBy { if (it.candidate.source == RootProjectSource.History) 0 else 1 }
                    .thenBy { it.inputOrder },
            )
            .map { it.candidate }
    }

    private data class Ranked(
        val candidate: RootProjectCandidate,
        val rank: Int,
        val inputOrder: Int,
    )

    private fun matchRank(candidate: RootProjectCandidate, query: String): Int {
        val label = candidate.label
        val labelLower = label.lowercase()
        val queryLower = query.lowercase()

        if (labelLower.startsWith(queryLower)) return RANK_EXACT_PREFIX
        if (matchesWordBoundary(label, queryLower)) return RANK_WORD_BOUNDARY
        if (isSubsequence(labelLower, queryLower)) return RANK_SUBSEQUENCE
        if (isSubsequence(candidate.path.lowercase(), queryLower)) return RANK_PATH_ONLY
        return RANK_NONE
    }

    /**
     * True when [query] is a prefix of any word in [text] (words split on
     * common path/identifier separators) or matches the initials of those
     * words, e.g. `zoom` matches `llm-zoomcamp` and `psh` matches the
     * `p`/`s`/`h` initials of `pocket-shell-host`.
     */
    private fun matchesWordBoundary(text: String, queryLower: String): Boolean {
        val words = text.split('-', '_', ' ', '.', '/').filter { it.isNotEmpty() }
        if (words.any { it.lowercase().startsWith(queryLower) }) return true
        val initials = words.joinToString(separator = "") { it.first().lowercaseChar().toString() }
        return initials.startsWith(queryLower)
    }

    /** True when every char of [query] appears in [text] in order (not necessarily contiguous). */
    private fun isSubsequence(text: String, query: String): Boolean {
        if (query.isEmpty()) return true
        var qi = 0
        for (ch in text) {
            if (ch == query[qi]) {
                qi++
                if (qi == query.length) return true
            }
        }
        return false
    }
}
