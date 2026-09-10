package com.pocketshell.next.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tick marks (issue #2633).
 *
 * The persistence test is not housekeeping. Push REPLACES the account's
 * content with the ticked set, so a selection that forgot itself across a
 * relaunch would turn the next "Sync now" into a silent wipe of every host on
 * every device. `docs/SYNC.md` calls that out as "the dangerous direction",
 * and it is the reason this store exists at all rather than the ticks living
 * in the ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SyncSelectionStoreTest {

    private lateinit var context: Context
    private val prefsFile = "test-sync-selection"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences(prefsFile)
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(prefsFile)
    }

    @Test
    fun `starts with nothing ticked`() {
        assertEquals(emptyList<String>(), store().selected.value)
    }

    @Test
    fun `ticking and unticking a host`() {
        val store = store()
        store.setChecked("hetzner", true)
        assertEquals(listOf("hetzner"), store.selected.value)
        store.setChecked("hetzner", false)
        assertEquals(emptyList<String>(), store.selected.value)
    }

    @Test
    fun `a tick survives a relaunch`() {
        // A forgotten selection would make the next push upload an empty set,
        // which the server stores — wiping the account.
        store().setChecked("hetzner", true)
        assertEquals(listOf("hetzner"), store().selected.value)
    }

    @Test
    fun `preserves order so the same selection produces the same payload order`() {
        val store = store()
        listOf("c", "a", "b").forEach { store.setChecked(it, true) }
        assertEquals(listOf("c", "a", "b"), store.selected.value)
        assertEquals(listOf("c", "a", "b"), store().selected.value)
    }

    @Test
    fun `ticking twice does not duplicate`() {
        val store = store()
        store.setChecked("hetzner", true)
        store.setChecked("hetzner", true)
        assertEquals(listOf("hetzner"), store.selected.value)
    }

    @Test
    fun `addAll appends only the aliases not already ticked`() {
        val store = store()
        store.setChecked("a", true)
        store.addAll(listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), store.selected.value)
    }

    @Test
    fun `a corrupt stored value degrades to nothing ticked`() {
        // The safe direction is the one that uploads LESS. Degrading to "all
        // ticked" would push hosts the user never agreed to sync.
        context.getSharedPreferences(prefsFile, Context.MODE_PRIVATE)
            .edit()
            .putString(SyncSelectionStore.KEY_SELECTED, "{not an array")
            .commit()
        assertEquals(emptyList<String>(), store().selected.value)
    }

    private fun store() = SyncSelectionStore(context, prefsFile)
}
