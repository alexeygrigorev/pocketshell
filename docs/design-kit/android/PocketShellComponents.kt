package com.pocketshell.designpreview

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Reusable production-shaped primitives. No connections or credentials are owned here. */
@Composable
fun PsHeader(title: String, subtitle: String = "", connected: Boolean = false,
             onBack: (() -> Unit)? = null, actionIcon: String = "", actionDescription: String = "More actions",
             onAction: () -> Unit = {}, sheet: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 20.dp else 12.dp,
        end = 12.dp, top = 16.dp, bottom = 20.dp), verticalAlignment = Alignment.Top) {
        if (onBack != null) PsIconButton("back", "Back", onBack)
        Column(Modifier.weight(1f).padding(top = 3.dp)) {
            Text(title, style = if (sheet) PsTokens.titleType else PsTokens.screenType,
                color = PsTokens.text, modifier = Modifier.semantics { heading() })
            if (subtitle.isNotEmpty()) {
                Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (connected) Box(Modifier.size(7.dp).background(PsTokens.positive, CircleShape))
                    Text(subtitle, style = PsTokens.metadataType, color = PsTokens.secondary)
                }
            }
        }
        if (actionIcon.isNotEmpty()) PsIconButton(actionIcon, actionDescription, onAction)
    }
}

@Composable
fun PsIconButton(icon: String, label: String, onClick: () -> Unit) {
    IconButton(onClick, modifier = Modifier.size(PsTokens.touchMin)) {
        PsIcon(icon, description = label, color = PsTokens.secondary)
    }
}

@Composable
fun PsButton(label: String, onClick: () -> Unit, variant: String = "primary",
             modifier: Modifier = Modifier, enabled: Boolean = true) {
    val m = modifier.fillMaxWidth().heightIn(min = PsTokens.buttonMin)
    val shape = RoundedCornerShape(PsTokens.buttonRadius)
    val text: @Composable () -> Unit = { Text(label, style = PsTokens.buttonType) }
    when (variant) {
        "secondary", "danger" -> OutlinedButton(onClick, m, enabled = enabled, shape = shape,
            border = BorderStroke(1.dp, if (variant == "danger") PsTokens.error else PsTokens.inputBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (variant == "danger") PsTokens.error else PsTokens.text),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) { text() }
        "text" -> TextButton(onClick, m, enabled = enabled, shape = shape,
            colors = ButtonDefaults.textButtonColors(contentColor = PsTokens.secondary)) { text() }
        else -> Button(onClick, m, enabled = enabled, shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = PsTokens.accent, contentColor = PsTokens.onAccent),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp), elevation = null) { text() }
    }
}

@Composable
fun PsRow(title: String, subtitle: String = "", icon: String = "", trailing: String = "",
          danger: Boolean = false, onClick: (() -> Unit)? = null) {
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column {
        Row(Modifier.fillMaxWidth().then(clickable).heightIn(min = PsTokens.listRowMin)
            .padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (icon.isNotEmpty()) PsIcon(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = PsTokens.bodyType.copy(fontWeight = FontWeight.Medium), color = if (danger) PsTokens.error else PsTokens.text)
                if (subtitle.isNotEmpty()) Text(subtitle, style = PsTokens.metadataType,
                    color = PsTokens.muted, modifier = Modifier.padding(top = 4.dp))
            }
            if (trailing.isNotEmpty()) Text(trailing, style = PsTokens.metadataType,
                color = PsTokens.muted, modifier = Modifier.widthIn(max = 84.dp))
            if (onClick != null) PsIcon("chevron", size = 20.dp)
        }
        HorizontalDivider(color = PsTokens.divider, thickness = 1.dp)
    }
}

private fun agentLabel(kind: String): Pair<String, String?> = when (kind) {
    "claude" -> "Claude" to "hexagon"
    "codex" -> "Codex" to "code"
    "opencode" -> "OpenCode" to "terminal"
    "grok" -> "Grok" to "zap"
    "shell" -> "Terminal" to null
    else -> "Unknown" to null
}

/** Entire row is interactive. Agent marks are metadata, not undersized buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PsWorkspaceRow(name: String, sessions: List<AgentSummary>, unavailable: Boolean = false,
                   onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = PsTokens.workspaceRowMin).padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(name, style = PsTokens.workspaceType, color = PsTokens.text)
                if (unavailable || sessions.isEmpty()) {
                    Text(if (unavailable) "Status unavailable" else "No sessions",
                        style = PsTokens.metadataType, color = PsTokens.muted, modifier = Modifier.padding(top = 5.dp))
                } else {
                    FlowRow(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        sessions.take(3).forEach { session ->
                            val (label, mark) = agentLabel(session.kind)
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (mark != null) PsIcon(mark, size = PsTokens.metadataIcon)
                                Text(label + if (session.count > 1) " ×${session.count}" else "",
                                    style = PsTokens.metadataType, color = PsTokens.muted)
                            }
                        }
                        if (sessions.size > 3) Text("+${sessions.size - 3}", style = PsTokens.metadataType, color = PsTokens.muted)
                    }
                }
            }
            PsIcon("chevron", size = 20.dp)
        }
        HorizontalDivider(color = PsTokens.divider)
    }
}

@Composable
fun PsField(label: String, value: String, onValueChange: (String) -> Unit,
            placeholder: String = "", kind: String = "text") {
    val multi = kind in listOf("multiline", "password-multiline", "editor")
    Column(Modifier.padding(top = 18.dp, bottom = 22.dp)) {
        Text(label, style = PsTokens.labelType, color = PsTokens.secondary, modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(value, onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = when (kind) { "editor" -> 470.dp; "multiline", "password-multiline" -> 180.dp; else -> 56.dp }),
            textStyle = if (kind == "editor") PsTokens.terminalType else PsTokens.bodyType,
            placeholder = { Text(placeholder, style = PsTokens.bodyType, color = PsTokens.muted) },
            singleLine = !multi, shape = RoundedCornerShape(PsTokens.fieldRadius),
            visualTransformation = if (kind.startsWith("password")) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = when {
                kind.startsWith("password") -> KeyboardType.Password
                kind == "number" -> KeyboardType.Number
                else -> KeyboardType.Text
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = PsTokens.surface, unfocusedContainerColor = PsTokens.surface,
                focusedTextColor = PsTokens.text, unfocusedTextColor = PsTokens.text,
                focusedBorderColor = PsTokens.accent, unfocusedBorderColor = PsTokens.inputBorder,
                cursorColor = PsTokens.accent,
            ),
        )
    }
}

/** Use this native container for real modal behavior. Static previews use PsStaticSheet below. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PsSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = PsTokens.surface,
        shape = RoundedCornerShape(topStart = PsTokens.sheetRadius, topEnd = PsTokens.sheetRadius),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), content = content)
}

// The remainder of this file is a preview renderer, not a new production framework.
@Composable
fun PsMockupScreen(spec: MockupScreen, onAction: (String) -> Unit = {}) {
    if (spec.layout == "sheet") PsStaticSheet(spec, onAction) else PsPage(spec, onAction)
}

@Composable
private fun PsPage(spec: MockupScreen, onAction: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(PsTokens.background)) {
        PsHeader(spec.title, spec.subtitle, spec.connected,
            onBack = if (spec.back.isNotEmpty()) ({ onAction(spec.back) }) else null,
            actionIcon = spec.headerIcon, onAction = { onAction(spec.headerRoute) })
        if (spec.layout == "terminal") {
            Box(Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize()) {
                    spec.nodes.filterNot { it is MockupNode.Composer || it is MockupNode.SystemKeyboard }.forEach { n ->
                        if (n is MockupNode.Terminal) TerminalFixture(n, Modifier.weight(1f))
                        else Box(Modifier.padding(horizontal = if (n is MockupNode.SessionBar) 20.dp else 16.dp)) { PsNode(n, spec, onAction) }
                    }
                }
                if (spec.nodes.any { it is MockupNode.Composer }) {
                    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.66f),
                        color = PsTokens.surface, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            spec.nodes.filter { it is MockupNode.Composer || it is MockupNode.SystemKeyboard }
                                .forEach { PsNode(it, spec, onAction) }
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                itemsIndexed(spec.nodes) { _, node -> PsNode(node, spec, onAction) }
            }
            PsFooter(spec.footer, onAction)
        }
    }
}

@Composable
private fun PsStaticSheet(spec: MockupScreen, onAction: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        MockupScreens.byId[spec.base]?.let { PsPage(it) {} }
        Box(Modifier.fillMaxSize().background(PsTokens.scrim).clickable { onAction(spec.back) })
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * .88f),
            color = PsTokens.surface, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
            Column {
                Box(Modifier.padding(top = 10.dp).width(32.dp).height(3.dp).align(Alignment.CenterHorizontally).background(PsTokens.inputBorder, CircleShape))
                PsHeader(spec.title, sheet = true, actionIcon = "close", actionDescription = "Close", onAction = { onAction(spec.back) })
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                    itemsIndexed(spec.nodes) { _, n -> PsNode(n, spec, onAction) }
                }
                PsFooter(spec.footer, onAction)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PsFooter(actions: List<MockupNode.Button>, onAction: (String) -> Unit) {
    if (actions.isEmpty()) return
    HorizontalDivider(color = PsTokens.divider)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { PsButton(it.label, { onAction(it.route) }, it.variant) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PsNode(n: MockupNode, screen: MockupScreen, act: (String) -> Unit) {
    when (n) {
        is MockupNode.Text -> Text(n.text, color = if (n.tone == "error") PsTokens.error else PsTokens.secondary,
            style = PsTokens.bodyType, modifier = Modifier.padding(vertical = 16.dp))
        is MockupNode.Section -> {
            if (n.title.isEmpty()) Spacer(Modifier.height(24.dp)) else Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(n.title, style = PsTokens.bodyType, color = PsTokens.secondary, modifier = Modifier.weight(1f).semantics { heading() })
                if (n.action.isNotEmpty()) TextButton({ act(n.route) }, Modifier.heightIn(min = PsTokens.touchMin)) {
                    PsIcon("plus", color = PsTokens.accent, size = 20.dp)
                    Spacer(Modifier.width(6.dp)); Text(n.action, style = PsTokens.labelType, color = PsTokens.accent)
                }
            }
        }
        is MockupNode.Row -> PsRow(n.title, n.subtitle, n.icon, n.trailing, n.danger,
            if (n.route.isEmpty()) null else ({ act(n.route) }))
        is MockupNode.Workspace -> PsWorkspaceRow(n.name, n.sessions, screen.id == "host-offline") { act(n.route) }
        is MockupNode.Field -> { var value by remember(n) { mutableStateOf(n.value) }; PsField(n.label, value, { value = it }, n.placeholder, n.kind) }
        is MockupNode.Search -> {
            var value by remember(n) { mutableStateOf(n.value) }
            OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(bottom = 12.dp),
                textStyle = PsTokens.bodyType, placeholder = { Text(n.placeholder, style = PsTokens.bodyType, color = PsTokens.muted) },
                leadingIcon = { PsIcon("search") }, singleLine = true, shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = PsTokens.surface,
                    focusedContainerColor = PsTokens.surface, unfocusedBorderColor = PsTokens.inputBorder,
                    focusedBorderColor = PsTokens.accent))
        }
        is MockupNode.Button -> PsButton(n.label, { act(n.route) }, n.variant, Modifier.padding(vertical = 16.dp))
        is MockupNode.Choice -> {
            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).selectable(selected = n.selected, role = Role.RadioButton) {
                act(n.route.ifEmpty { "select:${n.title}" })
            }.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (n.icon.isNotEmpty()) PsIcon(n.icon)
                Column(Modifier.weight(1f)) {
                    Text(n.title, style = PsTokens.bodyType, color = PsTokens.text)
                    if (n.subtitle.isNotEmpty()) Text(n.subtitle, style = PsTokens.metadataType, color = PsTokens.muted)
                }
                RadioButton(n.selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = PsTokens.secondary, unselectedColor = PsTokens.inputBorder))
            }
            HorizontalDivider(color = PsTokens.divider)
        }
        is MockupNode.Toggle -> {
            var on by remember(n) { mutableStateOf(n.value) }
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).toggleable(value = on, role = Role.Switch, onValueChange = { on = it }).padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(n.title, style = PsTokens.bodyType, color = PsTokens.text)
                    if (n.subtitle.isNotEmpty()) Text(n.subtitle, style = PsTokens.metadataType, color = PsTokens.muted)
                }
                Switch(on, null, colors = SwitchDefaults.colors(checkedThumbColor = PsTokens.text,
                    checkedTrackColor = PsTokens.surfaceRaised, checkedBorderColor = PsTokens.secondary,
                    uncheckedTrackColor = PsTokens.surface, uncheckedBorderColor = PsTokens.inputBorder))
            }
            HorizontalDivider(color = PsTokens.divider)
        }
        is MockupNode.Location -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(n.path, style = PsTokens.metadataType, color = PsTokens.secondary, modifier = Modifier.weight(1f))
            if (n.action.isNotEmpty()) TextButton({ act(n.route) }) { Text(n.action, style = PsTokens.labelType, color = PsTokens.accent) }
        }
        is MockupNode.Disclosure -> {
            var expanded by remember(n) { mutableStateOf(false) }
            PsRow(n.title, icon = if (expanded) "up" else "down", onClick = { expanded = !expanded })
            if (expanded) n.children.forEach { PsNode(it, screen, act) }
        }
        is MockupNode.Alert -> {
            val tone = if (n.tone == "error") PsTokens.error else PsTokens.warning
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Box(Modifier.width(2.dp).height(56.dp).background(tone))
                Column(Modifier.padding(start = 14.dp)) {
                    Text(n.title, style = PsTokens.bodyType, color = tone)
                    if (n.text.isNotEmpty()) Text(n.text, style = PsTokens.metadataType, color = PsTokens.secondary, modifier = Modifier.padding(top = 6.dp))
                    if (n.action.isNotEmpty()) TextButton({ act(n.route) }) { Text(n.action, style = PsTokens.labelType, color = PsTokens.accent) }
                }
            }
        }
        is MockupNode.Code -> Text(n.text, style = PsTokens.terminalType, color = PsTokens.text,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).background(PsTokens.terminal, RoundedCornerShape(8.dp)).padding(16.dp))
        is MockupNode.Empty -> Column(Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 32.dp)) {
            if (n.icon.isNotEmpty()) { PsIcon(n.icon, size = 36.dp); Spacer(Modifier.height(18.dp)) }
            Text(n.title, style = PsTokens.titleType, color = PsTokens.text)
            Text(n.text, style = PsTokens.bodyType, color = PsTokens.secondary, modifier = Modifier.padding(top = 8.dp))
        }
        is MockupNode.Progress -> Column(Modifier.padding(vertical = 32.dp)) {
            Text(n.title, style = PsTokens.titleType, color = PsTokens.text)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp), color = PsTokens.secondary, trackColor = PsTokens.divider)
            Text(n.text, style = PsTokens.bodyType, color = PsTokens.secondary)
        }
        MockupNode.Scanner -> Box(Modifier.fillMaxWidth().height(300.dp).background(PsTokens.terminal, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PsIcon("qr", size = 64.dp); Spacer(Modifier.height(20.dp))
                Text("Camera preview · illustrative", style = PsTokens.metadataType, color = PsTokens.muted)
            }
        }
        is MockupNode.Tabs -> {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                n.modes.forEach { mode -> TextButton({ act(mode.route) }) {
                    Text(mode.label, style = PsTokens.bodyType, color = if (mode.selected) PsTokens.text else PsTokens.muted)
                } }
            }
            HorizontalDivider(color = PsTokens.divider)
        }
        is MockupNode.Document -> Column {
            Text(n.title, style = PsTokens.screenType, color = PsTokens.text, modifier = Modifier.padding(top = 30.dp))
            n.paragraphs.forEach { PsNode(MockupNode.Text(it), screen, act) }
            Text(n.heading, style = PsTokens.titleType, color = PsTokens.text, modifier = Modifier.padding(top = 24.dp))
            PsNode(MockupNode.Code(n.code), screen, act); PsNode(MockupNode.Text(n.after), screen, act)
        }
        MockupNode.ImagePreview -> {
            val context = LocalContext.current
            val bitmap = remember { runCatching { context.assets.open("approved-reference.png").use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull() }
            Box(Modifier.fillMaxWidth().height(620.dp).background(PsTokens.terminal), contentAlignment = Alignment.Center) {
                if (bitmap != null) Image(bitmap, "Approved workspace reference", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else PsIcon("image", size = 64.dp)
            }
        }
        is MockupNode.Quota -> Column(Modifier.padding(vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { PsIcon(n.icon); Text(n.title, style = PsTokens.titleType, color = PsTokens.text) }
            LinearProgressIndicator(progress = { n.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), color = PsTokens.secondary, trackColor = PsTokens.divider)
            Text(n.detail, style = PsTokens.metadataType, color = PsTokens.secondary)
            HorizontalDivider(Modifier.padding(top = 20.dp), color = PsTokens.divider)
        }
        is MockupNode.Transfer -> Column(Modifier.padding(vertical = 16.dp)) {
            Text(n.title, style = PsTokens.titleType, color = PsTokens.text)
            Text("${n.text} · ${n.percent}%", style = PsTokens.metadataType, color = PsTokens.secondary)
            LinearProgressIndicator(progress = { n.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), color = PsTokens.secondary, trackColor = PsTokens.divider)
        }
        is MockupNode.KeyGrid -> Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            n.keys.chunked(3).forEach { group -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                group.forEach { label -> OutlinedButton({ act("key:$label") }, Modifier.weight(1f).heightIn(min = 56.dp), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, PsTokens.inputBorder)) {
                    Text(label, style = PsTokens.bodyType, color = PsTokens.text)
                } }
            } }
        }
        is MockupNode.Order -> n.names.forEach { name -> Row(Modifier.heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = PsTokens.bodyType, color = PsTokens.text, modifier = Modifier.weight(1f))
            PsIconButton("up", "Move $name up") { act("up:$name") }; PsIconButton("down", "Move $name down") { act("down:$name") }
        } }
        is MockupNode.SessionBar -> {
            Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable { act(n.route) }.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(n.label, style = PsTokens.bodyType, color = PsTokens.text); Text(n.program, style = PsTokens.metadataType, color = PsTokens.muted) }
                Text(n.count.toString(), style = PsTokens.metadataType, color = PsTokens.secondary); Spacer(Modifier.width(8.dp)); PsIcon("down")
            }
        }
        is MockupNode.Terminal -> TerminalFixture(n, Modifier.heightIn(min = 320.dp))
        is MockupNode.Composer -> {
            var draft by remember(n) { mutableStateOf(n.draft) }
            Column(Modifier.padding(16.dp)) {
                Text("To Terminal · Claude Code", style = PsTokens.metadataType, color = PsTokens.muted)
                if (n.mode == "attachment") PsRow("workspace.png", "Ready", icon = "paperclip")
                if (n.mode == "voice") Text("Listening · 00:12", style = PsTokens.bodyType, color = PsTokens.secondary, modifier = Modifier.padding(vertical = 16.dp))
                TextField(draft, { draft = it }, Modifier.fillMaxWidth().heightIn(min = 116.dp), textStyle = PsTokens.bodyType,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                if (n.mode == "voice") PsButton("Stop and review", { act("composer") }) else Row(verticalAlignment = Alignment.CenterVertically) {
                    PsIconButton("plus", "Add to input") { act("composer-tools") }; PsIconButton("mic", "Dictate") { act("dictation") }
                    Spacer(Modifier.weight(1f)); TextButton({ act("action:paste") }) { Text("Paste", style = PsTokens.labelType, color = PsTokens.secondary) }
                    Button({ act("action:send") }, shape = RoundedCornerShape(12.dp)) { Text("Send", style = PsTokens.buttonType) }
                }
            }
        }
        MockupNode.SystemKeyboard -> Box(Modifier.fillMaxWidth().height(216.dp).background(PsTokens.surfaceRaised), contentAlignment = Alignment.Center) {
            Text("Android keyboard\nOS-owned; not an app component", style = PsTokens.metadataType, color = PsTokens.secondary)
        }
        is MockupNode.Launcher -> Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ act("composer") }, Modifier.weight(1f)) { Text(if (n.disabled) "Edit local draft" else "Write input…", style = PsTokens.bodyType, color = PsTokens.secondary) }
            PsIconButton("mic", "Dictate input") { act("dictation") }; PsIconButton("keyboard", "Terminal keys") { act("hotkeys") }
        }
        is MockupNode.Slider -> {
            var value by remember(n) { mutableFloatStateOf(n.value.toFloat()) }
            Column(Modifier.padding(vertical = 20.dp)) {
                Row { Text(n.title, style = PsTokens.bodyType, color = PsTokens.text, modifier = Modifier.weight(1f)); Text("${value.roundToInt()}${n.unit}", style = PsTokens.metadataType, color = PsTokens.secondary) }
                Slider(value, { value = it }, valueRange = n.min.toFloat()..n.max.toFloat(), colors = SliderDefaults.colors(thumbColor = PsTokens.secondary, activeTrackColor = PsTokens.secondary))
            }
        }
    }
}

/** This is fixture text only. Reuse the real TerminalHostView in production. */
@Composable
private fun TerminalFixture(n: MockupNode.Terminal, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(PsTokens.terminal).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp)) {
        Text(n.lines.joinToString("\n"), style = PsTokens.terminalType, color = PsTokens.text, softWrap = false)
    }
}
