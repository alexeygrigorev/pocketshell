package com.pocketshell.app.systemsurfaces

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ActiveSessionsWidgetCallbackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun resetSeams() {
        ActiveSessionsWidgetProvider.callbackScope = CoroutineScope(Dispatchers.IO)
        ActiveSessionsWidgetProvider.stateReader = { ctx ->
            SystemSurfaceStateStore(ctx).readSessionWidgetState()
        }
    }

    @Test
    fun onUpdateReturnsBeforeBlockedPreferenceReadCompletes() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        ActiveSessionsWidgetProvider.callbackScope = CoroutineScope(Dispatchers.IO)
        ActiveSessionsWidgetProvider.stateReader = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            finished.countDown()
            SessionWidgetState(3)
        }

        ActiveSessionsWidgetProvider().onUpdate(
            context,
            AppWidgetManager.getInstance(context),
            intArrayOf(42),
        )

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertFalse("widget callback blocked on preference IO", finished.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
    }
}
