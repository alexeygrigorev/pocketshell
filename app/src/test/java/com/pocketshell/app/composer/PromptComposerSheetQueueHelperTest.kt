package com.pocketshell.app.composer

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptComposerSheetQueueHelperTest {

    @Test
    fun outboundQueueSummaryIsStatusLedForEverySingleRowState() {
        assertEquals(
            OutboundQueueSummary("Queued — sending next", "“queued prompt”"),
            outboundQueueSummary(listOf(item("queued prompt", OutboundState.Queued, 1L)), false),
        )
        assertEquals(
            OutboundQueueSummary("Queued — will send on reconnect", "“queued prompt”"),
            outboundQueueSummary(listOf(item("queued prompt", OutboundState.Queued, 1L)), true),
        )
        assertEquals(
            OutboundQueueSummary("Uploading attachments", "“uploading”"),
            outboundQueueSummary(listOf(item("uploading", OutboundState.Uploading, 1L)), false),
        )
        assertEquals(
            OutboundQueueSummary("Sending", "“in flight”"),
            outboundQueueSummary(listOf(item("in flight", OutboundState.InFlight, 1L)), false),
        )
        assertEquals(
            OutboundQueueSummary("Failed — tap Retry", "“failed”", attention = true),
            outboundQueueSummary(listOf(item("failed", OutboundState.Failed, 1L)), false),
        )
    }

    @Test
    fun outboundQueueSummaryNormalizesPreviewAndSupportsAttachmentOnlyRows() {
        val multiline = item("  \n first useful line \nsecond", OutboundState.InFlight, 1L)
        val attachmentOnly = item("", OutboundState.Failed, 2L).copy(
            attachments = listOf(
                DurableAttachmentRef("/tmp/a", "a", "text/plain"),
                DurableAttachmentRef("/tmp/b", "b", "text/plain"),
            ),
        )

        assertEquals("“first useful line”", outboundQueueSummary(listOf(multiline), false).preview)
        assertEquals("“2 attachments”", outboundQueueSummary(listOf(attachmentOnly), false).preview)
    }

    @Test
    fun outboundQueueSummaryUsesFailurePrecedenceAndTruthfulMultiRowCopy() {
        val uploading = item("oldest", OutboundState.Uploading, 1L)
        val failed = item("failed", OutboundState.Failed, 2L)
        assertEquals(
            OutboundQueueSummary("2 queued", "“oldest”", attentionSuffix = "1 failed"),
            outboundQueueSummary(listOf(uploading, failed), false),
        )
        assertEquals(
            OutboundQueueSummary("2 queued · uploading oldest first", "“oldest”"),
            outboundQueueSummary(listOf(uploading, item("next", OutboundState.Queued, 2L)), false),
        )
        assertEquals(
            OutboundQueueSummary("2 queued · will send on reconnect", "“oldest”"),
            outboundQueueSummary(listOf(uploading, item("next", OutboundState.Queued, 2L)), true),
        )
    }

    @Test
    fun resendAndProgressPresentationUseTheSameQueueFactsAsTheirActions() {
        val failed = item("failed", OutboundState.Failed, 1L)
        assertTrue(isComposerResendMode("", false, failed, sendInFlight = false))
        assertFalse(isComposerResendMode("draft B", false, failed, sendInFlight = false))
        assertFalse(isComposerResendMode("", true, failed, sendInFlight = false))
        assertFalse(isComposerResendMode("", false, failed, sendInFlight = true))

        assertFalse(showComposerSendProgress(true, listOf(item("active", OutboundState.InFlight, 1L))))
        assertFalse(showComposerSendProgress(true, listOf(item("upload", OutboundState.Uploading, 1L))))
        assertTrue(showComposerSendProgress(true, emptyList()))
        assertFalse(showComposerSendProgress(false, emptyList()))
    }

    @Test
    fun retryableOutboundQueueItemPicksOldestQueuedOrFailedRow() {
        val uploading = item("uploading", OutboundState.Uploading, createdAtMs = 1L)
        val failed = item("failed", OutboundState.Failed, createdAtMs = 2L)
        val queued = item("queued", OutboundState.Queued, createdAtMs = 3L)

        assertEquals(failed, retryableOutboundQueueItem(listOf(uploading, failed, queued)))
    }

    @Test
    fun retryableOutboundQueueItemIgnoresActiveAndDeliveredRows() {
        assertNull(
            retryableOutboundQueueItem(
                listOf(
                    item("uploading", OutboundState.Uploading, createdAtMs = 1L),
                    item("in-flight", OutboundState.InFlight, createdAtMs = 2L),
                    item("delivered", OutboundState.Delivered, createdAtMs = 3L),
                ),
            ),
        )
    }

    @Test
    fun retryableOutboundQueueItemTreatsRecoveredUploadingRowAsRetryableAfterRequeue() {
        val recoveredUpload = item("recovered-upload", OutboundState.Queued, createdAtMs = 1L)
        val freshUpload = item("fresh-upload", OutboundState.Uploading, createdAtMs = 2L)

        assertEquals(
            recoveredUpload,
            retryableOutboundQueueItem(listOf(recoveredUpload, freshUpload)),
        )
    }

    @Test
    fun outboundRetryActionStatesAreImmediateAndExplainWhyBusyIsDisabled() {
        val row = item("retry-row", OutboundState.Failed, createdAtMs = 1L)

        assertEquals(
            OutboundRetryActionState("Retry", enabled = true),
            outboundRetryActionState(row, emptySet(), blockedByHealthyOwner = false, wireWritable = true),
        )
        assertEquals(
            OutboundRetryActionState(
                "Offline",
                enabled = false,
                status = "Waiting — connection is offline",
            ),
            outboundRetryActionState(
                row,
                emptySet(),
                blockedByHealthyOwner = false,
                wireWritable = false,
            ),
        )
        assertEquals(
            OutboundRetryActionState(
                "Retrying…",
                enabled = false,
                status = "Retrying — starting delivery",
            ),
            outboundRetryActionState(row, setOf(row.id), blockedByHealthyOwner = false, wireWritable = true),
        )
        assertEquals(
            OutboundRetryActionState(
                "Waiting…",
                enabled = false,
                status = "Waiting — another prompt is still sending",
            ),
            outboundRetryActionState(row, emptySet(), blockedByHealthyOwner = true, wireWritable = true),
        )
    }

    @Test
    fun outboundQueueStatusTagIsBoundToTheExactDurableRowIdentity() {
        assertEquals(
            "prompt-composer-outbound-queue-status:row-a",
            composerOutboundQueueStatusTestTag("row-a"),
        )
        assertEquals(
            "prompt-composer-outbound-queue-status:row-b",
            composerOutboundQueueStatusTestTag("row-b"),
        )
    }

    @Test
    fun statusLayoutObserverRejectsStaleCallbacksAndStaleOwnerCleanup() {
        val first = PromptComposerQueueStatusLayoutTestObserver.install()
        var second: PromptComposerQueueStatusLayoutTestObserver.Registration? = null
        try {
            val staleBinding = requireNotNull(first.bind("row-a"))
            val firstRow = fakeLayoutCoordinates(AtomicBoolean(true), "first-row")
            val firstStatus = fakeLayoutCoordinates(AtomicBoolean(true), "first-status")
            staleBinding.recordRow(firstRow)
            staleBinding.recordStatus(firstStatus)
            assertEquals(setOf("row-a"), first.observedRowIds())
            assertSame(firstRow, requireNotNull(first.currentCoordinates("row-a")).row)
            assertSame(firstStatus, requireNotNull(first.currentCoordinates("row-a")).status)

            first.close()
            val secondRegistration = PromptComposerQueueStatusLayoutTestObserver.install()
            second = secondRegistration
            assertTrue(
                "a replacement observer owner must have a newer generation token",
                secondRegistration.generation > first.generation,
            )
            val currentBinding = requireNotNull(secondRegistration.bind("row-a"))
            val secondRow = fakeLayoutCoordinates(AtomicBoolean(true), "second-row")
            val secondStatus = fakeLayoutCoordinates(AtomicBoolean(true), "second-status")
            currentBinding.recordRow(secondRow)
            currentBinding.recordStatus(secondStatus)
            staleBinding.recordRow(fakeLayoutCoordinates(AtomicBoolean(true), "stale-row"))
            staleBinding.recordStatus(fakeLayoutCoordinates(AtomicBoolean(true), "stale-status"))
            assertSame(
                "a closed generation must not replace the current owner's exact row coordinates",
                secondRow,
                requireNotNull(secondRegistration.currentCoordinates("row-a")).row,
            )
            assertSame(
                "a closed generation must not replace the current owner's exact status coordinates",
                secondStatus,
                requireNotNull(secondRegistration.currentCoordinates("row-a")).status,
            )

            first.close()
            assertSame(
                "a stale owner must not clear the newer registration",
                secondRegistration,
                PromptComposerQueueStatusLayoutTestObserver.observerForComposition(),
            )
            assertEquals(setOf("row-a"), secondRegistration.observedRowIds())
        } finally {
            first.close()
            second?.close()
        }
        assertNull(PromptComposerQueueStatusLayoutTestObserver.observerForComposition())
    }

    @Test
    fun statusLayoutObserverPairsAndIndependentlyReplacesCurrentRowAndStatusHandles() {
        val registration = PromptComposerQueueStatusLayoutTestObserver.install()
        try {
            val firstBinding = requireNotNull(registration.bind("row-a"))
            val firstRow = fakeLayoutCoordinates(AtomicBoolean(true), "first-row-handle")
            val firstStatus = fakeLayoutCoordinates(AtomicBoolean(true), "first-status-handle")
            firstBinding.recordRow(firstRow)
            assertTrue(
                "a row callback alone must not publish a mixed or incomplete coordinate pair",
                registration.observedRowIds().isEmpty(),
            )
            firstBinding.recordStatus(firstStatus)
            assertSame(firstRow, requireNotNull(registration.currentCoordinates("row-a")).row)
            assertSame(firstStatus, requireNotNull(registration.currentCoordinates("row-a")).status)

            val replacementBinding = requireNotNull(registration.bind("row-a"))
            assertTrue(
                "a replacement row binding must have a newer identity token",
                replacementBinding.token > firstBinding.token,
            )
            assertTrue(
                "a replacement binding must not inherit the first callback snapshot",
                registration.observedRowIds().isEmpty(),
            )
            firstBinding.recordRow(fakeLayoutCoordinates(AtomicBoolean(true), "late-first-row"))
            firstBinding.recordStatus(fakeLayoutCoordinates(AtomicBoolean(true), "late-first-status"))
            firstBinding.close()
            assertNull(
                "a stale callback/close must not populate or remove the replacement pair",
                registration.currentCoordinates("row-a"),
            )

            val rowAttached = AtomicBoolean(true)
            val statusAttached = AtomicBoolean(true)
            val currentRow = fakeLayoutCoordinates(rowAttached, "current-row")
            val currentStatus = fakeLayoutCoordinates(statusAttached, "current-status")
            replacementBinding.recordRow(currentRow)
            replacementBinding.recordStatus(currentStatus)
            assertEquals(setOf("row-a"), registration.observedRowIds())
            val currentPair = requireNotNull(registration.currentCoordinates("row-a"))
            assertSame(currentRow, currentPair.row)
            assertSame(currentStatus, currentPair.status)

            val latestStatus = fakeLayoutCoordinates(AtomicBoolean(true), "latest-status")
            replacementBinding.recordStatus(latestStatus)
            val statusReplacedPair = requireNotNull(registration.currentCoordinates("row-a"))
            assertSame(
                "a status callback must not replace the retained exact row handle",
                currentRow,
                statusReplacedPair.row,
            )
            assertSame(
                "a subsequent status callback must replace only the retained status handle",
                latestStatus,
                statusReplacedPair.status,
            )

            val latestRow = fakeLayoutCoordinates(rowAttached, "latest-row")
            replacementBinding.recordRow(latestRow)
            val rowReplacedPair = requireNotNull(registration.currentCoordinates("row-a"))
            assertSame(
                "a subsequent row callback must replace only the retained row handle",
                latestRow,
                rowReplacedPair.row,
            )
            assertSame(
                "a row callback must not replace the retained exact status handle",
                latestStatus,
                rowReplacedPair.status,
            )

            rowAttached.set(false)
            assertNull("a detached row handle must reject the exact coordinate pair", registration.currentCoordinates("row-a"))
            rowAttached.set(true)
            statusAttached.set(false)
            replacementBinding.recordStatus(currentStatus)
            assertNull("a detached status handle must reject the exact coordinate pair", registration.currentCoordinates("row-a"))
            statusAttached.set(true)

            val wrongId = registration.currentWindowGeometry("wrong-row-id")
            assertTrue("a wrong row ID must name its missing binding", "binding_not_current" in wrongId.failureTerms())
            assertTrue("a wrong row ID must name its missing row handle", "row_handle_missing" in wrongId.failureTerms())
            assertTrue("a wrong row ID must name its missing status handle", "status_handle_missing" in wrongId.failureTerms())
        } finally {
            registration.close()
        }
        assertNull(PromptComposerQueueStatusLayoutTestObserver.observerForComposition())
    }

    @Test
    fun statusLayoutObserverUsesRawWindowPositionAndSizeWhenClippedBoundsAreZero() {
        val registration = PromptComposerQueueStatusLayoutTestObserver.install()
        try {
            val binding = requireNotNull(registration.bind("row-a"))
            binding.recordRow(
                fakeLayoutCoordinates(
                    attached = AtomicBoolean(true),
                    label = "row-clipped-by-sheet",
                    rawLeft = 10f,
                    rawTop = 20f,
                    rawWidth = 100,
                    rawHeight = 80,
                    clippedBounds = Rect.Zero,
                ),
            )
            binding.recordStatus(
                fakeLayoutCoordinates(
                    attached = AtomicBoolean(true),
                    label = "status-clipped-by-sheet",
                    rawLeft = 10f,
                    rawTop = 30f,
                    rawWidth = 100,
                    rawHeight = 20,
                    clippedBounds = Rect.Zero,
                ),
            )

            val geometry = registration.currentWindowGeometry("row-a")

            assertEquals(Rect(10f, 20f, 110f, 100f), geometry.rowBounds)
            assertEquals(Rect(10f, 30f, 110f, 50f), geometry.statusBounds)
            assertTrue(
                "raw position plus measured size must prove the visible full-width status " +
                    "even when clip-aware bounds collapse to Rect.Zero: ${geometry.diagnosticSummary()}",
                geometry.isCurrentAttachedNonEmptyContainedFullWidthPair(),
            )
        } finally {
            registration.close()
        }
    }

    @Test
    fun statusLayoutObserverFailsClosedForInvalidRawGeometryAndReadExceptions() {
        val zeroSize = currentWindowGeometryFor(
            row = geometryCoordinates("row", left = 10f, top = 20f, width = 100, height = 80),
            status = geometryCoordinates("zero-status", left = 10f, top = 30f, width = 0, height = 20),
        )
        assertTrue("status_bounds_empty" in zeroSize.failureTerms())
        assertFalse(zeroSize.isCurrentAttachedNonEmptyContainedFullWidthPair())

        val nonFinite = currentWindowGeometryFor(
            row = geometryCoordinates("row", left = 10f, top = 20f, width = 100, height = 80),
            status = geometryCoordinates("nan-status", left = Float.NaN, top = 30f, width = 100, height = 20),
        )
        assertTrue("status_bounds_unavailable" in nonFinite.failureTerms())
        assertFalse(nonFinite.isCurrentAttachedNonEmptyContainedFullWidthPair())

        val throwing = currentWindowGeometryFor(
            row = geometryCoordinates("row", left = 10f, top = 20f, width = 100, height = 80),
            status = geometryCoordinates(
                "throwing-status",
                left = 10f,
                top = 30f,
                width = 100,
                height = 20,
                throwOnRawPositionRead = true,
            ),
        )
        assertTrue("status_bounds_unavailable" in throwing.failureTerms())
        assertFalse(throwing.isCurrentAttachedNonEmptyContainedFullWidthPair())
    }

    @Test
    fun statusLayoutObserverRevalidatesAttachmentBindingAndHandlesAfterRawRead() {
        val detachedDuringRead = AtomicBoolean(true)
        val detachedSnapshot = currentWindowGeometryFor(
            row = geometryCoordinates("row", left = 10f, top = 20f, width = 100, height = 80),
            status = geometryCoordinates(
                "detaching-status",
                attached = detachedDuringRead,
                left = 10f,
                top = 30f,
                width = 100,
                height = 20,
                onRawPositionRead = { detachedDuringRead.set(false) },
            ),
        )
        assertTrue("status_detached" in detachedSnapshot.failureTerms())
        assertFalse(detachedSnapshot.isCurrentAttachedNonEmptyContainedFullWidthPair())

        val ownerRegistration = PromptComposerQueueStatusLayoutTestObserver.install()
        try {
            val ownerBinding = requireNotNull(ownerRegistration.bind("row-a"))
            ownerBinding.recordRow(
                geometryCoordinates("owner-row", left = 10f, top = 20f, width = 100, height = 80),
            )
            ownerBinding.recordStatus(
                geometryCoordinates(
                    "owner-closed-during-read",
                    left = 10f,
                    top = 30f,
                    width = 100,
                    height = 20,
                    onRawPositionRead = { ownerRegistration.close() },
                ),
            )

            val staleOwnerSnapshot = ownerBinding.currentWindowGeometry()

            assertTrue("owner_not_current" in staleOwnerSnapshot.failureTerms())
            assertFalse(staleOwnerSnapshot.isCurrentAttachedNonEmptyContainedFullWidthPair())
        } finally {
            ownerRegistration.close()
        }

        val registration = PromptComposerQueueStatusLayoutTestObserver.install()
        try {
            val binding = requireNotNull(registration.bind("row-a"))
            val replacementStatus = geometryCoordinates(
                "replacement-status",
                left = 10f,
                top = 30f,
                width = 100,
                height = 20,
            )
            binding.recordRow(
                geometryCoordinates("row", left = 10f, top = 20f, width = 100, height = 80),
            )
            binding.recordStatus(
                geometryCoordinates(
                    "replaced-during-read",
                    left = 10f,
                    top = 30f,
                    width = 100,
                    height = 20,
                    onRawPositionRead = { binding.recordStatus(replacementStatus) },
                ),
            )

            val replacedSnapshot = registration.currentWindowGeometry("row-a")

            assertTrue("handle_pair_replaced_during_read" in replacedSnapshot.failureTerms())
            assertFalse(replacedSnapshot.isCurrentAttachedNonEmptyContainedFullWidthPair())
        } finally {
            registration.close()
        }

        val replacementRegistration = PromptComposerQueueStatusLayoutTestObserver.install()
        try {
            val binding = requireNotNull(replacementRegistration.bind("row-a"))
            binding.recordRow(
                geometryCoordinates("row", left = 10f, top = 20f, width = 100, height = 80),
            )
            binding.recordStatus(
                geometryCoordinates(
                    "binding-replaced-during-read",
                    left = 10f,
                    top = 30f,
                    width = 100,
                    height = 20,
                    onRawPositionRead = { replacementRegistration.bind("row-a") },
                ),
            )

            val staleBindingSnapshot = replacementRegistration.currentWindowGeometry("row-a")

            assertTrue("binding_not_current" in staleBindingSnapshot.failureTerms())
            assertFalse(staleBindingSnapshot.isCurrentAttachedNonEmptyContainedFullWidthPair())
        } finally {
            replacementRegistration.close()
        }
    }

    @Test
    fun statusWindowGeometryNamesDetachedOutsideAndNonFullWidthTerms() {
        val fullWidth = PromptComposerQueueStatusLayoutTestObserver.WindowGeometrySnapshot(
            rowId = "row-a",
            generation = 7L,
            bindingToken = 11L,
            ownerCurrent = true,
            bindingCurrent = true,
            rowHandlePresent = true,
            statusHandlePresent = true,
            rowAttached = true,
            statusAttached = true,
            handlesStillCurrent = true,
            rowBounds = Rect(10f, 20f, 110f, 100f),
            statusBounds = Rect(10f, 30f, 110f, 50f),
        )
        assertTrue(fullWidth.isCurrentAttachedNonEmptyContainedFullWidthPair())
        assertTrue(fullWidth.failureTerms().isEmpty())

        val narrowAndOutside = fullWidth.copy(
            statusBounds = Rect(12f, 10f, 108f, 120f),
        )
        assertEquals(
            listOf(
                "status_left_not_full_width",
                "status_right_not_full_width",
                "status_top_outside_row",
                "status_bottom_outside_row",
            ),
            narrowAndOutside.failureTerms(),
        )
        val diagnostic = narrowAndOutside.diagnosticSummary()
        assertTrue(diagnostic.contains("rowBounds="))
        assertTrue(diagnostic.contains("statusBounds="))
        assertTrue(diagnostic.contains("status_left_not_full_width"))

        val detachedPair = fullWidth.copy(rowAttached = false, statusAttached = false)
        assertTrue("row_detached" in detachedPair.failureTerms())
        assertTrue("status_detached" in detachedPair.failureTerms())
        assertFalse(detachedPair.isCurrentAttachedNonEmptyContainedFullWidthPair())
    }

    private fun fakeLayoutCoordinates(
        attached: AtomicBoolean,
        label: String,
        rawLeft: Float? = null,
        rawTop: Float? = null,
        rawWidth: Int? = null,
        rawHeight: Int? = null,
        clippedBounds: Rect = Rect.Zero,
        onRawPositionRead: () -> Unit = {},
        throwOnRawPositionRead: Boolean = false,
    ): LayoutCoordinates {
        val clippedWindowRoot = Proxy.newProxyInstance(
            LayoutCoordinates::class.java.classLoader,
            arrayOf(LayoutCoordinates::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "isAttached" -> attached.get()
                "getSize-YbymL2g" -> packInts(1080, 2400)
                "getParentLayoutCoordinates", "getParentCoordinates" -> null
                "localBoundingBoxOf" -> clippedBounds
                "localToWindow-MK-Hz9U" -> args?.firstOrNull() as Long
                "toString" -> "FakeClippedWindowRoot($label)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("unexpected clipped root call in JVM oracle: ${method.name}")
            }
        } as LayoutCoordinates
        return Proxy.newProxyInstance(
            LayoutCoordinates::class.java.classLoader,
            arrayOf(LayoutCoordinates::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "isAttached" -> attached.get()
                "getSize-YbymL2g" -> packInts(
                    requireNotNull(rawWidth) { "raw width unavailable for $label" },
                    requireNotNull(rawHeight) { "raw height unavailable for $label" },
                )
                "getParentLayoutCoordinates", "getParentCoordinates" -> clippedWindowRoot
                "localBoundingBoxOf" -> clippedBounds
                "localToWindow-MK-Hz9U" -> {
                    onRawPositionRead()
                    if (throwOnRawPositionRead) error("forced raw position failure for $label")
                    val packedLocal = args?.firstOrNull() as Long
                    packFloats(
                        requireNotNull(rawLeft) { "raw left unavailable for $label" } + unpackFirstFloat(packedLocal),
                        requireNotNull(rawTop) { "raw top unavailable for $label" } + unpackSecondFloat(packedLocal),
                    )
                }
                "toString" -> "FakeLayoutCoordinates($label, attached=${attached.get()})"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("unexpected LayoutCoordinates call in JVM oracle: ${method.name}")
            }
        } as LayoutCoordinates
    }

    private fun geometryCoordinates(
        label: String,
        attached: AtomicBoolean = AtomicBoolean(true),
        left: Float,
        top: Float,
        width: Int,
        height: Int,
        onRawPositionRead: () -> Unit = {},
        throwOnRawPositionRead: Boolean = false,
    ): LayoutCoordinates = fakeLayoutCoordinates(
        attached = attached,
        label = label,
        rawLeft = left,
        rawTop = top,
        rawWidth = width,
        rawHeight = height,
        onRawPositionRead = onRawPositionRead,
        throwOnRawPositionRead = throwOnRawPositionRead,
    )

    private fun currentWindowGeometryFor(
        row: LayoutCoordinates,
        status: LayoutCoordinates,
    ): PromptComposerQueueStatusLayoutTestObserver.WindowGeometrySnapshot {
        val registration = PromptComposerQueueStatusLayoutTestObserver.install()
        return try {
            val binding = requireNotNull(registration.bind("row-a"))
            binding.recordRow(row)
            binding.recordStatus(status)
            registration.currentWindowGeometry("row-a")
        } finally {
            registration.close()
        }
    }

    private fun packInts(first: Int, second: Int): Long =
        (first.toLong() shl 32) or (second.toLong() and 0xffffffffL)

    private fun packFloats(first: Float, second: Float): Long =
        (first.toRawBits().toLong() shl 32) or (second.toRawBits().toLong() and 0xffffffffL)

    private fun unpackFirstFloat(packed: Long): Float = Float.fromBits((packed shr 32).toInt())

    private fun unpackSecondFloat(packed: Long): Float = Float.fromBits(packed.toInt())

    private fun item(id: String, state: OutboundState, createdAtMs: Long): OutboundItem =
        OutboundItem(
            id = id,
            sessionKey = "1/session-a",
            cleanText = id,
            state = state,
            createdAtMs = createdAtMs,
        )
}
