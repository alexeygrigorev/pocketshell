package com.pocketshell.app.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AttachmentQueueRetentionProjectionTest {

    @Test
    fun issue1548_allUndeliveredStatesRetainExactDirectoryButDeliveredDoesNot() {
        val store = InMemoryOutboundQueueStore()
        OutboundState.entries.forEachIndexed { index, state ->
            store.enqueueExisting(
                OutboundItem(
                    id = "row-$state",
                    sessionKey = DURABLE_SESSION_KEY,
                    cleanText = state.name,
                    attachments = listOf(
                        DurableAttachmentRef(
                            remotePath = pathFor(state),
                            displayName = "${state.name.lowercase()}.png",
                            mimeType = "image/png",
                        ),
                    ),
                    state = state,
                    createdAtMs = index.toLong(),
                ),
            )
        }

        assertEquals(
            setOf("queued.png", "uploading.png", "inflight.png", "failed.png"),
            store.retainedRemoteAttachmentNames(DURABLE_SESSION_KEY, CURRENT_DIR),
        )
    }

    @Test
    fun issue1548_sameBasenameInAnotherDirectoryDoesNotPinCurrentDirectory() {
        val store = InMemoryOutboundQueueStore()
        store.enqueue(
            sessionKey = DURABLE_SESSION_KEY,
            cleanText = "send other scope",
            attachments = listOf(
                DurableAttachmentRef(
                    remotePath = "~/$OTHER_DIR/shared.png",
                    displayName = "shared.png",
                    mimeType = "image/png",
                ),
            ),
            createdAtMs = 1L,
        )

        assertFalse(
            "a reference in $OTHER_DIR must not pin $CURRENT_DIR/shared.png",
            "shared.png" in store.retainedRemoteAttachmentNames(DURABLE_SESSION_KEY, CURRENT_DIR),
        )
    }

    @Test
    fun issue1548_absoluteAndTraversalPathsCannotPinCurrentDirectory() {
        val store = InMemoryOutboundQueueStore()
        listOf(
            "/home/alex/$CURRENT_DIR/absolute.png",
            "../$CURRENT_DIR/escaping.png",
            "${CURRENT_DIR.substringBeforeLast('/')}/other/../" +
                "${CURRENT_DIR.substringAfterLast('/')}/interior.png",
        ).forEachIndexed { index, remotePath ->
            store.enqueue(
                sessionKey = DURABLE_SESSION_KEY,
                cleanText = "unsafe path $index",
                attachments = listOf(
                    DurableAttachmentRef(
                        remotePath = remotePath,
                        displayName = remotePath.substringAfterLast('/'),
                        mimeType = "image/png",
                    ),
                ),
                createdAtMs = index.toLong(),
            )
        }

        assertEquals(
            "production-generated attachment paths never need parent traversal",
            emptySet<String>(),
            store.retainedRemoteAttachmentNames(DURABLE_SESSION_KEY, CURRENT_DIR),
        )
    }

    private fun pathFor(state: OutboundState): String = when (state) {
        OutboundState.Queued -> "~/$CURRENT_DIR/queued.png"
        OutboundState.Uploading -> "$CURRENT_DIR/uploading.png"
        OutboundState.InFlight -> "./$CURRENT_DIR/inflight.png"
        OutboundState.Failed -> "~/$CURRENT_DIR/./failed.png"
        OutboundState.Delivered -> "~/$CURRENT_DIR/delivered.png"
    }

    private companion object {
        const val DURABLE_SESSION_KEY = "tmux:41:\$9:1700000000"
        const val CURRENT_DIR = ".pocketshell/attachments/host-41-current"
        const val OTHER_DIR = ".pocketshell/attachments/host-41-other"
    }
}
