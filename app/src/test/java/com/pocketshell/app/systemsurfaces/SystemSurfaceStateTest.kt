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
    fun storeFallsBackWhenRestoredPrefsHaveWrongTypes() {
        context.getSharedPreferences("system_surfaces", Context.MODE_PRIVATE)
            .edit()
            .putString("active_session_count", "many")
            .commit()

        val store = SystemSurfaceStateStore(context)

        assertEquals(SessionWidgetState(activeSessionCount = 0), store.readSessionWidgetState())
    }
}
