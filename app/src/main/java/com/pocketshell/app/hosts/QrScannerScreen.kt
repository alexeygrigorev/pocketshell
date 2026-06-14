package com.pocketshell.app.hosts

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.google.zxing.BarcodeFormat
import com.pocketshell.app.R
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Live QR scanner screen (issue #129).
 *
 * Wraps zxing-android-embedded's [DecoratedBarcodeView] in an
 * [AndroidView] so the camera surface integrates with Compose's
 * lifecycle. Each successful decode is handed to [QrScannerViewModel];
 * once the VM transitions to [QrScannerViewModel.State.Decoded] the
 * screen invokes [onDecoded] with the assembled payload and the host
 * Activity is responsible for calling `HostListViewModel.importSharedHostPayload`
 * and popping back to the host list.
 *
 * The fallback file-picker affordance (visible on permission-denied)
 * fires the same `GetContent` intent the host-list Import action uses
 * and routes the resulting URI back through [onPickFile].
 */
@Composable
fun QrScannerScreen(
    onDecoded: (String) -> Unit,
    onPickFile: (Uri) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QrScannerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val currentOnDecoded by rememberUpdatedState(onDecoded)

    // Snapshot the initial permission state. Once granted on this
    // device the system will not surface the prompt again unless the
    // user revokes the permission from settings; on first launch we
    // ask immediately.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.onPermissionGranted()
        } else {
            val activity = context as? Activity
            val canRetry = activity?.let {
                androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                    it,
                    Manifest.permission.CAMERA,
                )
            } ?: true
            viewModel.onPermissionDenied(canRetry = canRetry)
        }
    }

    // Drive the permission flow whenever we re-enter RequestingPermission.
    LaunchedEffect(state) {
        if (state is QrScannerViewModel.State.RequestingPermission) {
            val already = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (already) {
                viewModel.onPermissionGranted()
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // Once decoded, hop back through the activity's callback. The
    // LaunchedEffect key is the *value* of the payload so re-entry
    // with the same payload after navigation away is not re-fired.
    LaunchedEffect(state) {
        val decoded = state as? QrScannerViewModel.State.Decoded ?: return@LaunchedEffect
        currentOnDecoded(decoded.payload)
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(onPickFile) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .testTag(QR_SCANNER_ROOT_TAG),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Slice E1a: the bespoke 56dp / 18.sp app bar is replaced by the
            // shared [ScreenHeader] dev-tool block. The camera viewport,
            // viewfinder overlay, and scan flow below are untouched. The Close
            // affordance moves into the header's trailing slot and drops its
            // raw 13.sp for the muted `labelSmall` token.
            ScreenHeader(
                title = context.getString(R.string.qr_scanner_title),
                modifier = Modifier.border(width = 1.dp, color = PocketShellColors.BorderSoft),
                trailing = {
                    TextButton(onClick = onClose) {
                        Text(
                            text = context.getString(R.string.qr_scanner_close),
                            color = PocketShellColors.Accent,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
            )

            when (val current = state) {
                is QrScannerViewModel.State.RequestingPermission -> {
                    InfoCard(
                        title = context.getString(R.string.qr_scanner_title),
                        body = context.getString(R.string.qr_scanner_camera_permission_rationale),
                    )
                }

                is QrScannerViewModel.State.PermissionDenied -> {
                    PermissionDeniedBlock(
                        onRetry = if (current.canRetry) {
                            { permissionLauncher.launch(Manifest.permission.CAMERA) }
                        } else null,
                        onPickFile = { filePicker.launch("*/*") },
                    )
                }

                is QrScannerViewModel.State.Scanning -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        ScanningPrompt(state = current)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            BarcodeScannerSurface(
                                onPayload = viewModel::onPayloadDecoded,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(20.dp))
                                    .testTag(QR_SCANNER_PREVIEW_TAG),
                            )
                            ViewfinderOverlay(modifier = Modifier.fillMaxSize())
                        }
                        Text(
                            text = context.getString(R.string.qr_scanner_prompt),
                            color = PocketShellColors.TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(
                                horizontal = PocketShellSpacing.lg + PocketShellSpacing.sm,
                                vertical = PocketShellSpacing.lg,
                            ),
                        )
                    }
                }

                is QrScannerViewModel.State.Decoded -> {
                    InfoCard(
                        title = "Decoded",
                        body = "Importing host…",
                    )
                }

                is QrScannerViewModel.State.Error -> {
                    ErrorBlock(
                        message = current.message,
                        onRetry = { viewModel.retry() },
                        onClose = onClose,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanningPrompt(state: QrScannerViewModel.State.Scanning) {
    val context = LocalContext.current
    if (state.scanTotal > 0) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = PocketShellSpacing.lg + PocketShellSpacing.sm,
                    vertical = PocketShellDensity.rowPadV,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = PocketShellColors.Surface,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .padding(
                        horizontal = PocketShellDensity.chipPadH,
                        vertical = PocketShellSpacing.xs,
                    )
                    .testTag(QR_SCANNER_PROGRESS_CHIP_TAG),
            ) {
                Text(
                    text = context.getString(
                        R.string.qr_scanner_progress_chip,
                        state.scanCount,
                        state.scanTotal,
                    ),
                    color = PocketShellColors.Text,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun PermissionDeniedBlock(
    onRetry: (() -> Unit)?,
    onPickFile: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PocketShellSpacing.lg + PocketShellSpacing.sm),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = context.getString(R.string.qr_scanner_permission_denied),
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
        )
        Spacer(modifier = Modifier.height(PocketShellSpacing.lg))
        if (onRetry != null) {
            PocketShellButton(
                text = context.getString(R.string.qr_scanner_permission_retry),
                onClick = onRetry,
                variant = ButtonVariant.Primary,
                modifier = Modifier.testTag(QR_SCANNER_PERMISSION_RETRY_TAG),
            )
            Spacer(modifier = Modifier.height(PocketShellSpacing.sm))
        }
        PocketShellButton(
            text = context.getString(R.string.qr_scanner_fallback_pick_file),
            onClick = onPickFile,
            variant = ButtonVariant.Text,
            modifier = Modifier.testTag(QR_SCANNER_PICK_FILE_TAG),
        )
    }
}

@Composable
private fun ErrorBlock(
    message: String,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PocketShellSpacing.lg + PocketShellSpacing.sm),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = PocketShellColors.Red,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(PocketShellSpacing.sm))
        Text(
            text = context.getString(R.string.qr_scanner_error_generic),
            color = PocketShellColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(modifier = Modifier.height(PocketShellSpacing.lg))
        Row {
            PocketShellButton(
                text = context.getString(R.string.qr_scanner_retry),
                onClick = onRetry,
                variant = ButtonVariant.Primary,
                modifier = Modifier.testTag(QR_SCANNER_ERROR_RETRY_TAG),
            )
            Spacer(modifier = Modifier.width(PocketShellSpacing.sm))
            PocketShellButton(
                text = context.getString(R.string.qr_scanner_close),
                onClick = onClose,
                variant = ButtonVariant.Text,
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PocketShellSpacing.lg + PocketShellSpacing.sm),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = PocketShellColors.Text,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(PocketShellSpacing.sm))
        Text(
            text = body,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
        )
    }
}

/**
 * Lightweight corner-bracket viewfinder drawn over the camera preview.
 * Uses four [Box] strips per corner so we don't need a custom
 * `Canvas` implementation — the strips are positioned with offsets
 * to look like the classic camera bracket shape.
 */
@Composable
private fun ViewfinderOverlay(modifier: Modifier = Modifier) {
    val accent = PocketShellColors.Accent
    Box(modifier = modifier) {
        val bracketSize = 28.dp
        val thickness = 3.dp
        // Top-left.
        Box(
            modifier = Modifier
                .padding(start = 16.dp, top = 16.dp)
                .size(width = bracketSize, height = thickness)
                .background(accent)
                .align(Alignment.TopStart),
        )
        Box(
            modifier = Modifier
                .padding(start = 16.dp, top = 16.dp)
                .size(width = thickness, height = bracketSize)
                .background(accent)
                .align(Alignment.TopStart),
        )
        // Top-right.
        Box(
            modifier = Modifier
                .padding(end = 16.dp, top = 16.dp)
                .size(width = bracketSize, height = thickness)
                .background(accent)
                .align(Alignment.TopEnd),
        )
        Box(
            modifier = Modifier
                .padding(end = 16.dp, top = 16.dp)
                .size(width = thickness, height = bracketSize)
                .background(accent)
                .align(Alignment.TopEnd),
        )
        // Bottom-left.
        Box(
            modifier = Modifier
                .padding(start = 16.dp, bottom = 16.dp)
                .size(width = bracketSize, height = thickness)
                .background(accent)
                .align(Alignment.BottomStart),
        )
        Box(
            modifier = Modifier
                .padding(start = 16.dp, bottom = 16.dp)
                .size(width = thickness, height = bracketSize)
                .background(accent)
                .align(Alignment.BottomStart),
        )
        // Bottom-right.
        Box(
            modifier = Modifier
                .padding(end = 16.dp, bottom = 16.dp)
                .size(width = bracketSize, height = thickness)
                .background(accent)
                .align(Alignment.BottomEnd),
        )
        Box(
            modifier = Modifier
                .padding(end = 16.dp, bottom = 16.dp)
                .size(width = thickness, height = bracketSize)
                .background(accent)
                .align(Alignment.BottomEnd),
        )
    }
}

/**
 * Hosts a [DecoratedBarcodeView] inside Compose. The view is paused
 * when leaving the composition so the camera releases promptly when
 * the user navigates back to the host list.
 *
 * We restrict the decoder factory to [BarcodeFormat.QR_CODE] so we
 * don't accidentally pick up barcodes from posters / packaging while
 * the camera is open.
 */
@Composable
private fun BarcodeScannerSurface(
    onPayload: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnPayload by rememberUpdatedState(onPayload)
    val context = LocalContext.current
    val barcodeView = remember(context) {
        DecoratedBarcodeView(context).apply {
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            setStatusText("")
        }
    }
    AndroidView(
        factory = { barcodeView },
        modifier = modifier
            .background(Color.Black),
        update = { view ->
            view.decodeContinuous(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) {
                    val text = result.text ?: return
                    currentOnPayload(text)
                }
            })
        },
    )
    DisposableEffect(barcodeView) {
        barcodeView.resume()
        onDispose {
            barcodeView.pause()
        }
    }
}

// Test tag exports — used by Compose UI tests and androidTest hooks.
internal const val QR_SCANNER_ROOT_TAG: String = "qr-scanner:root"
internal const val QR_SCANNER_PREVIEW_TAG: String = "qr-scanner:preview"
internal const val QR_SCANNER_PROGRESS_CHIP_TAG: String = "qr-scanner:progress"
internal const val QR_SCANNER_PERMISSION_RETRY_TAG: String = "qr-scanner:permission-retry"
internal const val QR_SCANNER_PICK_FILE_TAG: String = "qr-scanner:pick-file"
internal const val QR_SCANNER_ERROR_RETRY_TAG: String = "qr-scanner:error-retry"
