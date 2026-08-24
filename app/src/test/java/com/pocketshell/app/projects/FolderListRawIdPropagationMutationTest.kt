package com.pocketshell.app.projects

import com.pocketshell.uikit.model.SessionAgentKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #2320's executable mutation fixture.
 *
 * These tests enter the production tmux-row parser plus
 * [FolderSessionRow] mapping. The mutation runner executes them from private
 * source copies and requires each independent wiring mutation to redden only
 * its own load-bearing assertion.
 */
class FolderListRawIdPropagationMutationTest {
    @Test
    fun rawIdSurvivesProductionRowMapping() {
        assertEquals(CUSTOM_RAW_ID, productionRow().recordedKindId)
    }

    @Test
    fun familyResolverIsUsedByProductionRowMapping() {
        assertEquals(SessionAgentKind.Codex, productionRow().recordedKind)
    }

    @Test
    fun unrelatedSessionIdentityStillParses() {
        val row = productionRow()
        assertEquals("custom-session", row.sessionName)
        assertEquals("\$7", row.tmuxSessionId)
        assertEquals("/srv/custom", row.cwd)
    }

    private fun productionRow(): FolderSessionRow =
        SshFolderListGateway.parsePocketshellSessionsTmuxRows(
            stdout = RAW_TMUX_ROW,
            familyForRawId = { rawId ->
                SessionAgentKind.Codex.takeIf { rawId == CUSTOM_RAW_ID }
            },
        ).orEmpty().single()

    private companion object {
        const val CUSTOM_RAW_ID = "custom-codex"
        const val RAW_TMUX_ROW =
            "\$7::custom-session::100::200::1::custom-codex::/srv/custom\n"
    }
}
