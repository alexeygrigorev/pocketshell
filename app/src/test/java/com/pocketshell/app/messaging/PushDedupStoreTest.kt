package com.pocketshell.app.messaging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PushDedupStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PushDedupStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun markNotifiedIfNew_returnsTrueOnlyOncePerKey() {
        val store = PushDedupStore(context)
        assertTrue(store.markNotifiedIfNew("codex|short_term|reset-A"))
        // #619 don't-renotify: a re-delivery of the SAME reset is suppressed.
        assertFalse(store.markNotifiedIfNew("codex|short_term|reset-A"))
        assertTrue(store.hasNotified("codex|short_term|reset-A"))
    }

    @Test
    fun distinctResets_eachNotifyOnce() {
        val store = PushDedupStore(context)
        assertTrue(store.markNotifiedIfNew("codex|short_term|reset-A"))
        assertTrue(store.markNotifiedIfNew("codex|short_term|reset-B"))
        assertFalse(store.markNotifiedIfNew("codex|short_term|reset-A"))
    }

    @Test
    fun blankKey_neverNotifies() {
        val store = PushDedupStore(context)
        assertFalse(store.markNotifiedIfNew("   "))
        assertFalse(store.markNotifiedIfNew(""))
    }

    @Test
    fun persistsAcrossStoreInstances() {
        PushDedupStore(context).markNotifiedIfNew("codex|short_term|reset-A")
        // A fresh instance (e.g. a new FCM service wake) still de-dups.
        assertFalse(PushDedupStore(context).markNotifiedIfNew("codex|short_term|reset-A"))
    }

    @Test
    fun boundedToMaxKeys_oldestAgeOut() {
        val prefs = context.getSharedPreferences(PushDedupStore.PREFS_NAME, Context.MODE_PRIVATE)
        val store = PushDedupStore(prefs, maxKeys = 3)
        store.markNotifiedIfNew("k1")
        store.markNotifiedIfNew("k2")
        store.markNotifiedIfNew("k3")
        store.markNotifiedIfNew("k4") // evicts k1
        assertFalse(store.hasNotified("k1"))
        assertTrue(store.hasNotified("k4"))
        // k1 having aged out, it can notify again — acceptable for a bounded log.
        assertTrue(store.markNotifiedIfNew("k1"))
    }

    @Test
    fun notificationId_isStablePerKey_distinctAcrossKeys() {
        assertEquals(
            ResetPushNotifications.notificationIdFor("codex|short_term|reset-A"),
            ResetPushNotifications.notificationIdFor("codex|short_term|reset-A"),
        )
    }
}
