package com.pocketshell.app.prefs

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Reproduce-first, CLASS-COVERING regression proof for issue #1292 (#1291 /
 * D31 / D32-G2 / G10): the 7 screen-scoped stores that route their prefs open
 * through [DeferredPrefs] (composer draft + outbound queue + attachment sidecar,
 * port-forward panel, file viewer, FCM token, push dedup) all share the same
 * unguarded-open crash class as `app_settings` (#1229) and `last_session`
 * (#1292): a corrupt prefs file makes `getSharedPreferences(...)` THROW, the
 * exception latches in the warm-up `Deferred`, and the first `get()` on the
 * **Main** thread (at screen-open) `runBlocking`-rethrows it — crashing the
 * screen.
 *
 * Rather than re-fixture all 7 stores, this exercises the SHARED node they all
 * funnel through — [DeferredPrefs] — so a fix there covers the whole class (G2).
 *
 * ## The fixture that reproduces it (the non-happy state)
 * [CorruptScreenPrefsContext] throws on `getSharedPreferences(PREFS_NAME, ...)`
 * exactly as a corrupt XML parse would, until the file is DELETED. A happy
 * fixture can never enter the failing state, so it would prove nothing.
 *
 * RED on base: `DeferredPrefs.get()` rethrows the latched exception → RED.
 * GREEN with fix: the shared resilient helper catches the corrupt open,
 * best-effort deletes the file, re-opens a fresh handle, and `get()` returns a
 * usable prefs instance without throwing → GREEN.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeferredPrefsCorruptPrefsTest {

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
     * LOAD-BEARING (#1292 acceptance 2): a corrupt file in a [DeferredPrefs]-
     * backed screen store no longer crashes on Main at screen-open. On base
     * `get()` rethrows the latched corrupt-open exception (RED); with the shared
     * resilient helper it returns a usable prefs handle (GREEN).
     */
    @Test
    fun corrupt_screen_store_prefs_does_not_crash_on_get() {
        val corruptContext = CorruptScreenPrefsContext(realContext)

        val deferred = DeferredPrefs(corruptContext, PREFS_NAME)
        val prefs = deferred.get()

        // A usable handle came back (no crash) and reads a default cleanly.
        assertNull("recovered prefs must read a missing key as its default", prefs.getString("k", null))
        assertTrue(
            "the corrupt-prefs open must have been attempted at least once",
            corruptContext.throwingOpenAttempts.get() >= 1,
        )
    }

    /**
     * #1292 acceptance 2 (durable + writable recovery): after the corrupt open
     * recovers (best-effort delete + re-open), the returned handle is writable
     * and a fresh [DeferredPrefs] on the SAME cleared app data reads the value
     * back — proving the recovery is durable across screen re-open, not a
     * one-shot in-memory patch.
     */
    @Test
    fun recovery_is_durable_and_writable() {
        val corruptContext = CorruptScreenPrefsContext(realContext)

        val recovered = DeferredPrefs(corruptContext, PREFS_NAME).get()
        assertTrue(
            "the fix must best-effort delete the corrupt prefs file so recovery " +
                "is durable across screen re-open",
            corruptContext.wasDeleted,
        )
        recovered.edit().putString("k", "v").commit()

        val reopened = DeferredPrefs(corruptContext, PREFS_NAME).get()
        assertEquals("v", reopened.getString("k", null))
    }

    /**
     * Deterministic #1361 reproduction: the eager warm open observes the corrupt
     * file, then pauses before running recovery. The cold [DeferredPrefs.get]
     * path runs while that warm recovery is in flight, writes to the recovered
     * handle, and then the warm path is released. On the flaky shape this causes
     * two recovery deletes; the second delete can land after the write and make
     * the value disappear on screen re-open. The fixed implementation single-
     * flights the opener, so the cold path waits for the in-flight warm recovery
     * instead of starting a second delete.
     */
    @Test
    fun warm_recovery_delete_cannot_race_cold_write_and_reopen() {
        val corruptContext = RacingCorruptScreenPrefsContext(realContext)
        val warmDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "deferred-prefs-warm-test")
        }.asCoroutineDispatcher()

        try {
            val deferred = DeferredPrefs(corruptContext, PREFS_NAME, warmDispatcher)
            corruptContext.awaitFirstThrowingOpenStarted()

            val recoveredRef = AtomicReference<SharedPreferences?>()
            val failureRef = AtomicReference<Throwable?>()
            val coldGetThread = Thread(
                {
                    runCatching { deferred.get() }
                        .onSuccess { recoveredRef.set(it) }
                        .onFailure { failureRef.set(it) }
                },
                "deferred-prefs-cold-get-test",
            )
            coldGetThread.start()

            val coldPathStartedSecondRecovery =
                corruptContext.awaitSecondThrowingOpenOrColdGetBlocked(coldGetThread)
            if (coldPathStartedSecondRecovery) {
                coldGetThread.joinOrFail()
                failureRef.get()?.let { throw AssertionError("cold get failed", it) }
                recoveredRef.getOrFail()
                    .edit().putString("k", "v").commit()
                corruptContext.allowFirstCorruptOpenToRecover()
            } else {
                corruptContext.allowFirstCorruptOpenToRecover()
                coldGetThread.joinOrFail()
                failureRef.get()?.let { throw AssertionError("cold get failed", it) }
                recoveredRef.getOrFail()
                    .edit().putString("k", "v").commit()
            }

            deferred.awaitBuildThreadNameForTest()

            assertEquals(
                "DeferredPrefs must not let eager warm recovery run a second " +
                    "delete after the cold path has a writable recovered handle",
                1,
                corruptContext.deleteAttempts.get(),
            )

            val reopenedDeferred = DeferredPrefs(corruptContext, PREFS_NAME, warmDispatcher)
            val reopened = reopenedDeferred.get()
            reopenedDeferred.awaitBuildThreadNameForTest()
            assertEquals("v", reopened.getString("k", null))
        } finally {
            warmDispatcher.close()
        }
    }

    private fun clearPrefs() {
        realContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private class CorruptScreenPrefsContext(base: Context) : ContextWrapper(base) {
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

    private class RacingCorruptScreenPrefsContext(base: Context) : ContextWrapper(base) {
        @Volatile
        private var corrupt = true

        private val firstThrowingOpenStarted = CountDownLatch(1)
        private val firstThrowingOpenMayRecover = CountDownLatch(1)
        private val secondThrowingOpenStarted = CountDownLatch(1)

        val throwingOpenAttempts = AtomicInteger(0)
        val deleteAttempts = AtomicInteger(0)

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            if (name == PREFS_NAME && corrupt) {
                when (throwingOpenAttempts.incrementAndGet()) {
                    1 -> {
                        firstThrowingOpenStarted.countDown()
                        assertTrue(
                            "test did not release the paused warm corrupt open",
                            firstThrowingOpenMayRecover.await(5, TimeUnit.SECONDS),
                        )
                    }
                    2 -> secondThrowingOpenStarted.countDown()
                }
                throw RuntimeException(
                    "simulated corrupt $PREFS_NAME.xml: unexpected end of document",
                )
            }
            return super.getSharedPreferences(name, mode)
        }

        override fun deleteSharedPreferences(name: String?): Boolean {
            if (name == PREFS_NAME) {
                corrupt = false
                deleteAttempts.incrementAndGet()
            }
            return super.deleteSharedPreferences(name)
        }

        fun awaitFirstThrowingOpenStarted() {
            assertTrue(
                "warm open did not reach the corrupt prefs read",
                firstThrowingOpenStarted.await(5, TimeUnit.SECONDS),
            )
        }

        fun awaitSecondThrowingOpenOrColdGetBlocked(coldGetThread: Thread): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (secondThrowingOpenStarted.await(10, TimeUnit.MILLISECONDS)) return true
                if (coldGetThread.state == Thread.State.BLOCKED) return false
            }
            throw AssertionError(
                "cold get neither raced into a second corrupt open nor blocked " +
                    "behind the in-flight warm open",
            )
        }

        fun allowFirstCorruptOpenToRecover() {
            firstThrowingOpenMayRecover.countDown()
        }
    }

    private companion object {
        const val PREFS_NAME = "file_viewer_prefs"

        fun Thread.joinOrFail() {
            join(TimeUnit.SECONDS.toMillis(5))
            assertTrue("thread $name did not finish", !isAlive)
        }

        fun AtomicReference<SharedPreferences?>.getOrFail(): SharedPreferences =
            get() ?: throw AssertionError("DeferredPrefs.get did not return prefs")
    }
}
