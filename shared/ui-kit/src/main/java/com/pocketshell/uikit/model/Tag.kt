package com.pocketshell.uikit.model

/**
 * Small mixed-case chip rendered inside a `SessionRow`. Originally
 * matched the `.tag` block in `docs/mockups/styles.css` (uppercase
 * letter-spaced). Per issue #202 the labels are now rendered
 * mixed-case so a first-time user can read them without decoding —
 * "Claude" instead of "CLAUDE CODE", "Detached" instead of "ATTACHED".
 *
 * `kind` picks the colour ramp:
 *
 * - [TagKind.Default] -> neutral surface-elev background, muted text
 *   (generic / unknown classifier)
 * - [TagKind.Agent] -> accent (cyan) — agent CLI: `Claude`, `Codex`,
 *   `OpenCode`, `Agent`. Reflects the agent-awareness vocabulary in
 *   `docs/agent-awareness.md`.
 * - [TagKind.Deploy] -> amber — deploys / pipelines / prod
 * - [TagKind.Ml] -> purple — ML training / GPU / inference
 * - [TagKind.Attached] -> green — at least one tmux client is attached
 *   to this session right now. Activity-state, distinct from agent-kind
 *   per issue #202 acceptance criterion "no two indicators on the same
 *   row have similar shapes/colors". Aligns with the host-status
 *   vocabulary from issue #201 (host card uses "Attached" with the
 *   same green semantic).
 * - [TagKind.Detached] -> muted — no clients attached. Activity-state,
 *   complement of [Attached]. We surface "Detached" rather than the
 *   ambiguous "Idle" word that issue #201 explicitly removes from the
 *   host-card vocabulary; tmux's `man` page calls a session with no
 *   clients "detached", which reads unambiguously to first-time users.
 */
data class Tag(
    val label: String,
    val kind: TagKind = TagKind.Default,
)

/**
 * Tag colour ramps for [Tag]. Two distinct slot categories:
 *
 *  - Classifier slots ([Default], [Agent], [Deploy], [Ml]) describe
 *    *what* the session is — agent-kind, domain, etc. Cyan / amber /
 *    purple / neutral.
 *  - Activity-state slots ([Attached], [Detached]) describe *what is
 *    happening now* — whether a tmux client is attached. Green / muted.
 *
 * Per issue #202 the two slot categories are rendered as visually
 * distinct chips (activity-state chips lead with a small status dot)
 * so the user can read "what kind of session" and "what state is it
 * in" without conflating colour-only encoding.
 *
 * Adding a new kind: pair it with a matching colour rule in
 * `SessionRow.TagChip` and a legend entry in
 * `SessionsDashboardScreen.SessionsLegend` so the new chip is still
 * self-explanatory.
 */
enum class TagKind {
    Default,
    Agent,
    Deploy,
    Ml,
    Attached,
    Detached,
}
