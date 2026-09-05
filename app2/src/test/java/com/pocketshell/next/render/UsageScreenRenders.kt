package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageResetCredit
import com.pocketshell.core.usage.UsageResetCredits
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageWindow
import com.pocketshell.next.usage.UsageHostSnapshot
import com.pocketshell.next.usage.UsageScreen
import com.pocketshell.next.usage.UsageScreenState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Fast design renders for the usage panel's compact-first layout (issue #2534).
 *
 * The real [UsageScreen] lives in app2, so this harness snapshots it directly
 * rather than mirroring primitives in `:shared:ui-kit`'s DesignRenders:
 *
 * ```
 * ./gradlew :app2:testDebugUnitTest --tests '*UsageScreenRenders*' --rerun-tasks
 * # then open the PNGs under app2/build/renders/
 * ```
 *
 * Collapsed is the new default (strip only). Expanded is the same strip plus
 * the existing Codex card after that compact row is selected. Numbers follow
 * the maintainer's 2026-09-05 screenshot: Codex compact percent is the
 * most-constrained 7d window (60% used).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class UsageScreenRenders {

    @Test
    fun usageScreenCollapsed() = render("usage-screen-collapsed") {
        screen()
    }

    @Test
    fun usageScreenCodexExpanded() = render("usage-screen-codex-expanded") {
        screen(initiallyExpandedProviders = setOf("Codex"))
    }

    @Composable
    private fun screen(initiallyExpandedProviders: Set<String> = emptySet()) {
        UsageScreen(
            state = SAMPLE_STATE,
            onBack = {},
            onRefresh = {},
            now = NOW,
            initiallyExpandedProviders = initiallyExpandedProviders,
        )
    }

    private fun render(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/renders/$name.png") {
            PocketShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PocketShellColors.Background,
                ) {
                    content()
                }
            }
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-05T18:25:00Z")

        val SAMPLE_STATE: UsageScreenState = UsageScreenState(
            hosts = listOf(
                UsageHostSnapshot(
                    hostId = 1,
                    hostName = "hetzner",
                    records = listOf(
                        record(
                            provider = "claude",
                            windows = listOf(
                                window("5h", 12.0, Instant.parse("2026-09-05T20:59:00Z")),
                                window("7d", 11.0, Instant.parse("2026-09-10T14:59:00Z")),
                            ),
                        ),
                        record(
                            provider = "codex",
                            windows = listOf(
                                window("5h", 8.0, Instant.parse("2026-09-05T23:00:00Z")),
                                window("7d", 60.0, Instant.parse("2026-09-07T08:45:00Z")),
                            ),
                            resetCredits = UsageResetCredits(
                                availableCount = 3,
                                credits = listOf(
                                    UsageResetCredit(
                                        title = "Full reset",
                                        expiresAt = Instant.parse("2026-09-21T00:13:00Z"),
                                    ),
                                ),
                                unavailable = false,
                            ),
                        ),
                        record(
                            provider = "copilot",
                            windows = listOf(
                                window("5h", 0.0, null),
                                window("monthly", 0.0, Instant.parse("2026-10-01T00:00:00Z")),
                            ),
                        ),
                        record(
                            provider = "go",
                            windows = listOf(window("5h", 58.0, Instant.parse("2026-09-05T23:25:00Z"))),
                        ),
                        record(
                            provider = "grok",
                            windows = listOf(window("7d", 45.0, Instant.parse("2026-09-08T18:25:00Z"))),
                        ),
                        record(
                            provider = "zai",
                            windows = listOf(window("7d", 7.0, Instant.parse("2026-09-10T18:25:00Z"))),
                        ),
                    ),
                    lastSyncedAt = NOW,
                ),
            ),
            loaded = true,
            connectedHostCount = 1,
        )

        fun record(
            provider: String,
            windows: List<UsageWindow>,
            resetCredits: UsageResetCredits? = null,
        ): UsageProviderRecord = UsageProviderRecord(
            provider = provider,
            status = UsageStatus.Ok,
            windows = windows,
            rawStatus = "ok",
            resetCredits = resetCredits,
        )

        fun window(name: String, percent: Double, resetAt: Instant?): UsageWindow = UsageWindow(
            name = name,
            used = percent,
            limit = 100.0,
            unit = "percent",
            resetAt = resetAt,
        )
    }
}
