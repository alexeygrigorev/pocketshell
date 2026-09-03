package com.pocketshell.next.ports

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Persists the port table's "Show hidden/noisy ports" checkbox.
 *
 * ## Global, not per-host
 *
 * The checkbox is a viewing preference about port noise, not a property of any
 * one host — the user who wants the full list on one host wants it everywhere.
 * One key, no host-id plumbing.
 *
 * ## Why SharedPreferences
 *
 * The payload is a single boolean and write traffic is one edit per toggle. A
 * Room entity would need a schema bump for state with no relational queries;
 * DataStore would add a version-catalog entry for nothing.
 *
 * Both accessors are `suspend` and hop to the injected dispatcher: opening the
 * prefs file is disk I/O, and the old client hit exactly this as a Main-thread
 * stall when the panel's DI injection opened it during composition. Making it
 * suspend is a smaller, more honest fix than the old `DeferredPrefs` background
 * pre-warm — the only caller is a ViewModel coroutine.
 */
@Singleton
class ShowAllPortsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @com.pocketshell.next.di.IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** True when the user has opted to show every discovered port. */
    suspend fun isShowAll(): Boolean = withContext(dispatcher) {
        runCatching { prefs.getBoolean(KEY_SHOW_ALL, false) }.getOrDefault(false)
    }

    /** Persists the "Show hidden/noisy ports" choice. */
    suspend fun setShowAll(showAll: Boolean) = withContext(dispatcher) {
        runCatching { prefs.edit().putBoolean(KEY_SHOW_ALL, showAll).apply() }
        Unit
    }

    private companion object {
        const val PREFS_NAME = "port_forward_panel"
        const val KEY_SHOW_ALL = "show_all_ports"
    }
}
