package com.pocketshell.app.systemsurfaces

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SystemSurfaceStateTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("system_surfaces", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun activeSessionCountTextFormatsZeroAndPlural() {
        assertEquals("0 active sessions", activeSessionCountText(0))
        assertEquals("2 active sessions", activeSessionCountText(2))
    }

    @Test
    fun activeSessionCountTextFormatsSingular() {
        assertEquals("1 active session", activeSessionCountText(1))
    }

    @Test
    fun activeSessionCountTextClampsNegativeCounts() {
        assertEquals("0 active sessions", activeSessionCountText(-3))
    }

    @Test
    fun bootForwardingMessageFormatsCounts() {
        assertEquals("No enabled forwarding hosts queued for restore", bootForwardingMessage(0))
        assertEquals("Forwarding restore pending for enabled hosts", bootForwardingMessage(1))
        assertEquals("Forwarding restore pending for 2 enabled hosts", bootForwardingMessage(2))
    }

    @Test
    fun bootForwardingMessageClampsNegativeCounts() {
        assertEquals("No enabled forwarding hosts queued for restore", bootForwardingMessage(-1))
    }

    @Test
    fun storeFallsBackWhenRestoredPrefsHaveWrongTypes() {
        context.getSharedPreferences("system_surfaces", Context.MODE_PRIVATE)
            .edit()
            .putString("active_session_count", "many")
            .putString("boot_forwarding_requested", "true")
            .putInt("boot_forwarding_message", 42)
            .commit()

        val store = SystemSurfaceStateStore(context)

        assertEquals(SessionWidgetState(activeSessionCount = 0), store.readSessionWidgetState())
        assertEquals(
            BootForwardingStatus(requested = false, lastMessage = null),
            store.readBootForwardingStatus(),
        )
    }
}
