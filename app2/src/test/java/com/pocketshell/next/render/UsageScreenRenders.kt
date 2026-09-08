package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageResetCredit
import com.pocketshell.core.usage.UsageResetCredits
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageWindow
import com.pocketshell.next.usage.UsageHostSnapshot
import com.pocketshell.next.usage.UsageScreen
import com.pocketshell.next.usage.UsageScreenState
import com.pocketshell.next.usage.UsageResetBannerState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Fast design renders for the host-scoped Quiet usage layout (issue #2611).
 *
 * The real [UsageScreen] lives in app2, so this harness snapshots it directly
 * rather than mirroring primitives in `:shared:ui-kit`'s DesignRenders:
 *
 * ```
 * ./gradlew :app2:testDebugUnitTest --tests '*UsageScreenRenders*' --rerun-tasks
 * # then open the PNGs under app2/build/renders/
 * ```
 *
 * The compact render shows provider rows; the expanded render shows the real
 * Codex windows and reset-credit details inline. Numbers follow the
 * maintainer's 2026-09-05 screenshot: Codex's most-constrained 7d window is
 * 60% used.
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

    companion object {
        val NOW: Instant = Instant.parse("2026-09-05T18:25:00Z")

        val SAMPLE_STATE: UsageScreenState = UsageScreenState(
            selectedHostId = 1,
            selectedHostName = "hetzner",
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
            resetBanner = UsageResetBannerState(
                title = "Codex limits reset at 5:00 PM",
                detail = "Heavy work can resume.",
                resetKey = "codex-7d",
            ),
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-night-xxhdpi")
class UsageScreen360Renders {

    @Test
    fun usageScreenAt360() = render("usage-screen-360") {
        UsageScreen(
            state = UsageScreenRenders.SAMPLE_STATE,
            onBack = {},
            onRefresh = {},
            now = UsageScreenRenders.NOW,
        )
    }

    private fun render(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/renders/$name.png") {
            PocketShellTheme {
                Surface(Modifier.fillMaxSize(), color = PocketShellColors.Background) {
                    content()
                }
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w600dp-h915dp-night-xxhdpi")
class UsageScreen600Renders {

    @Test
    fun usageScreenAt600() = render("usage-screen-600") {
        UsageScreen(
            state = UsageScreenRenders.SAMPLE_STATE,
            onBack = {},
            onRefresh = {},
            now = UsageScreenRenders.NOW,
            initiallyExpandedProviders = setOf("Codex"),
        )
    }

    private fun render(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/renders/$name.png") {
            PocketShellTheme {
                Surface(Modifier.fillMaxSize(), color = PocketShellColors.Background) {
                    content()
                }
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class UsageScreenLargeTextRenders {

    @Test
    fun usageScreenWithLargeTextKeepsDetailsReachable() = render("usage-screen-large-text") {
        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.8f)) {
            UsageScreen(
                state = UsageScreenRenders.SAMPLE_STATE,
                onBack = {},
                onRefresh = {},
                now = UsageScreenRenders.NOW,
                initiallyExpandedProviders = setOf("Codex"),
            )
        }
    }

    private fun render(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/renders/$name.png") {
            PocketShellTheme {
                Surface(Modifier.fillMaxSize(), color = PocketShellColors.Background) {
                    content()
                }
            }
        }
    }
}
