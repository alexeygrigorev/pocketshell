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
import com.pocketshell.next.hosts.SshImportAuth
import com.pocketshell.next.hosts.SshImportConfig
import com.pocketshell.next.hosts.SshImportPayloadCodec
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
 *
 * ## Reading these PNGs
 *
 * Each capture is the FULL 1236x2745 phone viewport, so it is 2.2x taller than
 * it is wide. Downscaled to fit a review pane, sparse content at one end is easy
 * to mis-attribute to the other — #2630's review reported a semi-transparent
 * "Add host" ghosted into the Hosts header, and the pixels showed the only
 * "Add host" in the file was the real one, 2500px lower. Before filing a visual
 * bug from one of these, crop or amplify the region:
 *
 * ```
 * convert build/renders/<name>.png -crop 1236x400+0+0 +repage -level 5%,14% /tmp/band.png
 * ```
 *
 * The `-level` stretch maps the near-background range to full contrast, so any
 * ink down to alpha 1/255 becomes obvious and a genuinely empty band stays flat.
 * A screen's real structure is asserted by its own test class (for the host
 * list, `HostListDensityTest`), never by a reading of one of these images.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class HostScreenRenders {

    /** A fresh install: the state that had no way forward before P-6. */
    @Test
    fun hostListEmpty() = render("quiet-412-hosts-empty") {
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
    fun hostListUpdateBanner() = render("quiet-412-hosts-update") {
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
    fun hostListPopulated() = render("quiet-412-hosts-populated") {
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
    fun hostFormAdd() = render("quiet-412-host-form-add") {
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
    fun hostFormEdit() = render("quiet-412-host-form-edit") {
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
    fun hostFormErrors() = render("quiet-412-host-form-validation") {
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
    fun sshKeysEmpty() = render("quiet-412-ssh-keys-empty") {
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
    fun sshKeysPopulated() = render("quiet-412-ssh-keys") {
        SshKeysScreen(
            state = SshKeysUiState(
                loaded = true,
                keys = listOf(
                    SshKeyRow(1, "hetzner-key", ""),
                    SshKeyRow(2, "workstation-key", ""),
                ),
                message = "Key added on this device",
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
    fun qrScannerPermissionDenied() = render("quiet-412-qr-permission-denied") {
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
    fun qrScannerFailed() = render("quiet-412-qr-failed") {
        QrScannerScreen(
            state = QrScannerViewModel.State.Failed("That QR is not a PocketShell host code"),
            onScanned = {},
            onRetryPermission = {},
            onPickImage = {},
            onRetry = {},
            onClose = {},
        )
    }

    @Test
    fun qrScannerReview() = render("quiet-412-qr-review") {
        val config = SshImportConfig(
            name = "Development host",
            host = "dev.example.test",
            port = 22,
            username = "alexey",
            auth = SshImportAuth.KeyReference("hetzner-key"),
        )
        QrScannerScreen(
            state = QrScannerViewModel.State.Review(
                config = config,
                payload = SshImportPayloadCodec.encode(config),
            ),
            onScanned = {},
            onRetryPermission = {},
            onPickImage = {},
            onRetry = {},
            onConfirmImport = {},
            onCancelReview = {},
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
