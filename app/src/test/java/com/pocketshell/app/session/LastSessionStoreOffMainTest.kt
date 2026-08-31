package com.pocketshell.app.session

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression proof for issue #1087 freeze cause F6: building the
 * `last_session` SharedPreferences eagerly in [LastSessionStore]'s
 * constructor ran a synchronous first-touch disk read on the **Main**
 * thread — StrictMode captured a 69–117ms `DiskReadViolation` in
 * `LastSessionStore.<init>` during cold-launch Hilt injection
 * (`MainActivity.onCreate` → `injectMainActivity2`). It was the next dominant
 * cold-launch stall after F1 (keystore, #1085) and F5
 * (`SystemSurfaceStateStore`, #1086) were fixed.
 *
 * Reproduce-first (D33 / G10, #780 model — no self-skip): the load-bearing
 * assertion is that the prefs-file build runs on a thread OTHER than the
 * constructing (Main) thread. On the pre-fix code the `getSharedPreferences(...)`
 * read happened in `<init>` on the constructing thread, so
 * [prefs_build_does_not_run_on_constructing_thread] FAILS RED; with the
 * off-main eager-`async` build it runs on the IO dispatcher and PASSES GREEN.
 *
 * Class coverage (G2): the remaining tests prove the off-main init does not
 * introduce an empty/racey first read — the default read returns null, and a
 * save→fresh-instance read ("process restart") returns the persisted value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LastSessionStoreOffMainTest {

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
     * LOAD-BEARING (#1087 F6): the `last_session` prefs file must NOT be built
     * on the thread that constructs the store (which, in production, is the
     * Main thread during `MainActivity.onCreate` Hilt injection).
     */
    @Test
    fun prefs_build_does_not_run_on_constructing_thread() {
        val constructingThread = Thread.currentThread().name

        val store = LastSessionStore(context)
        val buildThread = store.awaitPrefsBuildThreadNameForTest()

        assertNotEquals(
            "last_session prefs must be built off the constructing (Main) " +
                "thread, not on it (#1087 F6). " +
                "constructing=$constructingThread build=$buildThread",
            constructingThread,
            buildThread,
        )
    }

    /** No empty/racey first read: a never-saved store reads null after off-main build. */
    @Test
    fun default_read_is_null_after_offmain_build() {
        val read = LastSessionStore(context).read(nowMillis = 2_000L)
        assertNull(read)
    }

    /** Save→fresh-instance read survives the off-main build (process restart). */
    @Test
    fun saved_session_round_trips_and_survives_restart_after_offmain_build() {
        val session = testSession()
        LastSessionStore(context).save(session)

        // A brand-new instance reads its prefs off-main; the first read must
        // return the persisted snapshot, not an empty/default one.
        val restored = LastSessionStore(context).read(
            nowMillis = 1_500L,
            maxAgeMillis = Long.MAX_VALUE,
        )

        assertEquals(session, restored)
    }

    /**
     * Issue #2265 process-kill regression: [SharedPreferences.Editor.apply]
     * updates only the current process's memory before returning. Model an
     * immediate kill by dropping every apply, then read through a fresh store
     * backed by the real prefs. The lifecycle save must already be committed
     * when [LastSessionStore.save] returns.
     */
    @Test
    fun save_is_durable_before_return_when_process_kill_drops_async_apply() {
        val backing = context.getSharedPreferences("last_session", Context.MODE_PRIVATE)
        val crashWindowPrefs = ApplyLosingSharedPreferences(backing)
        val store = LastSessionStore(PrefsOverrideContext(context, crashWindowPrefs))
        store.awaitPrefsBuildThreadNameForTest()
        val session = testSession()
        val callingThread = Thread.currentThread().name

        assertTrue("durable save should be acknowledged", store.save(session))

        val afterProcessKill = LastSessionStore(context).read(
            nowMillis = 1_500L,
            maxAgeMillis = Long.MAX_VALUE,
        )
        assertEquals(
            "save must be on disk before returning; an immediate process kill " +
                "must not lose the last-session snapshot",
            session,
            afterProcessKill,
        )
        assertEquals("durable save must commit exactly once", 1, crashWindowPrefs.commitCount)
        assertEquals("durable save must not use async apply", 0, crashWindowPrefs.applyCount)
        assertNotEquals(
            "prefs edit/open work must stay off the lifecycle-calling thread",
            callingThread,
            crashWindowPrefs.editThreadName,
        )
        assertNotEquals(
            "synchronous commit must stay off the lifecycle-calling thread (StrictMode)",
            callingThread,
            crashWindowPrefs.commitThreadName,
        )
    }

    /**
     * The adjacent #2265 class: an async clear lost to process death leaves the
     * old session on disk and resurrects a session the user left or stopped.
     * Clear therefore shares the exact same return-means-durable boundary.
     * The save acknowledgement and read-back immediately below are intentional
     * #2264 reconciliation coverage: retain the stronger fixture proof before
     * exercising the load-bearing clear crash window from #2265.
     */
    @Test
    fun clear_is_durable_before_return_when_process_kill_drops_async_apply() {
        val session = testSession()
        assertTrue("fixture seed must be durably saved before clear", LastSessionStore(context).save(session))
        assertEquals(
            "fixture seed must be visible before the simulated process kill",
            session,
            LastSessionStore(context).read(nowMillis = 1_500L, maxAgeMillis = Long.MAX_VALUE),
        )
        // Fence the fixture seed. This is setup only; the load-bearing clear
        // below remains the sole write inside the simulated crash window.
        context.getSharedPreferences("last_session", Context.MODE_PRIVATE).edit().commit()

        val backing = context.getSharedPreferences("last_session", Context.MODE_PRIVATE)
        val crashWindowPrefs = ApplyLosingSharedPreferences(backing)
        val store = LastSessionStore(PrefsOverrideContext(context, crashWindowPrefs))
        store.awaitPrefsBuildThreadNameForTest()
        val callingThread = Thread.currentThread().name

        assertTrue("durable clear should be acknowledged", store.clear())

        val afterProcessKill = LastSessionStore(context).read(
            nowMillis = 1_500L,
            maxAgeMillis = Long.MAX_VALUE,
        )
        assertNull(
            "clear must be on disk before returning; an immediate process kill " +
                "must not resurrect the old session",
            afterProcessKill,
        )
        assertEquals("durable clear must commit exactly once", 1, crashWindowPrefs.commitCount)
        assertEquals("durable clear must not use async apply", 0, crashWindowPrefs.applyCount)
        assertNotEquals(
            "clear edit/open work must stay off the lifecycle-calling thread",
            callingThread,
            crashWindowPrefs.editThreadName,
        )
        assertNotEquals(
            "clear commit must stay off the lifecycle-calling thread (StrictMode)",
            callingThread,
            crashWindowPrefs.commitThreadName,
        )
    }

    /**
     * ANR guard for the synchronous lifecycle handoff: a commit that never
     * returns must not park the onStop caller beyond the explicit budget.
     */
    @Test
    fun durable_write_wait_is_bounded_when_commit_stalls() {
        val backing = context.getSharedPreferences("last_session", Context.MODE_PRIVATE)
        val stalledPrefs = StallingCommitSharedPreferences(backing)
        val store = LastSessionStore(PrefsOverrideContext(context, stalledPrefs))
        store.awaitPrefsBuildThreadNameForTest()

        val productionBudgetMillis = LastSessionStore.DURABLE_WRITE_WAIT_MILLIS
        val startedAt = System.nanoTime()
        assertFalse(
            "a timed-out commit must be reported as not durably acknowledged",
            store.save(testSession()),
        )
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertEquals(
            "the fixture must enter the blocking commit or this bound is vacuous",
            0L,
            stalledPrefs.commitEntered.count,
        )
        assertTrue(
            "the test must exercise the production wait budget; " +
                "budgetMs=$productionBudgetMillis elapsedMs=$elapsedMillis",
            elapsedMillis >= productionBudgetMillis - 50L,
        )
        assertTrue(
            "onStop persistence must return after its bounded wait, not approach an ANR; " +
                "budgetMs=$productionBudgetMillis elapsedMs=$elapsedMillis",
            elapsedMillis < productionBudgetMillis + 750L,
        )
        assertTrue(
            "timed-out commit worker did not unwind after cancellation",
            stalledPrefs.commitFinished.await(2, TimeUnit.SECONDS),
        )
    }

    /**
     * Mutation-selective failure proof: save must not turn a commit=false into
     * a successful lifecycle persistence result. The old snapshot remains the
     * only restart-visible state because the failing editor never commits.
     */
    @Test
    fun save_reports_commit_failure_instead_of_claiming_durability() {
        val oldSession = testSession().copy(sessionName = "old-session")
        assertTrue(LastSessionStore(context).save(oldSession))

        val failingPrefs = CommitFailureSharedPreferences(
            context.getSharedPreferences("last_session", Context.MODE_PRIVATE),
        )
        val store = LastSessionStore(PrefsOverrideContext(context, failingPrefs))
        store.awaitPrefsBuildThreadNameForTest()

        assertFalse(
            "save must report commit=false to its lifecycle caller",
            store.save(testSession()),
        )
        assertEquals(1, failingPrefs.commitCount)
        assertEquals(
            oldSession,
            LastSessionStore(context).read(
                nowMillis = 1_500L,
                maxAgeMillis = Long.MAX_VALUE,
            ),
        )
    }

    /**
     * Mutation-selective failure proof for the destructive path: clear must
     * report commit=false, leaving callers unable to mistake a still-present
     * restore target for a durable clear.
     */
    @Test
    fun clear_reports_commit_failure_instead_of_claiming_durability() {
        val session = testSession()
        assertTrue(LastSessionStore(context).save(session))

        val failingPrefs = CommitFailureSharedPreferences(
            context.getSharedPreferences("last_session", Context.MODE_PRIVATE),
        )
        val store = LastSessionStore(PrefsOverrideContext(context, failingPrefs))
        store.awaitPrefsBuildThreadNameForTest()

        assertFalse(
            "clear must report commit=false to its lifecycle caller",
            store.clear(),
        )
        assertEquals(1, failingPrefs.commitCount)
        assertEquals(
            session,
            LastSessionStore(context).read(
                nowMillis = 1_500L,
                maxAgeMillis = Long.MAX_VALUE,
            ),
        )
    }

    private fun testSession() = LastSessionStore.LastSession(
        hostId = 42L,
        hostName = "prod-box",
        hostname = "10.0.0.9",
        port = 2022,
        username = "alex",
        keyPath = "/data/keys/id_ed25519",
        sessionName = "claude-main",
        startDirectory = "/srv/app",
        tmuxSessionId = "\$3",
        sessionCreated = 1_700_000_000L,
        savedAtMillis = 1_000L,
    )

    private fun clearPrefs() {
        context.getSharedPreferences("last_session", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}

/** Routes ResilientPrefs' application-context open through a crash-window fake. */
private class PrefsOverrideContext(
    base: Context,
    private val prefs: SharedPreferences,
) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
}

/** Simulates immediate process death dropping async apply while preserving commit. */
private class ApplyLosingSharedPreferences(
    private val delegate: SharedPreferences,
) : SharedPreferences by delegate {
    var applyCount: Int = 0
        private set
    var commitCount: Int = 0
        private set
    var editThreadName: String? = null
        private set
    var commitThreadName: String? = null
        private set

    override fun edit(): SharedPreferences.Editor {
        editThreadName = Thread.currentThread().name
        return Editor(delegate.edit())
    }

    private inner class Editor(
        private val delegateEditor: SharedPreferences.Editor,
    ) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) =
            apply { delegateEditor.putString(key, value) }

        override fun putStringSet(key: String?, values: MutableSet<String>?) =
            apply { delegateEditor.putStringSet(key, values) }

        override fun putInt(key: String?, value: Int) =
            apply { delegateEditor.putInt(key, value) }

        override fun putLong(key: String?, value: Long) =
            apply { delegateEditor.putLong(key, value) }

        override fun putFloat(key: String?, value: Float) =
            apply { delegateEditor.putFloat(key, value) }

        override fun putBoolean(key: String?, value: Boolean) =
            apply { delegateEditor.putBoolean(key, value) }

        override fun remove(key: String?) = apply { delegateEditor.remove(key) }

        override fun clear() = apply { delegateEditor.clear() }

        override fun commit(): Boolean {
            commitCount += 1
            commitThreadName = Thread.currentThread().name
            return delegateEditor.commit()
        }

        override fun apply() {
            applyCount += 1
        }
    }
}

/** A real blocking commit seam used to prove the lifecycle wait cannot ANR. */
private class StallingCommitSharedPreferences(
    private val delegate: SharedPreferences,
) : SharedPreferences by delegate {
    val commitEntered = CountDownLatch(1)
    val commitFinished = CountDownLatch(1)

    override fun edit(): SharedPreferences.Editor = Editor(delegate.edit())

    private inner class Editor(
        private val delegateEditor: SharedPreferences.Editor,
    ) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) =
            apply { delegateEditor.putString(key, value) }

        override fun putStringSet(key: String?, values: MutableSet<String>?) =
            apply { delegateEditor.putStringSet(key, values) }

        override fun putInt(key: String?, value: Int) =
            apply { delegateEditor.putInt(key, value) }

        override fun putLong(key: String?, value: Long) =
            apply { delegateEditor.putLong(key, value) }

        override fun putFloat(key: String?, value: Float) =
            apply { delegateEditor.putFloat(key, value) }

        override fun putBoolean(key: String?, value: Boolean) =
            apply { delegateEditor.putBoolean(key, value) }

        override fun remove(key: String?) = apply { delegateEditor.remove(key) }

        override fun clear() = apply { delegateEditor.clear() }

        override fun commit(): Boolean {
            commitEntered.countDown()
            return try {
                CountDownLatch(1).await()
                false
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } finally {
                commitFinished.countDown()
            }
        }

        override fun apply() = Unit
    }
}

/** Returns commit=false without applying the pending editor mutation. */
private class CommitFailureSharedPreferences(
    private val delegate: SharedPreferences,
) : SharedPreferences by delegate {
    var commitCount: Int = 0
        private set

    override fun edit(): SharedPreferences.Editor = Editor(delegate.edit())

    private inner class Editor(
        private val delegateEditor: SharedPreferences.Editor,
    ) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) =
            apply { delegateEditor.putString(key, value) }

        override fun putStringSet(key: String?, values: MutableSet<String>?) =
            apply { delegateEditor.putStringSet(key, values) }

        override fun putInt(key: String?, value: Int) =
            apply { delegateEditor.putInt(key, value) }

        override fun putLong(key: String?, value: Long) =
            apply { delegateEditor.putLong(key, value) }

        override fun putFloat(key: String?, value: Float) =
            apply { delegateEditor.putFloat(key, value) }

        override fun putBoolean(key: String?, value: Boolean) =
            apply { delegateEditor.putBoolean(key, value) }

        override fun remove(key: String?) = apply { delegateEditor.remove(key) }

        override fun clear() = apply { delegateEditor.clear() }

        override fun commit(): Boolean {
            commitCount += 1
            return false
        }

        override fun apply() = Unit
    }
}
