package com.pocketshell.next.hosts

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing

/** Stable test tags. */
const val QR_SCANNER_PREVIEW_TAG: String = "qr-scanner-preview"
const val QR_SCANNER_PROGRESS_TAG: String = "qr-scanner-progress"

/**
 * The QR import screen (rewrite task P-6): point the camera at a host QR, get a
 * host.
 *
 * The camera is wrapped rather than reimplemented — `DecoratedBarcodeView` from
 * `zxing-android-embedded`, the same library the shipping client used, held in
 * an [AndroidView] and paused when the composition leaves so the camera is
 * released as soon as the user navigates away.
 *
 * Everything decidable lives in [QrScannerViewModel], which sees only strings;
 * this file is permission plumbing, a camera surface, and a state rendering.
 */
@Composable
fun QrScannerRoute(
    onFinished: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QrScannerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val currentOnFinished by rememberUpdatedState(onFinished)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.onPermissionGranted()
        } else {
            // `shouldShowRequestPermissionRationale` is false once the user has
            // chosen "don't ask again": the prompt will never appear again, so
            // the screen must offer the file fallback rather than a Retry that
            // silently does nothing.
            val activity = context as? Activity
            val canRetry = activity
                ?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA) }
                ?: true
            viewModel.onPermissionDenied(canRetry = canRetry)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val decoded = runCatching {
            context.contentResolver.openInputStream(uri)?.use { HostQrCode.decode(it).getOrThrow() }
        }.getOrNull()
        if (decoded == null) {
            viewModel.onScanFailed("No QR code found in that image")
        } else {
            viewModel.onPayloadPicked(decoded)
        }
    }

    LaunchedEffect(state) {
        if (state !is QrScannerViewModel.State.RequestingPermission) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onPermissionGranted() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(state) {
        val imported = state as? QrScannerViewModel.State.Imported ?: return@LaunchedEffect
        currentOnFinished(imported.message)
    }

    QrScannerScreen(
        state = state,
        onScanned = viewModel::onScanned,
        onRetryPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        onPickImage = { imagePicker.launch("image/*") },
        onRetry = viewModel::retry,
        onClose = onClose,
        modifier = modifier,
    )
}

/** Stateless rendering of the scan state machine. */
@Composable
fun QrScannerScreen(
    state: QrScannerViewModel.State,
    onScanned: (String) -> Unit,
    onRetryPermission: () -> Unit,
    onPickImage: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        ScreenHeader(
            title = "Scan host QR",
            trailing = {
                PocketShellButton(
                    text = "Close",
                    onClick = onClose,
                    variant = ButtonVariant.Text,
                    compact = true,
                )
            },
        )

        when (state) {
            is QrScannerViewModel.State.RequestingPermission -> Centered(
                "PocketShell needs the camera to read a host QR code.",
            )

            is QrScannerViewModel.State.PermissionDenied -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(PocketShellSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Banner(text = "Camera access is off.", role = BannerRole.Warning)
                if (state.canRetry) {
                    PocketShellButton(text = "Allow camera", onClick = onRetryPermission)
                }
                PocketShellButton(
                    text = "Pick a QR image instead",
                    onClick = onPickImage,
                    variant = ButtonVariant.Secondary,
                )
            }

            is QrScannerViewModel.State.Scanning -> Column(modifier = Modifier.fillMaxSize()) {
                if (state.total > 0) {
                    Text(
                        text = "Scanned ${state.scanned} of ${state.total}",
                        color = PocketShellColors.Text,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .padding(PocketShellSpacing.md)
                            .testTag(QR_SCANNER_PROGRESS_TAG),
                    )
                }
                CameraPreview(
                    onScanned = onScanned,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(PocketShellSpacing.lg)
                        .testTag(QR_SCANNER_PREVIEW_TAG),
                )
            }

            is QrScannerViewModel.State.Importing -> Centered("Importing…")

            is QrScannerViewModel.State.Imported -> Centered(state.message)

            is QrScannerViewModel.State.Failed -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(PocketShellSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Banner(text = state.message, role = BannerRole.Error)
                Row(horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm)) {
                    PocketShellButton(text = "Try again", onClick = onRetry)
                    PocketShellButton(
                        text = "Pick a QR image",
                        onClick = onPickImage,
                        variant = ButtonVariant.Secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Centered(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(PocketShellSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = PocketShellColors.TextSecondary,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * The camera surface.
 *
 * The decoder is restricted to [BarcodeFormat.QR_CODE] so a barcode on
 * packaging in frame cannot fire a decode callback the import path then has to
 * reject.
 */
@Composable
private fun CameraPreview(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    val view = remember(context) {
        DecoratedBarcodeView(context).apply {
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            setStatusText("")
        }
    }

    AndroidView(
        factory = { view },
        modifier = modifier.background(Color.Black),
        update = { decorated ->
            decorated.decodeContinuous(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) {
                    result.text?.let(currentOnScanned)
                }
            })
        },
    )

    DisposableEffect(view) {
        view.resume()
        onDispose { view.pause() }
    }
}
