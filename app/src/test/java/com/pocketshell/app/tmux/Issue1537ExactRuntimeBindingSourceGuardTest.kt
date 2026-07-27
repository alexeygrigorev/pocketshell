package com.pocketshell.app.tmux

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compile-free structural guard for the #1537 exact-runtime hard cut.
 *
 * The behavioral suites prove the race semantics. This guard keeps the two
 * lifecycle omissions that caused the recurrence from silently returning:
 * host/session-wide death removal and an untracked prewarm cache insertion.
 */
class Issue1537ExactRuntimeBindingSourceGuardTest {

    @Test
    fun parkedDeathUsesAtomicExactRemovalWithoutSessionWideFallback() {
        val source = source("app/src/main/java/com/pocketshell/app/tmux/ParkedRuntimeDeathHandler.kt")
        assertTrue(source.contains("runtimeCache.removeExact(signal.binding)"))
        assertFalse(source.contains("runtimeCache.removeSession("))
    }

    @Test
    fun bothActiveParkingAndPrewarmBindTheExactRuntime() {
        val source = source("app/src/main/java/com/pocketshell/app/tmux/TmuxSessionViewModel.kt")
        assertTrue(
            "active-runtime parking must bind its exact generated runtime identity",
            source.contains("parkedRuntimeHealthEffects.bindParkedRuntime(runtime)"),
        )
        assertTrue(
            "prewarm must use the same exact parked-runtime binding helper",
            Regex(
                """prewarmRuntime[\s\S]*parkedRuntimeHealthEffects\.bindParkedRuntime\(runtime\)""",
            ).containsMatchIn(source),
        )
    }

    @Test
    fun booleanDisconnectedCollectorIsHardCutInFavorOfTypedDisconnectEvent() {
        val source = source(
            "app/src/main/java/com/pocketshell/app/tmux/connection/ParkedRuntimeHealthEffects.kt",
        )
        assertTrue(source.contains("client.disconnectEvent.first"))
        assertTrue(source.contains("SelfInflictedClose.isSelfInflictedControlChannelClose"))
        assertFalse(source.contains("client.disconnected.first"))
    }

    @Test
    fun connectedG10FixtureCompilesAgainstTheCommonBaseSurface() {
        val source = source(
            "app/src/androidTest/java/com/pocketshell/app/proof/" +
                "MultiSessionSwitchJourneyE2eTest.kt",
        )
        assertTrue(source.contains("lateOldRuntimeRemoteDeathCannotEvictHealthySameSessionReplacement"))
        assertTrue(source.contains("it.hostId == currentHostId"))
        assertTrue(source.contains("cache.put(generation2)"))
        assertTrue(source.contains("TmuxClientFactory(replacementScope).create("))
        assertTrue(source.contains("#{client_pid}"))
        assertTrue(source.contains("ps -o ppid= -p"))
        assertTrue(source.contains("shellPid"))
        assertTrue(source.contains("kill -KILL"))
        assertFalse(source.contains("RuntimeInstanceToken"))
        assertFalse(source.contains("removeExact("))
        assertFalse(source.contains("handleParkedRuntimeDeath("))
        assertFalse(source.contains("healthBinding ="))
        assertFalse(source.contains("setParkedRuntimeDeathDeliveryPausedForTest"))
    }

    private fun source(path: String): String {
        var cursor = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val candidate = File(cursor, path)
            if (candidate.isFile) return candidate.readText()
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Cannot locate $path from ${System.getProperty("user.dir")}")
    }
}
