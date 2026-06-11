package com.pocketshell.app.nav

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.shouldTrapSystemBack
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #520 — regression coverage for the navigator-level
 * [androidx.activity.compose.BackHandler] that `AppNavigator` registers so the
 * system/gesture Back button returns the user to the previous screen instead
 * of finishing the activity (which exited the app to the launcher on the
 * host-detail and terminal screens).
 *
 * `AppNavigator` itself is private and pulls a full Hilt graph, so this test
 * re-creates the exact back wiring it uses — a `BackHandler(enabled =
 * shouldTrapSystemBack(current)) { pop() }` over a small in-memory back stack —
 * and drives it through the activity's own
 * [androidx.activity.OnBackPressedDispatcher], the same dispatcher the
 * framework routes the system Back key through and that Compose's
 * `BackHandler` registers against. The production predicate
 * [shouldTrapSystemBack] is exercised directly, so the test stays in lockstep
 * with the navigator's enablement rule.
 */
@RunWith(AndroidJUnit4::class)
class NavigatorSystemBackTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun folderList() = AppDestination.FolderList(
        hostId = 1L,
        hostName = "dev",
        hostname = "host.example",
        port = 22,
        username = "user",
        keyPath = "/key",
        passphrase = null,
    )

    private fun session() = AppDestination.TmuxSession(
        hostId = 1L,
        hostName = "dev",
        hostname = "host.example",
        port = 22,
        username = "user",
        keyPath = "/key",
        passphrase = null,
        sessionName = "pocketshell",
    )

    /**
     * Mounts the same back wiring `AppNavigator` uses: a mutable back stack,
     * a `current` destination, and the navigator-level BackHandler gated by
     * [shouldTrapSystemBack]. Seeds the stack so a single Back press pops to
     * the previous destination.
     */
    private fun mountNavBack(
        initialStack: List<AppDestination>,
        initialCurrent: AppDestination,
    ): () -> AppDestination {
        var current by mutableStateOf(initialCurrent)
        val backStack = mutableStateListOf<AppDestination>().apply { addAll(initialStack) }
        compose.setContent {
            PocketShellTheme {
                BackHandler(enabled = shouldTrapSystemBack(current)) {
                    current = backStack.removeLastOrNull() ?: AppDestination.HostList
                }
            }
        }
        return { current }
    }

    private fun pressSystemBack() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    @Test
    fun systemBackOnHostDetailReturnsToHostList() {
        val readCurrent = mountNavBack(
            initialStack = listOf(AppDestination.HostList),
            initialCurrent = folderList(),
        )

        pressSystemBack()

        assertEquals(
            "system Back on host-detail must pop to the host list (not exit the app)",
            AppDestination.HostList,
            readCurrent(),
        )
    }

    @Test
    fun systemBackOnTerminalReturnsToPreviousScreen() {
        val readCurrent = mountNavBack(
            initialStack = listOf(AppDestination.HostList, folderList()),
            initialCurrent = session(),
        )

        pressSystemBack()

        assertEquals(
            "system Back on the terminal must pop to the previous screen (host-detail)",
            folderList(),
            readCurrent(),
        )
    }

    @Test
    fun systemBackOnRootHostListDoesNotPop() {
        // The navigator handler is disabled on the root, so the back press
        // is NOT consumed by it — `current` stays put. In production the
        // dispatcher then falls through to the Activity default (finish the
        // app), which is the intended root behaviour.
        val readCurrent = mountNavBack(
            initialStack = emptyList(),
            initialCurrent = AppDestination.HostList,
        )

        pressSystemBack()

        assertEquals(
            "Back on the root host list must not be trapped by the navigator handler",
            AppDestination.HostList,
            readCurrent(),
        )
    }
}
