package com.pocketshell.app.tmux

import com.pocketshell.app.composer.OutboundItem
import com.pocketshell.app.composer.OutboundState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutboundLateAckReconcilerTest {
    private val binding = TmuxOutboundQueueBinding(
        targetKey = "1/\$42/1700",
        fallbackKey = "1/demo",
        durableKey = "1/\$42/1700",
        tmuxSessionId = "\$42",
        sessionCreated = 1700L,
        generationPaneIds = setOf("%7"),
    )

    private fun row() = OutboundItem(
        id = "row-1",
        sessionKey = binding.targetKey,
        cleanText = "exact prompt",
        createdAtMs = 1L,
        attemptCount = 1,
        paneId = "%7",
        sendKey = "send-1",
        wireSubmitAttempted = true,
        wireAttemptGeneration = 1,
        tmuxSessionId = "\$42",
        tmuxSessionCreated = 1700L,
    )

    @Test
    fun exactGenerationAndAttemptAreResolvedOnce() = runTest {
        var calls = 0
        val resolved = resolveLateOutboundAcks(
            rows = listOf(row()),
            binding = binding,
            resolveAuthoritativeAck = { calls++; true },
        )
        assertEquals(listOf("row-1"), resolved.map { it.id })
        assertEquals(1, calls)
    }

    @Test
    fun staleOrForeignEvidenceNeverReachesTranscriptAuthority() = runTest {
        val rejected = listOf(
            row().copy(sessionKey = "1/other"),
            row().copy(paneId = "%8"),
            row().copy(tmuxSessionId = "\$99"),
            row().copy(tmuxSessionCreated = 1800L),
            row().copy(sendKey = ""),
            row().copy(wireAttemptGeneration = 0),
            row().copy(wireSubmitAttempted = false),
        )
        assertTrue(rejected.none { it.matchesLateAckBinding(binding) })
        var callbacks = 0
        val resolved = resolveLateOutboundAcks(
            rows = rejected,
            binding = binding,
            resolveAuthoritativeAck = { callbacks++; true },
        )
        assertEquals(0, callbacks)
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun missingAuthorityIsNotReturnedForDurablePrune() = runTest {
        val resolved = resolveLateOutboundAcks(
            listOf(row()), binding,
            resolveAuthoritativeAck = { false },
        )
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun activeWireAttemptNeverReachesLateAuthorityUntilItIsRequeued() = runTest {
        val active = listOf(
            row().copy(id = "uploading", state = OutboundState.Uploading),
            row().copy(id = "in-flight", state = OutboundState.InFlight),
        )
        var callbacks = 0
        assertTrue(resolveLateOutboundAcks(active, binding) { callbacks++; true }.isEmpty())
        assertEquals(0, callbacks)
        assertFalse(active.any { it.matchesLateAckBinding(binding) })

        val idle = listOf(
            active.first().copy(state = OutboundState.Failed),
            active.last().copy(state = OutboundState.Queued),
        )
        assertEquals(idle, resolveLateOutboundAcks(idle, binding) { true })
    }

    @Test
    fun mixedBacklogReturnsEveryConfirmedExactRowInFifoOrderAsOneBatch() = runTest {
        val rows = listOf(
            row().copy(id = "row-1", createdAtMs = 1L),
            row().copy(id = "foreign", paneId = "%8", createdAtMs = 2L),
            row().copy(id = "row-2", sendKey = "send-2", createdAtMs = 3L),
            row().copy(id = "unconfirmed", sendKey = "send-3", createdAtMs = 4L),
        )
        val resolved = resolveLateOutboundAcks(rows, binding) { it.id != "unconfirmed" }
        assertEquals(listOf("row-1", "row-2"), resolved.map { it.id })
    }
}
