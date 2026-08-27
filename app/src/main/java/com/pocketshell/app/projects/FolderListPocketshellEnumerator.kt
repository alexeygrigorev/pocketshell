package com.pocketshell.app.projects

import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.core.ssh.ExecResult
import kotlinx.coroutines.CancellationException

/**
 * Live session names from `pocketshell sessions list --json` (tmuxctl + aplexer),
 * with a human-table fallback for older hosts.
 */
internal object FolderListPocketshellEnumerator {
    suspend fun fetch(
        parser: HostTmuxSessionListParser,
        exec: suspend (String) -> ExecResult,
        jsonCommand: String,
        humanCommand: String,
    ): List<FolderSessionRow> {
        val json = try {
            exec(jsonCommand)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        if (json != null && json.exitCode == 0) {
            val parsed = parser.parsePocketshellSessionsJson(json.stdout)
            if (parsed != null) {
                return parsed.map { it.toFolderSessionRow() }
            }
            // Exit 0 with a blank body is an empty enumerator, not a prompt
            // to fall through to the human table (that second exec was
            // stealing the next queued fake response and breaking lease-reuse
            // command accounting).
            if (json.stdout.isBlank()) {
                return emptyList()
            }
        }
        val human = try {
            exec(humanCommand)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        if (human != null && human.exitCode == 0) {
            return SshFolderListGateway.parsePocketshellSessionsRows(human.stdout, parser)
        }
        return emptyList()
    }

    fun unionFolderSessionRows(
        authority: List<FolderSessionRow>,
        overlay: List<FolderSessionRow>,
    ): List<FolderSessionRow> {
        if (authority.isEmpty()) return overlay
        val overlayByName = overlay.associateBy { it.sessionName }
        val merged = authority.map { row ->
            val extra = overlayByName[row.sessionName] ?: return@map row
            extra.copy(
                sessionName = row.sessionName,
                sessionManager = row.sessionManager,
                aplexerId = row.aplexerId ?: extra.aplexerId,
                cwd = extra.cwd ?: row.cwd,
            )
        }
        val seen = authority.mapTo(HashSet()) { it.sessionName }
        return merged + overlay.filter { it.sessionName !in seen }
    }

    fun HostTmuxSessionRow.toFolderSessionRow(): FolderSessionRow =
        FolderSessionRow(
            sessionName = name,
            lastActivity = lastActivity,
            attached = attached,
            cwd = path,
            agentKind = agentKind,
            recordedKind = recordedKind,
            recordedKindId = recordedKindId,
            agentStateRaw = agentStateRaw,
            agentStateUpdatedAt = agentStateUpdatedAt,
            tmuxSessionId = tmuxSessionId,
            sessionCreated = createdAt,
            sessionManager = manager,
            aplexerId = aplexerId,
        )
}
