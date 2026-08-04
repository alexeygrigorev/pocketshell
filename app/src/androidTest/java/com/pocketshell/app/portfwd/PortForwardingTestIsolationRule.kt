package com.pocketshell.app.portfwd

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.portfwd.service.ForwardingService
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.MultipleFailureException
import org.junit.runners.model.Statement

/**
 * Issue #1984: hard post-test isolation for journeys that can own real forwards.
 *
 * The cleanup is an outer rule rather than an `@After`: if the test body already
 * failed, a cleanup failure is attached as suppressed evidence and the original
 * causal assertion remains the test's primary throwable. The rule also disables
 * persisted auto-forward intent before stopping the singleton controller, so a
 * later activity `ON_START` cannot resurrect the just-cleaned tunnel.
 */
internal class PortForwardingTestIsolationRule(
    private val beforeStop: () -> Unit = {},
    private val afterStop: () -> Unit = {},
) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val primaryFailure = runCatching { base.evaluate() }.exceptionOrNull()
                val cleanupFailures = mutableListOf<Throwable>()

                runCatching { beforeStop() }.exceptionOrNull()?.let(cleanupFailures::add)
                runCatching { hardStopAndAssertZero(description) }
                    .exceptionOrNull()
                    ?.let(cleanupFailures::add)
                runCatching { afterStop() }.exceptionOrNull()?.let(cleanupFailures::add)

                val cleanupFailure = runCatching {
                    MultipleFailureException.assertEmpty(cleanupFailures)
                }.exceptionOrNull()
                if (primaryFailure != null) {
                    cleanupFailure?.let(primaryFailure::addSuppressed)
                    throw primaryFailure
                }
                cleanupFailure?.let { throw it }
            }
        }

    private fun hardStopAndAssertZero(description: Description) {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(
            context,
            TestAccessEntryPoint::class.java,
        )
        val controller = entryPoint.forwardingController()
        val cleanupFailures = mutableListOf<Throwable>()

        runBlocking {
            // The instrumentation process owns its app DB. Disable every persisted
            // auto-forward intent before stopping runtime state; otherwise a later
            // MainActivity ON_START can re-adopt the same real tunnel (#1984).
            runCatching {
                withTimeout(CLEANUP_TIMEOUT_MS) {
                    entryPoint.appDatabase().hostDao().getEnabled().first().forEach { host ->
                        entryPoint.appDatabase().hostDao().update(host.copy(enabled = false))
                    }
                }
            }.exceptionOrNull()?.let(cleanupFailures::add)

            // A persistence failure must not prevent the runtime stop attempt.
            runCatching {
                withTimeout(CLEANUP_TIMEOUT_MS) {
                    controller.stopAllForwarding()
                }
            }.exceptionOrNull()?.let(cleanupFailures::add)

            runCatching {
                withTimeout(CLEANUP_TIMEOUT_MS) {
                    var zeroSinceMs: Long? = null
                    while (true) {
                        val quiescent = controller.flowOfActiveHostCount().value == 0 &&
                            controller.activeHostIdsSnapshot().isEmpty() &&
                            !forwardingServiceRunningForTest(context) &&
                            !forwardingNotificationPresentForTest(context)
                        if (quiescent) {
                            val now = SystemClock.elapsedRealtime()
                            val since = zeroSinceMs ?: now.also { zeroSinceMs = it }
                            if (now - since >= QUIESCENT_STABLE_MS) break
                        } else {
                            zeroSinceMs = null
                        }
                        delay(POLL_INTERVAL_MS)
                    }
                }
            }.exceptionOrNull()?.let(cleanupFailures::add)
        }

        runCatching {
            check(controller.flowOfActiveHostCount().value == 0) {
                "post-test forwarding count must be zero for ${description.displayName}"
            }
            check(controller.activeHostIdsSnapshot().isEmpty()) {
                "post-test active forwards must be empty for ${description.displayName}: " +
                    controller.activeHostIdsSnapshot()
            }
            check(
                !forwardingServiceRunningForTest(context) &&
                    !forwardingNotificationPresentForTest(context),
            ) {
                "post-test forwarding service/notification must be absent for ${description.displayName}"
            }
        }.exceptionOrNull()?.let(cleanupFailures::add)
        MultipleFailureException.assertEmpty(cleanupFailures)

        DiagnosticEvents.record(
            "test",
            "port_forward_post_test_zero",
            "test" to description.displayName,
            "activeHostCount" to 0,
        )
        Log.i(TAG, "post-test-zero test=${description.displayName}")
    }

    private companion object {
        const val TAG: String = "PsPortForwardTestIsolation"
        const val CLEANUP_TIMEOUT_MS: Long = 15_000L
        const val QUIESCENT_STABLE_MS: Long = 1_000L
        const val POLL_INTERVAL_MS: Long = 50L
    }
}

@Suppress("DEPRECATION")
internal fun forwardingServiceRunningForTest(context: Context): Boolean =
    context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == ForwardingService::class.java.name }

internal fun forwardingNotificationPresentForTest(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java)
        .activeNotifications
        .any { notification ->
            notification.packageName == context.packageName &&
                notification.notification.channelId.startsWith("pocketshell_forwarding_status")
        }
