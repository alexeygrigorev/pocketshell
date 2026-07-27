package com.pocketshell.core.ssh

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One immutable durable outbound-queue attachment.
 *
 * [stableToken] is opaque and stable across delivery attempts (the app uses the
 * durable sidecar id). It is never interpolated into a shell command: core-ssh
 * hashes it into [queueSidecarCheckpointPaths]' fixed-hex sibling name.
 */
public data class QueueSidecarResumableUploadRequest(
    val localFile: File,
    val remotePath: String,
    val stableToken: String,
    val expectedBytes: Long,
    val displayName: String = localFile.name,
)

public enum class QueueSidecarUploadDisposition {
    Uploaded,
    AlreadyComplete,
}

public data class QueueSidecarResumableUploadResult(
    val remotePath: String,
    val resumedFromBytes: Long,
    val transmittedBytes: Long,
    val disposition: QueueSidecarUploadDisposition,
)

public data class QueueSidecarUploadProgress(
    val checkpointPath: String,
    val resumedFromBytes: Long,
    val bytesTransferred: Long,
    val totalBytes: Long,
)

public data class QueueSidecarCheckpointPaths(
    val dataPath: String,
    val identityPath: String,
)

/**
 * Deterministic hidden sibling owned by [stableToken]. Only fixed hex derived
 * locally from the opaque token reaches the path.
 */
public fun queueSidecarCheckpointPaths(
    remotePath: String,
    stableToken: String,
): QueueSidecarCheckpointPaths {
    require(remotePath.isNotBlank()) { "remotePath must not be blank" }
    require(stableToken.isNotBlank()) { "stableToken must not be blank" }
    val slash = remotePath.lastIndexOf('/')
    val directory = if (slash >= 0) remotePath.substring(0, slash + 1) else ""
    val baseName = remotePath.substring(slash + 1)
    require(baseName.isNotBlank()) { "remotePath must name a file" }
    val tokenHash = sha256Hex(stableToken).take(CHECKPOINT_TOKEN_HEX_LENGTH)
    val data = "$directory.$baseName.pocketshell-part-$tokenHash"
    return QueueSidecarCheckpointPaths(dataPath = data, identityPath = "$data.identity")
}

internal fun queueSidecarCheckpointIdentity(
    request: QueueSidecarResumableUploadRequest,
): String = sha256Hex(
    buildString {
        append("pocketshell-queue-sidecar-v1\u0000")
        append(request.stableToken)
        append('\u0000')
        append(request.remotePath)
        append('\u0000')
        append(request.expectedBytes)
    },
)

internal data class ResumableUploadProbe(
    val finalSize: Long?,
    val checkpointSize: Long?,
    val checkpointIdentity: String?,
)

internal sealed interface ResumableUploadOffsetDecision {
    val cleanCheckpointFirst: Boolean

    data class Upload(
        val offsetBytes: Long,
        override val cleanCheckpointFirst: Boolean,
    ) : ResumableUploadOffsetDecision

    data class Promote(
        val resumedFromBytes: Long,
        override val cleanCheckpointFirst: Boolean = false,
    ) : ResumableUploadOffsetDecision

    data class AlreadyComplete(
        val resumedFromBytes: Long,
        override val cleanCheckpointFirst: Boolean = true,
    ) : ResumableUploadOffsetDecision

    data class Fail(
        val reason: String,
        override val cleanCheckpointFirst: Boolean,
    ) : ResumableUploadOffsetDecision
}

/**
 * Pure remote-offset policy. The deterministic checkpoint is resumed only when
 * its identity and size both belong to this immutable request.
 */
internal fun decideResumableUploadOffset(
    expectedBytes: Long,
    expectedIdentity: String,
    probe: ResumableUploadProbe,
): ResumableUploadOffsetDecision {
    if (probe.finalSize != null) {
        return if (probe.finalSize == expectedBytes) {
            ResumableUploadOffsetDecision.AlreadyComplete(expectedBytes)
        } else {
            ResumableUploadOffsetDecision.Fail(
                reason = "final path has ${probe.finalSize} bytes; expected $expectedBytes",
                cleanCheckpointFirst = true,
            )
        }
    }

    val checkpointSize = probe.checkpointSize
    if (checkpointSize == null) {
        return ResumableUploadOffsetDecision.Upload(
            offsetBytes = 0L,
            cleanCheckpointFirst = probe.checkpointIdentity != null,
        )
    }
    if (checkpointSize < 0L || checkpointSize > expectedBytes) {
        return ResumableUploadOffsetDecision.Upload(0L, cleanCheckpointFirst = true)
    }
    if (checkpointSize == 0L && probe.checkpointIdentity == null) {
        return ResumableUploadOffsetDecision.Upload(0L, cleanCheckpointFirst = false)
    }
    if (probe.checkpointIdentity != expectedIdentity) {
        return ResumableUploadOffsetDecision.Upload(0L, cleanCheckpointFirst = true)
    }
    return if (checkpointSize == expectedBytes) {
        ResumableUploadOffsetDecision.Promote(expectedBytes)
    } else {
        ResumableUploadOffsetDecision.Upload(checkpointSize, cleanCheckpointFirst = false)
    }
}

internal enum class ResumableUploadFailureKind {
    RetryableTransport,
    Cancellation,
    NoProgressTimeout,
    PermanentLocalValidation,
    PermanentIdentity,
    PermanentRemoteValidation,
}

internal enum class ResumableUploadCheckpointAction {
    Preserve,
    Cleanup,
}

internal fun checkpointActionFor(
    failure: ResumableUploadFailureKind,
): ResumableUploadCheckpointAction = when (failure) {
    ResumableUploadFailureKind.RetryableTransport,
    ResumableUploadFailureKind.Cancellation,
    ResumableUploadFailureKind.NoProgressTimeout,
    -> ResumableUploadCheckpointAction.Preserve

    ResumableUploadFailureKind.PermanentLocalValidation,
    ResumableUploadFailureKind.PermanentIdentity,
    ResumableUploadFailureKind.PermanentRemoteValidation,
    -> ResumableUploadCheckpointAction.Cleanup
}

/**
 * Queue-only remote checkpoint protocol, kept outside [RealSshSession] so the
 * transport owner remains focused on channels/dispatch. Every remote mutation
 * still runs through that session's injected [exec], and the byte stream through
 * its injected single-writer-owned upload channel.
 */
internal class QueueSidecarResumableUploader(
    private val isConnected: () -> Boolean,
    private val ensureConnected: () -> Unit,
    private val exec: suspend (String) -> ExecResult,
    private val shellQuote: (String) -> String,
    private val streamRemainder: suspend (
        paths: QueueSidecarCheckpointPaths,
        offsetBytes: Long,
    ) -> Long,
) {
    suspend fun upload(
        request: QueueSidecarResumableUploadRequest,
    ): QueueSidecarResumableUploadResult {
        val paths = runCatching {
            require(request.expectedBytes >= 0L) { "expectedBytes must be non-negative" }
            queueSidecarCheckpointPaths(request.remotePath, request.stableToken)
        }.getOrElse { error ->
            throw SshException("Invalid queue-sidecar upload identity: ${error.message}", error)
        }

        val local = request.localFile
        if (!local.isFile) {
            applyCheckpointAction(
                paths,
                ResumableUploadFailureKind.PermanentLocalValidation,
                onlyWhenConnected = true,
            )
            throw SshException("Queued attachment no longer exists: ${request.displayName}")
        }
        val actualLocalBytes = local.length()
        if (actualLocalBytes != request.expectedBytes) {
            applyCheckpointAction(
                paths,
                ResumableUploadFailureKind.PermanentLocalValidation,
                onlyWhenConnected = true,
            )
            throw SshException(
                "Queued attachment changed: ${request.displayName} has $actualLocalBytes bytes; " +
                    "expected ${request.expectedBytes}",
            )
        }

        ensureConnected()
        val expectedIdentity = queueSidecarCheckpointIdentity(request)
        val decision = decideResumableUploadOffset(
            expectedBytes = request.expectedBytes,
            expectedIdentity = expectedIdentity,
            probe = probe(request.remotePath, paths),
        )
        if (decision.cleanCheckpointFirst) cleanupCheckpoint(paths)

        return when (decision) {
            is ResumableUploadOffsetDecision.AlreadyComplete ->
                QueueSidecarResumableUploadResult(
                    remotePath = request.remotePath,
                    resumedFromBytes = decision.resumedFromBytes,
                    transmittedBytes = 0L,
                    disposition = QueueSidecarUploadDisposition.AlreadyComplete,
                )

            is ResumableUploadOffsetDecision.Fail ->
                throw SshException(
                    "Queue-sidecar upload refused for ${request.displayName}: ${decision.reason}",
                )

            is ResumableUploadOffsetDecision.Promote ->
                promote(request, paths, decision.resumedFromBytes, transmittedBytes = 0L)

            is ResumableUploadOffsetDecision.Upload -> {
                initialise(
                    paths = paths,
                    identity = expectedIdentity,
                    truncateData = decision.offsetBytes == 0L,
                )
                val transmitted = try {
                    streamRemainder(paths, decision.offsetBytes)
                } catch (cancelled: CancellationException) {
                    applyCheckpointAction(paths, ResumableUploadFailureKind.Cancellation)
                    throw cancelled
                } catch (error: Throwable) {
                    // Both a transport loss and the uploader's bounded
                    // no-progress timeout preserve the committed checkpoint.
                    applyCheckpointAction(paths, ResumableUploadFailureKind.RetryableTransport)
                    if (error is SshException) throw error
                    throw SshException(
                        "Queue-sidecar upload of ${request.displayName} failed: ${error.message}",
                        error,
                    )
                }
                promote(request, paths, decision.offsetBytes, transmitted)
            }
        }
    }

    private suspend fun probe(
        remotePath: String,
        paths: QueueSidecarCheckpointPaths,
    ): ResumableUploadProbe {
        suspend fun sizeOrNull(path: String): Long? {
            val result = exec(
                "if [ -f ${shellQuote(path)} ]; then " +
                    "wc -c < ${shellQuote(path)}; else printf MISSING; fi",
            )
            if (result.exitCode != 0) {
                throw SshException(
                    "Could not inspect queue-sidecar upload checkpoint: ${result.stderr.trim()}",
                )
            }
            val raw = result.stdout.trim()
            return if (raw == "MISSING") {
                null
            } else {
                raw.toLongOrNull()
                    ?: throw SshException("Invalid queue-sidecar checkpoint size '$raw'")
            }
        }

        val identityResult = exec(
            "if [ -f ${shellQuote(paths.identityPath)} ]; then " +
                "cat ${shellQuote(paths.identityPath)}; else printf MISSING; fi",
        )
        if (identityResult.exitCode != 0) {
            throw SshException(
                "Could not inspect queue-sidecar checkpoint identity: ${identityResult.stderr.trim()}",
            )
        }
        return ResumableUploadProbe(
            finalSize = sizeOrNull(remotePath),
            checkpointSize = sizeOrNull(paths.dataPath),
            checkpointIdentity = identityResult.stdout.trim().takeUnless { it == "MISSING" },
        )
    }

    private suspend fun initialise(
        paths: QueueSidecarCheckpointPaths,
        identity: String,
        truncateData: Boolean,
    ) {
        val dataOperation = if (truncateData) {
            ": > ${shellQuote(paths.dataPath)}"
        } else {
            "test -f ${shellQuote(paths.dataPath)}"
        }
        val result = exec(
            "$dataOperation && printf '%s' ${shellQuote(identity)} > " +
                shellQuote(paths.identityPath),
        )
        if (result.exitCode != 0) {
            applyCheckpointAction(paths, ResumableUploadFailureKind.PermanentIdentity)
            throw SshException(
                "Could not initialise queue-sidecar checkpoint: " +
                    result.stderr.ifBlank { result.stdout }.trim(),
            )
        }
    }

    private suspend fun promote(
        request: QueueSidecarResumableUploadRequest,
        paths: QueueSidecarCheckpointPaths,
        resumedFromBytes: Long,
        transmittedBytes: Long,
    ): QueueSidecarResumableUploadResult {
        val size = exec(
            "if [ -f ${shellQuote(paths.dataPath)} ]; then " +
                "wc -c < ${shellQuote(paths.dataPath)}; else printf MISSING; fi",
        )
        val actual = size.stdout.trim().toLongOrNull()
        if (size.exitCode != 0 || actual != request.expectedBytes) {
            applyCheckpointAction(paths, ResumableUploadFailureKind.PermanentRemoteValidation)
            throw SshException(
                "Queue-sidecar integrity check failed for ${request.displayName}: " +
                    "remote checkpoint has ${actual ?: "no"} bytes; expected ${request.expectedBytes}",
            )
        }
        val promote = exec(
            "mv -f ${shellQuote(paths.dataPath)} ${shellQuote(request.remotePath)}",
        )
        if (promote.exitCode != 0) {
            applyCheckpointAction(paths, ResumableUploadFailureKind.PermanentRemoteValidation)
            throw SshException(
                "Could not finalise queue-sidecar upload of ${request.displayName}: " +
                    promote.stderr.ifBlank { promote.stdout }.trim(),
            )
        }
        cleanupIdentity(paths.identityPath)
        return QueueSidecarResumableUploadResult(
            remotePath = request.remotePath,
            resumedFromBytes = resumedFromBytes,
            transmittedBytes = transmittedBytes,
            disposition = QueueSidecarUploadDisposition.Uploaded,
        )
    }

    /**
     * The pure classification is intentionally production-wired here: changing
     * preserve to cleanup spends already committed bytes again, while changing
     * cleanup to preserve leaves an owned corrupt/permanently invalid sidecar.
     */
    private suspend fun applyCheckpointAction(
        paths: QueueSidecarCheckpointPaths,
        failure: ResumableUploadFailureKind,
        onlyWhenConnected: Boolean = false,
    ) {
        if (checkpointActionFor(failure) == ResumableUploadCheckpointAction.Cleanup &&
            (!onlyWhenConnected || isConnected())
        ) {
            cleanupCheckpoint(paths)
        }
    }

    private suspend fun cleanupCheckpoint(paths: QueueSidecarCheckpointPaths) {
        runCatching {
            withTimeoutOrNull(UPLOAD_CLEANUP_TIMEOUT_MS) {
                exec("rm -f ${shellQuote(paths.dataPath)} ${shellQuote(paths.identityPath)}")
            }
        }
    }

    private suspend fun cleanupIdentity(path: String) {
        runCatching {
            withTimeoutOrNull(UPLOAD_CLEANUP_TIMEOUT_MS) {
                exec("rm -f ${shellQuote(path)}")
            }
        }
    }
}

internal fun skipQueueSidecarLocalPrefix(
    input: InputStream,
    offsetBytes: Long,
    displayName: String,
) {
    var remaining = offsetBytes
    while (remaining > 0L) {
        val skipped = input.skip(remaining)
        if (skipped > 0L) {
            remaining -= skipped
        } else if (input.read() >= 0) {
            remaining -= 1L
        } else {
            throw SshException(
                "Queued attachment $displayName ended before resume offset $offsetBytes",
            )
        }
    }
}

internal fun SshUploadProgress.toQueueSidecarProgress(
    paths: QueueSidecarCheckpointPaths,
    resumedFromBytes: Long,
): QueueSidecarUploadProgress = QueueSidecarUploadProgress(
    checkpointPath = paths.dataPath,
    resumedFromBytes = resumedFromBytes,
    bytesTransferred = bytesTransferred,
    totalBytes = totalBytes,
)

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val CHECKPOINT_TOKEN_HEX_LENGTH = 24
