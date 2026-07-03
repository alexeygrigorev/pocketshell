package com.pocketshell.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.terminal.ui.TerminalKeyboardMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Regression proof for issue #1088: building [SettingsRepository] eagerly in
 * its constructor ran a synchronous first-touch `getSharedPreferences(...)`
 * disk read PLUS a ~15-key `readSnapshot()` on the **Main** thread during
 * `App.onCreate` Hilt field injection — an invisible launch-path freeze (it is
 * built before `StrictModeInstaller` arms, so the cold-launch StrictMode log
 * does not flag it, the same as the #1087 batch off-main fixes).
 *
 * Reproduce-first (D33 / G10, #780 — no self-skip): the load-bearing assertion
 * is that the prefs open + snapshot read run on a thread OTHER than the
 * constructing (Main) thread. On the pre-fix code the read happened in `<init>`
 * on the constructing thread (RED); with the off-main eager-`async` preload it
 * runs on the IO dispatcher (GREEN).
 *
 * The reason this was DEFERRED from #1087 is the no-flash subtlety: [settings]
 * is consumed at FIRST COMPOSITION (theme / per-pane config), so a naive async
 * seed would flash a default→persisted UI config. [persisted snapshot is the
 * initial value][persisted_snapshot_is_the_initial_value_no_default_flash]
 * proves the very first value the composition observes is already the persisted
 * snapshot, never a default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SettingsRepositoryOffMainTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPrefs()
    }

    @After
    fun tearDown() {
        clearPrefs()
    }

    /**
     * LOAD-BEARING (#1088): the `app_settings` prefs file open + the ~15-key
     * [SettingsRepository.readSnapshot] must NOT run on the thread that
     * constructs the store (the Main thread during `App.onCreate` injection in
     * production).
     */
    @Test
    fun snapshot_build_does_not_run_on_constructing_thread() {
        val constructingThread = Thread.currentThread().name.substringBefore(" @coroutine")

        val repo = SettingsRepository(context)
        val buildThread = repo.awaitSnapshotBuildThreadNameForTest()

        assertNotEquals(
            "app_settings prefs+snapshot must be built off the constructing " +
                "(Main) thread, not on it (#1088). " +
                "constructing=$constructingThread build=$buildThread",
            constructingThread,
            buildThread,
        )
    }

    /**
     * No default→persisted flash (the reason #1088 was deferred from #1087):
     * after a non-default value is persisted, a FRESH instance's FIRST observed
     * [SettingsRepository.settings] value is the persisted snapshot, never the
     * default. The lazy StateFlow's initial value is the seeded snapshot, so the
     * composition never sees a default first.
     *
     * DETERMINISTIC, not timing-dependent (#1088 reviewer round 2 — the
     * TIMING1 hardening). The earlier version of this test read
     * `settings.value` AFTER the off-main `async` had already finished (the IO
     * preload wins the race under Robolectric), so it proved only "eventually
     * persisted" — a racy seed-default-then-update variant STILL passed because
     * the async update landed before the read. That is exactly the timing
     * sensitivity the reviewer demonstrated.
     *
     * This version uses the [SettingsRepository] warm-up GATE seam to hold the
     * OFF-MAIN preload provably IN-FLIGHT at the moment `settings.value` is first
     * read, on a worker thread. The two implementations then diverge
     * DETERMINISTICALLY, independent of CPU scheduling:
     *
     *  - Shipped (#1249 non-blocking seed): with the preload gated, the first
     *    read takes the SYNCHRONOUS cold-path — it opens the prefs file directly
     *    and returns the PERSISTED snapshot without awaiting the gated preload.
     *    GREEN.
     *  - Racy (seed a default StateFlow, then update from the async): the first
     *    read returns the DEFAULT immediately, BEFORE the gated async can update
     *    — the visible flash. The captured first value is the default. RED.
     *
     * The load-bearing assertion (first observed value == persisted) therefore
     * goes RED against the seed-default variant and GREEN against the shipped
     * code, with no reliance on who-wins-the-race timing.
     */
    @Test
    fun persisted_snapshot_is_the_initial_value_no_default_flash() {
        // Sanity: the values we persist are genuinely NON-default, so a flash
        // would be visible if the StateFlow seeded a default first.
        assertNotEquals(
            AppSettings.DEFAULT_TERMINAL_FONT_SP,
            PERSISTED_FONT_SP,
        )
        assertNotEquals(
            TerminalKeyboardMode.RawCommand,
            PERSISTED_KEYBOARD_MODE,
        )

        // Persist non-default values via a fully-warmed instance (no gate).
        SettingsRepository(context).apply {
            setTerminalFontSizeSp(PERSISTED_FONT_SP)
            setTerminalKeyboardMode(PERSISTED_KEYBOARD_MODE)
        }

        // A fresh instance = a "process restart", with its off-main snapshot
        // build GATED so it is provably in-flight when we first read the value.
        val gate = CountDownLatch(1)
        val repo = SettingsRepository(context, gate)

        val firstObserved = AtomicReference<AppSettings?>(null)
        val readEntered = CountDownLatch(1)
        val readReturned = CountDownLatch(1)
        val reader = thread(name = "settings-first-read") {
            readEntered.countDown()
            // Shipped (#1249): the gated preload forces the SYNCHRONOUS cold
            // seed, which reads the persisted snapshot directly. Racy
            // seed-default: this returns the default immediately.
            firstObserved.set(repo.settings.value)
            readReturned.countDown()
        }

        // The reader thread is running and about to read.
        readEntered.await()
        // Give the read a generous window to execute WHILE the gate is still
        // closed. The racy variant captures the default within this window; the
        // shipped variant reads the persisted snapshot synchronously (it never
        // parks on the gated preload — #1249). Both return here without opening
        // the gate.
        readReturned.await(2, TimeUnit.SECONDS)

        // Open the gate so the off-main build can complete and drain its scope.
        gate.countDown()
        reader.join(TimeUnit.SECONDS.toMillis(5))

        val observed = firstObserved.get()
            ?: error("settings.value read never completed")
        // LOAD-BEARING: the FIRST value the composition can observe is the
        // PERSISTED snapshot, never a default. The seed-default variant captured
        // the default above → RED here; the shipped synchronous seed returns the
        // persisted snapshot → GREEN.
        assertEquals(PERSISTED_FONT_SP, observed.terminalFontSizeSp, 0f)
        assertEquals(PERSISTED_KEYBOARD_MODE, observed.terminalKeyboardMode)
    }

    /**
     * LOAD-BEARING (#1249): the cold-start first read of [settings] must NOT
     * block on the off-main preload. This is the perf half of #1229 — the old
     * #1088 design seeded `_settings` with `runBlocking { snapshotDeferred.await()
     * }`, which parked the reading thread until the preload finished. On a
     * contended cold start (every store warming on [kotlinx.coroutines.Dispatchers.IO]
     * at once) that stalled launch.
     *
     * Reproduce-first (D33 / G10): the off-main preload is GATED and the gate is
     * NEVER opened, so the preload can never complete. The first `settings.value`
     * read is then required to RETURN — with the persisted snapshot — WITHOUT the
     * gate opening.
     *
     *  - Pre-fix (`runBlocking { snapshotDeferred.await() }`): the read blocks on
     *    the gated preload forever → `done.await(timeout)` returns false → the
     *    `assertTrue` fails → RED.
     *  - Fixed (#1249 synchronous seed): the read opens the prefs file directly,
     *    returns the persisted snapshot, never parks on the preload → GREEN.
     *
     * This single test proves BOTH #1249 properties at once: cold start no longer
     * blocks on the prefs read (the no-block assertion) AND the seed is still the
     * persisted snapshot, not a default (the no-#1088-flash assertion).
     */
    @Test
    fun cold_start_first_read_does_not_block_on_gated_offmain_preload() {
        // Persist a genuinely NON-default value via a fully-warmed instance.
        assertNotEquals(AppSettings.DEFAULT_TERMINAL_FONT_SP, PERSISTED_FONT_SP)
        SettingsRepository(context).setTerminalFontSizeSp(PERSISTED_FONT_SP)

        // Fresh instance = a "process restart" with its off-main preload GATED —
        // and the gate is NEVER opened, simulating a preload that has not yet
        // completed by first composition (the contended cold start).
        val gate = CountDownLatch(1)
        val repo = SettingsRepository(context, gate)

        val firstObserved = AtomicReference<AppSettings?>(null)
        val done = CountDownLatch(1)
        thread(name = "cold-start-first-read") {
            firstObserved.set(repo.settings.value)
            done.countDown()
        }

        // The read MUST return without the gate ever opening (#1249). Pre-fix
        // this blocks on the gated await → times out → RED.
        val returned = done.await(3, TimeUnit.SECONDS)
        assertTrue(
            "cold-start first settings read must NOT block on the off-main " +
                "preload (#1249) — it blocked past the timeout with the gate still closed",
            returned,
        )

        // And the value it returned is the PERSISTED snapshot (no #1088 flash),
        // read synchronously without the preload completing.
        val observed = firstObserved.get()
            ?: error("settings.value read never completed")
        assertEquals(PERSISTED_FONT_SP, observed.terminalFontSizeSp, 0f)
    }

    /** Defaults read correctly after the off-main build (clean install). */
    @Test
    fun defaults_are_correct_after_offmain_build() {
        val initial = SettingsRepository(context).settings.value
        assertEquals(
            AppSettings.DEFAULT_TERMINAL_FONT_SP,
            initial.terminalFontSizeSp,
            0f,
        )
        assertEquals(TerminalKeyboardMode.RawCommand, initial.terminalKeyboardMode)
    }

    private fun clearPrefs() {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private companion object {
        const val PERSISTED_FONT_SP = 19f
        val PERSISTED_KEYBOARD_MODE = TerminalKeyboardMode.SmartText
    }
}
