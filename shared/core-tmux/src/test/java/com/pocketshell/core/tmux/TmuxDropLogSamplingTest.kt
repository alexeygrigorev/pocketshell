package com.pocketshell.core.tmux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxDropLogSamplingTest {

    @Test
    fun dropLogSamplingKeepsFirstDropAndPowersOfTwo() {
        listOf(1, 2, 4, 8, 16, 32, 64, 128).forEach { count ->
            assertTrue("expected count $count to be logged", shouldLogTmuxDropCount(count))
        }
    }

    @Test
    fun dropLogSamplingSkipsZeroNegativeAndNonPowersOfTwo() {
        listOf(-4, -1, 0, 3, 5, 6, 7, 9, 10, 12, 63, 127).forEach { count ->
            assertFalse("expected count $count to be skipped", shouldLogTmuxDropCount(count))
        }
    }
}
