package com.pocketshell.app.assistant

/**
 * One known folder the assistant can open a session in. Always drawn from a
 * real candidate set (live tmux session cwds + discovered/known project
 * folders) so the resolver can never hand a hallucinated path to `tmux -c`.
 *
 * @property path the absolute folder path (the only value ever used as a cwd).
 * @property label the human label (watched-folder label, or the path tail).
 * @property sessionCount active tmux sessions in this folder (for the chooser).
 */
internal data class FolderCandidate(
    val path: String,
    val label: String,
    val sessionCount: Int = 0,
)

/**
 * Outcome of resolving a fuzzy folder name against the known candidate set.
 *
 * Every [FolderCandidate] embedded in any branch is guaranteed to come from
 * the input list passed to [FolderResolver.resolve] — the resolver never
 * synthesises a path. That invariant is what keeps a model-invented folder
 * name from reaching `start_session` → `tmux -c`.
 */
internal sealed interface FolderResolution {
    /** Exactly one folder is a clear winner; proceed straight to create. */
    data class Confident(val candidate: FolderCandidate) : FolderResolution

    /** Several folders match comparably; ASK the user which one. */
    data class Ambiguous(val candidates: List<FolderCandidate>) : FolderResolution

    /** Nothing matched well enough; surface the [nearest] for a "did you mean". */
    data class NoMatch(val nearest: List<FolderCandidate>) : FolderResolution
}

/**
 * Pure, Android-free fuzzy folder resolver (kept like [CommandSafety] so it is
 * unit-testable without an emulator).
 *
 * Given a free-text query ("the AI shipping labs workshops folder") and the
 * FULL candidate folder set, it scores each candidate on TWO fields — the
 * human [FolderCandidate.label] and the path tail
 * (`path.substringAfterLast('/')`) — and sorts them into three bands:
 *
 *  - **Confident**: a single clear winner (top score ≥ [HIGH_SCORE] and its
 *    margin over the runner-up ≥ [CONFIDENT_MARGIN]).
 *  - **Ambiguous**: multiple candidates cluster near the top (all within
 *    [AMBIGUOUS_MARGIN] of the leader and each ≥ [LOW_SCORE]).
 *  - **NoMatch**: nothing clears [LOW_SCORE].
 *
 * Scoring is deterministic token overlap + substring + tail-exact bonuses; no
 * external fuzzy-match dependency. It is intentionally conservative: when the
 * query under-specifies (e.g. "workshop" matching both "ROV workshop" and a
 * bare "workshop"), it lands in the ambiguous band so the user is ASKED rather
 * than silently sent to a best guess.
 */
internal object FolderResolver {

    /** Minimum top score for a single confident match. */
    private const val HIGH_SCORE: Double = 0.55

    /** Top must beat the runner-up by this much to be confident. */
    private const val CONFIDENT_MARGIN: Double = 0.20

    /** Candidates within this margin of the leader are treated as a tie. */
    private const val AMBIGUOUS_MARGIN: Double = 0.20

    /** A candidate must reach this score to be a plausible match at all. */
    private const val LOW_SCORE: Double = 0.20

    /** How many "did you mean" suggestions to surface on a no-match. */
    private const val NEAREST_LIMIT: Int = 3

    /** Trailing path segments considered when matching multi-word queries. */
    private const val PATH_CONTEXT_SEGMENTS: Int = 3

    /** Path-context match counts slightly less than a label/tail match. */
    private const val PATH_CONTEXT_WEIGHT: Double = 0.9

    private data class Scored(val candidate: FolderCandidate, val score: Double)

    /**
     * Resolve [query] against [candidates]. Both arguments are taken as-is;
     * the caller is responsible for supplying the FULL untruncated candidate
     * set (never a `take(N)` summary, which could silently drop the target).
     */
    fun resolve(query: String, candidates: List<FolderCandidate>): FolderResolution {
        val queryTokens = tokenize(query)
        if (candidates.isEmpty() || queryTokens.isEmpty()) {
            return FolderResolution.NoMatch(candidates.take(NEAREST_LIMIT))
        }

        val scored = candidates
            .map { Scored(it, score(queryTokens, it)) }
            // Stable, deterministic order: best score first; ties broken by the
            // shorter (more specific) label, then the path for total order.
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenBy { it.candidate.label.length }
                    .thenBy { it.candidate.path },
            )

        val top = scored.first()
        if (top.score < LOW_SCORE) {
            return FolderResolution.NoMatch(scored.take(NEAREST_LIMIT).map { it.candidate })
        }

        val cluster = scored.filter { top.score - it.score <= AMBIGUOUS_MARGIN && it.score >= LOW_SCORE }
        val runnerUp = scored.getOrNull(1)
        val isClearWinner = top.score >= HIGH_SCORE &&
            (runnerUp == null || top.score - runnerUp.score >= CONFIDENT_MARGIN)

        return when {
            isClearWinner || cluster.size == 1 -> FolderResolution.Confident(top.candidate)
            else -> FolderResolution.Ambiguous(cluster.map { it.candidate })
        }
    }

    /**
     * Score one candidate against the tokenized query, taking the better of the
     * label match and the path-tail match. Range roughly [0, 1.x] before
     * clamping; the band thresholds are tuned against this scale.
     */
    private fun score(queryTokens: List<String>, candidate: FolderCandidate): Double {
        val tail = candidate.path.substringAfterLast('/')
        val labelScore = fieldScore(queryTokens, candidate.label)
        val tailScore = fieldScore(queryTokens, tail)
        // The path tail is the primary discriminator, but a multi-word query
        // ("ai shipping labs workshops") often spans several path segments
        // ("…/ai-shipping-labs/workshops"). Score the trailing segments too so
        // such a query lands a confident match without inflating bare-tail ties.
        val pathScore = fieldScore(queryTokens, pathContext(candidate.path)) * PATH_CONTEXT_WEIGHT
        return maxOf(labelScore, tailScore, pathScore)
    }

    /** Last few path segments joined, for matching multi-segment queries. */
    private fun pathContext(path: String): String =
        path.trim('/').split('/').takeLast(PATH_CONTEXT_SEGMENTS).joinToString(" ")

    private fun fieldScore(queryTokens: List<String>, field: String): Double {
        val fieldNorm = normalize(field)
        if (fieldNorm.isBlank()) return 0.0
        val fieldTokens = tokenize(field)
        if (fieldTokens.isEmpty()) return 0.0

        // Token overlap: fraction of query tokens that appear (as a token or a
        // token-substring) somewhere in the field. This is the core signal.
        val matchedQueryTokens = queryTokens.count { qt ->
            fieldTokens.any { ft -> ft == qt || ft.contains(qt) || qt.contains(ft) }
        }
        val overlap = matchedQueryTokens.toDouble() / queryTokens.size

        // Bonus when the whole query string appears as a contiguous substring
        // of the field (e.g. query "rov workshop" inside "rov-workshop").
        val joinedQuery = queryTokens.joinToString("")
        val joinedField = fieldTokens.joinToString("")
        val substringBonus = if (joinedField.contains(joinedQuery)) 0.25 else 0.0

        // Strong bonus for an exact field match (every token, both ways).
        val exactBonus = if (fieldTokens.size == queryTokens.size &&
            fieldTokens.toSet() == queryTokens.toSet()
        ) {
            0.35
        } else {
            0.0
        }

        return (overlap + substringBonus + exactBonus).coerceAtMost(1.0)
    }

    /** Lowercase + collapse separators so "ROV_Workshop" ≈ "rov workshop". */
    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun tokenize(value: String): List<String> =
        normalize(value).split(' ').filter { it.isNotBlank() }
}
