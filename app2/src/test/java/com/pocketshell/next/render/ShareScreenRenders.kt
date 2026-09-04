package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.next.share.ShareHostRow
import com.pocketshell.next.share.SharePickerScreen
import com.pocketshell.next.share.ShareUiState
import com.pocketshell.next.share.ShareUploadState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fast design renders for the share target (task P-9), the same harness and
 * conventions as [HostScreenRenders]:
 *
 * ```
 * ./gradlew :app2:testDebugUnitTest --tests '*ShareScreenRenders*' --rerun-tasks
 * # then open the PNGs under app2/build/renders/
 * ```
 *
 * Renders, not assertions — the screen's behaviour is covered by
 * `SharePickerScreenTest`. These exist because the share surface is the one
 * screen the user meets from OUTSIDE the app, with no chrome around it to
 * explain what happened, so what each state looks like has to be looked at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class ShareScreenRenders {

    @Test
    fun sharePicker() = render("p9-share-picker") {
        screen(
            ShareUiState(
                items = listOf("Screenshot_20260903-114210.png"),
                hosts = listOf(
                    ShareHostRow(1, "hetzner", "alexey@135.181.114.209", connected = true),
                    ShareHostRow(2, "builder", "root@10.0.0.7", connected = false),
                ),
                hostsLoaded = true,
            ),
        )
    }

    /** The multi-file share, mid-upload. */
    @Test
    fun shareUploading() = render("p9-share-uploading") {
        screen(
            ShareUiState(
                items = listOf("one.png", "two.png", "three.png"),
                hosts = listOf(ShareHostRow(1, "hetzner", "alexey@135.181.114.209", true)),
                hostsLoaded = true,
                upload = ShareUploadState.Running("hetzner", "Uploading two.png (2 of 3)"),
            ),
        )
    }

    @Test
    fun shareSucceeded() = render("p9-share-succeeded") {
        screen(
            ShareUiState(
                items = listOf("Screenshot_20260903-114210.png"),
                hostsLoaded = true,
                upload = ShareUploadState.Success(
                    hostName = "hetzner",
                    paths = listOf(
                        "/home/alexey/inbox/pocketshell/" +
                            "20260903-114336-Screenshot_20260903-114210.png",
                    ),
                ),
            ),
        )
    }

    @Test
    fun shareFailedPartially() = render("p9-share-failed") {
        screen(
            ShareUiState(
                items = listOf("one.png", "two.png"),
                hosts = listOf(ShareHostRow(1, "hetzner", "alexey@135.181.114.209", false)),
                hostsLoaded = true,
                upload = ShareUploadState.Failed(
                    hostName = "hetzner",
                    message = "Connection lost during the upload — 1 of 2 uploaded, failed: two.png",
                    uploaded = listOf("/home/alexey/inbox/pocketshell/20260903-114336-one.png"),
                    failedNames = listOf("two.png"),
                ),
            ),
        )
    }

    /** A fresh install: nowhere to send to, said plainly. */
    @Test
    fun shareWithNoHosts() = render("p9-share-no-hosts") {
        screen(ShareUiState(items = listOf("note.txt"), hostsLoaded = true))
    }

    @Composable
    private fun screen(state: ShareUiState) {
        SharePickerScreen(
            state = state,
            onPickHost = {},
            onRetry = {},
            onPickAnother = {},
            onFinished = {},
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
