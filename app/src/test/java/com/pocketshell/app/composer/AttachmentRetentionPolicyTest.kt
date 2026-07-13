package com.pocketshell.app.composer

import com.pocketshell.core.ssh.RemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentRetentionPolicyTest {

    @Test
    fun defaultPolicyDeletesExpiredFilesButKeepsRecentFiles() {
        val now = 10_000_000_000L
        val plan = AttachmentRetentionPolicy().plan(
            entries = listOf(
                file("fresh.txt", now - hours(2)),
                file("expired.txt", now - days(8)),
            ),
            nowMillis = now,
        )

        assertEquals(listOf("expired.txt"), plan.delete.map { it.name })
    }

    @Test
    fun newestSafetyWindowWinsOverCap() {
        val now = 10_000_000_000L
        val policy = AttachmentRetentionPolicy(
            ttlMillis = days(7),
            keepNewest = 2,
            protectNewestMillis = days(1),
        )

        val plan = policy.plan(
            entries = listOf(
                file("a.txt", now - hours(1)),
                file("b.txt", now - hours(2)),
                file("c.txt", now - hours(3)),
                file("d.txt", now - hours(4)),
            ),
            nowMillis = now,
        )

        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun capDeletesOnlyFilesOutsideNewestWindow() {
        val now = 10_000_000_000L
        val policy = AttachmentRetentionPolicy(
            ttlMillis = days(7),
            keepNewest = 2,
            protectNewestMillis = days(1),
        )

        val plan = policy.plan(
            entries = listOf(
                file("newest.txt", now - days(2)),
                file("second.txt", now - days(3)),
                file("third.txt", now - days(4)),
                file("fourth.txt", now - days(5)),
            ),
            nowMillis = now,
        )

        assertEquals(listOf("third.txt", "fourth.txt"), plan.delete.map { it.name })
    }

    @Test
    fun entriesWithoutMtimeAndNonFilesAreNeverDeleted() {
        val now = 10_000_000_000L
        val plan = AttachmentRetentionPolicy().plan(
            entries = listOf(
                RemoteEntry(
                    name = "unknown-mtime.txt",
                    type = RemoteEntry.Type.FILE,
                    sizeBytes = 10L,
                    modifiedEpochSec = null,
                ),
                RemoteEntry(
                    name = "directory",
                    type = RemoteEntry.Type.DIRECTORY,
                    sizeBytes = 0L,
                    modifiedEpochSec = (now - days(30)) / 1_000L,
                ),
            ),
            nowMillis = now,
        )

        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun retentionDoesNotEvictAFreshMidSendAttachmentUnderNewestCapPressure() {
        // Issue #1531 (audit RC4, G2/AC2 class coverage): "retention-eviction-
        // mid-send". A just-uploaded attachment whose send is in flight (queued /
        // deferred across a flap) is FRESH — its mtime is well within
        // protectNewestMillis. Even when a burst of even-fresher files pushes it
        // OUTSIDE the newest-cap, retention must NOT evict it mid-send:
        // protectNewestMillis wins over the cap (shouldDelete short-circuits on
        // age). This is DISCRIMINATING, not vacuous — removing the protectNewest
        // guard would delete `mid-send.png` (it is 3rd-newest with keepNewest=2),
        // so a regression that drops the protection fails this test.
        val now = 10_000_000_000L
        val policy = AttachmentRetentionPolicy(
            ttlMillis = days(7),
            keepNewest = 2,
            protectNewestMillis = days(1),
        )

        val plan = policy.plan(
            entries = listOf(
                file("newer-1.png", now - minutes(1)),
                file("newer-2.png", now - minutes(2)),
                // The in-flight attachment: fresh (30 min old, < protectNewest)
                // but 3rd-newest, so OUTSIDE keepNewest=2.
                file("mid-send.png", now - minutes(30)),
            ),
            nowMillis = now,
        )

        assertFalse(
            "a fresh mid-send attachment must NOT be evicted by retention even " +
                "under newest-cap pressure (protectNewestMillis wins); plan=${plan.delete.map { it.name }}",
            plan.delete.any { it.name == "mid-send.png" },
        )
    }

    @Test
    fun deleteCommandsAreChunkedAndShellQuoted() {
        val commands = RemoteAttachmentPruner.buildDeleteCommands(
            remoteDir = ".pocketshell/attachments/host-1",
            names = listOf("plain.txt", "quote's.txt", "nested/bad.txt", "."),
            dryRun = false,
            batchSize = 1,
        )

        assertEquals(
            listOf(
                "rm -f -- ~/'.pocketshell/attachments/host-1/plain.txt' && printf 'deleted\\t1\\n'",
                "rm -f -- ~/'.pocketshell/attachments/host-1/quote'\\''s.txt' && printf 'deleted\\t1\\n'",
            ),
            commands,
        )
    }

    @Test
    fun dryRunCommandsDoNotRemoveFiles() {
        val commands = RemoteAttachmentPruner.buildDeleteCommands(
            remoteDir = ".pocketshell/attachments/host-1",
            names = listOf("old.txt"),
            dryRun = true,
            batchSize = 50,
        )

        assertEquals(
            listOf("printf 'would-delete\\t%s\\n' '~/.pocketshell/attachments/host-1/old.txt'"),
            commands,
        )
        assertFalse(commands.single().contains("rm -f"))
    }

    @Test
    fun dryRunCommandsShellQuoteFileNames() {
        val commands = RemoteAttachmentPruner.buildDeleteCommands(
            remoteDir = ".pocketshell/attachments/host-1",
            names = listOf("quote's \$file.txt"),
            dryRun = true,
            batchSize = 50,
        )

        assertEquals(
            listOf("printf 'would-delete\\t%s\\n' '~/.pocketshell/attachments/host-1/quote'\\''s \$file.txt'"),
            commands,
        )
    }

    private fun file(name: String, modifiedMillis: Long): RemoteEntry =
        RemoteEntry(
            name = name,
            type = RemoteEntry.Type.FILE,
            sizeBytes = 1L,
            modifiedEpochSec = modifiedMillis / 1_000L,
        )

    private fun minutes(value: Long): Long = value * 60L * 1_000L

    private fun hours(value: Long): Long = value * 60L * 60L * 1_000L

    private fun days(value: Long): Long = value * 24L * 60L * 60L * 1_000L
}
