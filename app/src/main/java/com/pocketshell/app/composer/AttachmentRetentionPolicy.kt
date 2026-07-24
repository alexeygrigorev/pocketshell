package com.pocketshell.app.composer

import android.util.Log
import com.pocketshell.core.ssh.RemoteEntry
import com.pocketshell.core.ssh.SshFileNotFoundException
import com.pocketshell.core.ssh.SshSession

internal data class AttachmentRetentionPolicy(
    val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    val keepNewest: Int = DEFAULT_KEEP_NEWEST,
    val protectNewestMillis: Long = DEFAULT_PROTECT_NEWEST_MILLIS,
    val maxScanEntries: Int = DEFAULT_MAX_SCAN_ENTRIES,
    val deleteBatchSize: Int = DEFAULT_DELETE_BATCH_SIZE,
    val dryRun: Boolean = false,
) {
    init {
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
        require(keepNewest > 0) { "keepNewest must be positive" }
        require(protectNewestMillis >= 0L) { "protectNewestMillis must be non-negative" }
        require(maxScanEntries > 0) { "maxScanEntries must be positive" }
        require(deleteBatchSize > 0) { "deleteBatchSize must be positive" }
    }

    fun plan(
        entries: List<RemoteEntry>,
        nowMillis: Long,
        retainedNames: Set<String> = emptySet(),
    ): AttachmentPrunePlan {
        val files = entries
            .asSequence()
            .filter { it.type == RemoteEntry.Type.FILE }
            .mapNotNull { entry ->
                val modifiedEpochSec = entry.modifiedEpochSec ?: return@mapNotNull null
                RemoteAttachment(
                    name = entry.name,
                    modifiedMillis = modifiedEpochSec * 1_000L,
                )
            }
            .sortedWith(
                compareByDescending<RemoteAttachment> { it.modifiedMillis }
                    .thenBy { it.name },
            )
            .toList()

        val delete = files.filterIndexed { index, attachment ->
            shouldDelete(
                attachment = attachment,
                newestIndex = index,
                nowMillis = nowMillis,
                retainedNames = retainedNames,
            )
        }
        return AttachmentPrunePlan(delete = delete)
    }

    private fun shouldDelete(
        attachment: RemoteAttachment,
        newestIndex: Int,
        nowMillis: Long,
        retainedNames: Set<String>,
    ): Boolean {
        if (attachment.name in retainedNames) return false
        val ageMillis = nowMillis - attachment.modifiedMillis
        if (ageMillis < protectNewestMillis) return false

        val expiredByTtl = ageMillis >= ttlMillis
        val outsideNewestCap = newestIndex >= keepNewest
        return expiredByTtl || outsideNewestCap
    }

    companion object {
        const val DEFAULT_KEEP_NEWEST: Int = 20
        const val DEFAULT_MAX_SCAN_ENTRIES: Int = 5_000
        const val DEFAULT_DELETE_BATCH_SIZE: Int = 50
        const val DEFAULT_TTL_MILLIS: Long = 7L * 24L * 60L * 60L * 1_000L
        const val DEFAULT_PROTECT_NEWEST_MILLIS: Long = 24L * 60L * 60L * 1_000L
    }
}

internal data class RemoteAttachment(
    val name: String,
    val modifiedMillis: Long,
)

internal data class AttachmentPrunePlan(
    val delete: List<RemoteAttachment>,
)

internal class RemoteAttachmentPruner(
    private val policy: AttachmentRetentionPolicy = AttachmentRetentionPolicy(),
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun prune(
        session: SshSession,
        remoteDir: String,
        retainedNames: Set<String> = emptySet(),
    ) {
        val listing = try {
            session.listDirectory(
                remotePath = "~/$remoteDir",
                maxEntries = policy.maxScanEntries,
            )
        } catch (notFound: SshFileNotFoundException) {
            return
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "attachment prune listing failed dir=$remoteDir", t)
            return
        }

        val plan = policy.plan(listing.entries, now(), retainedNames)
        if (plan.delete.isEmpty()) {
            Log.i(
                LOG_TAG,
                "attachment prune skipped dir=$remoteDir files=${listing.entries.size} truncated=${listing.truncated}",
            )
            return
        }

        val commands = buildDeleteCommands(
            remoteDir = remoteDir,
            names = plan.delete.map { it.name },
            dryRun = policy.dryRun,
            batchSize = policy.deleteBatchSize,
        )
        var deleted = 0
        for (command in commands) {
            val result = runCatching { session.exec(command) }
                .onFailure { Log.w(LOG_TAG, "attachment prune delete failed dir=$remoteDir", it) }
                .getOrNull()
                ?: continue
            if (result.exitCode != 0) {
                Log.w(
                    LOG_TAG,
                    "attachment prune delete failed dir=$remoteDir exit=${result.exitCode}: " +
                        result.stderr.ifBlank { result.stdout }.trim(),
                )
                continue
            }
            deleted += parseDeletedCount(result.stdout)
        }

        Log.i(
            LOG_TAG,
            "attachment prune completed dir=$remoteDir candidates=${plan.delete.size} " +
                "deleted=$deleted dryRun=${policy.dryRun} truncated=${listing.truncated}",
        )
    }

    companion object {
        private const val LOG_TAG = "AttachmentPrune"

        fun buildDeleteCommands(
            remoteDir: String,
            names: List<String>,
            dryRun: Boolean,
            batchSize: Int,
        ): List<String> {
            require(batchSize > 0) { "batchSize must be positive" }
            val cleanNames = names.filter { it.isNotBlank() && '/' !in it && it != "." && it != ".." }
            if (cleanNames.isEmpty()) return emptyList()
            return cleanNames.chunked(batchSize).map { batch ->
                val paths = batch.map { "~/$remoteDir/$it" }
                if (dryRun) {
                    "printf 'would-delete\\t%s\\n' ${paths.joinToString(" ") { shellQuoteLiteral(it) }}"
                } else {
                    val deleteArgs = paths.joinToString(" ") { shellQuoteRemotePath(it) }
                    "rm -f -- $deleteArgs && printf 'deleted\\t${batch.size}\\n'"
                }
            }
        }

        private fun parseDeletedCount(stdout: String): Int =
            stdout.lineSequence()
                .mapNotNull { line ->
                    line.removePrefix("deleted\t")
                        .takeIf { it != line }
                        ?.trim()
                        ?.toIntOrNull()
                }
                .sum()

        private fun shellQuoteLiteral(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"

        private fun shellQuoteRemotePath(path: String): String =
            when {
                path == "~" -> "~"
                path.startsWith("~/") -> {
                    val rest = path.removePrefix("~/")
                    if (rest.isEmpty()) "~/" else "~/" + shellQuoteLiteral(rest)
                }
                else -> shellQuoteLiteral(path)
            }
    }
}

internal fun OutboundQueueStore?.retainedRemoteAttachmentNames(
    sessionKey: String?,
    remoteDir: String,
): Set<String> {
    if (this == null || sessionKey.isNullOrBlank()) return emptySet()
    val normalizedDir = normalizeHomeRelativeRemotePath(remoteDir) ?: return emptySet()
    return itemsFor(sessionKey)
        .asSequence()
        .filter { it.state != OutboundState.Delivered }
        .flatMap { it.attachments.asSequence() }
        .mapNotNull { ref ->
            val path = normalizeHomeRelativeRemotePath(ref.remotePath) ?: return@mapNotNull null
            val separator = path.lastIndexOf('/')
            if (separator <= 0 || path.substring(0, separator) != normalizedDir) {
                return@mapNotNull null
            }
            path.substring(separator + 1).takeIf { it.isNotBlank() }
        }
        .toSet()
}

private fun normalizeHomeRelativeRemotePath(path: String): String? {
    val trimmed = path.trim()
    val relative = when {
        trimmed == "~" -> ""
        trimmed.startsWith("~/") -> trimmed.removePrefix("~/")
        trimmed.startsWith("/") || trimmed.startsWith("~") -> return null
        else -> trimmed
    }
    val segments = relative.split('/')
    // Staged attachment paths never require parent traversal. Reject alternate
    // spellings rather than allowing them to pin a different remote object.
    if (".." in segments) return null
    return segments
        .filter { it.isNotEmpty() && it != "." }
        .joinToString("/")
}
