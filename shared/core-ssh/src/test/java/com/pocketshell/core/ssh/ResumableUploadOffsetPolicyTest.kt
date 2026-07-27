package com.pocketshell.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ResumableUploadOffsetPolicyTest {

    private val expected = 1_000L
    private val identity = "expected-identity"

    @Test
    fun absentAndZeroCheckpointStartAtZero() {
        assertEquals(
            ResumableUploadOffsetDecision.Upload(0L, cleanCheckpointFirst = false),
            decide(final = null, checkpoint = null, checkpointIdentity = null),
        )
        assertEquals(
            ResumableUploadOffsetDecision.Upload(0L, cleanCheckpointFirst = false),
            decide(final = null, checkpoint = 0L, checkpointIdentity = null),
        )
        assertEquals(
            ResumableUploadOffsetDecision.Upload(0L, cleanCheckpointFirst = false),
            decide(final = null, checkpoint = 0L, checkpointIdentity = identity),
        )
    }

    @Test
    fun validPartialResumesAtExactCommittedOffset() {
        assertEquals(
            ResumableUploadOffsetDecision.Upload(417L, cleanCheckpointFirst = false),
            decide(final = null, checkpoint = 417L, checkpointIdentity = identity),
        )
    }

    @Test
    fun completeCheckpointPromotesWithoutTransmission() {
        assertEquals(
            ResumableUploadOffsetDecision.Promote(expected),
            decide(final = null, checkpoint = expected, checkpointIdentity = identity),
        )
    }

    @Test
    fun completeFinalIsAlreadyCompleteAndCleansStaleCheckpoint() {
        val decision = decide(
            final = expected,
            checkpoint = 417L,
            checkpointIdentity = identity,
        )
        assertEquals(ResumableUploadOffsetDecision.AlreadyComplete(expected), decision)
        assertTrue(decision.cleanCheckpointFirst)
    }

    @Test
    fun oversizedOrWrongIdentityCheckpointTakesExplicitCleanRestart() {
        assertEquals(
            ResumableUploadOffsetDecision.Upload(0L, cleanCheckpointFirst = true),
            decide(final = null, checkpoint = expected + 1L, checkpointIdentity = identity),
        )
        assertEquals(
            ResumableUploadOffsetDecision.Upload(0L, cleanCheckpointFirst = true),
            decide(final = null, checkpoint = 417L, checkpointIdentity = "other"),
        )
    }

    @Test
    fun wrongSizedFinalFailsWithoutOverwritingIt() {
        val decision = decide(
            final = expected - 1L,
            checkpoint = 417L,
            checkpointIdentity = identity,
        )
        assertTrue(decision is ResumableUploadOffsetDecision.Fail)
        assertTrue(decision.cleanCheckpointFirst)
    }

    @Test
    fun retryableFailuresPreserveAndPermanentFailuresClean() {
        listOf(
            ResumableUploadFailureKind.RetryableTransport,
            ResumableUploadFailureKind.Cancellation,
            ResumableUploadFailureKind.NoProgressTimeout,
        ).forEach {
            assertEquals(ResumableUploadCheckpointAction.Preserve, checkpointActionFor(it))
        }
        listOf(
            ResumableUploadFailureKind.PermanentLocalValidation,
            ResumableUploadFailureKind.PermanentIdentity,
            ResumableUploadFailureKind.PermanentRemoteValidation,
        ).forEach {
            assertEquals(ResumableUploadCheckpointAction.Cleanup, checkpointActionFor(it))
        }
    }

    @Test
    fun opaqueTokenIsHashedAndNeverAppearsInCheckpointPath() {
        val hostile = "id'; touch /tmp/pwned; #"
        val paths = queueSidecarCheckpointPaths("dir/report.bin", hostile)
        assertFalse(paths.dataPath.contains(hostile))
        assertTrue(paths.dataPath.matches(Regex("dir/\\.report\\.bin\\.pocketshell-part-[0-9a-f]{24}")))
        assertEquals("${paths.dataPath}.identity", paths.identityPath)
    }

    @Test
    fun identityBindsTokenFinalPathAndExpectedLength() {
        fun request(token: String, path: String, size: Long) =
            QueueSidecarResumableUploadRequest(File("local"), path, token, size)

        val base = queueSidecarCheckpointIdentity(request("stable", "dir/a", expected))
        assertFalse(base == queueSidecarCheckpointIdentity(request("other", "dir/a", expected)))
        assertFalse(base == queueSidecarCheckpointIdentity(request("stable", "dir/b", expected)))
        assertFalse(base == queueSidecarCheckpointIdentity(request("stable", "dir/a", expected + 1L)))
    }

    private fun decide(
        final: Long?,
        checkpoint: Long?,
        checkpointIdentity: String?,
    ): ResumableUploadOffsetDecision = decideResumableUploadOffset(
        expectedBytes = expected,
        expectedIdentity = identity,
        probe = ResumableUploadProbe(final, checkpoint, checkpointIdentity),
    )
}
