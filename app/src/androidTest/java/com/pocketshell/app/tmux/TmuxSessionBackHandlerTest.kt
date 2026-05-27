package com.pocketshell.app.tmux

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #167 — regression coverage for [TmuxSessionBackHandler], the
 * `BackHandler` wired into [TmuxSessionScreen] so system-back returns
 * the user to the host list instead of finishing the activity.
 *
 * `createAndroidComposeRule<ComponentActivity>` hosts the composable
 * inside a real `ComponentActivity`, which is what provides the
 * `LocalOnBackPressedDispatcherOwner` that `BackHandler` reads at
 * composition time. The bare `createComposeRule()` host lacks that
 * owner and would crash on first composition.
 *
 * Back-press is dispatched via the activity's own
 * [androidx.activity.OnBackPressedDispatcher] rather than `Espresso`
 * because the dispatcher path is what the screen's `BackHandler`
 * actually intercepts in production — the framework routes the system
 * Back key through the same dispatcher that Compose's `BackHandler`
 * registers against. Driving it directly keeps the test deterministic
 * (no UI navigation / IME timing) while still exercising the exact
 * BackHandler registration the screen uses.
 *
 * Covered behaviour:
 *
 *  1. With no overlays open, system-back fires the screen's `onBack`
 *     callback (which in production pops the in-app back stack back to
 *     the host list — the user-visible acceptance criterion).
 *  2. With each transient overlay open, system-back dismisses that
 *     overlay first and does NOT call `onBack`. A second back-press
 *     (overlay now closed) then routes to `onBack`. This is the
 *     dialog-Cancel mirroring that keeps back from leaving the session
 *     prematurely while a sub-surface is open.
 *  3. The precedence between overlays is deterministic — if multiple
 *     overlays are flagged open in the same composition, the dialog
 *     handler runs first, then the session drawer, then the window
 *     switcher, then the mic sheet, then the snippet picker. This is
 *     the order the screen renders them and is the order this test
 *     locks in.
 *
 * "Session cleanly detached" (the third acceptance criterion in the
 * issue body) is exercised implicitly: when the in-app navigation pops
 * `TmuxSessionScreen` out of composition, the hosted
 * `TmuxSessionViewModel` is cleared and its `onCleared()` runs
 * `closeCurrentConnection()` (cancels coroutines, closes the SSH
 * transport, unregisters from `ActiveTmuxClients`). That teardown path
 * is already covered by `SessionKillDashboardE2eTest` and the
 * #151 teardown regression coverage; testing it again here would
 * require Docker + a live tmux client without exercising any new
 * behaviour. The reviewer's emulator + Docker journey is the
 * authoritative artifact for the "session detached" half of the
 * acceptance criteria.
 */
@RunWith(AndroidJUnit4::class)
class TmuxSessionBackHandlerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun systemBackInvokesOnBackWhenNoOverlayOpen() {
        var onBackCalls = 0
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxSessionBackHandler(
                    dialogOpen = false,
                    sessionDrawerOpen = false,
                    windowSwitcherOpen = false,
                    micSheetOpen = false,
                    snippetPickerOpen = false,
                    onDismissDialog = {},
                    onDismissSessionDrawer = {},
                    onDismissWindowSwitcher = {},
                    onDismissMicSheet = {},
                    onDismissSnippetPicker = {},
                    onBack = { onBackCalls += 1 },
                )
            }
        }

        pressSystemBack()

        assertEquals(
            "system-back must invoke onBack exactly once when no overlays are open",
            1,
            onBackCalls,
        )
    }

    @Test
    fun systemBackDismissesDialogBeforeOnBack() {
        var dialogOpen by mutableStateOf(true)
        var dismissCalls = 0
        var onBackCalls = 0
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxSessionBackHandler(
                    dialogOpen = dialogOpen,
                    sessionDrawerOpen = false,
                    windowSwitcherOpen = false,
                    micSheetOpen = false,
                    snippetPickerOpen = false,
                    onDismissDialog = {
                        dismissCalls += 1
                        dialogOpen = false
                    },
                    onDismissSessionDrawer = {},
                    onDismissWindowSwitcher = {},
                    onDismissMicSheet = {},
                    onDismissSnippetPicker = {},
                    onBack = { onBackCalls += 1 },
                )
            }
        }

        pressSystemBack()
        assertEquals("first back closes the dialog", 1, dismissCalls)
        assertEquals("first back must not call onBack while dialog was open", 0, onBackCalls)
        assertFalse("dismiss callback must flip the dialog state", dialogOpen)

        // Second back: with the dialog now closed, back must route to onBack.
        pressSystemBack()
        assertEquals(
            "second back (overlay gone) must invoke onBack",
            1,
            onBackCalls,
        )
        assertEquals(
            "dismiss callback must not fire again once the dialog is closed",
            1,
            dismissCalls,
        )
    }

    @Test
    fun systemBackDismissesSessionDrawerBeforeOnBack() {
        var drawerOpen by mutableStateOf(true)
        var dismissCalls = 0
        var onBackCalls = 0
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxSessionBackHandler(
                    dialogOpen = false,
                    sessionDrawerOpen = drawerOpen,
                    windowSwitcherOpen = false,
                    micSheetOpen = false,
                    snippetPickerOpen = false,
                    onDismissDialog = {},
                    onDismissSessionDrawer = {
                        dismissCalls += 1
                        drawerOpen = false
                    },
                    onDismissWindowSwitcher = {},
                    onDismissMicSheet = {},
                    onDismissSnippetPicker = {},
                    onBack = { onBackCalls += 1 },
                )
            }
        }

        pressSystemBack()
        assertEquals("first back closes the session drawer", 1, dismissCalls)
        assertEquals(0, onBackCalls)
        assertFalse(drawerOpen)

        pressSystemBack()
        assertEquals(1, onBackCalls)
    }

    @Test
    fun systemBackDismissesWindowSwitcherBeforeOnBack() {
        var switcherOpen by mutableStateOf(true)
        var dismissCalls = 0
        var onBackCalls = 0
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxSessionBackHandler(
                    dialogOpen = false,
                    sessionDrawerOpen = false,
                    windowSwitcherOpen = switcherOpen,
                    micSheetOpen = false,
                    snippetPickerOpen = false,
                    onDismissDialog = {},
                    onDismissSessionDrawer = {},
                    onDismissWindowSwitcher = {
                        dismissCalls += 1
                        switcherOpen = false
                    },
                    onDismissMicSheet = {},
                    onDismissSnippetPicker = {},
                    onBack = { onBackCalls += 1 },
                )
            }
        }

        pressSystemBack()
        assertEquals(1, dismissCalls)
        assertEquals(0, onBackCalls)

        pressSystemBack()
        assertEquals(1, onBackCalls)
    }

    @Test
    fun systemBackDismissesMicSheetBeforeOnBack() {
        var sheetOpen by mutableStateOf(true)
        var dismissCalls = 0
        var onBackCalls = 0
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxSessionBackHandler(
                    dialogOpen = false,
                    sessionDrawerOpen = false,
                    windowSwitcherOpen = false,
                    micSheetOpen = sheetOpen,
                    snippetPickerOpen = false,
                    onDismissDialog = {},
                    onDismissSessionDrawer = {},
                    onDismissWindowSwitcher = {},
                    onDismissMicSheet = {
                        dismissCalls += 1
                        sheetOpen = false
                    },
                    onDismissSnippetPicker = {},
                    onBack = { onBackCalls += 1 },
                )
            }
        }

        pressSystemBack()
        assertEquals(1, dismissCalls)
        assertEquals(0, onBackCalls)

        pressSystemBack()
        assertEquals(1, onBackCalls)
    }

    @Test
    fun systemBackDismissesSnippetPickerBeforeOnBack() {
        var pickerOpen by mutableStateOf(true)
        var dismissCalls = 0
        var onBackCalls = 0
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxSessionBackHandler(
                    dialogOpen = false,
                    sessionDrawerOpen = false,
                    windowSwitcherOpen = false,
                    micSheetOpen = false,
                    snippetPickerOpen = pickerOpen,
                    onDismissDialog = {},
                    onDismissSessionDrawer = {},
                    onDismissWindowSwitcher = {},
                    onDismissMicSheet = {},
                    onDismissSnippetPicker = {
                        dismissCalls += 1
                        pickerOpen = false
                    },
                    onBack = { onBackCalls += 1 },
                )
            }
        }

        pressSystemBack()
        assertEquals(1, dismissCalls)
        assertEquals(0, onBackCalls)

        pressSystemBack()
        assertEquals(1, onBackCalls)
    }

    @Test
    fun overlayPrecedenceFollowsDialogThenDrawerThenSwitcherThenSheetThenPicker() {
        // All five overlays are flagged open. The deterministic
        // dispatch order ([TmuxSessionBackHandler]) is dialog -> drawer
        // -> switcher -> mic sheet -> snippet picker. We feed back
        // presses one at a time and lower one flag per press, asserting
        // each step routed to the right handler and never to onBack.
        var dialogOpen by mutableStateOf(true)
        var drawerOpen by mutableStateOf(true)
        var switcherOpen by mutableStateOf(true)
        var sheetOpen by mutableStateOf(true)
        var pickerOpen by mutableStateOf(true)
        val dismissEvents = mutableListOf<String>()
        var onBackCalls = 0
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxSessionBackHandler(
                    dialogOpen = dialogOpen,
                    sessionDrawerOpen = drawerOpen,
                    windowSwitcherOpen = switcherOpen,
                    micSheetOpen = sheetOpen,
                    snippetPickerOpen = pickerOpen,
                    onDismissDialog = {
                        dismissEvents += "dialog"
                        dialogOpen = false
                    },
                    onDismissSessionDrawer = {
                        dismissEvents += "drawer"
                        drawerOpen = false
                    },
                    onDismissWindowSwitcher = {
                        dismissEvents += "switcher"
                        switcherOpen = false
                    },
                    onDismissMicSheet = {
                        dismissEvents += "sheet"
                        sheetOpen = false
                    },
                    onDismissSnippetPicker = {
                        dismissEvents += "picker"
                        pickerOpen = false
                    },
                    onBack = { onBackCalls += 1 },
                )
            }
        }

        pressSystemBack()
        pressSystemBack()
        pressSystemBack()
        pressSystemBack()
        pressSystemBack()

        assertEquals(
            "overlays must close in dialog -> drawer -> switcher -> sheet -> picker order",
            listOf("dialog", "drawer", "switcher", "sheet", "picker"),
            dismissEvents.toList(),
        )
        assertEquals(
            "onBack must not fire while any overlay is still flagged open",
            0,
            onBackCalls,
        )

        // Sixth back press: all overlays now closed, back routes to onBack.
        pressSystemBack()
        assertEquals(1, onBackCalls)
    }

    private fun pressSystemBack() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    @Test
    fun sanityComposablesMountWithoutCrashing() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxSessionBackHandler(
                    dialogOpen = false,
                    sessionDrawerOpen = false,
                    windowSwitcherOpen = false,
                    micSheetOpen = false,
                    snippetPickerOpen = false,
                    onDismissDialog = {},
                    onDismissSessionDrawer = {},
                    onDismissWindowSwitcher = {},
                    onDismissMicSheet = {},
                    onDismissSnippetPicker = {},
                    onBack = {},
                )
            }
        }
        // No content; the BackHandler registers against the activity's
        // dispatcher. The assertion is "no crash on composition".
        assertTrue(true)
    }
}
