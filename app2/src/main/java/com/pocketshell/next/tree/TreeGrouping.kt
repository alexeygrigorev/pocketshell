package com.pocketshell.next.tree

import com.pocketshell.core.hostapi.SessionRow

/**
 * Label for the bucket that collects sessions the host reported with no
 * workspace.
 *
 * A literal, lowercase word rather than "Other" / "Ungrouped" so it reads as
 * one more section label next to the real workspace paths instead of a heading
 * that claims more than it knows.
 */
const val OTHER_WORKSPACE_LABEL: String = "other"

/**
 * One workspace section of the session tree.
 *
 * [workspace] is the host's own string, verbatim, or `null` for the [
 * OTHER_WORKSPACE_LABEL] bucket. [rows] are the sessions in that workspace,
 * already ordered by [groupSessionsByWorkspace].
 */
data class WorkspaceGroup(
    val workspace: String?,
    val rows: List<SessionRow>,
) {
    /** What the section header prints. */
    val label: String get() = workspace ?: OTHER_WORKSPACE_LABEL

    /**
     * The newest activity anywhere in this group, or `null` when the host
     * reported no activity for any of its sessions. Drives the group ordering.
     */
    val latestActivityEpoch: Long? get() = rows.mapNotNull { it.activityEpoch }.maxOrNull()
}

/**
 * Groups a host's sessions into workspace sections — the whole content model of
 * the session tree screen (rewrite task U-3).
 *
 * ## Grouping is a lookup, never an inference
 *
 * Rows are bucketed by the `workspace` field the host CLI reported, compared as
 * an EXACT string. Nothing here parses a session name, strips a `git-` prefix,
 * splits an `aplexer` `workspace:tag` name, or maps two paths onto one project.
 * The old client did all of that and it is why a session could appear under a
 * folder it did not belong to: the phone was guessing at structure the host
 * already knew. Schema 2 reports the workspace, so the phone's job is to bucket,
 * not to deduce.
 *
 * The single normalisation is that a `null` OR blank workspace lands in the
 * [OTHER_WORKSPACE_LABEL] bucket. Blank is folded in because a section header
 * printing an empty string is an invisible heading, not a group — and "the host
 * sent an empty string" and "the host sent nothing" are the same statement about
 * what it knows. That is a rendering decision, not name parsing.
 *
 * ## Ordering
 *
 * Both levels sort by "most recently active first", because the question this
 * screen answers is "where was I / who needs me", and that is a recency
 * question:
 *
 * - Rows inside a group: `activityEpoch` descending, rows with no activity
 *   timestamp last, ties broken by name ascending.
 * - Groups: by their newest member's `activityEpoch` descending, groups with no
 *   activity at all last, ties broken by [WorkspaceGroup.label] ascending.
 *
 * The [OTHER_WORKSPACE_LABEL] bucket is deliberately NOT pinned to the bottom.
 * A busy session the host could not attribute to a workspace is still the
 * session the user most likely wants, and burying it under every quiet named
 * workspace would hide exactly the row recency is supposed to surface.
 *
 * The name tie-breaks exist so the output is a pure function of the input set:
 * without them two rows sharing a timestamp would keep the host's enumeration
 * order, and the same host state could render two different screens.
 *
 * ## What is never dropped
 *
 * Every input row appears in exactly one output group. A [
 * com.pocketshell.core.hostapi.Backend.UNKNOWN] row (a manager this build does
 * not know) is grouped and rendered like any other — dropping it would recreate
 * the "the list is silently short" failure the schema-2 `errors[]` contract
 * exists to prevent.
 *
 * Pure data transformation: no Android, no Compose, no coroutines, no clock.
 */
fun groupSessionsByWorkspace(sessions: List<SessionRow>): List<WorkspaceGroup> =
    sessions
        .groupBy { it.workspace?.takeIf { workspace -> workspace.isNotBlank() } }
        .map { (workspace, rows) -> WorkspaceGroup(workspace, rows.sortedWith(ROW_ORDER)) }
        .sortedWith(GROUP_ORDER)

/**
 * `activityEpoch` descending with nulls last, then name ascending.
 *
 * Written as "is it null" + "descending value" rather than a nullable
 * descending comparator because Kotlin's `compareByDescending` puts nulls
 * FIRST, which would float sessions the host knows nothing about above the one
 * the user was just in.
 */
private val ROW_ORDER: Comparator<SessionRow> =
    compareBy<SessionRow> { it.activityEpoch == null }
        .thenByDescending { it.activityEpoch ?: Long.MIN_VALUE }
        .thenBy { it.name }

/** Same rule as [ROW_ORDER], one level up. */
private val GROUP_ORDER: Comparator<WorkspaceGroup> =
    compareBy<WorkspaceGroup> { it.latestActivityEpoch == null }
        .thenByDescending { it.latestActivityEpoch ?: Long.MIN_VALUE }
        .thenBy { it.label }

/**
 * Coarse "how long ago" label for a session's last activity.
 *
 * Deliberately coarse and deliberately not a clock time. The value answers
 * "is this still warm?" at a glance while scanning a list; a `14:32` would make
 * the reader do the subtraction, and a live-updating "3 minutes 12 seconds"
 * would be a second thing to keep in sync for no extra information.
 *
 * [epochSec] is the host's `activity_epoch` (seconds), [nowSec] the phone's
 * current time in the same unit. `null` in gives `null` out — the caller renders
 * nothing rather than inventing "unknown", the same "absent, not wrong" rule the
 * agent-state chip follows.
 *
 * A timestamp in the future (host clock ahead of the phone's, which happens) is
 * clamped to "now" rather than rendering a negative age.
 */
fun relativeActivityLabel(epochSec: Long?, nowSec: Long): String? {
    if (epochSec == null) return null
    val ageSec = (nowSec - epochSec).coerceAtLeast(0)
    return when {
        ageSec < MINUTE_SEC -> "just now"
        ageSec < HOUR_SEC -> "${ageSec / MINUTE_SEC}m ago"
        ageSec < DAY_SEC -> "${ageSec / HOUR_SEC}h ago"
        else -> "${ageSec / DAY_SEC}d ago"
    }
}

private const val MINUTE_SEC: Long = 60
private const val HOUR_SEC: Long = 60 * MINUTE_SEC
private const val DAY_SEC: Long = 24 * HOUR_SEC
