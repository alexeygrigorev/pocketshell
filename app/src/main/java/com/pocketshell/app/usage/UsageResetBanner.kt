package com.pocketshell.app.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Resolved "limits just reset" banner content (issue #690).
 *
 * The non-push fallback: on app open the usage screen reads the host's detected
 * reset events (`pocketshell usage --reset-events`) and, when a reset is recent,
 * shows a prominent banner — value lands even before/without FCM push being
 * wired. [resetKey] threads the server-side de-dup identity through so the
 * banner shows once per actual reset (#619 don't-renotify), not once per
 * capture.
 */
public data class UsageResetBannerState(
    val title: String,
    val detail: String,
    val resetKey: String,
)

/**
 * Picks the single most-relevant reset to surface on the banner, and formats
 * its copy.
 *
 * The "most relevant" reset is the most RECENTLY detected one (the log is
 * oldest-first, so the last entry with a usable detected time wins) that is
 * still recent enough to be worth announcing on open — older than
 * [recencyWindow] is treated as stale history, not a fresh "just reset". Returns
 * null when there is nothing recent to show, so the banner is simply absent.
 */
public fun usageResetBannerState(
    events: List<UsageResetEvent>,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    recencyWindow: java.time.Duration = DEFAULT_RECENCY_WINDOW,
): UsageResetBannerState? {
    val recent = events
        .filter { event ->
            val detected = event.detectedAt ?: return@filter false
            !detected.isAfter(now) && java.time.Duration.between(detected, now) <= recencyWindow
        }
        .maxByOrNull { it.detectedAt ?: Instant.MIN }
        ?: return null

    val providerLabel = providerDisplayName(recent.provider)
    val resetAt = recent.newResetAt ?: recent.detectedAt
    val resetClause = resetAt?.let { "limits reset at ${formatTime(it, zoneId)}" }
        ?: "limits just reset"
    val earlyClause = if (recent.isEarly) {
        recent.minutesEarly?.takeIf { it > 0 }?.let { " · ~${it}m earlier than stated" } ?: " · earlier than stated"
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
 * Prominent in-app reset banner (issue #690 non-push fallback). Rendered at the
 * top of [UsageScreen] when [state] is non-null.
 */
@Composable
public fun UsageResetBanner(
    state: UsageResetBannerState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(10.dp))
            .border(width = 1.dp, color = PocketShellColors.Green, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
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

public const val USAGE_RESET_BANNER_TAG: String = "usage_reset_banner"

/** Default window within which a detected reset is still announced on open. */
public val DEFAULT_RECENCY_WINDOW: java.time.Duration = java.time.Duration.ofHours(12)

private fun providerDisplayName(provider: String): String = when (provider.lowercase()) {
    "codex", "openai", "chatgpt" -> "Codex"
    "claude", "anthropic" -> "Claude"
    else -> provider.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
}

private fun formatTime(instant: Instant, zoneId: ZoneId): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.US)
        .withZone(zoneId)
        .format(instant)
