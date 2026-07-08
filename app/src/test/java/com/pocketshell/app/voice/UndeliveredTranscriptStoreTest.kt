package com.pocketshell.app.voice

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [SharedPrefsUndeliveredTranscriptStore] and
 * [InMemoryUndeliveredTranscriptStore] — the durable holding pen for a
 * successfully-transcribed voice command whose text could not be delivered to a
 * pane (issue #1272).
 *
 * Covers the two #1272 gaps at the store layer:
 *  - **Durability**: a persisted transcript survives a process "restart" (a
 *    fresh store instance reads it back from disk) so it is genuinely
 *    recoverable, not silently lost.
 *  - **Bounding**: the queue is capped at [MAX_UNDELIVERED_TRANSCRIPTS]; rapid
 *    repeated persistence drops the oldest instead of growing without bound.
 *  - **Resilience**: a corrupt prefs file is opened through [ResilientPrefs] so
 *    the store never crashes the app (the #1292 hardening the brief calls for).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UndeliveredTranscriptStoreTest {

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

    // -- Durability -----------------------------------------------------------

    @Test
    fun persistedTranscriptSurvivesProcessRestart() {
        val store = SharedPrefsUndeliveredTranscriptStore(context)
        store.persist("git push origin main")
        store.persist("make deploy")

        // A fresh instance models a process restart reading from the same
        // SharedPreferences file.
        val relaunched = SharedPrefsUndeliveredTranscriptStore(context)
        val restored = relaunched.snapshot().map { it.text }
        assertEquals(
            "both undelivered transcripts must survive a restart",
            setOf("git push origin main", "make deploy"),
            restored.toSet(),
        )
        assertEquals(
            "the store surfaces the restored items via its items flow",
            restored.toSet(),
            relaunched.items.value.map { it.text }.toSet(),
        )
    }

    @Test
    fun removePersistsAcrossRestart() {
        val store = SharedPrefsUndeliveredTranscriptStore(context)
        val a = store.persist("cmd-a")!!
        store.persist("cmd-b")
        store.remove(a.id)

        val relaunched = SharedPrefsUndeliveredTranscriptStore(context)
        assertEquals(
            listOf("cmd-b"),
            relaunched.snapshot().map { it.text },
        )
    }

    @Test
    fun blankTranscriptIsNotPersisted() {
        val store = SharedPrefsUndeliveredTranscriptStore(context)
        assertNull(store.persist("   "))
        assertNull(store.persist(""))
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun itemsAreSurfacedNewestFirst() {
        val store = SharedPrefsUndeliveredTranscriptStore(context)
        store.persist("first")
        store.persist("second")
        store.persist("third")
        assertEquals(
            listOf("third", "second", "first"),
            store.snapshot().map { it.text },
        )
    }

    // -- Bounding (second #1272 gap) ------------------------------------------

    @Test
    fun queueIsBoundedAndDropsOldestBeyondCap() {
        val store = SharedPrefsUndeliveredTranscriptStore(context)
        val overflow = 5
        repeat(MAX_UNDELIVERED_TRANSCRIPTS + overflow) { store.persist("cmd-$it") }

        val snapshot = store.snapshot()
        assertEquals(
            "the queue must be capped, not grow unbounded",
            MAX_UNDELIVERED_TRANSCRIPTS,
            snapshot.size,
        )
        // The OLDEST `overflow` entries (cmd-0 .. cmd-4) are dropped.
        val texts = snapshot.map { it.text }.toSet()
        repeat(overflow) { assertFalse("cmd-$it (oldest) must be dropped", "cmd-$it" in texts) }
        assertTrue("the newest entry must be retained", "cmd-${MAX_UNDELIVERED_TRANSCRIPTS + overflow - 1}" in texts)
    }

    // -- Resilience (ResilientPrefs, #1292) -----------------------------------

    @Test
    fun corruptPrefsFileDoesNotCrashTheStore() {
        val corruptContext = CorruptContext(context)

        // Constructing the store opens the prefs file; a corrupt open must be
        // caught + recovered (ResilientPrefs) rather than propagating.
        val store = SharedPrefsUndeliveredTranscriptStore(corruptContext)
        assertTrue(
            "the corrupt open must have been attempted (guard against a vacuous pass)",
            corruptContext.throwingOpenAttempts.get() >= 1,
        )

        // The recovered store is usable: a persist round-trips.
        val item = store.persist("recovered command")
        assertEquals("recovered command", item?.text)
        assertEquals(listOf("recovered command"), store.snapshot().map { it.text })
    }

    // -- In-memory variant ----------------------------------------------------

    @Test
    fun inMemoryStorePersistExposeRemove() {
        val store = InMemoryUndeliveredTranscriptStore()
        val a = store.persist("alpha")!!
        store.persist("beta")
        assertEquals(listOf("beta", "alpha"), store.items.value.map { it.text })
        store.remove(a.id)
        assertEquals(listOf("beta"), store.items.value.map { it.text })
        assertNull(store.persist("  "))
    }

    private fun clearPrefs() {
        context.getSharedPreferences(SharedPrefsUndeliveredTranscriptStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    /**
     * A [ContextWrapper] that throws on the first
     * `getSharedPreferences(undelivered_transcripts, ...)` — mirroring a corrupt
     * XML parse — until the file is deleted, at which point [ResilientPrefs]'s
     * recovery re-opens cleanly.
     */
    private class CorruptContext(base: Context) : ContextWrapper(base) {
        @Volatile
        private var corrupt = true
        val throwingOpenAttempts = AtomicInteger(0)

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            if (name == SharedPrefsUndeliveredTranscriptStore.PREFS_NAME && corrupt) {
                throwingOpenAttempts.incrementAndGet()
                throw RuntimeException("simulated corrupt undelivered_transcripts.xml")
            }
            return super.getSharedPreferences(name, mode)
        }

        override fun deleteSharedPreferences(name: String?): Boolean {
            if (name == SharedPrefsUndeliveredTranscriptStore.PREFS_NAME) {
                corrupt = false
            }
            return super.deleteSharedPreferences(name)
        }
    }
}
