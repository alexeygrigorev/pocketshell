package com.pocketshell.next.connect

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.pocketshell.next.hosts.hostRowTag
import com.pocketshell.next.terminal.SESSION_SCREEN_TAG
import com.pocketshell.next.tree.sessionRowTag
import com.pocketshell.next.workspaces.HOST_WORKSPACES_EMPTY_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_ERROR_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_LIST_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_LOADING_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_TAG
import com.pocketshell.next.workspaces.WORKSPACE_SCREEN_TAG
import com.pocketshell.next.workspaces.workspaceRowTag
import com.pocketshell.next.workspaces.workspaceSessionRowTag

/**
 * Device-test navigation for the Quiet host → workspace → session hierarchy.
 *
 * The production route no longer exposes the legacy session-tree screen. A
 * session whose cwd is exactly a root appears on the host screen; a session
 * below a root appears after opening that workspace row. Keeping that choice
 * here makes every terminal/composer journey assert the same real route.
 */
fun ComposeTestRule.openQuietHost(hostId: Long, timeoutMillis: Long = 60_000L) {
    awaitQuietTag(hostRowTag(hostId), timeoutMillis)
    onNodeWithTag(hostRowTag(hostId)).performClick()
    awaitQuietTag(HOST_WORKSPACES_TAG, timeoutMillis)
    waitUntil(timeoutMillis) {
        listOf(
            HOST_WORKSPACES_LIST_TAG,
            HOST_WORKSPACES_EMPTY_TAG,
            HOST_WORKSPACES_ERROR_TAG,
            HOST_WORKSPACES_LOADING_TAG,
        ).any { onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
    }
}

/** Opens a live session through the real Quiet hierarchy and waits for terminal chrome. */
fun ComposeTestRule.openQuietSession(
    hostId: Long,
    sessionName: String,
    workspacePath: String,
    timeoutMillis: Long = 60_000L,
) {
    openQuietHost(hostId, timeoutMillis)
    val rootSessionTag = workspaceSessionRowTag(sessionName)
    val workspaceTag = workspaceRowTag(workspacePath)
    try {
        waitUntil(timeoutMillis) {
            val rootVisible = onAllNodesWithTag(rootSessionTag).fetchSemanticsNodes().isNotEmpty()
            val workspaceVisible = onAllNodesWithTag(workspaceTag).fetchSemanticsNodes().isNotEmpty()
            if (rootVisible || workspaceVisible) return@waitUntil true

            // The host list is a real LazyColumn. A session may be below the
            // first viewport when another journey has left additional
            // workspaces on the fixture. Listing is asynchronous, so retry
            // after the list itself appears rather than racing the initial
            // loading state.
            if (onAllNodesWithTag(HOST_WORKSPACES_LIST_TAG).fetchSemanticsNodes().isNotEmpty()) {
                val list = onNodeWithTag(HOST_WORKSPACES_LIST_TAG)
                runCatching { list.performScrollToNode(hasTestTag(rootSessionTag)) }
                runCatching { list.performScrollToNode(hasTestTag(workspaceTag)) }
                // Keep a user-like fallback for older Compose semantics trees
                // that cannot match an off-screen test tag through the
                // lazy-list provider. Repeated swipes are bounded by
                // [timeoutMillis] and make the journey independent of
                // unrelated session count.
                runCatching { list.performTouchInput { swipeUp() } }
            }
            false
        }
    } catch (error: Throwable) {
        val listCount = onAllNodesWithTag(HOST_WORKSPACES_LIST_TAG)
            .fetchSemanticsNodes().size
        val rootCount = onAllNodesWithTag(rootSessionTag).fetchSemanticsNodes().size
        val workspaceCount = onAllNodesWithTag(workspaceTag).fetchSemanticsNodes().size
        println(
            "QUIET_NAV_TIMEOUT session=$sessionName workspace=$workspacePath " +
                "listNodes=$listCount rootNodes=$rootCount workspaceNodes=$workspaceCount",
        )
        runCatching {
            JourneyScreenshots.capture("quiet-navigation-timeout", "quiet-navigation")
        }.onSuccess { shot -> println("QUIET_NAV_SCREENSHOT ${shot.absolutePath}") }
        throw error
    }
    if (onAllNodesWithTag(rootSessionTag).fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithTag(rootSessionTag).performClick()
    } else {
        onNodeWithTag(workspaceTag).performClick()
        awaitQuietTag(WORKSPACE_SCREEN_TAG, timeoutMillis)
        awaitQuietTag(sessionRowTag(sessionName), timeoutMillis)
        onNodeWithTag(sessionRowTag(sessionName)).performClick()
    }
    awaitQuietTag(SESSION_SCREEN_TAG, timeoutMillis)
}

fun ComposeTestRule.awaitQuietTag(tag: String, timeoutMillis: Long = 60_000L) {
    waitUntil(timeoutMillis) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}
