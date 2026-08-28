package com.pocketshell.app.projects

import com.pocketshell.app.sessions.HostSessionEnumerator
import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.core.ssh.ExecResult

/**
 * Folder-list view of the shared [HostSessionEnumerator]: live session names
 * from `pocketshell sessions list --json` (tmuxctl + aplexer), with a
 * human-table fallback for older hosts that reject `--json`.
 *
 * Issue #2377: the fetch state machine (including the `Empty` vs `Unavailable`
 * distinction and the #2348 no-second-hop rules) moved to [HostSessionEnumerator]
 * so the session PICKER shares it instead of keeping its own narrower copy that
 * short-circuited on a single-socket live `-CC` client. This object is now only
 * the row-type adapter ([HostTmuxSessionRow] -> [FolderSessionRow]); every
 * behavioural rule lives in the shared object and is documented there.
 */
internal object FolderListPocketshellEnumerator {
    /**
     * JSON enumerator with stdin closed. [SshFolderListGateway.pathAware] wraps
     * this in `/bin/sh -lc` so the redirect applies to the pocketshell process.
     */
    val JSON_EXEC_BODY: String =
        HostSessionEnumerator.jsonExecBody(SshFolderListGateway.POCKETSHELL_SESSIONS_JSON_COMMAND)

    sealed class Fetch {
        abstract val rows: List<FolderSessionRow>

        data class Json(override val rows: List<FolderSessionRow>) : Fetch()
        data class Human(override val rows: List<FolderSessionRow>) : Fetch()
        /** The host ran the enumerator and authoritatively reported no rows. */
        data object Empty : Fetch() {
            override val rows: List<FolderSessionRow> get() = emptyList()
        }
        /** Human fallback ran and failed (missing binary / tmux error). */
        data object Failed : Fetch() {
            override val rows: List<FolderSessionRow> get() = emptyList()
        }

        /**
         * Issue #2377: the enumerator could not be RUN (bounded-exec timeout or
         * transport throw). Rows are empty because nothing was read, not because
         * the host has no sessions — the caller must NOT treat this as "no extra
         * sessions" and publish a narrower list.
         */
        data object Unavailable : Fetch() {
            override val rows: List<FolderSessionRow> get() = emptyList()
        }
    }

    suspend fun fetch(
        parser: HostTmuxSessionListParser,
        exec: suspend (String) -> ExecResult,
        jsonCommand: String,
        humanCommand: String,
    ): Fetch =
        when (
            val fetched = HostSessionEnumerator.fetch(
                parser = parser,
                exec = exec,
                jsonCommand = jsonCommand,
                humanCommand = humanCommand,
            )
        ) {
            is HostSessionEnumerator.Fetch.Json ->
                Fetch.Json(fetched.rows.map { it.toFolderSessionRow() })
            is HostSessionEnumerator.Fetch.Human ->
                Fetch.Human(fetched.rows.map { it.toFolderSessionRow() })
            HostSessionEnumerator.Fetch.Empty -> Fetch.Empty
            HostSessionEnumerator.Fetch.Failed -> Fetch.Failed
            HostSessionEnumerator.Fetch.Unavailable -> Fetch.Unavailable
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
