package com.pocketshell.app.portfwd.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.portfwd.ForwardingController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deterministic regression for the late notification repost from issue #1933. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ForwardingServiceNotificationOrderingTest {

    @Test
    fun inFlightPositiveSnapshotCannotPublishAfterStopInvalidatesItsGeneration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()

        val controller = ForwardingController(context)
        controller.registerActiveHost(HOST_ID, "race-host")
        controller.updateActiveTunnels(HOST_ID, mapOf(2222 to 2222))

        val executor = Executors.newSingleThreadExecutor()
        val observeDispatcher = executor.asCoroutineDispatcher()
        val positivePublishReachedBoundary = CountDownLatch(1)
        val releasePositivePublish = CountDownLatch(1)
        val service = Robolectric.buildService(ForwardingService::class.java).get().apply {
            this.controller = controller
            this.observeDispatcher = observeDispatcher
            createNotificationChannel()
            beforeNotificationPublishForTest = {
                positivePublishReachedBoundary.countDown()
                check(releasePositivePublish.await(5, TimeUnit.SECONDS)) {
                    "test did not release the held positive notification publish"
                }
            }
        }

        try {
            service.onStartCommand(
                Intent(context, ForwardingService::class.java)
                    .setAction(ForwardingService.ACTION_START),
                0,
                1,
            )
            assertTrue(
                "production observer must reach the held positive publish boundary",
                positivePublishReachedBoundary.await(5, TimeUnit.SECONDS),
            )

            // The snapshot has already been computed on the observe dispatcher,
            // but has not entered the mutation authority. The real Stop action
            // reaches zero and removes the FGS notification first.
            service.onStartCommand(
                Intent(context, ForwardingService::class.java)
                    .setAction(ForwardingService.ACTION_STOP),
                0,
                2,
            )
            assertTrue(
                "real Stop path must empty the controller before the stale publish resumes",
                controller.activeHostIdsSnapshot().isEmpty(),
            )

            releasePositivePublish.countDown()
            // Single-thread executor barrier: the resumed collector has either
            // published or rejected the stale generation before this completes.
            executor.submit {}.get(5, TimeUnit.SECONDS)

            assertNull(
                "a positive snapshot computed before Stop must not repost the " +
                    "notification after zero/foreground removal",
                manager.activeNotifications.firstOrNull {
                    it.notification.extras.getCharSequence("android.title")
                        ?.toString()
                        ?.contains("Port forwarding running") == true
                },
            )
        } finally {
            releasePositivePublish.countDown()
            service.beforeNotificationPublishForTest = null
            service.onDestroy()
            observeDispatcher.close()
            executor.shutdownNow()
            manager.cancelAll()
        }
    }

    private companion object {
        const val HOST_ID = 1_933L
    }
}
