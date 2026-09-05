package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.hosts.AddEditHostScreen
import com.pocketshell.next.hosts.HostFormErrors
import com.pocketshell.next.hosts.HostFormState
import com.pocketshell.next.hosts.HostListScreen
import com.pocketshell.next.hosts.HostListUiState
import com.pocketshell.next.hosts.HostListUpdateNotice
import com.pocketshell.next.hosts.HostRow
import com.pocketshell.next.hosts.QrScannerViewModel
import com.pocketshell.next.hosts.QrScannerScreen
import com.pocketshell.next.hosts.SshKeyRow
import com.pocketshell.next.hosts.SshKeysScreen
import com.pocketshell.next.hosts.SshKeysUiState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fast design-render harness for app2's host-management screens (task P-6),
 * mirroring `:shared:ui-kit`'s `DesignRenders` (issue #555).
 *
 * It lives here rather than there because ui-kit is a *dependency of* app2 and
 * can never see an app2 composable. Same Pixel-7-class viewport, same
 * always-dark [PocketShellTheme], same `build/renders/` output convention, so
 * the iteration loop is identical:
 *
 * ```
 * ./gradlew :app2:testDebugUnitTest --tests '*HostScreenRenders*' --rerun-tasks
 * # then open the PNGs under app2/build/renders/
 * ```
 *
 * These are renders, not assertions — they exist to be looked at. The behaviour
 * of every screen below is covered by its own test class; nothing here is the
 * only check on anything.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class HostScreenRenders {

    /** A fresh install: the state that had no way forward before P-6. */
    @Test
    fun hostListEmpty() = render("p6-host-list-empty") {
        HostListScreen(
            state = HostListUiState(loaded = true),
            onOpenHost = {},
            onAddHost = {},
            onEditHost = {},
            onScanQr = {},
            onOpenSettings = {},
            onDeleteHost = {},
        )
    }

    /** Issue #2531: the GitHub-Releases update banner on the host list. */
    @Test
    fun hostListUpdateBanner() = render("host-list-update-banner") {
        HostListScreen(
            state = HostListUiState(
                loaded = true,
                hosts = listOf(
                    HostRow(1, "hetzner", "alexey@135.181.114.209"),
                    HostRow(2, "builder", "root@10.0.0.7"),
                ),
            ),
            onOpenHost = {},
            onAddHost = {},
            onEditHost = {},
            onScanQr = {},
            onOpenSettings = {},
            onDeleteHost = {},
            updateNotice = HostListUpdateNotice.Available(
                text = "v0.5.1 is available — you are on v0.5.0 · 5 Sep 2026",
                apkUrl = "https://example.com/pocketshell-0.5.1.apk",
                htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v0.5.1",
            ),
        )
    }

    /** The populated list, with the per-row management kebab. */
    @Test
    fun hostListPopulated() = render("p6-host-list") {
        HostListScreen(
            state = HostListUiState(
                loaded = true,
                hosts = listOf(
                    HostRow(1, "hetzner", "alexey@135.181.114.209"),
                    HostRow(2, "builder", "root@10.0.0.7"),
                ),
            ),
            onOpenHost = {},
            onAddHost = {},
            onEditHost = {},
            onScanQr = {},
            onOpenSettings = {},
            onDeleteHost = {},
        )
    }

    /** The blank Add form with keys available. */
    @Test
    fun hostFormAdd() = render("p6-host-form-add") {
        AddEditHostScreen(
            state = HostFormState(),
            keys = listOf(key(1, "hetzner-key"), key(2, "builder-key")),
            onChange = {},
            onSave = {},
            onCancel = {},
            onAddKey = {},
        )
    }

    /** Edit mode, populated. */
    @Test
    fun hostFormEdit() = render("p6-host-form-edit") {
        AddEditHostScreen(
            state = HostFormState(
                name = "hetzner",
                hostname = "135.181.114.209",
                port = "2222",
                username = "alexey",
                selectedKeyId = 1,
                editing = true,
            ),
            keys = listOf(key(1, "hetzner-key")),
            onChange = {},
            onSave = {},
            onCancel = {},
            onAddKey = {},
        )
    }

    /** A rejected submit: how five per-field messages read at once. */
    @Test
    fun hostFormErrors() = render("p6-host-form-errors") {
        AddEditHostScreen(
            state = HostFormState(
                port = "22x",
                errors = HostFormErrors(
                    name = "Required",
                    hostname = "Required",
                    port = "Enter a port between 1 and 65535",
                    username = "Required",
                    key = "Choose an SSH key",
                ),
            ),
            keys = emptyList(),
            onChange = {},
            onSave = {},
            onCancel = {},
            onAddKey = {},
        )
    }

    @Test
    fun sshKeysEmpty() = render("p6-ssh-keys-empty") {
        SshKeysScreen(
            state = SshKeysUiState(loaded = true),
            onBack = {},
            onGenerate = {},
            onImportPasted = { _, _ -> },
            onPickFile = {},
            onDelete = {},
            onDismissMessage = {},
        )
    }

    @Test
    fun sshKeysPopulated() = render("p6-ssh-keys") {
        SshKeysScreen(
            state = SshKeysUiState(
                loaded = true,
                keys = listOf(
                    SshKeyRow(1, "hetzner-key", "sha256:0f2a9c4d8e1b"),
                    SshKeyRow(2, "generated-1756900000000", "sha256:77c1aa30bb42"),
                ),
                message = "Generated generated-1756900000000",
            ),
            onBack = {},
            onGenerate = {},
            onImportPasted = { _, _ -> },
            onPickFile = {},
            onDelete = {},
            onDismissMessage = {},
        )
    }

    /** The scanner's non-camera states (the preview itself needs a device). */
    @Test
    fun qrScannerPermissionDenied() = render("p6-qr-scanner-permission-denied") {
        QrScannerScreen(
            state = QrScannerViewModel.State.PermissionDenied(canRetry = true),
            onScanned = {},
            onRetryPermission = {},
            onPickImage = {},
            onRetry = {},
            onClose = {},
        )
    }

    @Test
    fun qrScannerFailed() = render("p6-qr-scanner-failed") {
        QrScannerScreen(
            state = QrScannerViewModel.State.Failed("That QR is not a PocketShell host code"),
            onScanned = {},
            onRetryPermission = {},
            onPickImage = {},
            onRetry = {},
            onClose = {},
        )
    }

    private fun key(id: Long, name: String) =
        SshKeyEntity(id = id, name = name, privateKeyPath = "/data/data/ssh-keys/$name")

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
