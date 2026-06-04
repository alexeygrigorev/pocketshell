package com.pocketshell.app.portfwd

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #492: persists the port-forward table's "Show all ports" checkbox.
 *
 * By default the table shows only the useful dev-port range
 * ([InterestingPortFilter.DEFAULT_RANGE], `1000-10000`). Ticking "Show all
 * ports" reveals the hidden out-of-range ports (`< 1000` system, `> 10000`
 * high/ephemeral). The user's choice should survive panel navigation and app
 * restarts, so it is persisted here.
 *
 * ## Global, not per-host
 *
 * The checkbox is a single global preference rather than per-host. The
 * default-vs-show-all distinction is a viewing preference about port noise,
 * not a property of any one host — the same user who wants the full list on
 * one host wants it everywhere. A global flag is the simpler model (one key,
 * no host-id plumbing) and matches the maintainer's "pick the simpler" steer.
 *
 * ## Why SharedPreferences (not Room / DataStore)
 *
 * Same trade-off as [com.pocketshell.app.session.LastSessionStore] and
 * [com.pocketshell.app.settings.SettingsRepository]: the payload is a single
 * boolean, write traffic is one edit per checkbox toggle, and
 * SharedPreferences is already on the classpath. A Room entity would force a
 * schema bump for state that needs no relational queries; DataStore would add
 * a version-catalog entry without buying a feature we need.
 *
 * Singleton scope so every panel composition shares one instance over the same
 * prefs file.
 */
@Singleton
class ShowAllPortsStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when the user has opted to show every discovered port. */
    fun isShowAll(): Boolean =
        runCatching { prefs.getBoolean(KEY_SHOW_ALL, false) }.getOrDefault(false)

    /** Persist the "Show all ports" choice. A synchronous `apply()` suffices. */
    fun setShowAll(showAll: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_ALL, showAll).apply()
    }

    companion object {
        private const val PREFS_NAME = "port_forward_panel"
        private const val KEY_SHOW_ALL = "show_all_ports"
    }
}
