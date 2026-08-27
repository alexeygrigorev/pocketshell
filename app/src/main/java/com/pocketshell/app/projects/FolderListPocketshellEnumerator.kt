package com.pocketshell.app.projects

import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.core.ssh.ExecResult
import kotlinx.coroutines.CancellationException

/**
 * Live session names from `pocketshell sessions list --json` (tmuxctl + aplexer),
 * with a human-table fallback for older hosts that reject `--json`.
 *
 * Issue #2348: this enumerator is best-effort overlay on the 12s mobile
 * reconcile path. A hung or timed-out JSON exec must fail-safe to [Fetch.Empty]
 * rather than spending a second human exec; JSON empty-success must not fall
 * through to human either. Exit 0 with a non-JSON body (the 0.4.45 fixture
 * prints the human table for `--json`) is parsed in-process from that same
 * stdout — never a second `humanCommand` hop. Stdin is closed on the JSON
 * body so a wrap()-style first-statement `read()` cannot park the hop until
 * the 12s bound. Unknown `--json` (nonzero exit) may still fall back to one
 * human exec.
 */
internal object FolderListPocketshellEnumerator {
    /**
     * JSON enumerator with stdin closed. [SshFolderListGateway.pathAware] wraps
     * this in `/bin/sh -lc` so the redirect applies to the pocketshell process.
     */
    const val JSON_EXEC_BODY: String =
        "{ ${SshFolderListGateway.POCKETSHELL_SESSIONS_JSON_COMMAND} ; } </dev/null"

    sealed class Fetch {
        abstract val rows: List<FolderSessionRow>

        data class Json(override val rows: List<FolderSessionRow>) : Fetch()
        data class Human(override val rows: List<FolderSessionRow>) : Fetch()
        data object Empty : Fetch() {
            override val rows: List<FolderSessionRow> get() = emptyList()
        }
        /** Human fallback ran and failed (missing binary / tmux error). */
        data object Failed : Fetch() {
            override val rows: List<FolderSessionRow> get() = emptyList()
        }
    }

    suspend fun fetch(
        parser: HostTmuxSessionListParser,
        exec: suspend (String) -> ExecResult,
        jsonCommand: String,
        humanCommand: String,
    ): Fetch {
        val json = try {
            exec(jsonCommand)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Timed out / wrap-stdin hang / transport throw: fail-safe. A second
            // human exec here is the mutation that reddens #2348 (3.5s + 3.5s
            // serial on the 12s bound).
            return Fetch.Empty
        }
        if (json.exitCode == 0) {
            val parsed = parser.parsePocketshellSessionsJson(json.stdout)
            if (parsed != null) {
                return Fetch.Json(parsed.map { it.toFolderSessionRow() })
            }
            // Exit 0 with a blank body is an empty enumerator, not a prompt
            // to fall through to the human table (that second exec was
            // stealing the next queued fake response and breaking lease-reuse
            // command accounting).
            if (json.stdout.isBlank()) {
                return Fetch.Empty
            }
            // Docker agents fixture 0.4.45 (and any host that accepts `--json`
            // then prints the human table): exit 0, stdout starts with IDX,
            // not `{`. parsePocketshellSessionsJson returns null. A second
            // `pocketshell sessions list --by activity` here is the extra
            // mobile-RTT hop that misses the 12s bound. Parse this same
            // stdout in-process; garbage that is not a table is Empty.
            val humanRows = SshFolderListGateway.parsePocketshellSessionsRows(
                json.stdout,
                parser,
            )
            return if (humanRows.isEmpty()) {
                Fetch.Empty
            } else {
                Fetch.Human(humanRows)
            }
        }
        val human = try {
            exec(humanCommand)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return Fetch.Empty
        }
        if (human.exitCode == 0) {
            return Fetch.Human(
                SshFolderListGateway.parsePocketshellSessionsRows(human.stdout, parser),
            )
        }
        return Fetch.Failed
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
