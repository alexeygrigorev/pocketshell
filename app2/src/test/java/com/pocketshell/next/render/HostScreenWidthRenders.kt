package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.hosts.AddEditHostScreen
import com.pocketshell.next.hosts.HostFormErrors
import com.pocketshell.next.hosts.HostFormState
import com.pocketshell.next.hosts.HostListScreen
import com.pocketshell.next.hosts.HostListUiState
import com.pocketshell.next.hosts.HostRow
import com.pocketshell.next.hosts.DuplicateAction
import com.pocketshell.next.hosts.ExistingHost
import com.pocketshell.next.hosts.QrScannerScreen
import com.pocketshell.next.hosts.QrScannerViewModel
import com.pocketshell.next.hosts.SshImportAuth
import com.pocketshell.next.hosts.SshImportConfig
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Width edge renders for the quiet Hosts/access layouts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-night-xxhdpi")
class HostScreenRenders360 {

    @Test
    fun hostsEmpty() = render("quiet-360-hosts-empty") { hosts(emptyList()) }

    @Test
    fun hostsPopulated() = render("quiet-360-hosts-populated") {
        hosts(listOf(HostRow(1, "Development host", "alexey@dev.example.test")))
    }

    @Test
    fun hostFormAdd() = render("quiet-360-host-form-add") {
        AddEditHostScreen(
            state = HostFormState(),
            keys = listOf(key(1, "device-key")),
            onChange = {},
            onSave = {},
            onCancel = {},
            onAddKey = {},
        )
    }

    @Test
    fun hostFormEdit() = render("quiet-360-host-form-edit") {
        AddEditHostScreen(
            state = HostFormState(
                name = "Development host",
                hostname = "dev.example.test",
                port = "2222",
                username = "alexey",
                usageCommand = "pocketshell host usage",
                selectedKeyId = 1,
                editing = true,
            ),
            keys = listOf(key(1, "device-key")),
            onChange = {},
            onSave = {},
            onCancel = {},
            onAddKey = {},
        )
    }

    @Test
    fun hostFormValidation() = render("quiet-360-host-form-validation") {
        AddEditHostScreen(
            state = HostFormState(
                port = "bad",
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
    fun qrReviewDuplicate() = render("quiet-360-qr-review-duplicate") {
        QrScannerScreen(
            state = duplicateReview(),
            onScanned = {},
            onRetryPermission = {},
            onPickImage = {},
            onRetry = {},
            onConfirmImport = { _: DuplicateAction? -> },
            onCancelReview = {},
            onClose = {},
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w600dp-h1000dp-night-xxhdpi")
class HostScreenRenders600 {

    @Test
    fun hostsEmpty() = render("quiet-600-hosts-empty") { hosts(emptyList()) }

    @Test
    fun hostsPopulated() = render("quiet-600-hosts-populated") {
        hosts(
            listOf(
                HostRow(1, "Development host", "alexey@dev.example.test"),
                HostRow(2, "Home workstation", "alexey@workstation.example.test"),
            ),
        )
    }

    @Test
    fun hostFormAdd() = render("quiet-600-host-form-add") {
        AddEditHostScreen(
            state = HostFormState(),
            keys = listOf(key(1, "device-key")),
            onChange = {},
            onSave = {},
            onCancel = {},
            onAddKey = {},
        )
    }

    @Test
    fun hostFormEdit() = render("quiet-600-host-form-edit") {
        AddEditHostScreen(
            state = HostFormState(
                name = "Development host",
                hostname = "dev.example.test",
                port = "2222",
                username = "alexey",
                selectedKeyId = 1,
                editing = true,
            ),
            keys = listOf(key(1, "device-key")),
            onChange = {},
            onSave = {},
            onCancel = {},
            onAddKey = {},
        )
    }

    @Test
    fun hostFormValidation() = render("quiet-600-host-form-validation") {
        AddEditHostScreen(
            state = HostFormState(
                port = "bad",
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
    fun qrReviewDuplicate() = render("quiet-600-qr-review-duplicate") {
        QrScannerScreen(
            state = duplicateReview(),
            onScanned = {},
            onRetryPermission = {},
            onPickImage = {},
            onRetry = {},
            onConfirmImport = { _: DuplicateAction? -> },
            onCancelReview = {},
            onClose = {},
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-night-xxhdpi")
class HostScreenRendersLargeText {

    @Test
    fun qrReviewDuplicateLargeText() = render("quiet-360-qr-review-duplicate-large-text", fontScale = 1.3f) {
        QrScannerScreen(
            state = duplicateReview(),
            onScanned = {},
            onRetryPermission = {},
            onPickImage = {},
            onRetry = {},
            onConfirmImport = { _: DuplicateAction? -> },
            onCancelReview = {},
            onClose = {},
        )
    }
}

@Composable
private fun hosts(rows: List<HostRow>) {
    HostListScreen(
        state = HostListUiState(loaded = true, hosts = rows),
        onOpenHost = {},
        onAddHost = {},
        onEditHost = {},
        onScanQr = {},
        onOpenSettings = {},
        onDeleteHost = {},
    )
}

private fun key(id: Long, name: String) =
    SshKeyEntity(id = id, name = name, privateKeyPath = "/data/data/ssh-keys/$name")

private fun duplicateReview() = QrScannerViewModel.State.Review(
    config = SshImportConfig(
        name = "Development host",
        host = "dev.example.test",
        port = 22,
        username = "alexey",
        auth = SshImportAuth.KeyReference("device-key"),
    ),
    payload = "review-payload",
    existingHost = ExistingHost(id = 7L, name = "Existing development host"),
    duplicateChecked = true,
)

private fun render(
    name: String,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    captureRoboImage("build/renders/$name.png") {
        PocketShellTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
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
