package com.pocketshell.app.conversation

/**
 * Pure expand/collapse decision for a Conversation tool-call card (#824).
 *
 * The card has an *auto-expand-while-running* default: a tool call that has
 * not produced a result yet (e.g. a Codex `write_stdin` long poll with
 * `yield_time_ms`) opens itself so the user sees what is in flight. Likewise a
 * card surfaced by an active search query is auto-expanded so the match is
 * visible.
 *
 * Before #824 those defaults were OR-ed unconditionally on top of the user's
 * tap state:
 *
 * ```
 * val expanded = isExplicitlyExpanded || (isRunning && result == null)
 * ```
 *
 * That made the most-recent running card *impossible to collapse* — the tap
 * toggled the explicit-set membership but the `isRunning && result == null`
 * term always won, so the chevron never changed and the card stayed open
 * forever. The maintainer reported exactly this: "the last tool call is not
 * collapsible, it's always open."
 *
 * The fix records the user's choice as an explicit override (collapse OR
 * expand) that wins over the auto-expand defaults. Absent an override, the
 * defaults still apply. The override is keyed per card id and survives new
 * events / recomposition, so a card the user collapsed does NOT silently
 * re-expand when the next transcript event arrives while it is still running.
 */
internal object ConversationToolCardExpansion {

    /**
     * Resolve whether a tool-call card should render expanded.
     *
     * @param userOverride the user's explicit choice for this card if any:
     *   `true` = the user expanded it, `false` = the user collapsed it,
     *   `null` = the user has not toggled it (follow the defaults).
     * @param isRunning the tool call has no paired result yet.
     * @param hasResult a paired tool result is present.
     * @param isSearchExpanded the active search query surfaced this card.
     */
    fun isExpanded(
        userOverride: Boolean?,
        isRunning: Boolean,
        hasResult: Boolean,
        isSearchExpanded: Boolean,
    ): Boolean {
        // The user's explicit tap always wins — including a collapse of a
        // still-running card. This is the #824 fix.
        if (userOverride != null) return userOverride
        // No explicit choice: auto-expand while running (no result) or when a
        // search match surfaced the card.
        return (isRunning && !hasResult) || isSearchExpanded
    }

    /**
     * Compute the next override map after the user taps a card whose current
     * resolved state is [currentlyExpanded]. The new override is simply the
     * opposite of what is on screen, so a single tap always flips the card —
     * the very property #824 was missing for running cards.
     */
    fun toggle(
        overrides: Map<String, Boolean>,
        id: String,
        currentlyExpanded: Boolean,
    ): Map<String, Boolean> = overrides + (id to !currentlyExpanded)
}
