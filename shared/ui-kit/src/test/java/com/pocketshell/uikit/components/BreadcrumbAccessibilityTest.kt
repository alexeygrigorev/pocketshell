package com.pocketshell.uikit.components

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.pocketshell.uikit.model.Crumb
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BreadcrumbAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun backAndMoreExposeLabelsAndAtLeast48DpTargets() {
        compose.setContent {
            PocketShellTheme {
                Breadcrumb(
                    crumbs = listOf(Crumb("host", isCurrent = true, onClick = {})),
                    onBack = {},
                    onMore = {},
                )
            }
        }

        listOf("Back", "More options").forEach { label ->
            val node = compose.onNodeWithContentDescription(label)
                .assertContentDescriptionEquals(label)
                .assertHasClickAction()
                .fetchSemanticsNode()
            val minimumPx = 48f * compose.density.density
            assertTrue("$label width was ${node.boundsInRoot.width}px", node.boundsInRoot.width >= minimumPx)
            assertTrue("$label height was ${node.boundsInRoot.height}px", node.boundsInRoot.height >= minimumPx)
        }
    }
}
