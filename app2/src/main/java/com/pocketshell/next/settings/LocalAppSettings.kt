package com.pocketshell.next.settings

import androidx.compose.runtime.compositionLocalOf

/**
 * The current [AppSettings], readable from any composable (rewrite task P-6).
 *
 * ## Why a CompositionLocal and not another ViewModel
 *
 * The terminal's font size is a global preference read by a screen that already
 * has two ViewModels ([com.pocketshell.next.terminal.SessionViewModel] and the
 * composer's). Threading a third one through for a single Int would put a
 * settings dependency on every screen that ever grows a preference, and adding
 * the field to `SessionViewModel` would put a settings dependency inside the
 * connection core — the subsystem D28 says to keep clean.
 *
 * `MainActivity` collects the repository's flow once and provides it here, so
 * there is exactly one read of the preferences file per process and every
 * consumer sees the same value in the same frame.
 *
 * ## The default is the fresh-install behaviour
 *
 * `compositionLocalOf`, not `staticCompositionLocalOf`: settings change while
 * the app is running (that is the point of the screen), and the static variant
 * would recompose the entire subtree — including the terminal — on every
 * unrelated preference change. The default value means a test or a design
 * render can compose any screen without providing anything.
 */
val LocalAppSettings = compositionLocalOf { AppSettings() }
