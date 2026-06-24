package com.pocketshell.app.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.CommandChip
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

internal const val SESSION_CHECKLIST_CHIP_TAG: String = "session:checklist-chip"
internal const val SESSION_CHECKLIST_SHEET_TAG: String = "session:checklist:sheet"
internal const val SESSION_CHECKLIST_CARD_TAG_PREFIX: String = "session:checklist:card:"
internal const val SESSION_CHECKLIST_ITEM_TAG_PREFIX: String = "session:checklist:item:"
internal const val SESSION_CHECKLIST_CLOSE_TAG: String = "session:checklist:close"

@Immutable
internal data class ChecklistChipUiState(
    val total: Int,
    val checked: Int,
    val title: String? = null,
) {
    val hasItems: Boolean
        get() = total > 0
}

internal fun checklistChipState(
    cards: List<SessionCardsRemoteSource.ChecklistCard>,
): ChecklistChipUiState? {
    val total = cards.sumOf { it.items.size }
    if (total == 0) return null
    val checked = cards.sumOf { card -> card.items.count { it.id in card.checkedIds } }
    val title = cards.singleOrNull()?.title
    return ChecklistChipUiState(total = total, checked = checked, title = title)
}

@Composable
internal fun ChecklistChip(
    state: ChecklistChipUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.hasItems) return
    val label = if (state.title.isNullOrBlank()) {
        "Checklist ${state.checked}/${state.total}"
    } else {
        "${state.title} ${state.checked}/${state.total}"
    }
    CommandChip(
        label = label,
        onClick = onClick,
        modifier = modifier.testTag(SESSION_CHECKLIST_CHIP_TAG),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChecklistCardsSheet(
    cards: List<SessionCardsRemoteSource.ChecklistCard>,
    onToggle: (cardId: String, itemId: String, checked: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
        modifier = modifier,
    ) {
        ChecklistCardsContent(
            cards = cards,
            onToggle = onToggle,
            onClose = onDismiss,
            modifier = Modifier.testTag(SESSION_CHECKLIST_SHEET_TAG),
        )
    }
}

@Composable
internal fun ChecklistCardsContent(
    cards: List<SessionCardsRemoteSource.ChecklistCard>,
    onToggle: (cardId: String, itemId: String, checked: Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = PocketShellSpacing.lg)
            .padding(bottom = PocketShellSpacing.lg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Checklist",
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
                color = PocketShellColors.Text,
            )
            Box(
                modifier = Modifier
                    .size(PocketShellDensity.tapTargetMin)
                    .clickable(onClick = onClose)
                    .testTag(SESSION_CHECKLIST_CLOSE_TAG),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "X",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        ) {
            items(cards, key = { it.id }) { card ->
                ChecklistCardRows(
                    card = card,
                    onToggle = onToggle,
                )
            }
            item {
                Spacer(modifier = Modifier.height(PocketShellSpacing.sm))
            }
        }
    }
}

@Composable
private fun ChecklistCardRows(
    card: SessionCardsRemoteSource.ChecklistCard,
    onToggle: (cardId: String, itemId: String, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
            .padding(PocketShellSpacing.sm)
            .testTag(SESSION_CHECKLIST_CARD_TAG_PREFIX + card.id),
    ) {
        if (!card.title.isNullOrBlank()) {
            Text(
                text = card.title,
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PocketShellSpacing.sm),
            )
        }
        card.items.forEach { item ->
            val checked = item.id in card.checkedIds
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(card.id, item.id, !checked) }
                    .padding(vertical = 2.dp)
                    .testTag(SESSION_CHECKLIST_ITEM_TAG_PREFIX + card.id + ":" + item.id),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { next -> onToggle(card.id, item.id, next) },
                )
                Text(
                    text = item.text,
                    color = PocketShellColors.Text,
                    style = PocketShellType.bodyDense,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
