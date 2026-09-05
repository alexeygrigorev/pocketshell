package com.pocketshell.next.terminal

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.next.settings.AppSettings
import com.pocketshell.next.settings.LocalAppSettings
import com.pocketshell.next.settings.SettingsRepository
import com.termux.view.TerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for issue #2512 — the Settings text-size stepper has to reach the
 * thing that paints glyphs.
 *
 * ## What was broken
 *
 * [AppSettings.terminalTextSizePx] is persisted by [SettingsRepository] and
 * rendered as a user-facing Settings stepper (task P-6). `MainActivity` even
 * provides the snapshot through [LocalAppSettings] specifically so the
 * terminal can read font size without a third ViewModel. Zero call sites
 * under `app2/` actually read it. [TerminalHostView] always
 * `setTextSize(TERMINAL_TEXT_SIZE_RAW_PX)` (hardcoded 28) and
 * [rememberTerminalCellMetrics] measured at the same constant, so moving the
 * stepper changed a stored Int and nothing on screen.
 *
 * Same class as issue #2488 (grace window): a control that persists a value
 * the production path never reads. Worse than no control, because a fresh
 * install at the default 28 looks fine and the hole is silent.
 *
 * ## Why the assertions are on the VIEW and the METRICS, not on SharedPreferences
 *
 * [SettingsRepositoryTest] already round-trips the Int. That cannot fail on
 * this bug. The defect lives entirely in the consumer wiring, so the only
 * test that can fail on it is one that composes the REAL [TerminalHostView]
 * and the REAL [rememberTerminalCellMetrics] under a non-default
 * [LocalAppSettings] — the same CompositionLocal `MainActivity` provides —
 * and watches what size the vendored renderer and the geometry estimate
 * actually used.
 *
 * Robolectric cannot paint the emulator's canvas (`libtermux.so` is a device
 * artifact) and its `Paint` reports a 1 px glyph at every text size, so the
 * metrics assertion is on [TerminalCellMetrics.textSizePx] (the input
 * [measureTerminalCellMetrics] recorded) rather than on a cell-width that
 * would be 1 px either way. The view assertion is on the renderer the
 * vendored [TerminalView.setTextSize] built — not a stub, not a captured
 * lambda.
 */
@RunWith(AndroidJUnit4::class)
class TerminalTextSizeSettingWiringTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var rootView: View? = null
    private var capturedMetrics: TerminalCellMetrics? = null

    /**
     * The bug itself: a user who steps the size to 40 px must get 40 px glyphs
     * and a geometry estimate measured at 40 px. Before the fix both were 28.
     */
    @Test
    fun `the provided text size is the one the view and the metrics use`() {
        setWiredContent(AppSettings(terminalTextSizePx = NON_DEFAULT_PX))

        assertEquals(
            "TerminalView.setTextSize must receive the Settings value, not the " +
                "$TERMINAL_TEXT_SIZE_RAW_PX px literal (issue #2512: the stepper " +
                "was inert and every glyph was the default)",
            NON_DEFAULT_PX,
            requireTerminalView().rendererTextSize(),
        )
        assertEquals(
            "rememberTerminalCellMetrics must measure at the same Settings value " +
                "so the geometry estimate and the glyphs agree",
            NON_DEFAULT_PX,
            requireMetrics().textSizePx,
        )
    }

    /**
     * The half a value captured at first composition would fail.
     *
     * The user moves the stepper while a session is open: `MainActivity`
     * provides a new [AppSettings] snapshot and the already-hosted
     * [TerminalView] has to pick it up in `AndroidView`'s `update` block. A
     * factory-only read would pin the size until the view was torn down.
     */
    @Test
    fun `changing the setting on a live terminal changes the glyphs and the metrics`() {
        var settings by mutableStateOf(AppSettings(terminalTextSizePx = NON_DEFAULT_PX))
        setWiredContent { settings }

        val view = requireTerminalView()
        assertEquals(NON_DEFAULT_PX, view.rendererTextSize())
        assertEquals(NON_DEFAULT_PX, requireMetrics().textSizePx)

        composeRule.runOnIdle {
            settings = AppSettings(terminalTextSizePx = LIVE_UPDATE_PX)
        }
        composeRule.waitForIdle()

        assertSame(
            "the hosted TerminalView must be updated in place, not rebuilt " +
                "(rebuilding would reset the viewport)",
            view,
            requireTerminalView(),
        )
        assertEquals(
            "setTextSize must run again on the existing view when the stepper moves",
            LIVE_UPDATE_PX,
            view.rendererTextSize(),
        )
        assertEquals(LIVE_UPDATE_PX, requireMetrics().textSizePx)
        assertNotEquals(
            "the two sizes the stepper offers must be distinct or this " +
                "assertion cannot catch a factory-only read",
            NON_DEFAULT_PX,
            LIVE_UPDATE_PX,
        )
    }

    /**
     * A value that survived a process restart is what the next composition
     * uses — the same repository instance is not what production has (the
     * graph is rebuilt on every cold start), so the snapshot has to come off
     * the PERSISTED file, the way `MainActivity` collects it into
     * [LocalAppSettings].
     */
    @Test
    fun `a size stored by a previous process run is honoured after a restart`() {
        SettingsRepository(ApplicationProvider.getApplicationContext())
            .setTerminalTextSizePx(NON_DEFAULT_PX)

        val restarted = SettingsRepository(ApplicationProvider.getApplicationContext())
        setWiredContent(restarted.settings.value)

        assertEquals(NON_DEFAULT_PX, requireTerminalView().rendererTextSize())
        assertEquals(NON_DEFAULT_PX, requireMetrics().textSizePx)
    }

    /**
     * A fresh install must behave exactly as it did before this wiring existed:
     * the settings default and the terminal's own raw-pixel default are the
     * same 28 px, so passing the setting through changes nothing for a user
     * who never opens Settings.
     */
    @Test
    fun `a fresh install still gets the twenty-eight-pixel default`() {
        assertEquals(
            "the settings default and the terminal default must not drift apart",
            TERMINAL_TEXT_SIZE_RAW_PX,
            AppSettings.DEFAULT_TERMINAL_TEXT_SIZE_PX,
        )

        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        assertEquals(TERMINAL_TEXT_SIZE_RAW_PX, settings.settings.value.terminalTextSizePx)

        setWiredContent(settings.settings.value)

        assertEquals(TERMINAL_TEXT_SIZE_RAW_PX, requireTerminalView().rendererTextSize())
        assertEquals(TERMINAL_TEXT_SIZE_RAW_PX, requireMetrics().textSizePx)
    }

    // --- helpers -------------------------------------------------------------

    private fun setWiredContent(settings: AppSettings) = setWiredContent { settings }

    private fun setWiredContent(settings: () -> AppSettings) {
        val session = createRemoteTerminalSession()
        composeRule.setContent {
            rootView = LocalView.current
            CompositionLocalProvider(LocalAppSettings provides settings()) {
                capturedMetrics = rememberTerminalCellMetrics()
                TerminalHostView(
                    session = session,
                    onResized = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SESSION_TERMINAL_TAG).assertExists()
    }

    private fun requireTerminalView(): TerminalView =
        requireNotNull(findTerminalView(requireNotNull(rootView).rootView)) {
            "no TerminalView in the composition — TerminalHostView did not host the vendored view"
        }

    private fun requireMetrics(): TerminalCellMetrics =
        requireNotNull(capturedMetrics) { "rememberTerminalCellMetrics never ran" }

    private fun findTerminalView(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findTerminalView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    /**
     * The size [TerminalView.setTextSize] actually built a renderer at.
     *
     * [com.termux.view.TerminalRenderer.mTextSize] is package-private (the
     * vendored class is pinned byte-identical to upstream), so this is a
     * reflective read of the field `setTextSize` writes — the same field
     * `setTypeface` then reads. A test that stubbed `setTextSize` would still
     * pass with the factory hard-coding 28.
     */
    private fun TerminalView.rendererTextSize(): Int {
        val renderer = checkNotNull(mRenderer) {
            "TerminalView has no renderer — setTextSize never ran"
        }
        val field = renderer.javaClass.getDeclaredField("mTextSize")
        field.isAccessible = true
        return field.getInt(renderer)
    }

    private companion object {
        /** A stepper stop that is not the shipped default, and on the 2 px grid. */
        const val NON_DEFAULT_PX: Int = 40

        /** A different stop, used to prove the live view updates in place. */
        const val LIVE_UPDATE_PX: Int = 48
    }
}
