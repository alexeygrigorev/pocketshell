package com.pocketshell.app.jobs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.components.KEBAB_BUTTON_TAG
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI proof for the recurring-jobs screen (audit #657 Gap-9, issue #723).
 *
 * `RecurringJobsScreen` previously had only unit coverage
 * ([RecurringJobsParserTest] / [RecurringJobsViewModelTest] /
 * `PocketshellJobsRemoteSourceTest`) — a `ListRow` / `JobEditorDialog`
 * rewire could silently break add / edit / remove with green unit tests.
 * This drives the real `RecurringJobsScreen` composable and pins:
 *
 *  - the list renders one row per job, carrying its detail title, the
 *    `session | every X | next Y` schedule subtitle, and the
 *    enabled/paused [com.pocketshell.uikit.components.Badge];
 *  - the primary "+ Job" action opens the create dialog and routes a
 *    [RecurringJobDraft];
 *  - the per-row [com.pocketshell.uikit.components.Kebab] Edit / Remove
 *    actions route to `onEdit` / `onRemove` with the right job id;
 *  - the empty and error states render their copy.
 *
 * This composable lives in `com.pocketshell.app.jobs` (app module), so the
 * ui-kit JVM render harness (`scripts/render.sh` / `DesignRenders.kt`)
 * cannot render it — it only composes ui-kit-level screens. This
 * instrumented Compose test is the right level for an app-only composable.
 */
@RunWith(AndroidJUnit4::class)
class RecurringJobsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun job(
        id: Int,
        enabled: Boolean,
        sessionName: String,
        every: String,
        nextRun: String,
        detail: String,
        source: RecurringJobSource = RecurringJobSource.Inline,
    ) = RecurringJob(
        id = id,
        enabled = enabled,
        sessionName = sessionName,
        every = every,
        enterDelayMs = null,
        source = source,
        nextRun = nextRun,
        detail = detail,
    )

    private fun screen(
        state: RecurringJobsScreenState,
        onAdd: (RecurringJobDraft) -> Unit = {},
        onEdit: (Int, RecurringJobDraft, Boolean) -> Unit = { _, _, _ -> },
        onRemove: (Int) -> Unit = {},
    ) {
        compose.setContent {
            PocketShellTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    RecurringJobsScreen(
                        state = state,
                        onBack = {},
                        onRefresh = {},
                        onAdd = onAdd,
                        onEdit = onEdit,
                        onRemove = onRemove,
                    )
                }
            }
        }
    }

    @Test
    fun rendersJobListWithDetailsScheduleAndStatusBadge() {
        screen(
            RecurringJobsScreenState(
                hostName = "agent-box",
                sessionName = "agent main",
                jobs = listOf(
                    job(
                        id = 7,
                        enabled = true,
                        sessionName = "agent main",
                        every = "15m",
                        nextRun = "12:30",
                        detail = "keep working",
                    ),
                    job(
                        id = 9,
                        enabled = false,
                        sessionName = "agent main",
                        every = "1h",
                        nextRun = "13:00",
                        detail = "summarise",
                    ),
                ),
            ),
        )

        // Header reflects the job count.
        compose.onNodeWithText("2 jobs").assertIsDisplayed()
        // Both job detail titles render.
        compose.onNodeWithText("keep working").assertIsDisplayed()
        compose.onNodeWithText("summarise").assertIsDisplayed()
        // The schedule subtitle for each job (session | every | next).
        compose.onNodeWithText("agent main | every 15m | next 12:30").assertIsDisplayed()
        compose.onNodeWithText("agent main | every 1h | next 13:00").assertIsDisplayed()
        // The enabled/paused badges (enabled job 7 -> Enabled; paused job 9 -> Paused).
        compose.onNodeWithText("Enabled").assertIsDisplayed()
        compose.onNodeWithText("Paused").assertIsDisplayed()
    }

    @Test
    fun primaryAddActionOpensDialogAndRoutesDraft() {
        var added: RecurringJobDraft? = null
        screen(
            RecurringJobsScreenState(
                hostName = "agent-box",
                sessionName = "agent main",
                jobs = emptyList(),
            ),
            onAdd = { added = it },
        )

        // Empty-state copy renders when there are no jobs.
        compose.onNodeWithText("No scheduled jobs").assertIsDisplayed()

        // The primary "+ Job" action opens the create dialog.
        compose.onNodeWithText("+ Job").performClick()
        compose.onNodeWithText("New job").assertIsDisplayed()

        // The Message field is the only initially-empty field (Session + Every
        // are pre-filled). performTextReplacement on the empty field fills it so
        // Save is enabled, then routes the draft.
        compose.onNodeWithText("Message").performTextReplacement("ship it")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertTrue("add should route a draft", added != null)
        assertEquals("agent main", added?.sessionName)
        assertEquals("15m", added?.every)
        assertEquals("ship it", added?.message)
    }

    @Test
    fun kebabEditAndRemoveRouteForTheRightJob() {
        var editedId: Int? = null
        var removedId: Int? = null
        screen(
            RecurringJobsScreenState(
                hostName = "agent-box",
                sessionName = "agent main",
                jobs = listOf(
                    job(
                        id = 7,
                        enabled = true,
                        sessionName = "agent main",
                        every = "15m",
                        nextRun = "12:30",
                        detail = "keep working",
                    ),
                ),
            ),
            onEdit = { id, _, _ -> editedId = id },
            onRemove = { id -> removedId = id },
        )

        // Open the only row's kebab and tap Remove -> routes onRemove(7).
        compose.onAllNodesWithTag(KEBAB_BUTTON_TAG)[0].performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()
        assertEquals(7, removedId)
        assertNull("edit should not fire on a Remove tap", editedId)

        // Re-open the kebab and tap Edit -> opens the editor dialog for job 7.
        compose.onAllNodesWithTag(KEBAB_BUTTON_TAG)[0].performClick()
        compose.onNodeWithText("Edit").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Edit job 7").assertIsDisplayed()
    }

    @Test
    fun errorStateRendersErrorCopy() {
        screen(
            RecurringJobsScreenState(
                hostName = "agent-box",
                sessionName = "agent main",
                jobs = emptyList(),
                error = "pocketshell jobs unavailable",
            ),
        )
        compose.onNodeWithText("pocketshell jobs unavailable").assertIsDisplayed()
    }
}
