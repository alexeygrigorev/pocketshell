package com.pocketshell.app.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.terminal.ui.TerminalKeyboardMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reproduce-first regression proof for issue #1229 (D33 / G10): a corrupt /
 * unreadable `app_settings` prefs file must NOT crash-loop the app at launch.
 *
 * ## The bug (audit finding A1)
 * `SettingsRepository`'s off-main warm-up eagerly opened the prefs file with
 * `getSharedPreferences(PREFS_NAME, ...)` — a synchronous first-touch open +
 * XML parse — with **no** guard around the open (individual key reads were
 * already `safeXxx`-wrapped; the file open was not). A power loss / disk-full
 * event that truncates or mangles `shared_prefs/app_settings.xml` makes that
 * open THROW. The exception latched in the warm-up `Deferred`, and the first
 * `settings.collectAsState()` at the theme root then rethrew it on **Main** —
 * crashing the launch. Every relaunch repeated it: unrecoverable without
 * clearing app data.
 *
 * ## The fixture that reproduces it (the non-happy state)
 * [CorruptPrefsContext] throws on `getSharedPreferences("app_settings", ...)`
 * exactly as a corrupt XML parse would, until the file is DELETED — a happy
 * fixture (a normal writable prefs file) can never enter the failing state, so
 * it would prove nothing (the v0.4.10/#847 lesson).
 *
 * RED on base: `repo.settings.value` rethrows the latched exception on the
 * reading thread → the test throws → RED.
 * GREEN with fix: the warm-up catches the corrupt open, best-effort deletes the
 * file, and degrades to default [AppSettings]; `settings.value` returns
 * defaults without throwing → GREEN.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SettingsRepositoryCorruptPrefsTest {

    private lateinit var realContext: Context

    @Before
    fun setUp() {
        realContext = ApplicationProvider.getApplicationContext()
        clearPrefs()
    }

    @After
    fun tearDown() {
        clearPrefs()
    }

    /**
     * LOAD-BEARING (#1229 acceptance 1): a corrupt/throwing prefs source
     * degrades to default settings at launch instead of crashing. On base the
     * unguarded open latches in the Deferred and `settings.value` rethrows on
     * the reading thread (RED); with the fix it returns defaults (GREEN).
     */
    @Test
    fun corrupt_prefs_degrades_to_defaults_without_crashing_at_launch() {
        val corruptContext = CorruptPrefsContext(realContext)

        // Constructing the repo kicks off the off-main warm-up; reading
        // `settings.value` is exactly what the theme root does at first
        // composition — the crash site.
        val repo = SettingsRepository(corruptContext)
        val settings = repo.settings.value

        // Degraded to defaults, not crashed.
        assertEquals(
            "corrupt prefs must degrade to the default terminal font",
            AppSettings.DEFAULT_TERMINAL_FONT_SP,
            settings.terminalFontSizeSp,
            0f,
        )
        assertEquals(
            "corrupt prefs must degrade to the default keyboard mode",
            TerminalKeyboardMode.RawCommand,
            settings.terminalKeyboardMode,
        )
        // The corrupt open was actually exercised (guards against a vacuous
        // pass where the fixture never threw — G3).
        assertTrue(
            "the corrupt-prefs open must have been attempted at least once",
            corruptContext.throwingOpenAttempts.get() >= 1,
        )
    }

    /**
     * #1229 acceptance 2: the recovery is durable. After the first launch's
     * warm-up recovers (best-effort deleting the corrupt file), a SECOND
     * instance — a "relaunch" on the SAME app data, without the user clearing
     * data — opens cleanly and reads defaults, no crash. The fix's
     * `deleteSharedPreferences` clears [CorruptPrefsContext]'s corrupt marker
     * so the relaunch's open succeeds.
     */
    @Test
    fun recovery_is_durable_relaunch_after_corruption_works() {
        val corruptContext = CorruptPrefsContext(realContext)

        // First launch: recovers + best-effort clears the corrupt file.
        SettingsRepository(corruptContext).settings.value
        assertTrue(
            "first launch must have attempted the corrupt open",
            corruptContext.throwingOpenAttempts.get() >= 1,
        )
        assertTrue(
            "the fix must best-effort delete the corrupt prefs file so the " +
                "recovery is durable across relaunch",
            corruptContext.wasDeleted,
        )

        // Second launch on the SAME (now cleared) app data: opens cleanly.
        val relaunch = SettingsRepository(corruptContext)
        val relaunchSettings = relaunch.settings.value
        assertEquals(
            AppSettings.DEFAULT_TERMINAL_FONT_SP,
            relaunchSettings.terminalFontSizeSp,
            0f,
        )
        // A non-default write on the relaunch must persist (proves the fix
        // re-seeded a usable prefs handle from the cleared file).
        relaunch.setTerminalFontSizeSp(RELAUNCH_FONT_SP)
        assertEquals(RELAUNCH_FONT_SP, relaunch.settings.value.terminalFontSizeSp, 0f)
    }

    /**
     * #1229 acceptance 3 (no theme-flash regression on the NORMAL path): a
     * healthy prefs file still reads its persisted snapshot as the initial
     * value. The corrupt-file guard must not alter the happy path — a
     * previously-persisted non-default value is the first observed value.
     */
    @Test
    fun healthy_prefs_still_reads_persisted_snapshot_no_regression() {
        // Persist a non-default value on the real (healthy) context.
        SettingsRepository(realContext).setTerminalFontSizeSp(RELAUNCH_FONT_SP)

        // A fresh instance reads it back as the initial value (no default flash).
        val reopened = SettingsRepository(realContext).settings.value
        assertEquals(RELAUNCH_FONT_SP, reopened.terminalFontSizeSp, 0f)
    }

    private fun clearPrefs() {
        realContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    /**
     * A [ContextWrapper] that simulates a corrupt `app_settings` XML: it throws
     * on `getSharedPreferences("app_settings", ...)` — mirroring the
     * open/parse failure the audit describes — until the file is deleted, at
     * which point (the durable recovery) it opens cleanly against the real
     * backing context. `getApplicationContext()` returns `this` so the
     * repository's `context.applicationContext` is the throwing wrapper.
     */
    private class CorruptPrefsContext(base: Context) : ContextWrapper(base) {
        @Volatile
        private var corrupt = true

        @Volatile
        var wasDeleted = false
            private set

        val throwingOpenAttempts = AtomicInteger(0)

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            if (name == PREFS_NAME && corrupt) {
                throwingOpenAttempts.incrementAndGet()
                throw RuntimeException(
                    "simulated corrupt $PREFS_NAME.xml: unexpected end of document",
                )
            }
            return super.getSharedPreferences(name, mode)
        }

        override fun deleteSharedPreferences(name: String?): Boolean {
            if (name == PREFS_NAME) {
                corrupt = false
                wasDeleted = true
            }
            return super.deleteSharedPreferences(name)
        }
    }

    private companion object {
        const val PREFS_NAME = "app_settings"
        const val RELAUNCH_FONT_SP = 21f
    }
}
