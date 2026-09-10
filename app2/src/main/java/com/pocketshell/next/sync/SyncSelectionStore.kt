package com.pocketshell.next.sync

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException

/**
 * The tick marks: which host aliases this device uploads (issue #2633).
 *
 * Persisted, per device, and that is the load-bearing part — a forgotten
 * selection is the dangerous direction. If a relaunch reset every tick, an
 * innocent "Sync now" would push an empty set and WIPE the account, because
 * push replaces rather than merges (`docs/SYNC.md`, "What syncs: the
 * selection").
 *
 * A `SharedPreferences` JSON array rather than a `StringSet`, because the
 * order of [assembleSyncSet]'s input is the order of the uploaded list and a
 * set has none — an account whose host order shuffled on every sync would
 * produce a different blob each time for no reason.
 */
class SyncSelectionStore(
    context: Context,
    private val fileName: String = DEFAULT_PREFERENCES_FILE,
) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences by lazy { openPrefs() }

    private val _selected: MutableStateFlow<List<String>> by lazy { MutableStateFlow(read()) }

    /** The ticked aliases, in upload order. */
    val selected: StateFlow<List<String>> get() = _selected.asStateFlow()

    fun setChecked(alias: String, checked: Boolean) {
        val current = _selected.value
        val next = when {
            checked && alias !in current -> current + alias
            !checked -> current.filterNot { it == alias }
            else -> current
        }
        write(next)
    }

    /** Tick aliases the account holds that this device has never seen. */
    fun addAll(aliases: List<String>) {
        if (aliases.isEmpty()) return
        val current = _selected.value
        val additions = aliases.filterNot { it in current }
        if (additions.isEmpty()) return
        write(current + additions)
    }

    private fun write(next: List<String>) {
        if (next == _selected.value) return
        val array = JSONArray()
        next.forEach(array::put)
        runCatching { prefs.edit().putString(KEY_SELECTED, array.toString()).apply() }
        _selected.value = next
    }

    private fun read(): List<String> {
        val raw = runCatching { prefs.getString(KEY_SELECTED, null) }.getOrNull() ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
        } catch (_: JSONException) {
            // A hand-edited or truncated value degrades to "nothing ticked",
            // which cannot upload anything — the safe direction here is the
            // one that sends less, never the one that sends more.
            emptyList()
        }
    }

    private fun openPrefs(): SharedPreferences =
        runCatching { appContext.getSharedPreferences(fileName, Context.MODE_PRIVATE) }
            .getOrElse {
                runCatching { appContext.deleteSharedPreferences(fileName) }
                appContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
            }

    companion object {
        const val DEFAULT_PREFERENCES_FILE: String = "next_sync_selection"
        const val KEY_SELECTED: String = "sync_selected_hosts"
    }
}
