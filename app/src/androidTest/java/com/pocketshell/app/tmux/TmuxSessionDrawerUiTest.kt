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
        // Issue #156 (4.1): header stacks the title row over a
        // "<host> / <session>" subtitle row.
        compose.onNodeWithText("Tmux sessions").assertExists()
        compose.onNodeWithText("Docker Agent / codex").assertExists()
        // Issue #156 (4.2): the host-scoped actions group into a labelled
        // "Options" card; the sessions sit under "Available sessions".
        compose.onNodeWithText("Options").assertExists()
        compose.onNodeWithText("Available sessions").assertExists()
        compose.onNodeWithText("codex").assertExists()
        compose.onNodeWithText("current").assertExists()
        compose.onNodeWithText("fresh-work").assertExists()
        compose.onNodeWithText("available").assertExists()

        compose.onNodeWithText("fresh-work").performClick()
        compose.onNodeWithTag(TMUX_SESSION_DRAWER_CREATE_TAG).performClick()
        compose.onNodeWithTag(TMUX_SESSION_DRAWER_REFRESH_TAG).performClick()
        compose.onNodeWithTag(TMUX_SESSION_DRAWER_CLOSE_TAG).performClick()

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
        compose.onNodeWithTag(TMUX_SESSION_DRAWER_CREATE_TAG).performClick()

        assertTrue(createClicked)
    }

    @Test
    fun longSessionNameKeepsTrailingAttachVisibleAndRowClickable() {
        val longName = "feature/sync-audit-with-extra-long-branch-name-and-retry-investigation"
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
                            name = longName,
                            createdAt = 900L,
                            lastActivity = 1_500L,
                            attached = false,
                        ),
                    ),
                    message = null,
                ),
                hostName = "Docker Agent",
                currentSessionName = "codex",
                onRefresh = {},
                onDismiss = {},
                onAttach = { events += "attach:$it" },
                onCreate = {},
            )
        }

        compose.onNodeWithText("Attach").assertExists()
        compose.onNodeWithText(longName).performClick()

        assertEquals(listOf("attach:$longName"), events)
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
