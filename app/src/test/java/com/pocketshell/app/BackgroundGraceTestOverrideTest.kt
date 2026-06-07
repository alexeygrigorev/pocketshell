package com.pocketshell.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackgroundGraceTestOverrideTest {

    @After
    fun resetOverride() {
        BackgroundGraceTestOverride.setForTest(null)
    }

    @Test
    fun `currentOr returns default when no override is installed`() {
        BackgroundGraceTestOverride.setForTest(null)

        assertEquals(60_000L, BackgroundGraceTestOverride.currentOr(60_000L))
    }

    @Test
    fun `currentOr returns installed test override until reset`() {
        BackgroundGraceTestOverride.setForTest(250L)

        assertEquals(250L, BackgroundGraceTestOverride.currentOr(60_000L))

        BackgroundGraceTestOverride.setForTest(null)

        assertEquals(60_000L, BackgroundGraceTestOverride.currentOr(60_000L))
    }

    @Test
    fun `override rejects negative durations`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundGraceTestOverride.setForTest(-1L)
        }
    }
}
