package com.pocketshell.app.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Issue #859 Slice B: the typed-card **renderer registry**.
 *
 * The session feed is a heterogeneous list of [SessionCardsRemoteSource.SessionCard]s
 * of different `type`s. Rather than a `when (card.type)` ladder embedded in the
 * feed sheet (the half-built checklist-only state before this slice), the feed
 * dispatches each card to the [SessionCardRenderer] registered for its `type`.
 *
 * Adding a new card type (e.g. `note`) is exactly: register one
 * [SessionCardRenderer] in [SessionCardRenderers]. The feed sheet
 * ([SessionCardFeedContent]) never changes — it asks the registry for the
 * renderer and calls [SessionCardRenderer.Render]. This mirrors the host-side
 * `CardType` registry in `cards.py`, so the host and app stay symmetrically
 * extensible (hard-cut per D22: there is one dispatch site, the registry).
 *
 * A genuinely unknown type falls back to [UnknownCardRenderer], a graceful
 * "unsupported card" row — forward-compat for a future host type the app build
 * does not yet know about.
 */

/** Callbacks a renderer may invoke for the human's interaction with a card. */
@Stable
internal interface SessionCardInteractions {
    /** Tick/untick a checklist item. */
    fun onToggleChecklistItem(cardId: String, itemId: String, checked: Boolean)

    /** Mark a note read/unread. */
    fun onSetNoteRead(cardId: String, read: Boolean)
}

/** Renders one [type] of [SessionCardsRemoteSource.SessionCard]. */
internal interface SessionCardRenderer {
    /** The card `type` this renderer handles (the registry key). */
    val type: String

    @Composable
    fun Render(
        card: SessionCardsRemoteSource.SessionCard,
        interactions: SessionCardInteractions,
        modifier: Modifier,
    )
}

/**
 * The renderer registry, keyed by card `type`. The single dispatch site for the
 * feed. New types register here; the feed never special-cases a type.
 */
internal object SessionCardRenderers {

    private val byType: Map<String, SessionCardRenderer> = listOf(
        ChecklistCardRenderer,
        NoteCardRenderer,
    ).associateBy { it.type }

    /**
     * The renderer for [type], or [UnknownCardRenderer] when no registered
     * renderer claims it (forward-compat: a future host type renders as a
     * graceful "unsupported" row rather than vanishing).
     */
    fun rendererFor(type: String): SessionCardRenderer =
        byType[type] ?: UnknownCardRenderer

    /** Test/introspection seam: the set of types with a real renderer. */
    fun registeredTypes(): Set<String> = byType.keys
}

internal const val SESSION_NOTE_CARD_TAG_PREFIX: String = "session:note:card:"
internal const val SESSION_NOTE_READ_TOGGLE_TAG_PREFIX: String = "session:note:read:"
internal const val SESSION_UNKNOWN_CARD_TAG_PREFIX: String = "session:card:unknown:"

// ---------------------------------------------------------------------------
// checklist renderer — the existing checklist rows, now a registered renderer
// ---------------------------------------------------------------------------

internal object ChecklistCardRenderer : SessionCardRenderer {
    override val type: String = SessionCardsRemoteSource.TYPE_CHECKLIST

    @Composable
    override fun Render(
        card: SessionCardsRemoteSource.SessionCard,
        interactions: SessionCardInteractions,
        modifier: Modifier,
    ) {
        val checklist = card as? SessionCardsRemoteSource.ChecklistCard ?: return
        ChecklistCardRows(
            card = checklist,
            onToggle = interactions::onToggleChecklistItem,
            modifier = modifier,
        )
    }
}

// ---------------------------------------------------------------------------
// note renderer (#859 Slice B — proves the registry is genuinely generic)
// ---------------------------------------------------------------------------

internal object NoteCardRenderer : SessionCardRenderer {
    override val type: String = SessionCardsRemoteSource.TYPE_NOTE

    @Composable
    override fun Render(
        card: SessionCardsRemoteSource.SessionCard,
        interactions: SessionCardInteractions,
        modifier: Modifier,
    ) {
        val note = card as? SessionCardsRemoteSource.NoteCard ?: return
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
                .padding(PocketShellSpacing.sm)
                .testTag(SESSION_NOTE_CARD_TAG_PREFIX + note.id),
        ) {
            if (!note.title.isNullOrBlank()) {
                Text(
                    text = note.title,
                    color = PocketShellColors.Text,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = PocketShellSpacing.sm),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { interactions.onSetNoteRead(note.id, !note.read) }
                    .padding(vertical = 2.dp)
                    .testTag(SESSION_NOTE_READ_TOGGLE_TAG_PREFIX + note.id),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = note.read,
                    onCheckedChange = { next -> interactions.onSetNoteRead(note.id, next) },
                )
                Text(
                    text = note.text,
                    color = PocketShellColors.Text,
                    style = PocketShellType.bodyDense,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// unknown renderer — graceful "unsupported card" row (forward-compat)
// ---------------------------------------------------------------------------

internal object UnknownCardRenderer : SessionCardRenderer {
    // Not a real card type; never used as a registry key.
    override val type: String = "__unknown__"

    @Composable
    override fun Render(
        card: SessionCardsRemoteSource.SessionCard,
        interactions: SessionCardInteractions,
        modifier: Modifier,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
                .padding(PocketShellSpacing.sm)
                .testTag(SESSION_UNKNOWN_CARD_TAG_PREFIX + card.id),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = card.title?.takeIf { it.isNotBlank() } ?: card.type,
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Unsupported card (${card.type}) — update the app to view it.",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
            )
        }
    }
}
