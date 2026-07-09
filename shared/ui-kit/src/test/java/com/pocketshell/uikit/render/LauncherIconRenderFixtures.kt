package com.pocketshell.uikit.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.theme.PocketShellColors

@Composable
internal fun ComposerLauncherBrandGlyphRender() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Enabled state — cyan accent glyph in an elevated rounded button.
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(PocketShellColors.SurfaceElev, RoundedCornerShape(10.dp))
                .border(1.dp, PocketShellColors.AccentDim, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = composerLauncherBrandIcon,
                contentDescription = null,
                tint = PocketShellColors.Accent,
                modifier = Modifier.size(20.dp),
            )
        }
        // Disabled state.
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(PocketShellColors.Surface, RoundedCornerShape(10.dp))
                .border(1.dp, PocketShellColors.Border, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = composerLauncherBrandIcon,
                contentDescription = null,
                tint = PocketShellColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        // Large reference so the >_ motif is unambiguous at inspection size.
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(PocketShellColors.SurfaceElev, RoundedCornerShape(18.dp))
                .border(1.dp, PocketShellColors.AccentDim, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = composerLauncherBrandIcon,
                contentDescription = null,
                tint = PocketShellColors.Accent,
                modifier = Modifier.size(44.dp),
            )
        }
    }
    Text(
        text = "Composer launcher: enabled · disabled · large reference",
        color = PocketShellColors.TextSecondary,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 18.dp),
    )
}

@Composable
internal fun ThemedIconSilhouettePreviewRender() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Simulated Material You themed tiles: tinted circle backdrop +
        // tinted >_ silhouette. Two example wallpaper tints.
        listOf(
            Color(0xFF1C2B33) to PocketShellColors.Accent,
            Color(0xFF2A2433) to Color(0xFFCBB6F0),
        ).forEach { (backdrop, tint) ->
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(backdrop, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = launcherMonochromeIcon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }
    Text(
        text = "Themed-icon silhouette (Material You) — simulated tints",
        color = PocketShellColors.TextSecondary,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 18.dp),
    )
}

private val launcherMonochromeIcon: ImageVector by lazy {
    // Mirror of drawable/ic_launcher_monochrome.xml (#612), remapped from
    // the 108-viewport launcher coords into a 24-viewport icon. Chevron `>`
    // + cursor `_`, the brand silhouette the themed icon tints.
    val builder = PathBuilder()
    // Chevron `>` (108-coords M43,43 L50,43 L63,57 L50,71 L43,71 L56,57 Z
    // scaled by 24/108 ~= 0.222, recentred).
    builder.moveTo(6.5f, 6.5f)
    builder.lineTo(9.5f, 6.5f)
    builder.lineTo(15f, 12f)
    builder.lineTo(9.5f, 17.5f)
    builder.lineTo(6.5f, 17.5f)
    builder.lineTo(12f, 12f)
    builder.close()
    // Cursor `_`.
    builder.moveTo(11.5f, 15f)
    builder.lineTo(17.5f, 15f)
    builder.lineTo(17.5f, 17.5f)
    builder.lineTo(11.5f, 17.5f)
    builder.close()
    ImageVector.Builder(
        name = "LauncherMonochrome",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply { addPath(pathData = builder.nodes, fill = SolidColor(Color.White)) }.build()
}

private val composerLauncherBrandIcon: ImageVector by lazy {
    // Mirror of app `ComposerLauncherIcon` (#612) — the `>_` brand motif.
    val builder = PathBuilder()
    // Prompt chevron `>`.
    builder.moveTo(6f, 6.5f)
    builder.lineTo(8.6f, 6.5f)
    builder.lineTo(13.6f, 12f)
    builder.lineTo(8.6f, 17.5f)
    builder.lineTo(6f, 17.5f)
    builder.lineTo(11f, 12f)
    builder.close()
    // Cursor block `_`.
    builder.moveTo(13.5f, 15.5f)
    builder.lineTo(18.5f, 15.5f)
    builder.lineTo(18.5f, 17.5f)
    builder.lineTo(13.5f, 17.5f)
    builder.close()
    ImageVector.Builder(
        name = "ComposerLauncher",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply { addPath(pathData = builder.nodes, fill = SolidColor(Color.White)) }.build()
}
