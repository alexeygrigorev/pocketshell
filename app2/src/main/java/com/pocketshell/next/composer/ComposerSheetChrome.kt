package com.pocketshell.next.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope

/**
 * The composer's Material chrome, in one place (ported from v0.4.47 #1622
 * for overlay geometry only).
 *
 * Horizontal-only content insets so a floating sheet is not charged the
 * status-bar inset as dead padding above the grabber. Compact drag handle
 * instead of Material's 48 dp default. [composerImeAnchorPolicy] re-anchors
 * a settled partial sheet when the IME would cover the draft.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
        modifier = modifier.composerImeAnchorPolicy(sheetState),
        dragHandle = { ComposerDragHandle() },
        contentWindowInsets = {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
        },
        content = content,
    )
}

@Composable
internal fun ComposerDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(vertical = ComposerDragHandleVerticalPadding)
            .size(width = ComposerDragHandleBarWidth, height = ComposerDragHandleBarHeight)
            .background(
                color = PocketShellColors.TextSecondary,
                shape = RoundedCornerShape(percent = 50),
            )
            .testTag(COMPOSER_DRAG_HANDLE_TAG),
    )
}

internal val ComposerDragHandleBlockHeight: Dp
    get() = ComposerDragHandleVerticalPadding * 2 + ComposerDragHandleBarHeight

internal val ComposerDragHandleVerticalPadding = 9.dp
internal val ComposerDragHandleBarWidth = 32.dp
internal val ComposerDragHandleBarHeight = 4.dp

internal const val COMPOSER_DRAG_HANDLE_TAG = "prompt-composer-drag-handle"

@OptIn(ExperimentalMaterial3Api::class)
internal fun Modifier.composerImeAnchorPolicy(
    sheetState: SheetState,
): Modifier = composed {
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    var geometry by remember(sheetState) {
        mutableStateOf<ComposerModalSurfaceGeometry?>(null)
    }
    var autoExpandedFromPartial by remember(sheetState) { mutableStateOf(false) }
    var preservePreImeExpanded by remember(sheetState) { mutableStateOf(false) }

    LaunchedEffect(sheetState, density) {
        snapshotFlow {
            val imeBottomPx = imeInsets.getBottom(density)
            val surfaceTopPx = runCatching { sheetState.requireOffset() }.getOrNull()
            ComposerImeAnchorSnapshot(
                imeVisible = imeBottomPx > 0,
                overlapsKeyboard = composerModalSurfaceOverlapsIme(
                    geometry = geometry,
                    surfaceTopPx = surfaceTopPx,
                    imeBottomPx = imeBottomPx,
                ),
                currentValue = sheetState.currentValue,
                targetValue = sheetState.targetValue,
                hasPartiallyExpandedState = sheetState.hasPartiallyExpandedState,
                autoExpandedFromPartial = autoExpandedFromPartial,
            )
        }.collect { snapshot ->
            preservePreImeExpanded = updateComposerPreImeExpanded(
                previous = preservePreImeExpanded,
                snapshot = snapshot,
            )
            when (decideComposerImeAnchorAction(snapshot)) {
                ComposerImeAnchorAction.None -> Unit
                ComposerImeAnchorAction.ExpandFromPartial -> {
                    try {
                        supervisorScope { sheetState.expand() }
                        autoExpandedFromPartial = composerImeOwnsExpansionAfter(
                            ComposerImeExpansionOutcome.Completed,
                            preservePreImeExpanded = preservePreImeExpanded,
                        )
                    } catch (cancelled: CancellationException) {
                        val effectDisposed = !currentCoroutineContext().isActive
                        autoExpandedFromPartial = composerImeOwnsExpansionAfter(
                            if (effectDisposed) {
                                ComposerImeExpansionOutcome.EffectDisposed
                            } else {
                                ComposerImeExpansionOutcome.InterruptedByUser
                            },
                            preservePreImeExpanded = preservePreImeExpanded,
                        )
                        if (effectDisposed) throw cancelled
                    }
                }
                ComposerImeAnchorAction.RestorePartial -> {
                    autoExpandedFromPartial = false
                    try {
                        supervisorScope { sheetState.partialExpand() }
                    } catch (cancelled: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw cancelled
                    }
                }
                ComposerImeAnchorAction.ReleaseOwnership -> {
                    autoExpandedFromPartial = false
                }
            }
        }
    }

    onGloballyPositioned { coordinates ->
        geometry = ComposerModalSurfaceGeometry(
            rootBottomPx = coordinates.findRootCoordinates().size.height,
            surfaceHeightPx = coordinates.size.height,
        )
    }
}
