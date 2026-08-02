package com.pocketshell.app.tmux

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.TerminalTestTimeouts
import com.pocketshell.app.proof.signals.FOREIGN_WINDOW_FOCUS_SIGNATURE
import com.pocketshell.app.proof.signals.awaitActivityWindowFocus
import com.pocketshell.app.proof.signals.waitForActivityWindowFocusLost
import com.pocketshell.app.proof.signals.waitForActivityWindowFocused
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.snippets.snippetSendChipTag
import com.pocketshell.app.voice.HOTKEYS_CHIP_TAG
import com.pocketshell.app.voice.SESSION_ADD_SNIPPET_CHIP_TAG
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.app.voice.SESSION_ENTER_CHIP_TAG
import com.pocketshell.app.voice.SHOW_KEYBOARD_CHIP_TAG
import com.pocketshell.app.voice.TERMINAL_HOTKEYS_ACCESSIBILITY_LABEL
import com.pocketshell.uikit.components.TERMINAL_HOTKEYS_PANEL_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SnippetEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #641 (reopened) — full-device E2E proving that every composer
 * control in the shell bottom bar is fully visible AND reachable in BOTH
 * keyboard states. This is the maintainer's exact reported scenario, run
 * on the real `TmuxSessionScreen` over the Docker `agents` fixture, with
 * the real soft IME — not an isolated component test.
 *
 * Two reported symptoms (see the reopen comment + screenshots on #641):
 *  1. Keyboard DOWN: a composer control is occluded/clipped behind the
 *     far-right composer launcher button. The round-1 fix capped the
 *     primary cluster width so the launcher is not clipped, but the
 *     rightmost cluster chip (`snippets`) was left half-clipped at the
 *     cap edge — sitting *behind/under* the launcher. We assert the
 *     `snippets` chip's right edge does not overlap the launcher's left
 *     edge (i.e. nothing hides behind the launcher).
 *  2. Keyboard UP: the maintainer reports composer action icons wedged
 *     between the terminal and the keyboard, unreachable. We raise the
 *     real IME via the `show keyboard` chip and assert the bottom accessory
 *     band's bottom edge sits at or above the IME inset's top — i.e. the
 *     whole accessory is above the keyboard, not under it.
 *
 * Both states are captured as full-device PNGs under
 * `<media>/additional_test_output/issue641-shell-composer-occlusion/` so a
 * reviewer can inspect the authoritative on-screen state.
 *
 * Modelled on [TmuxResizeSessionE2eTest]: seed a Docker host, attach to a
 * plain shell tmux session, land on the tmux session screen.
 */
@RunWith(AndroidJUnit4::class)
class TmuxShellComposerOcclusionE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var focusStealer: Dialog? = null
    private val summaryLines = mutableListOf<String>()
    private var imeRequestCount: Int = 0
    private var commandSnippetId: Long = 0
    private var commandWithEnterSnippetId: Long = 0

    @After
    fun cleanup() {
        dismissSyntheticFocusStealingWindow(requireFocusReturn = false)
        launchedActivity?.close()
        launchedActivity = null
        runBlocking {
            runCatching { cleanupSeededSessions(readFixtureKey()) }
        }
    }

    @Test
    fun shellComposerControlsAreVisibleAndReachableInBothKeyboardStates() { runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        seedShellSession(key)
        val hostRowTag = seedDockerHost(key, "Issue641 Shell Composer")

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // Host row -> picker -> attach to the seeded shell session.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(SESSION_LAB, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(SESSION_LAB).performClick()

        // Land on the tmux session screen with a live terminal grid.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 30_000) { terminalGridReady() }
        // Let the bottom band settle (chips/launcher measured).
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Issue #1748: the always-present #810 launcher is not proof that the
        // #1672 held surface has reached Live. The Show-keyboard chip exists
        // only in the Live command band, so await and contain that real
        // user-facing state before measuring the keyboard-down layout.
        waitForLiveKeyboardChip()
        compose.waitForIdle()

        // Issue #1754 fail-first oracle: query the complete unmerged semantics
        // tree. The four obsolete literals can be laid out in the horizontally
        // scrollable strip while fully offscreen, so a visible-only matcher
        // would produce a false green.
        FORBIDDEN_LITERAL_CHIPS.forEach { literal ->
            assertEquals(
                "the live shell strip must hard-delete the unconfigured '$literal' chip",
                0,
                compose.onAllNodesWithText(literal, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .size,
            )
        }

        // ---------------------------------------------------------------
        // SYMPTOM 1 — keyboard DOWN: nothing hides behind the launcher.
        // ---------------------------------------------------------------
        val launcherBounds = boundsInRoot(SESSION_COMPOSER_LAUNCHER_TAG)
        val rootBounds = rootBounds()
        summaryLines += "keyboard_down_root=$rootBounds"
        summaryLines += "keyboard_down_launcher=$launcherBounds"

        // The launcher must be fully inside the viewport horizontally
        // (round-1 regression: it was pushed off the right edge).
        assertTrue(
            "composer launcher must be fully on-screen (keyboard down); " +
                "launcher=$launcherBounds root=$rootBounds",
            launcherBounds.left >= rootBounds.left - 0.5f &&
                launcherBounds.right <= rootBounds.right + 0.5f,
        )

        // The `snippets` chip (the rightmost primary chip in the dogfood
        // 4-chip state) must NOT overlap the launcher — i.e. it cannot be
        // clipped behind / under the launcher button. This is the
        // "something hidden behind the compose button" symptom.
        val snippetsNodes = compose
            .onAllNodesWithTag(SESSION_ADD_SNIPPET_CHIP_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(
            "live shell keyboard-down band must expose exactly one snippets chip",
            1,
            snippetsNodes.size,
        )
        val snippetsBounds = compose.onNodeWithTag(
            SESSION_ADD_SNIPPET_CHIP_TAG,
            useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()
        val launcherUnclippedBounds = compose.onNodeWithTag(
            SESSION_COMPOSER_LAUNCHER_TAG,
            useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()
        val rootUnclippedBounds = compose.onNode(isRoot()).getUnclippedBoundsInRoot()
        summaryLines += "keyboard_down_snippets=$snippetsBounds"
        compose.onNodeWithTag(
            SESSION_ADD_SNIPPET_CHIP_TAG,
            useUnmergedTree = true,
        ).assertHasClickAction()
        assertTrue(
            "snippets chip must be fully contained and not clipped behind the launcher " +
                "(keyboard down); snippets=$snippetsBounds " +
                "launcher=$launcherUnclippedBounds root=$rootUnclippedBounds",
            snippetsBounds.left >= rootUnclippedBounds.left &&
                snippetsBounds.top >= rootUnclippedBounds.top &&
                snippetsBounds.right <= rootUnclippedBounds.right &&
                snippetsBounds.bottom <= rootUnclippedBounds.bottom &&
                snippetsBounds.right <= launcherUnclippedBounds.left,
        )
        listOf(
            SESSION_ENTER_CHIP_TAG,
            SHOW_KEYBOARD_CHIP_TAG,
            HOTKEYS_CHIP_TAG,
            SESSION_ADD_SNIPPET_CHIP_TAG,
        ).forEach { tag ->
            val control = compose.onNodeWithTag(tag, useUnmergedTree = true)
            val bounds = control.getUnclippedBoundsInRoot()
            summaryLines += "keyboard_down_primary[$tag]=$bounds"
            control.assertHasClickAction()
            assertTrue(
                "every keyboard-down primary control must be fully visible and " +
                    "clear of the launcher; tag=$tag bounds=$bounds " +
                    "launcher=$launcherUnclippedBounds root=$rootUnclippedBounds",
                bounds.left >= rootUnclippedBounds.left &&
                    bounds.top >= rootUnclippedBounds.top &&
                    bounds.right <= rootUnclippedBounds.right &&
                    bounds.bottom <= rootUnclippedBounds.bottom &&
                    bounds.right <= launcherUnclippedBounds.left,
            )
        }
        compose.onNodeWithTag(
            HOTKEYS_CHIP_TAG,
            useUnmergedTree = true,
        ).assertContentDescriptionEquals(TERMINAL_HOTKEYS_ACCESSIBILITY_LABEL)
        val hotkeysOnClick = compose.onNodeWithTag(
            HOTKEYS_CHIP_TAG,
            useUnmergedTree = true,
        ).fetchSemanticsNode().config[SemanticsActions.OnClick]
        assertEquals(
            "compact hotkeys button must announce its action",
            TERMINAL_HOTKEYS_ACCESSIBILITY_LABEL,
            hotkeysOnClick.label,
        )

        captureFullDevice("01-keyboard-down")
        captureTerminalViewport("01-keyboard-down")

        // The hard cut must not remove the independent host-scoped Command
        // snippet route. Plain Send writes the stored body byte-for-byte and
        // exactly once (without a line ending); the retained Enter control then
        // submits it. The authoritative remote side effect must be one byte.
        compose.onNodeWithTag(
            SESSION_ADD_SNIPPET_CHIP_TAG,
            useUnmergedTree = true,
        ).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(SNIPPET_LABEL, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(
            snippetSendChipTag(commandSnippetId, withEnter = false),
            useUnmergedTree = true,
        ).performClick()
        compose.waitForIdle()
        val beforeSubmitCapture = runSsh(
            key,
            "tmux capture-pane -p -t ${shellQuote(SESSION_LAB)}",
        )
        artifactFile("02-snippet-typed-remote-capture-pane.txt")
            .writeText(beforeSubmitCapture)
        assertEquals(
            "plain Send must place the exact stored body once in the real pane",
            1,
            beforeSubmitCapture.windowed(SNIPPET_BODY.length, 1)
                .count { it == SNIPPET_BODY },
        )
        assertEquals(
            "plain Send must not submit before the separate Enter action",
            0,
            remoteSideEffectBytes(key, SIDE_EFFECT_PATH),
        )
        compose.onNodeWithTag(
            SESSION_ENTER_CHIP_TAG,
            useUnmergedTree = true,
        ).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            runBlocking { remoteSideEffectBytes(key, SIDE_EFFECT_PATH) } == 1
        }
        assertRemoteSideEffectRemainsExactlyOnce(
            key = key,
            path = SIDE_EFFECT_PATH,
            label = "plain Send plus explicit Enter",
        )

        // Preserve the picker's second action too: Send+Enter applies the
        // existing CRLF/trailing-line-ending normalization and submits exactly
        // once without a separate Enter tap.
        compose.onNodeWithTag(
            SESSION_ADD_SNIPPET_CHIP_TAG,
            useUnmergedTree = true,
        ).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(SNIPPET_WITH_ENTER_LABEL, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(
            snippetSendChipTag(commandWithEnterSnippetId, withEnter = true),
            useUnmergedTree = true,
        ).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            runBlocking { remoteSideEffectBytes(key, SIDE_EFFECT_WITH_ENTER_PATH) } == 1
        }
        assertRemoteSideEffectRemainsExactlyOnce(
            key = key,
            path = SIDE_EFFECT_WITH_ENTER_PATH,
            label = "Send+Enter",
        )
        val afterSendWithEnterCapture = runSsh(
            key,
            "tmux capture-pane -p -t ${shellQuote(SESSION_LAB)}",
        )
        artifactFile("03-send-with-enter-remote-capture-pane.txt")
            .writeText(afterSendWithEnterCapture)
        assertEquals(
            "Send+Enter must normalize the stored CRLF body to one shell command",
            1,
            afterSendWithEnterCapture.windowed(SNIPPET_WITH_ENTER_COMMAND.length, 1)
                .count { it == SNIPPET_WITH_ENTER_COMMAND },
        )
        summaryLines += "forbidden_literal_nodes=0"
        summaryLines += "snippet_plain_send_exact_body_occurrences=1"
        summaryLines += "snippet_remote_side_effect_bytes=1"
        summaryLines += "snippet_send_with_enter_normalized_occurrences=1"
        summaryLines += "snippet_send_with_enter_remote_side_effect_bytes=1"
        captureFullDevice("03-snippet-dispatched")
        captureTerminalViewport("03-snippet-dispatched")
        awaitTestOpenedSnippetPickerDismissed()

        // ---------------------------------------------------------------
        // SYMPTOM 2 — keyboard UP: accessory band is above the keyboard.
        // ---------------------------------------------------------------
        // Issue #1942 fail-first reproduction: CI twice reached this exact
        // point with a Pixel Launcher ANR dialog holding window focus. The old
        // oracle burned the entire IME budget and reported only a generic
        // ComposeTimeoutException. Reproduce the same window geometry with a
        // non-cancelable focus owner and require a causal diagnosis; after it
        // is removed, the original real-IME journey still has to pass.
        raiseSyntheticFocusStealingWindow()
        val focusStolen = waitForActivityWindowFocusLost(
            scenario = requireNotNull(launchedActivity),
            timeoutMs = WINDOW_FOCUS_TIMEOUT_MS,
        )
        assertTrue("synthetic #1942 focus owner must take window focus", focusStolen)
        val focusFailure = runCatching { waitForRealImeAfterShowKeyboard() }.exceptionOrNull()
        assertTrue(
            "#1942 must name the foreign focus owner instead of timing out generically; " +
                "failure=${focusFailure?.message}",
            focusFailure?.message.orEmpty().contains(FOREIGN_WINDOW_FOCUS_SIGNATURE),
        )
        assertTrue(
            "the focus oracle must observe only; it must not dismiss the obstructing window",
            focusStealer?.isShowing == true,
        )
        captureFullDevice("04-synthetic-focus-owner")
        dismissSyntheticFocusStealingWindow(requireFocusReturn = true)

        // Raise the real soft IME exactly as the user does — tap the
        // `show keyboard` chip, which calls showTerminalSoftKeyboard().
        var imeTopPx = waitForRealImeAfterShowKeyboard()
        compose.waitForIdle()
        SystemClock.sleep(500)
        imeTopPx = imeInsetTopOnScreenPx()
        assertImeUpTerminalSurface()
        assertImeHotkeysLauncherReachableAbove(imeTopPx)
        val rootBoundsKbUp = rootBounds()
        summaryLines += "keyboard_up_ime_top_px=$imeTopPx"
        summaryLines += "keyboard_up_root=$rootBoundsKbUp"

        // Issue #784 (D22 hard-cut): the terminal hotkey grid no longer lives on
        // the raw terminal surface OR in the composer (where #755 had wedged it).
        // It is the dedicated `TerminalHotkeysPanel` bottom sheet, opened on
        // demand from the launcher. So raising the raw terminal IME directly (the
        // `show keyboard` chip) never surfaces a key grid wedged under the
        // keyboard. Here we assert the hard-cut: no hotkeys-grid panel is laid out
        // on the terminal surface with the IME up.
        val keyBarBottomScreenPx = bottomEdgeOnScreenPx(TERMINAL_HOTKEYS_PANEL_TAG)
        summaryLines += "keyboard_up_keybar_present=${keyBarBottomScreenPx >= 0}"

        // Capture + persist the authoritative on-screen state BEFORE the
        // assertions so the artifacts exist regardless of the outcome.
        captureFullDevice("02-keyboard-up")
        writeSummary()

        assertTrue(
            "After #784 the hotkeys grid is NOT on the raw terminal surface; it " +
                "is the dedicated panel sheet. keyBarBottomScreenPx=$keyBarBottomScreenPx",
            keyBarBottomScreenPx < 0,
        )
        Unit
    } }

    // ---------------------------------------------------------------- IME insets

    private fun waitForRealImeAfterShowKeyboard(): Int {
        val request = ++imeRequestCount
        val scenario = requireNotNull(launchedActivity)
        val focus = awaitActivityWindowFocus(
            scenario = scenario,
            timeoutMs = WINDOW_FOCUS_TIMEOUT_MS,
        )
        summaryLines += "ime_request$request.app_window_focused_before_tap=${focus.focused}"
        summaryLines += "ime_request$request.window_focus_before_tap=${focus.diagnosis}"
        if (!focus.focused) {
            throw AssertionError(
                "$FOREIGN_WINDOW_FOCUS_SIGNATURE The shell-composer IME geometry " +
                    "cannot be measured in that state (request $request): " +
                    "${focus.diagnosis}.",
            )
        }

        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG, useUnmergedTree = true).performClick()
        var imeTopPx = -1
        var lastKeyboardRequestAt = 0L
        try {
            compose.waitUntil(timeoutMillis = IME_VISIBILITY_TIMEOUT_MS) {
                imeTopPx = imeInsetTopOnScreenPx()
                val now = SystemClock.elapsedRealtime()
                if (imeTopPx <= 0 && now - lastKeyboardRequestAt >= KEYBOARD_REQUEST_RETRY_MS) {
                    compose.onNodeWithTag(
                        SHOW_KEYBOARD_CHIP_TAG,
                        useUnmergedTree = true,
                    ).performClick()
                    lastKeyboardRequestAt = now
                }
                imeTopPx in 1..Int.MAX_VALUE
            }
        } catch (cause: Throwable) {
            val lateFocus = awaitActivityWindowFocus(scenario = scenario, timeoutMs = 0L)
            summaryLines += "ime_request$request.window_focus_after_timeout=${lateFocus.diagnosis}"
            if (!lateFocus.focused) {
                throw AssertionError(
                    "$FOREIGN_WINDOW_FOCUS_SIGNATURE Focus was lost after the " +
                        "shell-composer pre-condition check (request $request): " +
                        "${lateFocus.diagnosis}.",
                    cause,
                )
            }
            throw cause
        }
        summaryLines += "ime_request$request.ime_visible=true"
        return imeTopPx
    }

    private fun awaitTestOpenedSnippetPickerDismissed() {
        // Sending a snippet already calls the picker's production onDismiss.
        // Its ModalBottomSheet window releases focus asynchronously, however;
        // do not drive a hidden terminal control while that transition still
        // owns input focus (the exact sequencing error exposed by #1942).
        compose.waitUntil(timeoutMillis = WINDOW_FOCUS_TIMEOUT_MS) {
            compose.onAllNodesWithText("Search snippets…", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        val focus = awaitActivityWindowFocus(
            scenario = requireNotNull(launchedActivity),
            timeoutMs = WINDOW_FOCUS_TIMEOUT_MS,
        )
        summaryLines += "snippet_picker_dismissed=true"
        summaryLines += "snippet_picker_dismiss_focus=${focus.diagnosis}"
        assertTrue(
            "the sent-snippet modal must release input focus before the " +
                "keyboard-up shell-composer phase: ${focus.diagnosis}",
            focus.focused,
        )
    }

    private fun raiseSyntheticFocusStealingWindow() {
        requireNotNull(launchedActivity).onActivity { activity ->
            val dialog = Dialog(activity)
            dialog.setContentView(TextView(activity).apply {
                text = "issue-1942 synthetic focus owner"
                isFocusable = true
                isFocusableInTouchMode = true
            })
            dialog.setCancelable(false)
            dialog.setOnDismissListener { focusStealer = null }
            dialog.show()
            focusStealer = dialog
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun dismissSyntheticFocusStealingWindow(requireFocusReturn: Boolean) {
        val dialog = focusStealer ?: return
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (dialog.isShowing) dialog.dismiss()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        if (requireFocusReturn) {
            assertTrue(
                "PocketShell must regain focus after the synthetic #1942 owner is removed",
                waitForActivityWindowFocused(
                    scenario = requireNotNull(launchedActivity),
                    timeoutMs = WINDOW_FOCUS_TIMEOUT_MS,
                ),
            )
        }
    }

    /**
     * Top Y (screen pixels) of the soft IME inset. Returns -1 when the
     * keyboard is hidden. Read off the activity's root insets so it
     * reflects the REAL keyboard, not a simulated value.
     */
    private fun imeInsetTopOnScreenPx(): Int {
        var top = -1
        launchedActivity?.onActivity { activity ->
            val root = activity.window.decorView
            val insets = WindowInsetsCompat.toWindowInsetsCompat(root.rootWindowInsets, root)
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            if (ime.bottom > 0) {
                top = root.height - ime.bottom
            }
        }
        return top
    }

    /**
     * Bottom Y (screen pixels) of the view tagged [tag]. Converts the
     * root-relative Compose bounds to screen coordinates using the compose
     * root view's location on screen. Returns -1 when absent.
     */
    private fun bottomEdgeOnScreenPx(tag: String): Int {
        val nodes = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        if (nodes.isEmpty()) return -1
        val bottomInRoot = nodes.first().boundsInRoot.bottom
        var rootTopOnScreen = 0
        launchedActivity?.onActivity { activity ->
            val composeRoot = activity.window.decorView.findComposeRoot()
            if (composeRoot != null) {
                val loc = IntArray(2)
                composeRoot.getLocationOnScreen(loc)
                rootTopOnScreen = loc[1]
            }
        }
        return rootTopOnScreen + bottomInRoot.toInt()
    }

    private fun View.findComposeRoot(): View? {
        // The compose hierarchy is hosted by an AndroidComposeView; we
        // approximate "the compose root" as the top-most view that hosts
        // the decor content. Using the decor's content view location is
        // sufficient because the compose surface fills it.
        return this
    }

    private fun boundsInRoot(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    private fun rootBounds(): Rect {
        // Use the launcher's window-level root: any node's boundsInRoot is
        // relative to the same root, and the compose root fills the screen
        // width, so we read the root via the screen tag node.
        return compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
    }

    private fun terminalGridReady(): Boolean {
        var ready = false
        launchedActivity?.onActivity { activity ->
            ready = activity.window.decorView.findTerminalView()?.currentSession?.emulator != null
        }
        return ready
    }

    private fun assertImeUpTerminalSurface() {
        fun state(): Triple<Int, Int, Boolean> {
            val conversationLoadingCount = compose
                .onAllNodesWithText("Loading conversation…", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size
            val hotkeysLauncherCount = compose
                .onAllNodesWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size
            var terminalVisible = false
            launchedActivity?.onActivity { activity ->
                val terminal = activity.window.decorView.findTerminalView()
                terminalVisible =
                    terminal?.isShown == true &&
                    terminal.visibility == View.VISIBLE &&
                    terminal.width > 0 &&
                    terminal.height > 0
            }
            return Triple(conversationLoadingCount, hotkeysLauncherCount, terminalVisible)
        }

        try {
            compose.waitUntil(timeoutMillis = 15_000) {
                val (conversationLoadingCount, hotkeysLauncherCount, terminalVisible) = state()
                conversationLoadingCount == 0 &&
                    hotkeysLauncherCount == 1 &&
                    terminalVisible
            }
        } catch (cause: Throwable) {
            val (conversationLoadingCount, hotkeysLauncherCount, terminalVisible) = state()
            throw AssertionError(
                "IME-up assertions must remain on the same visible Terminal surface. " +
                    "loadingConversationNodes=$conversationLoadingCount " +
                    "terminalHotkeysLauncherNodes=$hotkeysLauncherCount " +
                    "terminalVisible=$terminalVisible",
                cause,
            )
        }
        val (conversationLoadingCount, hotkeysLauncherCount, terminalVisible) = state()
        assertTrue(
            "IME-up assertions must remain on the same visible Terminal surface. " +
                "loadingConversationNodes=$conversationLoadingCount " +
                "terminalHotkeysLauncherNodes=$hotkeysLauncherCount " +
                "terminalVisible=$terminalVisible",
            conversationLoadingCount == 0 &&
                hotkeysLauncherCount == 1 &&
                terminalVisible,
        )
        summaryLines += "keyboard_up_same_terminal_surface=true"
    }

    private fun assertImeHotkeysLauncherReachableAbove(imeTopPx: Int) {
        val nodes = compose
            .onAllNodesWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(
            "Keyboard-up Terminal surface must expose exactly one compact hotkeys " +
                "launcher; count=${nodes.size}",
            nodes.size == 1,
        )
        compose.onNodeWithTag(
            TERMINAL_HOTKEYS_LAUNCHER_TAG,
            useUnmergedTree = true,
        ).assertHasClickAction()

        val node = nodes.single()
        val owningRoot = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single { it.root === node.root }
        val bounds = node.boundsInRoot
        val rootBounds = owningRoot.boundsInRoot
        assertTrue(
            "Compact hotkeys launcher must be fully contained in its owning root. " +
                "launcher=$bounds root=$rootBounds",
            bounds.left >= rootBounds.left - ROOT_SLOP_PX &&
                bounds.top >= rootBounds.top - ROOT_SLOP_PX &&
                bounds.right <= rootBounds.right + ROOT_SLOP_PX &&
                bounds.bottom <= rootBounds.bottom + ROOT_SLOP_PX,
        )

        val launcherBottomScreenPx = bottomEdgeOnScreenPx(TERMINAL_HOTKEYS_LAUNCHER_TAG)
        summaryLines += "keyboard_up_hotkeys_launcher_count=${nodes.size}"
        summaryLines += "keyboard_up_hotkeys_launcher_bounds=$bounds"
        summaryLines += "keyboard_up_hotkeys_launcher_bottom_screen_px=$launcherBottomScreenPx"
        assertTrue(
            "Compact hotkeys launcher must remain fully above the real IME and " +
                "clickable. launcherBottom=$launcherBottomScreenPx imeTop=$imeTopPx",
            launcherBottomScreenPx >= 0 &&
                launcherBottomScreenPx <= imeTopPx + ROOT_SLOP_PX.toInt(),
        )
    }

    private fun waitForLiveKeyboardChip() {
        try {
            compose.waitUntil(
                timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs(),
            ) {
                liveKeyboardChipFullyWithinOwningRoot()
            }
            assertLiveKeyboardChipFullyWithinOwningRoot()
            summaryLines += "live_band_ready=true"
        } catch (cause: Throwable) {
            val screenCount = semanticsNodeCount(TMUX_SESSION_SCREEN_TAG)
            val launcherCount = semanticsNodeCount(SESSION_COMPOSER_LAUNCHER_TAG)
            val keyboardChipCount = semanticsNodeCount(SHOW_KEYBOARD_CHIP_TAG)
            val terminalReady = terminalGridReady()
            val connection = currentConnectionDiagnostics()
            summaryLines += "live_band_ready=false"
            summaryLines += "live_band_screen_nodes=$screenCount"
            summaryLines += "live_band_launcher_nodes=$launcherCount"
            summaryLines += "live_band_keyboard_chip_nodes=$keyboardChipCount"
            summaryLines += "live_band_terminal_ready=$terminalReady"
            summaryLines += "live_band_connection=$connection"
            val screenshotFailure = runCatching {
                captureFullDevice("00-live-band-timeout")
            }.exceptionOrNull()
            summaryLines += "live_band_timeout_screenshot_saved=${screenshotFailure == null}"
            screenshotFailure?.let {
                summaryLines += "live_band_timeout_screenshot_error=${it.message}"
            }
            val summaryFailure = runCatching {
                writeSummary(liveTimeoutScreenshotSaved = screenshotFailure == null)
            }.exceptionOrNull()
            throw AssertionError(
                "Timed out waiting for the contained Live-only Show-keyboard chip. " +
                    "screenNodes=$screenCount launcherNodes=$launcherCount " +
                    "keyboardChipNodes=$keyboardChipCount terminalReady=$terminalReady " +
                    "connection=$connection screenshotFailure=${screenshotFailure?.message} " +
                    "summaryFailure=${summaryFailure?.message}. " +
                    "The composer launcher alone is not a Live oracle (#1672/#1748).",
                cause,
            )
        }
    }

    private fun liveKeyboardChipFullyWithinOwningRoot(): Boolean =
        runCatching {
            val node = compose.onAllNodesWithTag(
                SHOW_KEYBOARD_CHIP_TAG,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().singleOrNull() ?: return@runCatching false
            val root = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .singleOrNull { it.root === node.root }
                ?: return@runCatching false
            val bounds = node.boundsInRoot
            val rootBounds = root.boundsInRoot
            bounds.left >= rootBounds.left - ROOT_SLOP_PX &&
                bounds.top >= rootBounds.top - ROOT_SLOP_PX &&
                bounds.right <= rootBounds.right + ROOT_SLOP_PX &&
                bounds.bottom <= rootBounds.bottom + ROOT_SLOP_PX
        }.getOrDefault(false)

    private fun assertLiveKeyboardChipFullyWithinOwningRoot() {
        val node = compose.onNodeWithTag(
            SHOW_KEYBOARD_CHIP_TAG,
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val root = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single { it.root === node.root }
        val bounds = node.boundsInRoot
        val rootBounds = root.boundsInRoot
        assertTrue(
            "Live-only Show-keyboard chip must be fully contained in its owning " +
                "Compose root. node=$bounds root=$rootBounds",
            bounds.left >= rootBounds.left - ROOT_SLOP_PX &&
                bounds.top >= rootBounds.top - ROOT_SLOP_PX &&
                bounds.right <= rootBounds.right + ROOT_SLOP_PX &&
                bounds.bottom <= rootBounds.bottom + ROOT_SLOP_PX,
        )
    }

    private fun currentConnectionDiagnostics(): String =
        runCatching {
            var diagnostics = "activity-unavailable"
            launchedActivity?.onActivity { activity ->
                val viewModel = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                diagnostics =
                    "raw=${viewModel.connectionStatus.value} " +
                    "display=${viewModel.displayConnectionStatus.value} " +
                    "reveal=${viewModel.revealState.value} panes=${viewModel.panes.value.size}"
            }
            diagnostics
        }.getOrElse { "unavailable(${it::class.simpleName}: ${it.message})" }

    private fun semanticsNodeCount(tag: String): Int =
        runCatching {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size
        }.getOrDefault(-1)

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findTerminalView()
            if (match != null) return match
        }
        return null
    }

    // ---------------------------------------------------------------- Docker seed

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedShellSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_LAB)} 2>/dev/null || true")
            appendLine("rm -f ${shellQuote(SIDE_EFFECT_PATH)}")
            appendLine("rm -f ${shellQuote(SIDE_EFFECT_WITH_ENTER_PATH)}")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_LAB)} " +
                    shellQuote("printf 'SHELL-READY\\n'; exec sh -i"),
            )
            appendLine("tmux set-option -t ${shellQuote(SESSION_LAB)} @ps_agent_kind shell")
        }
        runSsh(key, script)
    }

    private suspend fun seedDockerHost(key: String, hostName: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue641-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            commandSnippetId = db.snippetDao().insert(
                SnippetEntity(
                    hostId = hostId,
                    label = SNIPPET_LABEL,
                    body = SNIPPET_BODY,
                    kind = "command",
                ),
            )
            commandWithEnterSnippetId = db.snippetDao().insert(
                SnippetEntity(
                    hostId = hostId,
                    label = SNIPPET_WITH_ENTER_LABEL,
                    body = SNIPPET_WITH_ENTER_BODY,
                    kind = "command",
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private suspend fun cleanupSeededSessions(key: String) {
        runCatching {
            withTimeout(20_000) {
                runSsh(
                    key,
                    "tmux kill-session -t ${shellQuote(SESSION_LAB)} 2>/dev/null || true",
                )
            }
        }
    }

    private suspend fun runSsh(key: String, script: String): String {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec(script) }
        }
        val exec = result.getOrNull()
        Log.i(LOG_TAG, "ssh exec exit=${exec?.exitCode} stdout='${exec?.stdout?.trim()}'")
        return exec?.stdout?.trim().orEmpty()
    }

    private suspend fun remoteSideEffectBytes(key: String, path: String): Int =
        runSsh(
            key,
            "if [ -f ${shellQuote(path)} ]; then " +
                "wc -c < ${shellQuote(path)}; else printf 0; fi",
        ).trim().toIntOrNull() ?: -1

    private suspend fun assertRemoteSideEffectRemainsExactlyOnce(
        key: String,
        path: String,
        label: String,
    ) {
        val deadline = SystemClock.elapsedRealtime() + EXACT_ONCE_STABILITY_MS
        var polls = 0
        do {
            assertEquals(
                "$label side effect must remain exactly one byte throughout the stability window",
                1,
                remoteSideEffectBytes(key, path),
            )
            polls++
        } while (SystemClock.elapsedRealtime() < deadline)
        assertTrue("$label exact-once stability pump must execute", polls > 0)
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    // ---------------------------------------------------------------- Artifacts

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "UiAutomation returned no screenshot for $name"
        }
        val file = artifactFile("$name.png")
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "could not write screenshot ${file.absolutePath}"
                }
            }
            println("ISSUE641_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureTerminalViewport(name: String) {
        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val rendered = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(rendered))
            bitmap = rendered
        }
        val rendered = checkNotNull(bitmap) { "TerminalView was not renderable for $name" }
        try {
            FileOutputStream(artifactFile("$name-viewport.png")).use { output ->
                check(rendered.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            artifactFile("$name-visible-terminal.txt").writeText(visibleTerminalText())
        } finally {
            rendered.recycle()
        }
    }

    private fun visibleTerminalText(): String {
        var text = ""
        launchedActivity?.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return text
    }

    private fun writeSummary(liveTimeoutScreenshotSaved: Boolean? = null) {
        val file = artifactFile("summary.txt")
        file.writeText(
            buildString {
                appendLine("scenario=shell-composer-occlusion")
                appendLine("host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER")
                appendLine("seeded_session=$SESSION_LAB")
                summaryLines.forEach { appendLine(it) }
                appendLine("artifacts:")
                when (liveTimeoutScreenshotSaved) {
                    true -> appendLine("  00-live-band-timeout.png")
                    false -> Unit
                    null -> {
                        appendLine("  01-keyboard-down.png")
                        appendLine("  01-keyboard-down-viewport.png")
                        appendLine("  01-keyboard-down-visible-terminal.txt")
                        appendLine("  02-snippet-typed-remote-capture-pane.txt")
                        appendLine("  02-keyboard-up.png")
                        appendLine("  03-send-with-enter-remote-capture-pane.txt")
                        appendLine("  03-snippet-dispatched.png")
                        appendLine("  03-snippet-dispatched-viewport.png")
                        appendLine("  03-snippet-dispatched-visible-terminal.txt")
                        appendLine("  04-synthetic-focus-owner.png")
                    }
                }
            },
        )
        println("ISSUE641_SUMMARY ${file.absolutePath}")
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue641Composer"
        const val DEVICE_DIR_NAME: String = "issue641-shell-composer-occlusion"
        const val SESSION_LAB: String = "issue1748-641-shell"
        const val SNIPPET_LABEL: String = "Issue 1754 exact command"
        const val SNIPPET_WITH_ENTER_LABEL: String = "Issue 1754 command plus Enter"
        const val SIDE_EFFECT_PATH: String = "/tmp/issue1754-command-count"
        const val SIDE_EFFECT_WITH_ENTER_PATH: String = "/tmp/issue1754-enter-command-count"
        const val SNIPPET_BODY: String = "printf z>>$SIDE_EFFECT_PATH"
        const val SNIPPET_WITH_ENTER_COMMAND: String =
            "printf y>>$SIDE_EFFECT_WITH_ENTER_PATH"
        const val SNIPPET_WITH_ENTER_BODY: String = "$SNIPPET_WITH_ENTER_COMMAND\r\n"
        const val ROOT_SLOP_PX: Float = 1f
        const val EXACT_ONCE_STABILITY_MS: Long = 750
        const val KEYBOARD_REQUEST_RETRY_MS: Long = 1_000
        const val WINDOW_FOCUS_TIMEOUT_MS: Long = 10_000
        const val IME_VISIBILITY_TIMEOUT_MS: Long = 15_000
        val FORBIDDEN_LITERAL_CHIPS: List<String> =
            listOf("git status", "tmux ls", "k logs", "clear")
    }
}
