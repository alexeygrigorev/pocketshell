package com.pocketshell.app.tmux

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.sessions.HostTmuxSessionPickerRequest
import com.pocketshell.app.sessions.HostTmuxSessionPickerState
import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.core.storage.entity.HostEntity
import java.io.File
import java.io.FileOutputStream
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
                            tmuxSessionId = "\$0",
                            createdAt = 1_000L,
                            lastActivity = 2_000L,
                            attached = true,
                        ),
                        HostTmuxSessionRow(
                            name = "fresh-work",
                            tmuxSessionId = "\$1",
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
                onAttach = { events += "attach:${it.sessionName}" },
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
        var attachedTarget: TmuxSessionNavigationTarget? = null
        compose.setContent {
            TmuxSessionDrawer(
                visible = true,
                state = HostTmuxSessionPickerState.Ready(
                    request = request(),
                    rows = listOf(
                        HostTmuxSessionRow(
                            name = "codex",
                            tmuxSessionId = "\$0",
                            createdAt = 1_000L,
                            lastActivity = 2_000L,
                            attached = true,
                        ),
                        HostTmuxSessionRow(
                            name = longName,
                            tmuxSessionId = "\$1",
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
                onAttach = { attachedTarget = it },
                onCreate = {},
            )
        }

        val actionTag = tmuxSessionDrawerActionTag(longName)
        compose.assertNodeFullyWithinRoot(actionTag, useUnmergedTree = true)

        // ListRow must allocate the fixed trailing slot before the title. The
        // full text remains in semantics for accessibility while its measured
        // visual bounds stop before the reachable Attach action.
        val titleBounds = compose
            .onNodeWithText(longName, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val attachBounds = compose
            .onNodeWithTag(actionTag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "Long session title must yield width to Attach: title=$titleBounds attach=$attachBounds",
            titleBounds.right <= attachBounds.left,
        )

        captureDrawer("issue-2002-long-session-attach.png")

        compose.onNodeWithText(longName)
            .assertHasClickAction()
            .performClick()

        assertEquals(
            TmuxSessionNavigationTarget(
                sessionName = longName,
                tmuxSessionId = "\$1",
                sessionCreated = 900L,
            ),
            attachedTarget,
        )
    }

    private fun captureDrawer(fileName: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
            val dir = File(mediaRoot, "additional_test_output/issue-2002-session-drawer")
            check(dir.exists() || dir.mkdirs()) {
                "Could not create #2002 screenshot dir: ${dir.absolutePath}"
            }
            val file = File(dir, fileName)
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write #2002 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE2002_SESSION_DRAWER_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
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
