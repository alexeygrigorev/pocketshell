package com.pocketshell.app.session

import com.pocketshell.app.session.SessionViewModel.Modifier
import com.pocketshell.uikit.model.KeyModifierState
import org.junit.Assert.assertEquals
import org.junit.Test

class ArmedModifierStripTest {
    @Test
    fun armedModifierPillsUseCompactKeyLabelsAndPreserveState() {
        val pills = armedModifierPills(
            linkedMapOf(
                Modifier.Ctrl to KeyModifierState.OneShot,
                Modifier.Alt to KeyModifierState.Locked,
            ),
        )

        assertEquals(
            listOf(
                ArmedModifierPillUi("CTRL", KeyModifierState.OneShot),
                ArmedModifierPillUi("ALT", KeyModifierState.Locked),
            ),
            pills,
        )
    }

    @Test
    fun armedModifierPillsSkipOffState() {
        val pills = armedModifierPills(
            mapOf(Modifier.Ctrl to KeyModifierState.Off),
        )

        assertEquals(emptyList<ArmedModifierPillUi>(), pills)
    }
}
