package com.pocketshell.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.settings.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * Regression proof for issue #1124: the highest-impact cold-launch freeze found
 * in the #1085 hunt. [DiagnosticRecorder] is an `@Inject @Singleton` field on
 * `App`, constructed during `App.onCreate` on the **Main thread** before
 * StrictMode arms. The old `<init>` seeded `AtomicLong(store.lastSequence())`,
 * and [DiagnosticLogStore.lastSequence] does a **full synchronous `readLines()`**
 * of the unbounded, ever-growing diagnostics JSONL — so the launch cost *grew
 * with usage* and was invisible to the freeze gate.
 *
 * Reproduce-first (D33 / G10, #780 — no self-skip): the LOAD-BEARING assertion is
 * that the JSONL `lastSequence()` read runs on a thread OTHER than the
 * constructing (Main) thread. On the pre-fix code the read happened in `<init>`
 * on the constructing thread (RED); with the off-main eager-`async` build it runs
 * on the recorder's `Dispatchers.IO` scope (GREEN).
 *
 * Class coverage (G2): the off-main move must NOT regress the recorder's
 * monotonic-sequence contract — events recorded after a process restart (a fresh
 * instance over an existing JSONL) keep counting up from the persisted
 * high-water mark, not from 0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiagnosticRecorderOffMainTest {

    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.filesDir, "diagnostics").deleteRecursively()
        File(context.cacheDir, DIAGNOSTICS_EXPORT_CACHE_DIR).deleteRecursively()
        settingsRepository = SettingsRepository(context)
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
    }

    /**
     * LOAD-BEARING (#1124): the unbounded diagnostics JSONL `lastSequence()` read
     * must NOT run on the thread that constructs the recorder (the Main thread
     * during `App.onCreate` Hilt injection in production).
     */
    @Test
    fun `lastSequence read does not run on the constructing thread`() {
        val constructingThread = Thread.currentThread().name

        val recorder = DiagnosticRecorder(context, settingsRepository)
        val readThread = recorder.awaitLastSequenceReadThreadNameForTest()

        assertNotEquals(
            "the diagnostics JSONL lastSequence() read must run off the " +
                "constructing (Main) thread, not on it (#1124). " +
                "constructing=$constructingThread read=$readThread",
            constructingThread,
            readThread,
        )
    }

    /**
     * Monotonic-sequence contract preserved (G2): a fresh recorder instance over
     * an existing JSONL (a process restart) continues numbering from the
     * persisted high-water mark seeded off-main, never restarting at 1.
     */
    @Test
    fun `sequence resumes from persisted high-water mark after off-main seed`() = runTest {
        settingsRepository.setDiagnosticsRecordingEnabled(true)

        // Pre-seed the JSONL with three events as a prior process would have.
        val logFile = File(context.filesDir, "diagnostics/pocketshell-diagnostics.jsonl")
        logFile.parentFile?.mkdirs()
        logFile.writeText(
            (1L..3L).joinToString(separator = "\n", postfix = "\n") { seq ->
                DiagnosticEventJson.encode(
                    DiagnosticsEvent(
                        sequence = seq,
                        wallClockTime = Instant.EPOCH.plusSeconds(seq),
                        monotonicTimestampNanos = seq,
                        category = "app",
                        name = "prior_$seq",
                    ),
                )
            },
        )

        // A fresh instance simulates a cold relaunch over the existing file.
        val recorder = DiagnosticRecorder(context, settingsRepository)
        recorder.record("app", "after_restart")

        val events = recorder.readEvents()
        assertEquals(listOf(1L, 2L, 3L, 4L), events.map { it.sequence })
        assertEquals("after_restart", events.last().name)
        assertEquals(
            "the new event must resume from the persisted high-water mark (#1124)",
            4L,
            events.last().sequence,
        )
    }

    /**
     * A fresh-install recorder (no prior JSONL) still numbers from 1 after the
     * off-main seed — the empty-file / missing-data case (G2).
     */
    @Test
    fun `sequence starts at one on a fresh install after off-main seed`() = runTest {
        settingsRepository.setDiagnosticsRecordingEnabled(true)

        val recorder = DiagnosticRecorder(context, settingsRepository)
        recorder.record("app", "created")
        recorder.record("app", "foreground")

        val events = recorder.readEvents()
        assertEquals(listOf(1L, 2L), events.map { it.sequence })
    }
}
