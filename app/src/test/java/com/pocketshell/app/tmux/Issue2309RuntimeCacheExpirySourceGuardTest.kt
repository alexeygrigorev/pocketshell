package com.pocketshell.app.tmux

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural guard for #2309's lifecycle ownership and D21 hard cut. */
class Issue2309RuntimeCacheExpirySourceGuardTest {

    @Test
    fun processLifecycleOwnsForegroundOnlyExpiryScheduling() {
        val app = source("app/src/main/java/com/pocketshell/app/App.kt")
        assertTrue(
            Regex(
                """Lifecycle\.Event\.ON_STOP[\s\S]{0,500}tmuxRuntimeCache\.onProcessBackgrounded\(\)""",
            ).containsMatchIn(app),
        )
        assertTrue(
            Regex(
                """Lifecycle\.Event\.ON_START[\s\S]{0,500}tmuxRuntimeCache\.onProcessForegrounded\(\)""",
            ).containsMatchIn(app),
        )
    }

    @Test
    fun cacheUsesGenerationOneShotsWithoutLegacyLazyOrBackgroundFacilities() {
        val cache = source(
            "app/src/main/java/com/pocketshell/app/tmux/TmuxSessionRuntimeCache.kt",
        )
        assertTrue(cache.contains("delay(delayMs)"))
        assertTrue(cache.contains("current?.generation != generation"))
        assertTrue(cache.contains("entry.expiryJob?.cancel()"))
        assertFalse(cache.contains("evictExpiredLocked"))
        listOf("WorkManager", "AlarmManager", "ScheduledExecutor", "java.util.Timer").forEach {
            forbidden -> assertFalse("D21 forbids $forbidden", cache.contains(forbidden))
        }
    }

    @Test
    fun expiryPublicationCannotBackpressureResourceTeardown() {
        val cache = source(
            "app/src/main/java/com/pocketshell/app/tmux/TmuxSessionRuntimeCache.kt",
        )
        val cleanup = cache.substringAfter("private suspend fun cleanupExpiry")
            .substringBefore("private fun recordCleanupDiagnostic")
        val closeIndex = cleanup.indexOf("claim.runtime.closeCachedRuntime()")
        val publishIndex = cleanup.indexOf("mutableExpiryClaims.emit")
        assertTrue("cleanup must close the exact runtime", closeIndex >= 0)
        assertTrue("cleanup must publish the exact ownership claim", publishIndex >= 0)
        assertTrue(
            "slow diagnostic/ownership subscribers must never delay bounded runtime teardown",
            closeIndex < publishIndex,
        )
    }

    @Test
    fun connectedAcceptanceCrossesInjectedTtlAndReturnsThroughRealSwitchPath() {
        val journey = source(
            "app/src/androidTest/java/com/pocketshell/app/proof/MultiSessionSwitchJourneyE2eTest.kt",
        )
        val method = journey.substringAfter(
            "fun parkedRuntimeCrossesInjectedTtlThenUserReturnFreshAttaches",
        ).substringBefore("Issue #1537 G10 deterministic stale-generation reproduction")
        assertTrue(method.contains("configureExpiryPolicyForTest"))
        assertTrue(method.contains("clock.addAndGet(ISSUE2309_CONNECTED_TTL_MS + 1L)"))
        assertTrue(method.contains("cache.containsExact(parkedBinding)"))
        assertTrue(method.contains("listTmuxClientsForSession(SESSION_A).isNotEmpty()"))
        assertTrue(method.contains("switchAndAssert(step = 2"))
        assertTrue(method.contains("freshRemoteClient.pid != oldRemoteClient.pid"))
    }

    private fun source(path: String): String {
        var cursor = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(cursor, path)
            if (candidate.isFile) return candidate.readText()
            cursor = cursor.parentFile ?: error("Cannot locate $path above $cursor")
        }
        error("Cannot locate $path from ${System.getProperty("user.dir")}")
    }
}
