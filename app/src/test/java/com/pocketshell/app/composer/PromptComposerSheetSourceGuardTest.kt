package com.pocketshell.app.composer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
        assertOrderedMarkers(
            "must prove immediate ordered visibility before bounded raw-store durability",
            promotionDraftBlock,
            listOf(
                "composer.loadComposerDraft(durableKey)",
                "compose.waitUntil(timeoutMillis = uiTimeoutMs)",
                "composer.composerDraftStore.load(durableKey) == replacementDraft",
                "composer.composerDraftStore.load(fallbackKey) == null",
            ),
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

    /**
     * Walk [markers] in order through [block], searching each from after the
     * previous match. A miss hard-fails immediately, naming that marker —
     * Java's indexOf would otherwise restart from offset 0. Returns the match
     * indices only when the whole chain is strictly increasing.
     */
    private fun assertOrderedMarkers(
        label: String,
        block: String,
        markers: List<String>,
    ): List<Int> {
        val indices = ArrayList<Int>(markers.size)
        var startIndex = 0
        for ((offset, marker) in markers.withIndex()) {
            val found = block.indexOf(marker, startIndex)
            val markerNumber = offset + 1
            val predecessor = if (offset == 0) "start of block" else "marker $offset"
            assertTrue(
                "#1602 $label: marker $markerNumber of ${markers.size} not found after $predecessor — \"$marker\"",
                found >= 0,
            )
            if (indices.isNotEmpty()) {
                assertTrue(
                    "#1602 $label: marker $markerNumber of ${markers.size} is not after marker $offset — \"$marker\"",
                    found > indices.last(),
                )
            }
            indices.add(found)
            startIndex = found + 1
        }
        return indices
    }

    private fun assertCopyScrollThenPairedGeometry(
        label: String,
        oracle: String,
        copyMatcherName: String,
    ) {
        assertOrderedMarkers(
            "$label exact copy must precede scroll, idle, and lazy paired geometry",
            oracle,
            listOf(
                "compose.onAllNodes($copyMatcherName, useUnmergedTree = true).assertCountEquals(1)",
                ".performScrollTo()",
                "compose.waitForIdle()",
                "currentWindowGeometry(row.id)",
            ),
        )
    }

    private fun assertScrollThenPairedGeometryThenCopy(
        label: String,
        oracle: String,
        copyMatcherName: String,
    ) {
        assertOrderedMarkers(
            "$label must prove raw geometry before the old product copy boundary",
            oracle,
            listOf(
                ".performScrollTo()",
                "compose.waitForIdle()",
                "currentWindowGeometry(row.id)",
                "compose.onAllNodes($copyMatcherName, useUnmergedTree = true).assertCountEquals(1)",
            ),
        )
    }

    private fun assertExactRetryScrollBeforeDisabledProof(
        label: String,
        oracle: String,
        actionMarker: String,
        endMarker: String,
    ) {
        val actionBlock = boundedOracleBlock(label, oracle, actionMarker, endMarker)
        assertOrderedMarkers(
            "$label must scroll the exact bounded-viewport Retry control before " +
                "proving it physically displayed and disabled",
            actionBlock,
            listOf(
                ".performScrollTo()",
                ".assertIsDisplayed()",
                ".assertIsNotEnabled()",
            ),
        )
    }

    private fun assertExactRetryScrollBeforeDisabledPointerTap(
        label: String,
        oracle: String,
        actionMarker: String,
        endMarker: String,
    ) {
        val actionBlock = boundedOracleBlock(label, oracle, actionMarker, endMarker)
        assertOrderedMarkers(
            "$label must scroll the exact bounded-viewport Retry control before " +
                "proving it physically displayed, disabled, and pointer-no-op",
            actionBlock,
            listOf(
                ".performScrollTo()",
                ".assertIsDisplayed()",
                ".assertIsNotEnabled()",
                ".performTouchInput { click() }",
            ),
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

        assertOrderedMarkers(
            "must scroll exact waiting status, then exact row-owned control and prove containment " +
                "before its label, then scroll the exact younger Sending status",
            method,
            listOf(
                "compose.onNode(waitingStatus, useUnmergedTree = true)",
                ".performScrollTo()",
                "val waitingRetryNode = compose.onNode(waitingRetryControl, useUnmergedTree = true)",
                ".performScrollTo()",
                "stage = \"waiting behind younger real send\"",
                "compose.onNode(waitingRetryLabel, useUnmergedTree = true).assertIsDisplayed()",
                "compose.onNode(youngerSendingStatus, useUnmergedTree = true)",
                ".performScrollTo()",
            ),
        )

        assertOrderedMarkers(
            "Retrying copy must follow exact status/control scroll and containment",
            method,
            listOf(
                "compose.onNode(retryingStatus, useUnmergedTree = true)",
                ".performScrollTo()",
                "val retryingControlNode = compose.onNode(waitingRetryControl, useUnmergedTree = true)",
                ".performScrollTo()",
                "stage = \"Retrying after physical tap\"",
                "compose.onNode(retryingLabel, useUnmergedTree = true).assertIsDisplayed()",
            ),
        )
    }

    private fun assertComponentRetryClipTransition(component: String) {
        val method = boundedOracleBlock(
            label = "component clipped-to-visible Retry transition",
            oracle = component,
            actionMarker = "fun offlineModalRowsOwnGeometryCopyAndScrollableDisabledRetryControls()",
            endMarker = "fun statusLedSingleRowsOwnCopyProgressAndResendPresentation()",
        )
        // Issue #2123 deleted the `offlineRetryNode.assertIsNotDisplayed()`
        // precondition that used to separate the row-level scroll from the exact
        // action scroll: it pinned a viewport-size accident ("this control happens
        // to be off-screen at this offset"), which was false on every device. The
        // separator is now the exact trailing-action RECEIVER — the scroll proving
        // reachability must be performed on the Retry control itself, not merely on
        // its potentially taller row. The blocked-reason copy is the
        // device-independent property that replaced the accident: a reachable
        // action never reads as a bare, silently-inert "Retry".
        assertOrderedMarkers(
            "component must seed the real FIFO capture position, prove the laid-out Retry contained by " +
                "its own row after the row scroll, then exact-scroll the trailing action itself into the " +
                "bounded viewport before the disabled/blocked-reason/containment/tap proof",
            method,
            listOf(
                "val seededViewportRowIds = buildList",
                "composerOutboundQueueItemRowTestTag(seedRow.id)",
                ".performScrollTo()",
                "FIFO viewport seed must finish on the lower exact queue row",
                "compose.onNodeWithTag(rowTag, useUnmergedTree = true)",
                ".performScrollTo()",
                "val retryRawBoundsBeforeExactScroll",
                "retryRawBoundsBeforeExactScroll.isNonEmptyAndFullyContainedBy(",
                "offlineRetryNode",
                ".performScrollTo()",
                "postcondition: exact offline Retry must be displayed after its own scroll",
                "offlineRetryNode.assertIsNotEnabled()",
                "hasText(\"Offline\") and",
                "val retryBoundsAfterExactScroll",
                "retryBoundsAfterExactScroll.isNonEmptyAndFullyContainedBy(",
                "offlineRetryNode.performTouchInput { click() }",
            ),
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
        assertTrue("#1602 $label action marker must exist", actionIndex >= 0)
        val endIndex = oracle.indexOf(endMarker, startIndex = actionIndex + 1)
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

    @Test
    fun missingMarkerFailsClosedNamingTheGapInsteadOfSearchingFromZero() {
        // #2123 replay: the seed's `.performScrollTo()` sits BEFORE the last
        // found marker. The next required marker is absent. Search-from-0 then
        // resolves `.performScrollTo()` at the seed — earlier than the last
        // found marker.
        val block =
            """
            val seededViewportRowIds = buildList
            seedRow.performScrollTo()
            FIFO viewport seed must finish on the lower exact queue row
            retryRawBoundsBeforeExactScroll.isNonEmptyAndFullyContainedBy(
            offlineRetryNode
            .performScrollTo()
            postcondition: exact offline Retry must be displayed after its own scroll
            """.trimIndent()
        val markers = listOf(
            "val seededViewportRowIds = buildList",
            "FIFO viewport seed must finish on the lower exact queue row",
            "retryRawBoundsBeforeExactScroll.isNonEmptyAndFullyContainedBy(",
            "offlineRetryNode.assertIsNotDisplayed()",
            ".performScrollTo()",
        )
        val lastFound = block.indexOf("retryRawBoundsBeforeExactScroll.isNonEmptyAndFullyContainedBy(")
        val seedScroll = block.indexOf(".performScrollTo()")
        val searchFromZero = block.indexOf(".performScrollTo()", startIndex = 0)
        assertTrue(lastFound >= 0)
        assertEquals(
            "fixture must reproduce the #2123 search-from-0 trap, otherwise this test is vacuous",
            seedScroll,
            searchFromZero,
        )
        assertTrue(
            "search-from-0 must be anti-ordered vs the last found marker",
            searchFromZero < lastFound,
        )

        // G6: restoring search-from-0 (and continuing past a -1) must redden
        // the named-missing-marker assertion. The old conjunction either
        // fails with a message that names none of the markers, or — if the
        // missing conjunct is dropped — goes green on the remaining terms.
        val error = assertThrows(AssertionError::class.java) {
            assertOrderedMarkers(
                "component clipped-to-visible Retry transition",
                block,
                markers,
            )
        }
        val message = error.message.orEmpty()
        assertTrue(
            "must name the missing marker, not a later anti-ordered conjunction: $message",
            message.contains("marker 4 of 5 not found after marker 3"),
        )
        assertTrue(
            "must quote the missing marker text: $message",
            message.contains("offlineRetryNode.assertIsNotDisplayed()"),
        )
    }

    @Test
    fun missingMarkerFailsClosedWhenLaterMarkersStayUniqueAndOrdered() {
        // The fail-open shape: the missing marker's successor exists only
        // AFTER the predecessor, so search-from-0 still finds it in order.
        // Soundness then rests on every computed index appearing in the
        // final conjunction — drop that one conjunct and the guard is green
        // while a required marker is gone.
        val block =
            """
            first-marker
            third-marker
            """.trimIndent()
        val first = block.indexOf("first-marker")
        val thirdFromZero = block.indexOf("third-marker", startIndex = 0)
        assertTrue(first >= 0)
        assertTrue(
            "fixture must keep the later marker unique and ordered from 0",
            thirdFromZero > first,
        )

        val error = assertThrows(AssertionError::class.java) {
            assertOrderedMarkers(
                "fail-open trap",
                block,
                listOf("first-marker", "second-marker-absent", "third-marker"),
            )
        }
        val message = error.message.orEmpty()
        assertTrue(
            "must fail at the missing marker, not accept the unique later term: $message",
            message.contains("marker 2 of 3 not found after marker 1"),
        )
        assertTrue(
            "must quote the missing marker text: $message",
            message.contains("second-marker-absent"),
        )
    }

    @Test
    fun orderedMarkersReturnStrictlyIncreasingIndices() {
        val block = "aaa xxx bbb yyy ccc"
        val indices = assertOrderedMarkers("toy chain", block, listOf("aaa", "bbb", "ccc"))
        assertEquals(listOf(block.indexOf("aaa"), block.indexOf("bbb"), block.indexOf("ccc")), indices)
        assertTrue(indices.zipWithNext().all { (prev, next) -> next > prev })
    }

    @Test
    fun orderedMarkerChainsUseSharedHelperAndDoNotRestartMissingSearchAtZero() {
        val src = locateProject(
            "app/src/test/java/com/pocketshell/app/composer/PromptComposerSheetSourceGuardTest.kt",
        )
        val helperStart = src.indexOf("private fun assertOrderedMarkers(")
        assertTrue(helperStart >= 0)
        val helperEnd = src.indexOf("\n    private fun ", helperStart + 1)
        val helperBody = src.substring(helperStart, if (helperEnd >= 0) helperEnd else src.length)
        val restartToken = "coerceAtLeast" + "(0)"
        assertFalse(
            "assertOrderedMarkers must fail closed on a missing predecessor instead of restarting at offset 0",
            helperBody.contains(restartToken),
        )
        listOf(
            "private fun assertCopyScrollThenPairedGeometry",
            "private fun assertScrollThenPairedGeometryThenCopy",
            "private fun assertExactRetryScrollBeforeDisabledProof",
            "private fun assertExactRetryScrollBeforeDisabledPointerTap",
            "private fun assertRecoveredJourneyControlScrollOrdering",
            "private fun assertComponentRetryClipTransition",
        ).forEach { helper ->
            val start = src.indexOf(helper)
            assertTrue("$helper must exist", start >= 0)
            val nextFn = src.indexOf("\n    private fun ", start + helper.length)
            val body = src.substring(start, if (nextFn >= 0) nextFn else src.length)
            assertTrue(
                "$helper must walk markers through assertOrderedMarkers",
                body.contains("assertOrderedMarkers("),
            )
        }
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
