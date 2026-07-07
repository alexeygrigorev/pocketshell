package com.pocketshell.app.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * The ONE resilient [SharedPreferences] open used by every cold-start / Main-path
 * prefs store — the class-coverage hardening for issue #1292 (the #1291 crash
 * class).
 *
 * ## The crash class (#1291)
 * `Context.getSharedPreferences(name, ...)` does a synchronous first-touch open +
 * XML parse. A power loss / disk-full event can truncate or mangle
 * `shared_prefs/<name>.xml`, and a corrupt file makes that open THROW. Every
 * store on the cold-start / Main path warmed the open in an eager off-main
 * `async` and then, on the first read, `runBlocking`-awaited (or synchronously
 * opened) it on **Main** — so a corrupt file rethrew on Main and crash-looped
 * the app at launch (`app_settings` was fixed in #1229/#1248; `last_session` and
 * the 7 [DeferredPrefs]-backed screen stores shared the identical unguarded
 * pattern — #1292).
 *
 * ## The #1248 shape (hard-cut recovery, D22)
 * [open] mirrors `SettingsRepository.buildSnapshotResiliently`:
 *  1. `runCatching` the open.
 *  2. On failure: best-effort `deleteSharedPreferences(name)` so a fresh empty
 *     file is created and the recovery is DURABLE across relaunch (no "clear app
 *     data" needed), then re-open a writable handle on the cleared file so
 *     subsequent writes still persist.
 *  3. Return that handle (a fresh empty prefs) instead of propagating.
 *
 * There is no compat branch and no fallback to the old unguarded path (D22 hard
 * cut). Callers open synchronously via [open] on the cold path rather than
 * `runBlocking`-awaiting an off-main coroutine — a single bounded small-file read
 * that never parks Main on the contended IO-dispatcher queue (the #1249 lesson).
 */
internal object ResilientPrefs {

    private const val TAG = "PsResilientPrefs"

    /**
     * Open [prefsName] under [context]'s application context, tolerating a
     * corrupt/unreadable prefs file: on an open/parse failure the file is
     * best-effort deleted and re-opened fresh so launch/screen-open survives and
     * the recovery is durable. Never propagates the open failure.
     */
    fun open(context: Context, prefsName: String): SharedPreferences {
        val appContext = context.applicationContext
        return runCatching {
            appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        }.getOrElse { error ->
            Log.e(
                TAG,
                "$prefsName prefs open/parse failed — corrupt prefs file; " +
                    "clearing it and re-opening fresh so launch survives",
                error,
            )
            // Best-effort clear the corrupt file so a fresh empty one is created
            // and the recovery is durable across relaunch. Then re-open on the
            // cleared file so writes still persist. The re-open of a just-deleted
            // prefs file creates a brand-new empty XML — the reliable recovery
            // path the #1291 fixture exercises.
            runCatching { appContext.deleteSharedPreferences(prefsName) }
            appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        }
    }
}
