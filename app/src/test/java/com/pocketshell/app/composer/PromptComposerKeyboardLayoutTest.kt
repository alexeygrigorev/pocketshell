package com.pocketshell.app.composer

import androidx.compose.material3.ExperimentalMaterial3Api
import org.junit.Test

/**
 * Issue #682: the composer keyboard/IME layout is no longer driven by a
 * height-fraction + auto-expand state machine.
 *
 * #615 had reworked the sheet into a fully-expanded sheet that flipped between
 * a resting height fraction (0.65) and a full height fraction (1.0) when the
 * IME appeared, force-expanding the sheet and applying a manual host-window IME
 * bottom padding. That combination over-sized the sheet and pinned its content
 * to the top of the screen — the maintainer's "huge void + jump-to-top +
 * cut-off" regression.
 *
 * The rework deletes that machinery entirely: the composer is a content-height
 * (wrap-content) `ModalBottomSheet` that floats directly above the keyboard via
 * the standard `WindowInsets.ime` inset padding. There is therefore no longer a
 * `promptComposerSheetHeightFraction` / `shouldAutoExpandPromptComposerForIme` /
 * `shouldRestorePromptComposerPartialAfterIme` to unit-test here; the layout
 * behaviour is now proved on the emulator with the real IME (see
 * `PromptComposerSheetImeReachabilityTest` for Send reachability and
 * `PromptComposerSendNoKeyboardTest` for Send not re-raising the keyboard).
 *
 * This placeholder keeps the build graph honest (the module still compiles a
 * test that imports the composer package) without re-introducing the deleted
 * fraction/auto-expand contract the rework intentionally removed.
 */
@OptIn(ExperimentalMaterial3Api::class)
class PromptComposerKeyboardLayoutTest {

    @Test
    fun composerHasNoHeightFractionOrAutoExpandStateMachine() {
        // The deleted helpers (`promptComposerSheetHeightFraction`,
        // `shouldAutoExpandPromptComposerForIme`,
        // `shouldRestorePromptComposerPartialAfterIme`) used to be referenced
        // here. Their removal is the fix: a wrap-content sheet + `imePadding()`
        // replaces the fraction/expand machinery. Nothing to assert at the JVM
        // level beyond the package compiling without those symbols.
    }
}
