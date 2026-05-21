package com.pocketshell.uikit.model

/**
 * Small uppercase chip rendered inside a `SessionRow`. Matches the
 * `.tag` block in `docs/mockups/styles.css` and the example values in
 * `docs/mockups/dashboard.html` (`claude code`, `scheduled`, `ml`,
 * `deploy`).
 *
 * `kind` picks the colour ramp:
 *
 * - [TagKind.Default] -> neutral surface-elev background, muted text
 * - [TagKind.Agent] -> accent (cyan) — for `claude code`, `codex`, etc.
 * - [TagKind.Deploy] -> amber — for deploys / pipelines
 * - [TagKind.Ml] -> purple — for ML training / inference sessions
 */
data class Tag(
    val label: String,
    val kind: TagKind = TagKind.Default,
)

/**
 * The four `.tag` variants from `docs/mockups/styles.css`. Anything more
 * exotic gets a new `TagKind` value with a matching CSS rule; we don't
 * pass colour through directly because the design system reserves that
 * decision to the theme.
 */
enum class TagKind {
    Default,
    Agent,
    Deploy,
    Ml,
}
