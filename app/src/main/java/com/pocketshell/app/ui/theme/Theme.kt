package com.pocketshell.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Tokens taken verbatim from docs/design-language.md and issue #2.
private val PocketShellDarkColors = darkColorScheme(
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    primary = Color(0xFF22D3EE),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
    onPrimary = Color(0xFF04101A),
)

@Composable
fun PocketShellTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PocketShellDarkColors,
        content = content,
    )
}
