package com.pocketshell.next.terminal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The second half of issue #2483's fix: the [GraceService] seam itself.
 *
 * [GraceCoordinator.register] now retires the instance it replaces, so in
 * principle no stale owner is left to issue a stop. This asserts the property
 * that makes that safe rather than merely likely — a stop may only take down
 * the hold ITS caller opened. There is one [GraceService] per process and
 * `Context.stopService()` is unconditional, so without the token an owner whose
 * window ended can cancel a window opened by someone else, which is exactly the
 * "`isHolding == true`, no notification, no wake lock" state the `app2` journey
 * lane caught (CI run 33888824496).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AndroidGraceServiceControlTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `a stale owner's stop cannot take down a newer owner's hold`() {
        val stale = AndroidGraceServiceControl(application)
        val current = AndroidGraceServiceControl(application)

        stale.start(DEADLINE_MS)
        current.start(DEADLINE_MS + 1_000L)
        drainStartedServices()

        stale.stop()

        assertNull(
            "issue #2483: an owner whose window has ended must not stop the service " +
                "a later owner is holding",
            shadowOf(application).nextStoppedService,
        )

        // ...and the owner that DID open the live hold still can.
        current.stop()
        assertEquals(
            GraceService::class.java.name,
            shadowOf(application).nextStoppedService?.component?.className,
        )
    }

    @Test
    fun `the owner of the newest hold stops the service`() {
        val control = AndroidGraceServiceControl(application)

        control.start(DEADLINE_MS)
        assertNotNull(
            "the hold must really start the foreground service",
            shadowOf(application).nextStartedService,
        )

        control.stop()
        assertEquals(
            GraceService::class.java.name,
            shadowOf(application).nextStoppedService?.component?.className,
        )
    }

    @Test
    fun `stopping a control that never started anything is a no-op`() {
        val control = AndroidGraceServiceControl(application)

        control.stop()

        assertNull(
            "a control holding nothing must not take down whatever is running",
            shadowOf(application).nextStoppedService,
        )
    }

    @Test
    fun `a second stop from the same owner does not take down the next hold`() {
        val control = AndroidGraceServiceControl(application)
        control.start(DEADLINE_MS)
        drainStartedServices()
        control.stop()
        assertNotNull(shadowOf(application).nextStoppedService)

        // A duplicate stop (both lifecycle paths racing to end the same window)
        // must not survive as a pending cancel for the NEXT window.
        control.stop()
        val next = AndroidGraceServiceControl(application)
        next.start(DEADLINE_MS + 2_000L)
        drainStartedServices()

        assertNull(
            "a repeated stop must not take down a window opened afterwards",
            shadowOf(application).nextStoppedService,
        )
    }

    private fun drainStartedServices() {
        while (shadowOf(application).nextStartedService != null) {
            // Robolectric queues started services; clear it so the assertions
            // below are about what THIS step did.
        }
    }

    private companion object {
        const val DEADLINE_MS = 1_700_000_000_000L
    }
}
