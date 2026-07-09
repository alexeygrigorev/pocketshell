package com.pocketshell.uikit.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.components.CommandChip
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType

@Composable
internal fun ComposerControlsRowRender() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        // Grabber.
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(40.dp)
                .height(4.dp)
                .background(PocketShellColors.Border, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.height(14.dp))
        // Header: title + circular close chip.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Prompt Composer",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = PocketShellColors.Text,
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(PocketShellColors.SurfaceElev, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "×", color = PocketShellColors.TextSecondary, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        // Draft field with sample text.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .background(PocketShellColors.SurfaceElev, RoundedCornerShape(12.dp))
                .border(1.dp, PocketShellColors.Border, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = "check the deploy log and tell me what failed in the last run",
                color = PocketShellColors.Text,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(14.dp))
        // Controls row — the #701 focus.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Grouped left tools pill.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(PocketShellColors.SurfaceElev, RoundedCornerShape(22.dp))
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Glyphs stand in for the Material AttachFile / DataObject
                // icons (the icons-extended set isn't on the ui-kit render
                // classpath); the real app row uses the proper icons.
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Text(text = "📎", color = PocketShellColors.TextSecondary, fontSize = 18.sp)
                }
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "{ }",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                // Issue #787: the new `/` slash-command button — third in the
                // 📎 / `{}` / `/` group, the single consolidated entry.
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "/",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // Filled accent Send pill.
            Row(
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(PocketShellColors.Accent, RoundedCornerShape(22.dp))
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = "Send",
                    color = PocketShellColors.OnAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = "➤", color = PocketShellColors.OnAccent, fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            // Cyan mic disc.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(PocketShellColors.Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "●",
                    color = PocketShellColors.OnAccent,
                    fontSize = 18.sp,
                )
            }
        }
    }
}

@Composable
internal fun ComposerRecordingControlsRowRender() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Recording surface: timer + waveform (no lock, no Discard here).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PocketShellColors.SurfaceElev, RoundedCornerShape(12.dp))
                .border(1.dp, PocketShellColors.Border, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "00:14",
                color = PocketShellColors.Accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "▁▂▄▆█▆▄▂▁▂▄▆█▆▄▂▁▂▄▆",
                    color = PocketShellColors.Accent,
                    fontSize = 16.sp,
                )
            }
        }
        // Bottom row: a single right-aligned balanced action row —
        // [Discard · Insert · Send]. No editing tools compete for this row while
        // recording, so Discard sits next to Insert and Send (#1245).
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.weight(1f))
            // Discard — outlined secondary pill (48dp).
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(PocketShellColors.SurfaceElev, RoundedCornerShape(22.dp))
                    .border(1.dp, PocketShellColors.Border, RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Discard",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // Insert — outlined secondary pill (48dp).
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(PocketShellColors.SurfaceElev, RoundedCornerShape(22.dp))
                    .border(1.dp, PocketShellColors.Border, RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Insert",
                    color = PocketShellColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // Send — accent primary pill (48dp).
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(PocketShellColors.Accent, RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Send",
                    color = PocketShellColors.OnAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = "➤", color = PocketShellColors.OnAccent, fontSize = 13.sp)
            }
        }
    }
}

@Composable
internal fun ComposerLongDraftCaretVisibleRender() {
    val lines = (1..14).map { "line $it of a long multi-line prompt I'm typing" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        // Header (fixed).
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Prompt Composer",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = PocketShellColors.Text,
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(PocketShellColors.SurfaceElev, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "×", color = PocketShellColors.TextSecondary, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        // Draft field, internally scrolled to the LAST lines (caret at end).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PocketShellColors.SurfaceElev, RoundedCornerShape(12.dp))
                .border(1.dp, PocketShellColors.Border, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column {
                // Only the bottom slice of a long draft is visible — the
                // field has scrolled to keep the caret in view.
                lines.takeLast(7).forEach { line ->
                    Text(text = line, color = PocketShellColors.Text, fontSize = 14.sp)
                }
                // The caret on the last line being typed.
                Row {
                    Text(
                        text = "line 14, still typing",
                        color = PocketShellColors.Text,
                        fontSize = 14.sp,
                    )
                    Box(
                        modifier = Modifier
                            .padding(start = 1.dp)
                            .width(2.dp)
                            .height(18.dp)
                            .background(PocketShellColors.Accent),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        // Controls row — must stay fully reachable below the field.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(PocketShellColors.SurfaceElev, RoundedCornerShape(22.dp))
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Text(text = "📎", color = PocketShellColors.TextSecondary, fontSize = 18.sp)
                }
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "{ }",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(PocketShellColors.Accent, RoundedCornerShape(22.dp))
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = "Send",
                    color = PocketShellColors.OnAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = "➤", color = PocketShellColors.OnAccent, fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(PocketShellColors.Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "●", color = PocketShellColors.OnAccent, fontSize = 18.sp)
            }
        }
    }
}

@Composable
internal fun TerminalBottomChipsWithCompactHotkeysRender() {
        Spacer(Modifier.height(560.dp))
        // The reclaimed space: NO full-width bar row here anymore — just the
        // single chip band below, with the compact `hotkeys` chip inline.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PocketShellColors.Surface)
                .border(1.dp, PocketShellColors.Border)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The flexible static-chip strip yields/scrolls in production;
            // the primary cluster (incl. the new compact `hotkeys` chip) is
            // pinned to the right and always fully visible.
            CommandChip(label = "clear", onClick = {})
            Spacer(Modifier.weight(1f))
            CommandChip(label = "Enter", onClick = {})
            CommandChip(label = "hotkeys", onClick = {})
            CommandChip(label = "snippets", onClick = {})
        }
}

@Composable
internal fun ConversationLauncherOnlyBottomRender() {
        Column(modifier = Modifier.fillMaxSize()) {
            // The transcript claims all the vertical space the deleted search
            // bar + command bar used to eat.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Let me check the build status.",
                    color = PocketShellColors.Text,
                    fontSize = 14.sp,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(PocketShellColors.SurfaceElev, RoundedCornerShape(12.dp))
                        .border(1.dp, PocketShellColors.Border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "▸ Bash  ./gradlew assembleDebug",
                        color = PocketShellColors.Text,
                        fontSize = 13.sp,
                    )
                }
                Text(
                    text = "Build succeeded in 41s.",
                    color = PocketShellColors.Text,
                    fontSize = 14.sp,
                )
            }
            // The ONLY bottom chrome on the Conversation tab: the launcher,
            // right-anchored, no bordered chip-bar row around it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PocketShellColors.SurfaceElev, RoundedCornerShape(8.dp))
                        .border(1.dp, PocketShellColors.AccentDim, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = ">_",
                        color = PocketShellColors.Accent,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
}

@Composable
internal fun ComposerKeyboardUpNoKeyBarRender() {
    Spacer(Modifier.height(260.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Prompt Composer",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = PocketShellColors.Text,
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(PocketShellColors.SurfaceElev, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "×", color = PocketShellColors.TextSecondary, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PocketShellColors.SurfaceElev, RoundedCornerShape(12.dp))
                .border(1.dp, PocketShellColors.Border, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = "git status --short",
                color = PocketShellColors.Text,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(PocketShellColors.SurfaceElev, RoundedCornerShape(22.dp))
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Text(text = "📎", color = PocketShellColors.TextSecondary, fontSize = 18.sp)
                }
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "{ }",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(PocketShellColors.Accent, RoundedCornerShape(22.dp))
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = "Send",
                    color = PocketShellColors.OnAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = "➤", color = PocketShellColors.OnAccent, fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(PocketShellColors.Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "●", color = PocketShellColors.OnAccent, fontSize = 18.sp)
            }
        }
    }
    // Soft-keyboard stand-in so "key bar sits above the keyboard" reads.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(Color(0xFF202124)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "soft keyboard (system IME)",
            color = PocketShellColors.TextMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
internal fun ComposerSlashAutocompleteRender() {
    Spacer(Modifier.height(140.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        // The autocomplete dropdown — rides the top of the composer column,
        // above the field, filtered to commands matching the typed `/comp`.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(PocketShellColors.SurfaceElev, RoundedCornerShape(12.dp))
                .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(12.dp)),
        ) {
            SlashCommandMirrorRow("/compact", "Summarise the conversation to free up context.", arg = true)
        }
        Spacer(Modifier.height(12.dp))
        // Header.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Prompt Composer",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = PocketShellColors.Text,
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(PocketShellColors.SurfaceElev, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "×", color = PocketShellColors.TextSecondary, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        // The draft field showing the `/comp` slash query being typed.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PocketShellColors.SurfaceElev, RoundedCornerShape(12.dp))
                .border(1.dp, PocketShellColors.Border, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row {
                Text(text = "/comp", color = PocketShellColors.Text, fontSize = 14.sp)
                Box(
                    modifier = Modifier
                        .padding(start = 1.dp)
                        .width(2.dp)
                        .height(18.dp)
                        .background(PocketShellColors.Accent),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        // Action row — stays reachable below the field.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(PocketShellColors.SurfaceElev, RoundedCornerShape(22.dp))
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Text(text = "📎", color = PocketShellColors.TextSecondary, fontSize = 18.sp)
                }
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "{ }",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                // Issue #787: the `/` button that opens THIS dropdown — the
                // single consolidated slash entry beside 📎 / `{}`.
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "/",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(PocketShellColors.Accent, RoundedCornerShape(22.dp))
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = "Send",
                    color = PocketShellColors.OnAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = "➤", color = PocketShellColors.OnAccent, fontSize = 13.sp)
            }
        }
    }
    // Soft-keyboard stand-in so "dropdown sits above the keyboard" reads.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(Color(0xFF202124)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "soft keyboard (system IME)",
            color = PocketShellColors.TextMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
internal fun ComposerSlashDropdownClaudeRender() {
    Spacer(Modifier.height(120.dp))
    SlashCommandPaletteFrame {
        SlashCommandMirrorDropdown(
            listOf(
                Triple("/clear", "Start a fresh conversation (clears current context).", false),
                Triple("/compact", "Summarise the conversation to free up context.", true),
                Triple("/goal", "Set a persistent objective for the session.", true),
                Triple("/rewind", "Roll back to an earlier point in the conversation.", false),
                Triple("/model", "Switch the active model.", false),
            ),
        )
    }
}

@Composable
internal fun ComposerSlashDropdownCodexRender() {
    Spacer(Modifier.height(120.dp))
    SlashCommandPaletteFrame {
        SlashCommandMirrorDropdown(
            listOf(
                Triple("/new", "Start a fresh conversation in this CLI session.", false),
                Triple("/compact", "Summarise the conversation to free up context.", true),
                Triple("/goal", "Set a persistent objective for the session.", true),
                Triple("/diff", "Show the working-tree diff.", false),
                Triple("/status", "Show the current session status.", false),
            ),
        )
    }
}

@Composable
internal fun ComposerSlashDropdownOpenCodeRender() {
    Spacer(Modifier.height(120.dp))
    SlashCommandPaletteFrame {
        SlashCommandMirrorDropdown(
            listOf(
                Triple("/new", "Start a fresh conversation (clears current context).", false),
                Triple("/compact", "Summarise the conversation to free up context.", true),
                Triple("/sessions", "Browse and resume previous sessions.", false),
                Triple("/undo", "Undo the last change.", false),
                Triple("/share", "Create a shareable link for the session.", false),
            ),
        )
    }
}


// Issue #791: a composer-surface frame so each catalog render reads in
// context — the dropdown floating above a draft field on the composer card.
@Composable
internal fun SlashCommandPaletteFrame(dropdown: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        dropdown()
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PocketShellColors.SurfaceElev, RoundedCornerShape(12.dp))
                .border(1.dp, PocketShellColors.Border, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(text = "/", color = PocketShellColors.Text, fontSize = 14.sp)
        }
    }
}


// Issue #791: a single mirror row of the redesigned `/`-autocomplete
// dropdown — the command token leads (mono + agent-accent), an inline
// `<arg>` hint follows for argument-taking commands, and a short wrapping
// description sits below. No right-side badge (it duplicated the token).
// Mirrors the app-level `SlashCommandDropdown` row visuals (which ui-kit
// cannot import).
@Composable
internal fun SlashCommandMirrorRow(
    command: String,
    description: String,
    arg: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = command,
                color = PocketShellColors.Accent,
                style = PocketShellType.bodyMono,
                fontWeight = FontWeight.SemiBold,
            )
            if (arg) {
                Text(
                    text = "<${command.removePrefix("/")}>",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.bodyMono,
                )
            }
        }
        Text(
            text = description,
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


// Issue #791: the redesigned dropdown showing several rows for ONE agent
// catalog, so the three-catalog render cases can each eyeball a full,
// realistic list above the composer field.
@Composable
internal fun SlashCommandMirrorDropdown(rows: List<Triple<String, String, Boolean>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(12.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(12.dp)),
    ) {
        rows.forEach { (command, description, arg) ->
            SlashCommandMirrorRow(command = command, description = description, arg = arg)
        }
    }
}
