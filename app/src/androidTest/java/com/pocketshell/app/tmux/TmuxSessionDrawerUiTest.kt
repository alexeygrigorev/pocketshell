package com.pocketshell.app.tmux

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pocketshell.app.sessions.HostTmuxSessionPickerRequest
import com.pocketshell.app.sessions.HostTmuxSessionPickerState
import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.core.storage.entity.HostEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TmuxSessionDrawerUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun drawerShowsHostContextRowsAndActions() {
        val events = mutableListOf<String>()
        compose.setContent {
            TmuxSessionDrawer(
                visible = true,
                state = HostTmuxSessionPickerState.Ready(
                    request = request(),
                    rows = listOf(
                        HostTmuxSessionRow(
                            name = "codex",
                            createdAt = 1_000L,
                            lastActivity = 2_000L,
                            attached = true,
                        ),
                        HostTmuxSessionRow(
                            name = "fresh-work",
                            createdAt = 900L,
                            lastActivity = 1_500L,
                            attached = false,
                        ),
                    ),
                    message = null,
                ),
                hostName = "Docker Agent",
                currentSessionName = "codex",
                onRefresh = { events += "refresh" },
                onDismiss = { events += "dismiss" },
                onAttach = { events += "attach:$it" },
                onCreate = { events += "create" },
            )
        }

        compose.onNodeWithTag(TMUX_SESSION_SWITCHER_TAG).assertExists()
        compose.onNodeWithText("Docker Agent / codex").assertExists()
        compose.onNodeWithText("codex").assertExists()
        compose.onNodeWithText("current").assertExists()
        compose.onNodeWithText("fresh-work").assertExists()
        compose.onNodeWithText("available").assertExists()

        compose.onNodeWithText("fresh-work").performClick()
        compose.onNodeWithText("+ New tmux session (separate workspace)").performClick()
        compose.onNodeWithText("Refresh").performClick()
        compose.onNodeWithText("Close").performClick()

        assertEquals(
            listOf("attach:fresh-work", "create", "refresh", "dismiss"),
            events,
        )
    }

    @Test
    fun drawerKeepsUsableEmptyState() {
        var createClicked = false
        compose.setContent {
            TmuxSessionDrawer(
                visible = true,
                state = HostTmuxSessionPickerState.Ready(
                    request = request(),
                    rows = emptyList(),
                    message = "No tmux sessions found.",
                ),
                hostName = "Docker Agent",
                currentSessionName = "codex",
                onRefresh = {},
                onDismiss = {},
                onAttach = {},
                onCreate = { createClicked = true },
            )
        }

        compose.onNodeWithTag(TMUX_SESSION_SWITCHER_TAG).assertExists()
        compose.onNodeWithText("No tmux sessions found.").assertExists()
        compose.onNodeWithText("+ New tmux session (separate workspace)").performClick()

        assertTrue(createClicked)
    }

    private fun request(): HostTmuxSessionPickerRequest =
        HostTmuxSessionPickerRequest(
            host = HostEntity(
                id = 73L,
                name = "Docker Agent",
                hostname = "10.0.2.2",
                port = 2222,
                username = "testuser",
                keyId = 1L,
            ),
            keyPath = "/tmp/test_key",
            passphrase = null,
        )
}
