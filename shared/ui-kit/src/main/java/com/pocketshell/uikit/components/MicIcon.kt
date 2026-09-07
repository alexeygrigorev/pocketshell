package com.pocketshell.uikit.components

import androidx.compose.ui.graphics.vector.ImageVector
import com.pocketshell.uikit.icons.PocketShellIcons

/**
 * Issue #453: compatibility name for the canonical Quiet microphone vector.
 *
 * The path itself lives only in [PocketShellIcons], so every dictation
 * affordance uses the same native vector.
 */
val MicGlyphIcon: ImageVector = PocketShellIcons.Mic
