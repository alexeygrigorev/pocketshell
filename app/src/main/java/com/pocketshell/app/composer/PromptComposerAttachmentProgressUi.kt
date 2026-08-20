package com.pocketshell.app.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.ProgressBar
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellType

internal const val COMPOSER_ATTACHMENT_PROGRESS_BAR_TAG = "prompt-composer-attachment-progress-bar"
internal const val COMPOSER_QUEUE_UPLOAD_PROGRESS_BAR_TAG = "prompt-composer-queue-upload-progress-bar"

@Composable
internal fun AttachmentUploadProgressBanner(
    count: Int,
    progress: AttachmentTransferProgress?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = PocketShellShapes.small,
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = PocketShellShapes.small,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(COMPOSER_ATTACHMENT_PROGRESS_TAG)
            .then(attachmentProgressSemantics(progress, attachmentUploadBannerText(count, progress))),
    ) {
        Text(
            text = attachmentUploadBannerText(count, progress),
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
        )
        Spacer(modifier = Modifier.height(6.dp))
        AttachmentTransferProgressBar(
            progress = progress,
            testTag = COMPOSER_ATTACHMENT_PROGRESS_BAR_TAG,
        )
    }
}

@Composable
internal fun AttachmentTransferProgressBlock(
    progress: AttachmentTransferProgress,
    modifier: Modifier = Modifier,
    textColor: Color = PocketShellColors.TextSecondary,
    barTestTag: String = COMPOSER_QUEUE_UPLOAD_PROGRESS_BAR_TAG,
) {
    val label = formatAttachmentTransferLabel(progress)
    Column(
        modifier = modifier.then(attachmentProgressSemantics(progress, label)),
    ) {
        Text(
            text = label,
            color = textColor,
            style = PocketShellType.bodyDense,
        )
        Spacer(modifier = Modifier.height(6.dp))
        AttachmentTransferProgressBar(
            progress = progress,
            testTag = barTestTag,
        )
    }
}

@Composable
private fun AttachmentTransferProgressBar(
    progress: AttachmentTransferProgress?,
    testTag: String,
) {
    val fraction = progress?.fraction
    if (fraction != null) {
        ProgressBar(
            progress = fraction,
            modifier = Modifier.testTag(testTag),
        )
    } else {
        LoadingIndicator.Bar(modifier = Modifier.testTag(testTag))
    }
}

private fun attachmentProgressSemantics(
    progress: AttachmentTransferProgress?,
    fallback: String,
): Modifier {
    val description = progress?.let(::accessibilityProgressDescription) ?: fallback
    val fraction = progress?.fraction
    return Modifier.semantics {
        contentDescription = description
        liveRegion = LiveRegionMode.Polite
        if (fraction != null) {
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        }
    }
}
