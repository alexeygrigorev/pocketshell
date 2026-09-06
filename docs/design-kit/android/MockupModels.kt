package com.pocketshell.designpreview

/** Presentation fixtures only. Production ViewModels should own the real state. */
data class MockupScreen(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val back: String = "workspaces",
    val headerIcon: String = "",
    val headerRoute: String = "",
    val layout: String = "page",
    val base: String = "workspaces",
    val connected: Boolean = false,
    val nodes: List<MockupNode> = emptyList(),
    val footer: List<MockupNode.Button> = emptyList(),
)
data class AgentSummary(val kind: String, val count: Int = 1)
data class FileMode(val label: String, val route: String, val selected: Boolean)
sealed interface MockupNode {
    data class Text(val text: String, val tone: String = "secondary") : MockupNode
    data class Section(val title: String, val action: String = "", val route: String = "") : MockupNode
    data class Row(val title: String, val subtitle: String = "", val route: String = "", val icon: String = "", val trailing: String = "", val danger: Boolean = false) : MockupNode
    data class Workspace(val name: String, val sessions: List<AgentSummary> = emptyList(), val route: String = "workspace") : MockupNode
    data class Field(val label: String, val value: String = "", val placeholder: String = "", val kind: String = "text") : MockupNode
    data class Search(val placeholder: String, val value: String = "") : MockupNode
    data class Button(val label: String, val route: String, val variant: String = "primary") : MockupNode
    data class Choice(val title: String, val subtitle: String = "", val selected: Boolean = false, val icon: String = "", val route: String = "") : MockupNode
    data class Toggle(val title: String, val value: Boolean, val subtitle: String = "") : MockupNode
    data class Location(val path: String, val action: String = "", val route: String = "") : MockupNode
    data class Disclosure(val title: String, val children: List<MockupNode>) : MockupNode
    data class Alert(val title: String, val text: String = "", val tone: String = "warning", val action: String = "", val route: String = "") : MockupNode
    data class Code(val text: String) : MockupNode
    data class Empty(val title: String, val text: String, val icon: String = "") : MockupNode
    data class Progress(val title: String, val text: String) : MockupNode
    data object Scanner : MockupNode
    data class Tabs(val modes: List<FileMode>) : MockupNode
    data class Document(val title: String, val paragraphs: List<String>, val heading: String, val code: String, val after: String) : MockupNode
    data object ImagePreview : MockupNode
    data class Quota(val title: String, val percent: Int, val detail: String, val icon: String) : MockupNode
    data class Transfer(val title: String, val text: String, val percent: Int) : MockupNode
    data class KeyGrid(val keys: List<String>) : MockupNode
    data class Order(val names: List<String>) : MockupNode
    data class SessionBar(val label: String, val program: String, val count: Int, val route: String) : MockupNode
    data class Terminal(val lines: List<String>) : MockupNode
    data class Composer(val mode: String, val draft: String) : MockupNode
    data object SystemKeyboard : MockupNode
    data class Launcher(val disabled: Boolean = false) : MockupNode
    data class Slider(val title: String, val value: Int, val min: Int, val max: Int, val unit: String) : MockupNode
}
