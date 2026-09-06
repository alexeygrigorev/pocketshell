package com.pocketshell.designpreview

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider

/** Use in src/debug, not in the shipping APK. The catalog is fixture data. */
class PocketShellScreenProvider : PreviewParameterProvider<String> {
    override val values: Sequence<String> = MockupScreens.all.asSequence().map { it.id }
}

@Preview(name = "Every screen", widthDp = 412, heightDp = 915, showBackground = true)
@Composable
fun PocketShellCatalogPreview(@PreviewParameter(PocketShellScreenProvider::class) id: String) {
    PocketShellQuietTheme { PsMockupScreen(requireNotNull(MockupScreens.byId[id])) }
}

@Preview(name = "Host workspaces", widthDp = 412, heightDp = 915)
@Preview(name = "Compact / large text", widthDp = 360, heightDp = 800, fontScale = 1.3f)
@Preview(name = "Accessibility text", widthDp = 412, heightDp = 915, fontScale = 2f)
@Composable
fun PocketShellWorkspacePreview() {
    PocketShellQuietTheme { PsMockupScreen(requireNotNull(MockupScreens.byId["workspaces"])) }
}

/** A navigable DEBUG-only fixture gallery. This does not implement SSH or Android app navigation. */
@Composable
fun PocketShellMockupDemo(startId: String = "workspaces") {
    var current by remember { mutableStateOf(startId) }
    var selection by remember { mutableStateOf<String?>(null) }
    val fixture = MockupScreens.byId[current] ?: MockupScreens.all.first()
    val spec = if (selection == null) fixture else fixture.copy(nodes = fixture.nodes.map {
        if (it is MockupNode.Choice) it.copy(selected = it.title == selection) else it
    })
    PocketShellQuietTheme {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            PsMockupScreen(spec) { route ->
                when {
                    route.startsWith("select:") -> selection = route.removePrefix("select:")
                    route == "action:start-session" -> { current = "terminal"; selection = null }
                    route == "action:create-file-folder" -> { current = "files"; selection = null }
                    route == "action:create-workspace" -> { current = "workspace-empty"; selection = null }
                    route == "action:paste" || route == "action:send" -> { current = "terminal" }
                    route in MockupScreens.byId -> { current = route; selection = null }
                    // Native handoffs, clipboard, input bytes and storage are intentionally NOT performed.
                    else -> Unit
                }
            }
        }
    }
}
