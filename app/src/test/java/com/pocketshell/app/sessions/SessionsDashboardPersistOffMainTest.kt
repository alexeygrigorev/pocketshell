package com.pocketshell.app.sessions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

/**
 * Regression proof for issue #1086 freeze cause F5 at the call site:
 * [SessionsDashboardViewModel.persistActiveSessionCount] used to construct a
 * [com.pocketshell.app.systemsurfaces.SystemSurfaceStateStore] and write to it
 * **inline on `viewModelScope` (Main)**, dragging the store's ~648ms
 * first-touch disk read onto the cold-launch UI thread.
 *
 * The fix dispatches the construct-and-write onto an injectable `ioDispatcher`.
 * This test proves, on the real production path
 * (`emitAggregate -> persistActiveSessionCount`):
 *  - the persist work is dispatched off the Main path — the injected dispatcher
 *    is actually used (the LOAD-BEARING off-main assertion at this layer), and
 *  - the live session count is still persisted correctly afterwards (G2: no
 *    regression to the visible widget count).
 *
 * The persist runs on a real `Dispatchers.IO`-backed recording dispatcher (not
 * the virtual test clock). The VM's `init` collect persists count=0 for the
 * empty registry first; the test bounded-awaits that landing before switching
 * to the recording dispatcher and driving the count=2 write, so there is only
 * ever one outstanding writer and the final value is deterministically 2. The
 * assertions hard-fail on timeout rather than self-skipping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SessionsDashboardPersistOffMainTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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
     * Records whether it was ever asked to dispatch a coroutine, so the test
     * can assert the persist path actually went through `ioDispatcher` rather
     * than running inline on the Main path.
     */
    private class RecordingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        @Volatile
        var dispatched: Boolean = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched = true
            delegate.dispatch(context, block)
        }
    }

    @Test
    fun persistActiveSessionCountDispatchesOffMainAndPersistsCount() {
        val recording = RecordingDispatcher(Dispatchers.IO)
        val vm = SessionsDashboardViewModel(
            activeClients = ActiveTmuxClients(),
            appContext = context,
        )

        try {
            // The VM init collect persists count=0 for the empty registry on
            // the default dispatcher; wait for it to land so it cannot race the
            // count=2 write driven below (one outstanding writer at a time).
            assertEquals(0, awaitPersistedCount(expected = 0, timeoutMs = 5_000))

            vm.ioDispatcher = recording

            // Drive a two-session aggregate through the real production path
            // (emitAggregate -> persistActiveSessionCount).
            vm.applyHostSnapshotForTest(
                hostId = 1L,
                summaries = listOf(
                    SessionSummary(1L, "alpha", "work", lastActivity = 200, attached = true),
                    SessionSummary(1L, "alpha", "logs", lastActivity = 100, attached = false),
                ),
            )

            // G2: the live count is persisted correctly after the off-main
            // construct-and-write completes (bounded-await the raw prefs value;
            // hard-fails on timeout, never self-skips).
            assertEquals(2, awaitPersistedCount(expected = 2, timeoutMs = 5_000))

            // LOAD-BEARING (#1086 F5): the count=2 persist was dispatched onto
            // the injected ioDispatcher, i.e. NOT run inline on the Main path.
            assertTrue(
                "persistActiveSessionCount must dispatch onto ioDispatcher " +
                    "(off Main), not run inline (#1086 F5)",
                recording.dispatched,
            )
        } finally {
            vm.stopForTest()
        }
    }

    /**
     * Bounded-await the raw persisted count, reading the same in-memory
     * SharedPreferences instance the off-main write targets. Returns the last
     * observed value (which hard-fails the assertion) if [expected] never
     * appears within [timeoutMs].
     */
    private fun awaitPersistedCount(expected: Int, timeoutMs: Long): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = -1
        while (System.currentTimeMillis() < deadline) {
            last = context.getSharedPreferences("system_surfaces", Context.MODE_PRIVATE)
                .getInt("active_session_count", -1)
            if (last == expected) return last
            Thread.sleep(20)
        }
        return last
    }

    private fun clearPrefs() {
        context.getSharedPreferences("system_surfaces", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
