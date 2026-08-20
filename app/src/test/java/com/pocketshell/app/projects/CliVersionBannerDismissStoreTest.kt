package com.pocketshell.app.projects

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #2033 — dismiss of the host-CLI banner must survive process death
 * until the (host, hostVersion, expectedVersion) triple changes.
 *
 * G6 mutation: if [CliVersionBannerDismissStore.dismiss] wrote only the
 * in-memory set and skipped prefs, `survivesNewStoreInstance` reddens.
 * If the key ignored expectedVersion, `differentExpectedVersion_isNotDismissed`
 * reddens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CliVersionBannerDismissStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(CliVersionBannerDismissStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun survivesNewStoreInstance() {
        val first = CliVersionBannerDismissStore.from(context)
        assertFalse(first.isDismissed(hostId = 42L, hostVersion = "0.4.39", expectedVersion = "0.4.40"))
        first.dismiss(hostId = 42L, hostVersion = "0.4.39", expectedVersion = "0.4.40")

        val second = CliVersionBannerDismissStore.from(context)
        assertTrue(
            "the same unpublished triple must stay dismissed across a new store " +
                "(process death / next launch)",
            second.isDismissed(hostId = 42L, hostVersion = "0.4.39", expectedVersion = "0.4.40"),
        )
    }

    @Test
    fun differentExpectedVersion_isNotDismissed() {
        val store = CliVersionBannerDismissStore.from(context)
        store.dismiss(hostId = 42L, hostVersion = "0.4.39", expectedVersion = "0.4.40")
        assertFalse(
            "an app bump (new expected version) must re-raise the banner",
            store.isDismissed(hostId = 42L, hostVersion = "0.4.39", expectedVersion = "0.4.41"),
        )
    }

    @Test
    fun differentHostVersion_isNotDismissed() {
        val store = CliVersionBannerDismissStore.from(context)
        store.dismiss(hostId = 42L, hostVersion = "0.4.39", expectedVersion = "0.4.40")
        assertFalse(
            "a host that actually upgraded must re-raise if it is still behind",
            store.isDismissed(hostId = 42L, hostVersion = "0.4.40", expectedVersion = "0.4.41"),
        )
    }
}
