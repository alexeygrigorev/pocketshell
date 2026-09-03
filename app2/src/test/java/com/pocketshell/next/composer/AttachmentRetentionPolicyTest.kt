package com.pocketshell.next.composer

import com.pocketshell.core.transport.SftpEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attachment-directory sweep (rewrite task P-1).
 *
 * The load-bearing assertions are the ones about what it must NOT delete: this
 * runs immediately after an upload, against a directory whose newest files are
 * the ones the message about to be sent references.
 */
class AttachmentRetentionPolicyTest {

    @Test
    fun `nothing younger than the protection window is ever pruned`() {
        val policy = AttachmentRetentionPolicy(
            ttlMillis = 1,
            keepNewest = 1,
            protectNewestMillis = 60_000,
        )
        // Two files, both minutes-new: over the TTL and over the keep count,
        // and both must survive because they are inside the protected window.
        val entries = listOf(file("just-uploaded", NOW - 1_000), file("also-new", NOW - 2_000))

        assertTrue(policy.plan(entries, NOW).isEmpty())
    }

    @Test
    fun `files past the ttl go`() {
        val policy = AttachmentRetentionPolicy(
            ttlMillis = 10_000,
            keepNewest = 100,
            protectNewestMillis = 1_000,
        )
        val entries = listOf(file("fresh", NOW - 2_000), file("stale", NOW - 60_000))

        assertEquals(listOf("stale"), policy.plan(entries, NOW).map { it.name })
    }

    @Test
    fun `beyond the keep count the oldest go first`() {
        val policy = AttachmentRetentionPolicy(
            ttlMillis = Long.MAX_VALUE,
            keepNewest = 2,
            protectNewestMillis = 0,
        )
        val entries = listOf(
            file("oldest", NOW - 4_000),
            file("newest", NOW - 1_000),
            file("middle", NOW - 2_000),
            file("older", NOW - 3_000),
        )

        assertEquals(listOf("older", "oldest"), policy.plan(entries, NOW).map { it.name })
    }

    @Test
    fun `directories are never pruned`() {
        val policy = AttachmentRetentionPolicy(ttlMillis = 1, keepNewest = 1, protectNewestMillis = 0)
        val entries = listOf(
            SftpEntry("/a/subdir", isDirectory = true, sizeBytes = 0, modifiedEpochMs = 1L),
            file("x", 1L),
            file("y", 2L),
        )

        assertTrue(policy.plan(entries, NOW).none { it.isDirectory })
    }

    /**
     * A server that reports no mtime cannot be shown to have an expired file,
     * so the sweep leaves it alone rather than guessing.
     */
    @Test
    fun `a file with no reported mtime is left alone`() {
        val policy = AttachmentRetentionPolicy(ttlMillis = 1, keepNewest = 1, protectNewestMillis = 0)

        assertTrue(policy.plan(listOf(file("unknown", 0L)), NOW).isEmpty())
    }

    @Test
    fun `nonsense settings are rejected at construction`() {
        listOf(
            { AttachmentRetentionPolicy(ttlMillis = 0) },
            { AttachmentRetentionPolicy(keepNewest = 0) },
            { AttachmentRetentionPolicy(protectNewestMillis = -1) },
        ).forEach { build ->
            val failure = runCatching { build() }.exceptionOrNull()
            assertTrue("expected a rejection, got $failure", failure is IllegalArgumentException)
        }
    }

    private fun file(name: String, modifiedEpochMs: Long) = SftpEntry(
        path = "/a/$name",
        isDirectory = false,
        sizeBytes = 10,
        modifiedEpochMs = modifiedEpochMs,
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
