package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.next.sync.AccountSyncScreen
import com.pocketshell.next.sync.AccountSyncUiState
import com.pocketshell.next.sync.SyncHostRow
import com.pocketshell.next.sync.SyncOutcome
import com.pocketshell.next.sync.SyncSignInCoordinator
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Design renders for "Account & sync" (issue #2633). Run with:
 *
 * ```
 * ./gradlew :app2:testDebugUnitTest --tests '*AccountSyncRenders*' --rerun-tasks
 * # then open the PNGs under app2/build/renders/
 * ```
 *
 * Renders, not assertions — the screen's behaviour is covered by
 * `AccountSyncScreenTest`. Four states because the interesting ones here are
 * not the happy path: the build with no OAuth client yet (which is EVERY build
 * until the maintainer registers one), and the failure a wrong passphrase
 * produces, both of which the user meets more often than a clean sync.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class AccountSyncRenders {

    private val hosts = listOf(
        SyncHostRow("hetzner", "alexey@135.181.114.209:22", checked = true),
        SyncHostRow("builder", "root@10.0.0.7:2022", checked = false),
        SyncHostRow("laptop-only", "alexey@10.1.1.1:22 · in your account", checked = true, accountOnly = true),
    )

    @Test
    fun accountSignedOut() = render("i2633-account-signed-out") {
        AccountSyncScreen(
            state = AccountSyncUiState(clientConfigured = true, signedIn = false, hosts = hosts),
            onBack = {},
            onSignIn = {},
            onSignOut = {},
            onDismissSignInBanner = {},
            onHostChecked = { _, _ -> },
            onPush = {},
            onPull = {},
        )
    }

    @Test
    fun accountUnconfigured() = render("i2633-account-unconfigured") {
        AccountSyncScreen(
            state = AccountSyncUiState(clientConfigured = false, signedIn = false),
            onBack = {},
            onSignIn = {},
            onSignOut = {},
            onDismissSignInBanner = {},
            onHostChecked = { _, _ -> },
            onPush = {},
            onPull = {},
        )
    }

    @Test
    fun accountSignedIn() = render("i2633-account-signed-in") {
        AccountSyncScreen(
            state = AccountSyncUiState(
                clientConfigured = true,
                signedIn = true,
                email = "alexey.s.grigoriev@gmail.com",
                signInPhase = SyncSignInCoordinator.State.SignedIn("alexey.s.grigoriev@gmail.com"),
                hosts = hosts,
                outcome = SyncOutcome.Pushed(uploaded = 2, version = 7),
            ),
            onBack = {},
            onSignIn = {},
            onSignOut = {},
            onDismissSignInBanner = {},
            onHostChecked = { _, _ -> },
            onPush = {},
            onPull = {},
        )
    }

    @Test
    fun accountSyncFailed() = render("i2633-account-sync-failed") {
        AccountSyncScreen(
            state = AccountSyncUiState(
                clientConfigured = true,
                signedIn = true,
                email = "alexey.s.grigoriev@gmail.com",
                hosts = hosts,
                outcome = SyncOutcome.Failed("decryption failed — wrong passphrase or corrupted blob"),
            ),
            onBack = {},
            onSignIn = {},
            onSignOut = {},
            onDismissSignInBanner = {},
            onHostChecked = { _, _ -> },
            onPush = {},
            onPull = {},
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
}
