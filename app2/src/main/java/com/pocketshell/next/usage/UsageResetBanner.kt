package com.pocketshell.next.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Resolved "limits just reset" banner content. */
data class UsageResetBannerState(
    val title: String,
    val detail: String,
    val resetKey: String,
)

/**
 * Picks the single most-relevant reset to surface, and formats its copy.
 *
 * "Most relevant" is the most RECENTLY detected reset that is still recent
 * enough to be worth announcing — anything older than [recencyWindow] is stale
 * history, not news, and returns null so the banner is simply absent.
 */
fun usageResetBannerState(
    events: List<UsageResetEvent>,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    recencyWindow: Duration = DEFAULT_RESET_RECENCY_WINDOW,
): UsageResetBannerState? {
    val recent = events
        .filter { event ->
            val detected = event.detectedAt ?: return@filter false
            !detected.isAfter(now) && Duration.between(detected, now) <= recencyWindow
        }
        .maxByOrNull { it.detectedAt ?: Instant.MIN }
        ?: return null

    val providerLabel = resetProviderDisplayName(recent.provider)
    val resetAt = recent.newResetAt ?: recent.detectedAt
    val resetClause = resetAt?.let { "limits reset at ${formatShortTime(it, zoneId)}" }
        ?: "limits just reset"
    val earlyClause = if (recent.isEarly) {
        recent.minutesEarly?.takeIf { it > 0 }?.let { " · ~${it}m earlier than stated" }
            ?: " · earlier than stated"
    } else {
        ""
    }
    return UsageResetBannerState(
        title = "$providerLabel $resetClause",
        detail = "Heavy work can resume.$earlyClause",
        resetKey = recent.resetKey,
    )
}

/**
 * Prominent in-app reset banner, rendered at the top of [UsageScreen] when the
 * host reported a recent reset.
 */
@Composable
fun UsageResetBanner(
    state: UsageResetBannerState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.sm)
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.extraSmall)
            .border(
                width = 1.dp,
                color = PocketShellColors.Green,
                shape = PocketShellShapes.extraSmall,
            )
            .padding(horizontal = PocketShellSpacing.lg, vertical = PocketShellSpacing.md)
            .testTag(USAGE_RESET_BANNER_TAG),
    ) {
        Text(
            text = state.title,
            color = PocketShellColors.Green,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = state.detail,
            color = PocketShellColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

const val USAGE_RESET_BANNER_TAG: String = "usage:reset-banner"

/** How recent a detected reset must be to still be announced on open. */
val DEFAULT_RESET_RECENCY_WINDOW: Duration = Duration.ofHours(12)

private fun resetProviderDisplayName(provider: String): String = when (provider.lowercase()) {
    "codex", "openai", "chatgpt" -> "Codex"
    "claude", "anthropic" -> "Claude"
    else -> provider.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
    }
}

private fun formatShortTime(instant: Instant, zoneId: ZoneId): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.US)
        .withZone(zoneId)
        .format(instant)
