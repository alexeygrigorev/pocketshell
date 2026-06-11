package com.pocketshell.app.projects

import android.content.Context
import android.content.SharedPreferences
import com.pocketshell.uikit.model.SessionAgentKind
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small persisted snapshot of the last successful host-detail session list.
 *
 * This deliberately uses SharedPreferences rather than a Room entity: the
 * payload is tiny, keyed by host id, and only needs best-effort cold render
 * before the authoritative SSH refresh reconciles it.
 */
@Singleton
class HostSessionListCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Snapshot(
        val rows: List<FolderSessionRow>,
        val savedAtMillis: Long,
    )

    fun save(
        hostId: Long,
        rows: List<FolderSessionRow>,
        savedAtMillis: Long = System.currentTimeMillis(),
    ) {
        val payload = JSONObject()
            .put("savedAtMillis", savedAtMillis)
            .put("rows", JSONArray().also { array ->
                rows.forEach { row -> array.put(row.toJson()) }
            })
        prefs.edit()
            .putString(key(hostId), payload.toString())
            .apply()
    }

    fun read(hostId: Long): Snapshot? {
        val raw = prefs.getString(key(hostId), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val rowsJson = json.optJSONArray("rows") ?: return null
            val rows = buildList {
                for (index in 0 until rowsJson.length()) {
                    rowsJson.optJSONObject(index)?.toFolderSessionRow()?.let(::add)
                }
            }
            Snapshot(
                rows = rows,
                savedAtMillis = json.optLong("savedAtMillis", 0L),
            )
        }.getOrNull()
    }

    fun clear(hostId: Long) {
        prefs.edit().remove(key(hostId)).apply()
    }

    private fun key(hostId: Long): String = "host_$hostId"

    private fun FolderSessionRow.toJson(): JSONObject =
        JSONObject()
            .put("sessionName", sessionName)
            .put("lastActivity", lastActivity ?: JSONObject.NULL)
            .put("attached", attached)
            .put("cwd", cwd ?: JSONObject.NULL)
            .put("agentKind", agentKind.name)
            .put("windows", JSONArray().also { array ->
                windows.forEach { window -> array.put(window.toJson()) }
            })

    private fun FolderSessionWindowRow.toJson(): JSONObject =
        JSONObject()
            .put("sessionName", sessionName)
            .put("index", index ?: JSONObject.NULL)
            .put("name", name ?: JSONObject.NULL)
            .put("active", active)
            .put("cwd", cwd ?: JSONObject.NULL)
            .put("tty", tty ?: JSONObject.NULL)
            .put("command", command ?: JSONObject.NULL)
            .put("agentKind", agentKind.name)
            // Issue #653: persist the stable tmux window id so a cold-restored
            // tree can prune a window by id on a live `%window-close` before the
            // first reconcile re-tags it.
            .put("windowId", windowId ?: JSONObject.NULL)

    private fun JSONObject.toFolderSessionRow(): FolderSessionRow? {
        val sessionName = optString("sessionName").takeIf { it.isNotBlank() } ?: return null
        return FolderSessionRow(
            sessionName = sessionName,
            lastActivity = longOrNull("lastActivity"),
            attached = optBoolean("attached", false),
            cwd = stringOrNull("cwd"),
            agentKind = agentKindOrShell(optString("agentKind")),
            windows = optJSONArray("windows").toWindowRows(),
        )
    }

    private fun JSONArray?.toWindowRows(): List<FolderSessionWindowRow> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.toFolderSessionWindowRow()?.let(::add)
            }
        }
    }

    private fun JSONObject.toFolderSessionWindowRow(): FolderSessionWindowRow? {
        val sessionName = optString("sessionName").takeIf { it.isNotBlank() } ?: return null
        return FolderSessionWindowRow(
            sessionName = sessionName,
            index = intOrNull("index"),
            name = stringOrNull("name"),
            active = optBoolean("active", false),
            cwd = stringOrNull("cwd"),
            tty = stringOrNull("tty"),
            command = stringOrNull("command"),
            agentKind = agentKindOrShell(optString("agentKind")),
            windowId = stringOrNull("windowId"),
        )
    }

    private fun JSONObject.stringOrNull(name: String): String? =
        when (val value = opt(name)) {
            null, JSONObject.NULL -> null
            else -> value.toString()
        }

    private fun JSONObject.longOrNull(name: String): Long? =
        when (val value = opt(name)) {
            is Number -> value.toLong()
            else -> null
        }

    private fun JSONObject.intOrNull(name: String): Int? =
        when (val value = opt(name)) {
            is Number -> value.toInt()
            else -> null
        }

    private fun agentKindOrShell(name: String): SessionAgentKind =
        runCatching { SessionAgentKind.valueOf(name) }.getOrDefault(SessionAgentKind.Shell)

    companion object {
        private const val PREFS_NAME = "host_session_list_cache"
    }
}
