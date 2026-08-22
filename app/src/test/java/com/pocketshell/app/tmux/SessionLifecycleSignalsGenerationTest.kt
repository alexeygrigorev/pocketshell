package com.pocketshell.app.tmux

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionLifecycleSignalsGenerationTest {

    @Test
    fun incompleteKillIdentityEmitsReconcileHintInsteadOfDestructiveEvent() = runTest {
        val signals = SessionLifecycleSignals()
        val hint = async { signals.identityUncertain.first() }
        runCurrent()

        signals.emitKilled(
            hostId = 7L,
            generation = null,
            lastKnownName = "work",
        )

        assertEquals(
            SessionIdentityUncertain(
                hostId = 7L,
                lastKnownName = "work",
                folderPath = null,
                action = SessionLifecycleAction.Kill,
            ),
            hint.await(),
        )
    }

    @Test
    fun exactKillEventCarriesTheGeneration() = runTest {
        val signals = SessionLifecycleSignals()
        val generation = TmuxSessionGeneration("\$4", 1_710_000_000L)
        val killed = async { signals.killedSessions.first() }
        runCurrent()

        signals.emitKilled(
            hostId = 7L,
            generation = generation,
            lastKnownName = "work",
        )

        assertEquals(generation, killed.await().generation)
    }
}
