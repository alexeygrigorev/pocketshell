package com.pocketshell.app.composer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source guards for UI-thread staging and reopened #1602's exact geometry proof. */
class PromptComposerSheetSourceGuardTest {

    @Test
    fun outboundStatusTagUsesOneOwnedRowStatusCoordinatePairWithExactCopyFirst() {
        val production = locate("PromptComposerQueueBanners.kt")
        val sheet = locate("PromptComposerSheet.kt")
        val journey = locateProject(
            "app/src/androidTest/java/com/pocketshell/app/proof/Issue1602RecoveredQueueJourney.kt",
        )
        val component = locateProject(
            "app/src/androidTest/java/com/pocketshell/app/composer/PromptComposerOutboundQueueTest.kt",
        )
        val unit = locateProject(
            "app/src/test/java/com/pocketshell/app/composer/PromptComposerSheetQueueHelperTest.kt",
        )
        val entryPoint = locateProject(
            "app/src/androidTest/java/com/pocketshell/app/proof/OutboundExactlyOnceAcrossFlapE2eTest.kt",
        )

        assertTrue(entryPoint.contains("class OutboundExactlyOnceAcrossFlapE2eTest"))
        assertTrue(
            entryPoint.contains(
                "fun fallbackQueueRowsSurviveSessionSwitchAndDrainOnlyIntoSameGeneration()",
            ),
        )
        assertFalse(entryPoint.contains("OutboundExactlyOnceAcrossFlapE2ETest"))
        assertTrue(
            "#1602 must model a genuine row failure that survives transport recovery",
            journey.contains("ISSUE1602_GENUINE_NON_TRANSPORT_FAILURE_MESSAGE"),
        )
        assertFalse(
            "#1602 must not mark its manually retried head with the auto-recovery marker",
            journey.contains("OUTBOUND_AUTO_RETRY_EXHAUSTED_MESSAGE"),
        )
        listOf(
            "assertEquals(OUTBOUND_MAX_AUTO_ATTEMPTS, rows.first().attemptCount)",
            "assertEquals(ISSUE1602_GENUINE_NON_TRANSPORT_FAILURE_MESSAGE, rows.first().lastError)",
            "assertEquals(OUTBOUND_MAX_AUTO_ATTEMPTS, promoted.first().attemptCount)",
            "promoted.first().lastError",
        ).forEach { required ->
            assertTrue("#1602 genuine failed-head persistence proof lost term: $required", journey.contains(required))
        }
        listOf(
            "composer.loadComposerDraft(durableKey)",
            "compose.waitUntil(timeoutMillis = uiTimeoutMs)",
            "composer.composerDraftStore.load(durableKey) == replacementDraft",
            "composer.composerDraftStore.load(fallbackKey) == null",
            "promoted draft must eventually reach the durable backing slot",
        ).forEach { required ->
            assertTrue("#1602 draft promotion proof lost ordered/durable term: $required", journey.contains(required))
        }
        val promotionDraftBlock = boundedOracleBlock(
            label = "ordered and durable draft promotion",
            oracle = journey,
            actionMarker = "fun provePromotionThenRetry(",
            endMarker = "val youngerRelease = CountDownLatch",
        )
        val immediateReadIndex = promotionDraftBlock.indexOf("composer.loadComposerDraft(durableKey)")
        val durabilityWaitIndex = promotionDraftBlock.indexOf(
            "compose.waitUntil(timeoutMillis = uiTimeoutMs)",
            startIndex = immediateReadIndex.coerceAtLeast(0),
        )
        val durableReadIndex = promotionDraftBlock.indexOf(
            "composer.composerDraftStore.load(durableKey) == replacementDraft",
            startIndex = durabilityWaitIndex.coerceAtLeast(0),
        )
        val fallbackRemovalIndex = promotionDraftBlock.indexOf(
            "composer.composerDraftStore.load(fallbackKey) == null",
            startIndex = durableReadIndex.coerceAtLeast(0),
        )
        assertTrue(
            "#1602 must prove immediate ordered visibility before bounded raw-store durability",
            immediateReadIndex >= 0 &&
                durabilityWaitIndex > immediateReadIndex &&
                durableReadIndex > durabilityWaitIndex &&
                fallbackRemovalIndex > durableReadIndex,
        )
        assertRecoveredJourneyControlScrollOrdering(journey)
        listOf(
            "private val draftPersistenceDispatcherBeforeJourney =",
            "composer.draftPersistence.dispatcherOverrideForTest = Dispatchers.IO",
            "composer.draftPersistence.dispatcherOverrideForTest = draftPersistenceDispatcherBeforeJourney",
        ).forEach { required ->
            assertTrue(
                "#1602 must keep draft persistence runnable while its queue-drain worker is blocked: $required",
                journey.contains(required),
            )
        }

        listOf(
            "statusLayoutRegistration?.bind(item.id)",
            "statusLayoutBinding.recordRow(coordinates)",
            "statusLayoutBinding.recordStatus(coordinates)",
            "private val rowCoordinates = AtomicReference<LayoutCoordinates?>(null)",
            "private val statusCoordinates = AtomicReference<LayoutCoordinates?>(null)",
            "rowCoordinates.set(current)",
            "statusCoordinates.set(current)",
            "hasRecordedCoordinatePair()",
            "rowCoordinates.get() != null && statusCoordinates.get() != null",
            "val row = rowCoordinates.get() ?: return null",
            "val status = statusCoordinates.get() ?: return null",
            "if (!row.isAttached || !status.isAttached) return null",
            "rowCoordinates.get() === row",
            "statusCoordinates.get() === status",
            "currentWindowGeometry(rowId: String)",
            "handlesStillCurrent",
            "rawWindowBounds(row)",
            "rawWindowBounds(status)",
            "coordinates.positionInWindow()",
            "val measuredSize = coordinates.size",
            "left.isFinite()",
            "right.isFinite()",
            "handlesCurrentAtStart",
            "handlesCurrentAtEnd",
            "rowAttachedAtStart",
            "rowAttachedAtEnd",
            "statusAttachedAtStart",
            "statusAttachedAtEnd",
            "owner_not_current",
            "binding_not_current",
            "row_handle_missing",
            "status_handle_missing",
            "row_detached",
            "status_detached",
            "handle_pair_replaced_during_read",
            "status_left_not_full_width",
            "status_right_not_full_width",
            "status_top_outside_row",
            "status_bottom_outside_row",
            "diagnosticSummary()",
            "rowBounds=\$rowBounds",
            "statusBounds=\$statusBounds",
        ).forEach { required ->
            assertTrue("#1602 paired observer lost term: $required", production.contains(required))
        }
        assertTrue(
            "status wrapper must retain a load-bearing explicit full-width layout contract",
            production.contains(
                ".fillMaxWidth()\n" +
                    "                .wrapContentHeight()\n" +
                    "                .testTag(composerOutboundQueueStatusTestTag(item.id))",
            ),
        )
        listOf(
            "ConcurrentHashMap<String, Rect>()",
            "statusBoundsByRowId",
            "record(item.id, coordinates.boundsInWindow())",
            "import androidx.compose.ui.layout.boundsInWindow",
            ".boundsInWindow()",
        ).forEach { forbidden ->
            assertFalse(
                "#1602 must use unclipped raw position plus measured size, never clip-aware snapshots: $forbidden",
                production.contains(forbidden),
            )
        }

        assertScrollThenPairedGeometryThenCopy("journey", journey, "offlineStatusLabel")
        assertCopyScrollThenPairedGeometry("component", component, "statusLabel")
        assertExactRetryScrollBeforeDisabledProof(
            label = "journey",
            oracle = journey,
            actionMarker = "val offlineRetryControlNode = compose.onNode(",
            endMarker = "compose.assertNodeFullyWithinRoot(retryTag",
        )
        assertExactRetryScrollBeforeDisabledPointerTap(
            label = "journey physical first-row tap after proving both rows",
            oracle = journey,
            actionMarker = "val rowsBeforeTap = store.itemsFor(fallbackKey)",
            endMarker = "compose.waitForIdle()",
        )
        assertExactRetryScrollBeforeDisabledPointerTap(
            label = "component",
            oracle = component,
            actionMarker = "val offlineRetryNode = compose.onNode(offlineRetryControl",
            // The corrected component now reads the clip-aware post-scroll
            // geometry between the action and the displayed/disabled proof.
            // Bound this helper at the row completion, not at that intentional
            // intermediate idle barrier.
            endMarker = "add(row.id)",
        )
        assertComponentRetryClipTransition(component)
        assertTrue(
            "#1602 exact Retry controls need the production status viewport's scroll semantics",
            sheet.contains(
                ".testTag(COMPOSER_STATUS_VIEWPORT_TAG)\n" +
                    "                    .verticalScroll(rememberScrollState())",
            ),
        )
        listOf(journey, component).forEach { oracle ->
            listOf(
                "currentWindowGeometry(row.id)",
                "isCurrentAttachedNonEmptyContainedFullWidthPair()",
                "diagnosticSummary()",
                "geometryWait.isFailure",
                "geometryWait.exceptionOrNull()",
                "raw-window LayoutCoordinates",
                "assertNodeFullyWithinRoot(rowTag, useUnmergedTree = true)",
            ).forEach { required ->
                assertTrue("#1602 geometry oracle lost term: $required", oracle.contains(required))
            }
            listOf(
                "rowNode.boundsInWindow",
                "offlineStatusNode.boundsInWindow",
                "wrapperNode.boundsInWindow",
                "owningRootBounds",
                "StatusWindowGeometry(",
                "ModalStatusWindowGeometry(",
            ).forEach { forbidden ->
                assertFalse(
                    "#1602 must not mix Semantics and LayoutCoordinates geometry: $forbidden",
                    oracle.contains(forbidden),
                )
            }
        }

        listOf(
            "statusLayoutObserverRejectsStaleCallbacksAndStaleOwnerCleanup",
            "statusLayoutObserverPairsAndIndependentlyReplacesCurrentRowAndStatusHandles",
            "statusLayoutObserverUsesRawWindowPositionAndSizeWhenClippedBoundsAreZero",
            "statusLayoutObserverFailsClosedForInvalidRawGeometryAndReadExceptions",
            "statusLayoutObserverRevalidatesAttachmentBindingAndHandlesAfterRawRead",
            "clippedBounds = Rect.Zero",
            "FakeClippedWindowRoot",
            "\"localBoundingBoxOf\" -> clippedBounds",
            "raw position plus measured size must prove the visible full-width status",
            "status_bounds_unavailable",
            "status_bounds_empty",
            "handle_pair_replaced_during_read",
            "onRawPositionRead = { detachedDuringRead.set(false) }",
            "onRawPositionRead = { ownerRegistration.close() }",
            "onRawPositionRead = { binding.recordStatus(replacementStatus) }",
            "onRawPositionRead = { replacementRegistration.bind(\"row-a\") }",
            "a row callback alone must not publish a mixed or incomplete coordinate pair",
            "a status callback must not replace the retained exact row handle",
            "a subsequent status callback must replace only the retained status handle",
            "a subsequent row callback must replace only the retained row handle",
            "a row callback must not replace the retained exact status handle",
            "a detached row handle must reject the exact coordinate pair",
            "a detached status handle must reject the exact coordinate pair",
            "currentWindowGeometry(\"wrong-row-id\")",
            "statusWindowGeometryNamesDetachedOutsideAndNonFullWidthTerms",
            "status_left_not_full_width",
            "status_right_not_full_width",
            "status_top_outside_row",
            "status_bottom_outside_row",
            "assertTrue(diagnostic.contains(\"rowBounds=\"))",
            "assertTrue(diagnostic.contains(\"statusBounds=\"))",
            "assertNull(PromptComposerQueueStatusLayoutTestObserver.observerForComposition())",
        ).forEach { required ->
            assertTrue("#1602 paired ownership unit oracle lost term: $required", unit.contains(required))
        }
        listOf(
            "offlineModalRowsOwnGeometryCopyAndScrollableDisabledRetryControls",
            "offline-row-a",
            "offline-row-b",
            "observedRowIds().containsAll(rows.map { it.id })",
            "retainedCoordinatePairsBeforeScrollByRowId",
            "each row/status pair must retain two independently-owned handles",
            "val seededViewportRowIds = buildList",
            "FIFO viewport seed must finish on the lower exact queue row",
            "assertEquals(rows.map { it.id }, provenRowIds)",
            "retryRawBoundsBeforeExactScroll",
            "rowRawBoundsBeforeExactScroll",
            "retryRawBoundsBeforeExactScroll.isNonEmptyAndFullyContainedBy(",
            // #2123: replaces the deleted scroll-offset-dependent invisibility
            // precondition with the device-independent blocked-reason copy.
            "hasText(\"Offline\") and",
            "postcondition: exact offline Retry must be displayed after its own scroll",
            "retryBoundsAfterExactScroll",
            "statusViewportBoundsAfterExactScroll",
            "retryBoundsAfterExactScroll.isNonEmptyAndFullyContainedBy(",
            "fully inside the bounded status viewport after scroll",
            "offlineRetryNode.assertIsNotEnabled()",
            "disabled offline Retry pointer taps must remain no-ops",
            "statusLayoutRegistration.close()",
        ).forEach { required ->
            assertTrue("#1602 component oracle lost term: $required", component.contains(required))
        }
    }

    private fun assertCopyScrollThenPairedGeometry(
        label: String,
        oracle: String,
        copyMatcherName: String,
    ) {
        val copyIndex = oracle.indexOf(
            "compose.onAllNodes($copyMatcherName, useUnmergedTree = true).assertCountEquals(1)",
        )
        val scrollIndex = oracle.indexOf(".performScrollTo()", startIndex = copyIndex.coerceAtLeast(0))
        val idleIndex = oracle.indexOf("compose.waitForIdle()", startIndex = scrollIndex.coerceAtLeast(0))
        val geometryIndex = oracle.indexOf(
            "currentWindowGeometry(row.id)",
            startIndex = idleIndex.coerceAtLeast(0),
        )
        assertTrue(
            "#1602 $label exact copy must precede scroll, idle, and lazy paired geometry",
            copyIndex >= 0 && scrollIndex > copyIndex && idleIndex > scrollIndex && geometryIndex > idleIndex,
        )
    }

    private fun assertScrollThenPairedGeometryThenCopy(
        label: String,
        oracle: String,
        copyMatcherName: String,
    ) {
        val scrollIndex = oracle.indexOf(".performScrollTo()")
        val idleIndex = oracle.indexOf("compose.waitForIdle()", startIndex = scrollIndex.coerceAtLeast(0))
        val geometryIndex = oracle.indexOf(
            "currentWindowGeometry(row.id)",
            startIndex = idleIndex.coerceAtLeast(0),
        )
        val copyIndex = oracle.indexOf(
            "compose.onAllNodes($copyMatcherName, useUnmergedTree = true).assertCountEquals(1)",
            startIndex = geometryIndex.coerceAtLeast(0),
        )
        assertTrue(
            "#1602 $label must prove raw geometry before the old product copy boundary",
            scrollIndex >= 0 && idleIndex > scrollIndex && geometryIndex > idleIndex && copyIndex > geometryIndex,
        )
    }

    private fun assertExactRetryScrollBeforeDisabledProof(
        label: String,
        oracle: String,
        actionMarker: String,
        endMarker: String,
    ) {
        val actionBlock = boundedOracleBlock(label, oracle, actionMarker, endMarker)
        val scrollIndex = actionBlock.indexOf(".performScrollTo()")
        val displayedIndex = actionBlock.indexOf(".assertIsDisplayed()", startIndex = scrollIndex.coerceAtLeast(0))
        val disabledIndex = actionBlock.indexOf(".assertIsNotEnabled()", startIndex = displayedIndex.coerceAtLeast(0))
        assertTrue(
            "#1602 $label must scroll the exact bounded-viewport Retry control before " +
                "proving it physically displayed and disabled",
            scrollIndex >= 0 && displayedIndex > scrollIndex && disabledIndex > displayedIndex,
        )
    }

    private fun assertExactRetryScrollBeforeDisabledPointerTap(
        label: String,
        oracle: String,
        actionMarker: String,
        endMarker: String,
    ) {
        val actionBlock = boundedOracleBlock(label, oracle, actionMarker, endMarker)
        val scrollIndex = actionBlock.indexOf(".performScrollTo()")
        val displayedIndex = actionBlock.indexOf(".assertIsDisplayed()", startIndex = scrollIndex.coerceAtLeast(0))
        val disabledIndex = actionBlock.indexOf(".assertIsNotEnabled()", startIndex = displayedIndex.coerceAtLeast(0))
        val pointerTapIndex = actionBlock.indexOf(
            ".performTouchInput { click() }",
            startIndex = disabledIndex.coerceAtLeast(0),
        )
        assertTrue(
            "#1602 $label must scroll the exact bounded-viewport Retry control before " +
                "proving it physically displayed, disabled, and pointer-no-op",
            scrollIndex >= 0 &&
                displayedIndex > scrollIndex &&
                disabledIndex > displayedIndex &&
                pointerTapIndex > disabledIndex,
        )
    }

    private fun assertRecoveredJourneyControlScrollOrdering(journey: String) {
        val method = boundedOracleBlock(
            label = "recovered real-send/Retry viewport ordering",
            oracle = journey,
            actionMarker = "fun provePromotionThenRetry(",
            endMarker = "private fun assertRetryControlFullyInStatusViewport(",
        )
        listOf(
            "val parkedRowTag = composerOutboundQueueItemRowTestTag(queuedIds.first())",
            "val youngerRowTag = composerOutboundQueueItemRowTestTag(queuedIds[1])",
            "hasAnyAncestor(hasTestTag(parkedStatusTag))",
            "hasAnyAncestor(hasTestTag(youngerStatusTag))",
            "hasAnyAncestor(hasTestTag(retryTag))",
            "compose.onAllNodes(waitingStatus, useUnmergedTree = true).assertCountEquals(1)",
            "compose.onAllNodes(waitingRetryControl, useUnmergedTree = true).assertCountEquals(1)",
            "compose.onAllNodes(waitingRetryLabel, useUnmergedTree = true).assertCountEquals(1)",
            "compose.onAllNodes(youngerSendingStatus, useUnmergedTree = true).assertCountEquals(1)",
            "assertRetryControlFullyInStatusViewport(",
            "compose.onAllNodes(retryingStatus, useUnmergedTree = true).assertCountEquals(1)",
            "compose.onAllNodes(retryingLabel, useUnmergedTree = true).assertCountEquals(1)",
            "compose.onAllNodes(retriedSendingStatus, useUnmergedTree = true).assertCountEquals(1)",
        ).forEach { required ->
            assertTrue(
                "#1602 recovered viewport proof lost row-scoped copy/count/containment: $required",
                method.contains(required),
            )
        }

        val waitingStatusNode = method.indexOf("compose.onNode(waitingStatus, useUnmergedTree = true)")
        val waitingStatusScroll = method.indexOf(
            ".performScrollTo()",
            startIndex = waitingStatusNode.coerceAtLeast(0),
        )
        val waitingRetryNode = method.indexOf(
            "val waitingRetryNode = compose.onNode(waitingRetryControl, useUnmergedTree = true)",
            startIndex = waitingStatusScroll.coerceAtLeast(0),
        )
        val waitingRetryScroll = method.indexOf(
            ".performScrollTo()",
            startIndex = waitingRetryNode.coerceAtLeast(0),
        )
        val waitingContainment = method.indexOf(
            "stage = \"waiting behind younger real send\"",
            startIndex = waitingRetryScroll.coerceAtLeast(0),
        )
        val waitingLabelDisplayed = method.indexOf(
            "compose.onNode(waitingRetryLabel, useUnmergedTree = true).assertIsDisplayed()",
            startIndex = waitingContainment.coerceAtLeast(0),
        )
        val youngerStatusNode = method.indexOf(
            "compose.onNode(youngerSendingStatus, useUnmergedTree = true)",
            startIndex = waitingLabelDisplayed.coerceAtLeast(0),
        )
        val youngerStatusScroll = method.indexOf(
            ".performScrollTo()",
            startIndex = youngerStatusNode.coerceAtLeast(0),
        )
        assertTrue(
            "#1602 must scroll exact waiting status, then exact row-owned control and prove containment " +
                "before its label, then scroll the exact younger Sending status",
            waitingStatusNode >= 0 &&
                waitingStatusScroll > waitingStatusNode &&
                waitingRetryNode > waitingStatusScroll &&
                waitingRetryScroll > waitingRetryNode &&
                waitingContainment > waitingRetryScroll &&
                waitingLabelDisplayed > waitingContainment &&
                youngerStatusNode > waitingLabelDisplayed &&
                youngerStatusScroll > youngerStatusNode,
        )

        val retryingStatusNode = method.indexOf("compose.onNode(retryingStatus, useUnmergedTree = true)")
        val retryingStatusScroll = method.indexOf(
            ".performScrollTo()",
            startIndex = retryingStatusNode.coerceAtLeast(0),
        )
        val retryingControlNode = method.indexOf(
            "val retryingControlNode = compose.onNode(waitingRetryControl, useUnmergedTree = true)",
            startIndex = retryingStatusScroll.coerceAtLeast(0),
        )
        val retryingControlScroll = method.indexOf(
            ".performScrollTo()",
            startIndex = retryingControlNode.coerceAtLeast(0),
        )
        val retryingContainment = method.indexOf(
            "stage = \"Retrying after physical tap\"",
            startIndex = retryingControlScroll.coerceAtLeast(0),
        )
        val retryingLabelDisplayed = method.indexOf(
            "compose.onNode(retryingLabel, useUnmergedTree = true).assertIsDisplayed()",
            startIndex = retryingContainment.coerceAtLeast(0),
        )
        assertTrue(
            "#1602 Retrying copy must follow exact status/control scroll and containment",
            retryingStatusNode >= 0 &&
                retryingStatusScroll > retryingStatusNode &&
                retryingControlNode > retryingStatusScroll &&
                retryingControlScroll > retryingControlNode &&
                retryingContainment > retryingControlScroll &&
                retryingLabelDisplayed > retryingContainment,
        )
    }

    private fun assertComponentRetryClipTransition(component: String) {
        val method = boundedOracleBlock(
            label = "component clipped-to-visible Retry transition",
            oracle = component,
            actionMarker = "fun offlineModalRowsOwnGeometryCopyAndScrollableDisabledRetryControls()",
            endMarker = "fun statusLedSingleRowsOwnCopyProgressAndResendPresentation()",
        )
        val seedIndex = method.indexOf("val seededViewportRowIds = buildList")
        val seedRowIndex = method.indexOf(
            "composerOutboundQueueItemRowTestTag(seedRow.id)",
            startIndex = seedIndex.coerceAtLeast(0),
        )
        val seedScrollIndex = method.indexOf(
            ".performScrollTo()",
            startIndex = seedRowIndex.coerceAtLeast(0),
        )
        val seedOrderProofIndex = method.indexOf(
            "FIFO viewport seed must finish on the lower exact queue row",
            startIndex = seedScrollIndex.coerceAtLeast(0),
        )
        val rowMarkerIndex = method.indexOf(
            "compose.onNodeWithTag(rowTag, useUnmergedTree = true)",
            startIndex = seedOrderProofIndex.coerceAtLeast(0),
        )
        val rowLevelScrollIndex = method.indexOf(
            ".performScrollTo()",
            startIndex = rowMarkerIndex.coerceAtLeast(0),
        )
        val rawRetryBoundsIndex = method.indexOf(
            "val retryRawBoundsBeforeExactScroll",
            startIndex = rowLevelScrollIndex.coerceAtLeast(0),
        )
        val rawRetryWithinRowIndex = method.indexOf(
            "retryRawBoundsBeforeExactScroll.isNonEmptyAndFullyContainedBy(",
            startIndex = rawRetryBoundsIndex.coerceAtLeast(0),
        )
        // Issue #2123 deleted the `offlineRetryNode.assertIsNotDisplayed()`
        // precondition that used to separate the row-level scroll from the exact
        // action scroll: it pinned a viewport-size accident ("this control happens
        // to be off-screen at this offset"), which was false on every device. The
        // separator is now the exact trailing-action RECEIVER — the scroll proving
        // reachability must be performed on the Retry control itself, not merely on
        // its potentially taller row.
        val exactActionNodeIndex = method.indexOf(
            "offlineRetryNode",
            startIndex = rawRetryWithinRowIndex.coerceAtLeast(0),
        )
        val exactActionScrollIndex = method.indexOf(
            ".performScrollTo()",
            startIndex = exactActionNodeIndex.coerceAtLeast(0),
        )
        val namedDisplayedPostconditionIndex = method.indexOf(
            "postcondition: exact offline Retry must be displayed after its own scroll",
            startIndex = exactActionScrollIndex.coerceAtLeast(0),
        )
        val disabledPostconditionIndex = method.indexOf(
            "offlineRetryNode.assertIsNotEnabled()",
            startIndex = namedDisplayedPostconditionIndex.coerceAtLeast(0),
        )
        // The device-independent property #2123 put in the accident's place: the
        // blocked reason travels ON the control, so a reachable action never reads
        // as a bare, silently-inert "Retry" regardless of viewport height.
        val blockedReasonCopyIndex = method.indexOf(
            "hasText(\"Offline\") and",
            startIndex = disabledPostconditionIndex.coerceAtLeast(0),
        )
        val postBoundsIndex = method.indexOf(
            "val retryBoundsAfterExactScroll",
            startIndex = blockedReasonCopyIndex.coerceAtLeast(0),
        )
        val containedPostconditionIndex = method.indexOf(
            "retryBoundsAfterExactScroll.isNonEmptyAndFullyContainedBy(",
            startIndex = postBoundsIndex.coerceAtLeast(0),
        )
        val physicalTapIndex = method.indexOf(
            "offlineRetryNode.performTouchInput { click() }",
            startIndex = containedPostconditionIndex.coerceAtLeast(0),
        )
        assertTrue(
            "#1602 component must seed the real FIFO capture position, prove the laid-out Retry contained by " +
                "its own row after the row scroll, then exact-scroll the trailing action itself into the " +
                "bounded viewport before the disabled/blocked-reason/containment/tap proof",
            seedIndex >= 0 &&
                seedRowIndex > seedIndex &&
                seedScrollIndex > seedRowIndex &&
                seedOrderProofIndex > seedScrollIndex &&
                rowMarkerIndex > seedOrderProofIndex &&
                rowLevelScrollIndex > rowMarkerIndex &&
                rawRetryBoundsIndex > rowLevelScrollIndex &&
                rawRetryWithinRowIndex > rawRetryBoundsIndex &&
                exactActionNodeIndex > rawRetryWithinRowIndex &&
                exactActionScrollIndex > exactActionNodeIndex &&
                namedDisplayedPostconditionIndex > exactActionScrollIndex &&
                disabledPostconditionIndex > namedDisplayedPostconditionIndex &&
                blockedReasonCopyIndex > disabledPostconditionIndex &&
                postBoundsIndex > blockedReasonCopyIndex &&
                containedPostconditionIndex > postBoundsIndex &&
                physicalTapIndex > containedPostconditionIndex,
        )
        // Issue #2123: keep the deleted accident deleted. A scroll-offset-dependent
        // invisibility assertion inside THIS proof is the exact defect that made the
        // reopened #1602 evidence red on every device; the comment that records the
        // deletion carries no leading dot, so it is not matched here.
        assertFalse(
            "#1602 component must not reinstate a scroll-offset-dependent invisibility assertion " +
                "on a control it also proves reachable",
            method.contains(".assertIsNotDisplayed()"),
        )
    }

    private fun boundedOracleBlock(
        label: String,
        oracle: String,
        actionMarker: String,
        endMarker: String,
    ): String {
        val actionIndex = oracle.indexOf(actionMarker)
        val endIndex = oracle.indexOf(endMarker, startIndex = actionIndex.coerceAtLeast(0) + 1)
        assertTrue("#1602 $label action marker must exist", actionIndex >= 0)
        assertTrue("#1602 $label end marker must follow its action marker", endIndex > actionIndex)
        return oracle.substring(actionIndex, endIndex)
    }

    @Test
    fun documentPickerCallbackDoesNotProbeMimeOnMain() {
        val src = locate("PromptComposerSheet.kt")
        val callback = src.substringFrom("val attachmentLauncher = rememberLauncherForActivityResult")

        assertFalse(
            "PromptComposerSheet picker callback must not touch ContentResolver; " +
                "resolve MIME/display-name/bytes inside attachment staging off Main.",
            callback.contains("contentResolver") ||
                callback.contains(".getType(") ||
                callback.contains(".query(") ||
                callback.contains(".openInputStream("),
        )
    }

    private fun String.substringFrom(marker: String): String {
        val start = indexOf(marker)
        check(start >= 0) { "$marker not found" }
        return substring(start, minOf(start + 900, length))
    }

    private fun locate(relative: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/pocketshell/app/composer/$relative"),
            File("src/main/java/com/pocketshell/app/composer/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun locateProject(relative: String): String {
        var cursor = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val file = cursor.resolve(relative)
            if (file.isFile) return file.readText()
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Could not locate $relative from ${File(".").absolutePath}")
    }
}
