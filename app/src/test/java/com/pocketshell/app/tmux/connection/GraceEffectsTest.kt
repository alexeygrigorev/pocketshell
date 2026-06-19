package com.pocketshell.app.tmux.connection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EPIC #792 Slice B — focused unit pins for [GraceEffects], the single grace IO
 * dispatcher.
 *
 * These prove the dispatch contract that the hard-cut depends on: each grace trigger
 * routes to EXACTLY its one [GraceEffects.GraceIo] body, once, with no cross-talk — so
 * the driver seam and the lifecycle entrypoints can both route through this single owner
 * with the inline twin deleted (no dual-write).
 */
class GraceEffectsTest {

    private class RecordingGraceIo : GraceEffects.GraceIo {
        var detach = 0
        var reseed = 0
        var heal = 0

        override fun launchBackgroundDetachTeardown() {
            detach += 1
        }

        override fun launchForegroundReattachReseed() {
            reseed += 1
        }

        override fun launchForegroundHealWithinGrace() {
            heal += 1
        }
    }

    @Test
    fun onBackgrounded_dispatchesOnlyTheDetachTeardown() {
        val io = RecordingGraceIo()
        GraceEffects(io).onBackgrounded()

        assertEquals("detach fired exactly once", 1, io.detach)
        assertEquals("no reseed", 0, io.reseed)
        assertEquals("no heal", 0, io.heal)
    }

    @Test
    fun onForegroundReattachReseed_dispatchesOnlyTheReseed() {
        val io = RecordingGraceIo()
        GraceEffects(io).onForegroundReattachReseed()

        assertEquals("no detach", 0, io.detach)
        assertEquals("reseed fired exactly once", 1, io.reseed)
        assertEquals("no heal", 0, io.heal)
    }

    @Test
    fun onForegroundHealWithinGrace_dispatchesOnlyTheHeal() {
        val io = RecordingGraceIo()
        GraceEffects(io).onForegroundHealWithinGrace()

        assertEquals("no detach", 0, io.detach)
        assertEquals("no reseed", 0, io.reseed)
        assertEquals("heal fired exactly once", 1, io.heal)
    }

    @Test
    fun repeatedTriggers_eachDispatchExactlyOnceToItsOwnBody() {
        val io = RecordingGraceIo()
        val effects = GraceEffects(io)

        // A representative bg→within-grace-fg→re-bg sequence: each trigger maps to its
        // own body, the inline twin is gone so there is exactly one invocation per call.
        effects.onBackgrounded()
        effects.onForegroundReattachReseed()
        effects.onBackgrounded()
        effects.onForegroundHealWithinGrace()

        assertEquals(2, io.detach)
        assertEquals(1, io.reseed)
        assertEquals(1, io.heal)
    }
}
