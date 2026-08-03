package com.pocketshell.app.tmux

import com.pocketshell.app.composer.InMemoryOutboundQueueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutboundPromotionDrainOrderingTest {
    @Test
    fun promotionAfterEmptyDurableSnapshotTriggersCanonicalFifoDrainExactlyOnce() {
        val fallback = "1/renamed-a"
        val durable = "tmux:1:\$0:123"
        val store = InMemoryOutboundQueueStore()
        val first = enqueueGenerationRow(store, fallback, "first", 1L)
        val second = enqueueGenerationRow(store, fallback, "second", 2L)
        val controller = OutboundQueueAutoFlushController.boundTo(outboundBudgetTestComposer())
        val delivered = mutableListOf<String>()
        val retryNext = canonicalStoreDrain(store, durable, delivered)

        assertNull(controller.onQueueSnapshotChanged(sessionLive = true, retryNext = retryNext))
        val promoted = store.promoteSessionIdentity(fallback, durable, setOf("%0"), "\$0", 123L)

        requestOutboundDrainAfterPromotion(promoted, true, controller, { true }, retryNext)
        controller.onQueueSnapshotChanged(sessionLive = true, retryNext = retryNext)
        assertNull(controller.onQueueSnapshotChanged(sessionLive = true, retryNext = retryNext))

        assertEquals(listOf(first.id, second.id), delivered)
        assertEquals(2, delivered.toSet().size)
        assertEquals(emptyList<com.pocketshell.app.composer.OutboundItem>(), store.itemsFor(durable))
    }

    @Test
    fun promotionWithClosedWireWaitsForLaterCanonicalAvailabilityTrigger() {
        val fallback = "1/renamed-a"
        val durable = "tmux:1:\$0:123"
        val store = InMemoryOutboundQueueStore()
        val row = enqueueGenerationRow(store, fallback, "first", 1L)
        val controller = OutboundQueueAutoFlushController.boundTo(outboundBudgetTestComposer())
        val delivered = mutableListOf<String>()
        val retryNext = canonicalStoreDrain(store, durable, delivered)
        val promoted = store.promoteSessionIdentity(fallback, durable, setOf("%0"), "\$0", 123L)

        assertNull(requestOutboundDrainAfterPromotion(promoted, false, controller, { true }, retryNext))
        assertEquals(emptyList<String>(), delivered)
        controller.onQueueSnapshotChanged(sessionLive = true, retryNext = retryNext)

        assertEquals(listOf(row.id), delivered)
    }

    private fun canonicalStoreDrain(
        store: InMemoryOutboundQueueStore,
        durable: String,
        delivered: MutableList<String>,
    ): (Set<String>) -> String? = { excluded ->
        val next = store.itemsFor(durable).firstOrNull { it.id !in excluded }
        val claimed = next?.let { store.claim(it.id) }
        claimed?.id?.also { id ->
            delivered += id
            store.remove(id)
        }
    }

    private fun enqueueGenerationRow(
        store: InMemoryOutboundQueueStore,
        sessionKey: String,
        text: String,
        createdAtMs: Long,
    ) = store.enqueue(
        sessionKey = sessionKey,
        cleanText = text,
        createdAtMs = createdAtMs,
        paneId = "%0",
        tmuxSessionId = "\$0",
        tmuxSessionCreated = 123L,
    )
}
