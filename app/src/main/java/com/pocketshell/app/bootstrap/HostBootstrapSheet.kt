package com.pocketshell.app.bootstrap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Coarse visual / behavioural state of the bootstrap sheet.
 *
 * The sheet is driven from outside (the [HostListViewModel][com.pocketshell.app.hosts.HostListViewModel]
 * holds the state), so each state is a pure data value with no internal
 * coroutine. The sheet renders, the user taps, the ViewModel runs the
 * install and flips the state.
 *
 * - [Prompt] — first paint when tmux is missing. "Install" and "Skip"
 *   buttons.
 * - [Installing] — install command is in flight; both buttons disabled,
 *   spinner visible.
 * - [Success] — install succeeded. The sheet auto-dismisses after a
 *   short delay or on tap of "Continue".
 * - [Failed] — install failed. The sheet shows the stderr in a scrollable
 *   block + a "Close" button that dismisses to the host list (the user
 *   chooses to retry by reconnecting).
 */
public sealed interface HostBootstrapSheetState {
    public data class Prompt(
        val needsTmux: Boolean = false,
        val report: HostBootstrapReport? = null,
    ) : HostBootstrapSheetState
    public data object Installing : HostBootstrapSheetState
    public data object Success : HostBootstrapSheetState
    public data class Failed(val message: String) : HostBootstrapSheetState
}

public const val HOST_BOOTSTRAP_SHEET_TAG: String = "host-bootstrap-sheet"
public const val HOST_BOOTSTRAP_INSTALL_ALL_TAG: String = "host-bootstrap-install-all"
public const val HOST_BOOTSTRAP_SKIP_TAG: String = "host-bootstrap-skip"
public const val HOST_BOOTSTRAP_CONTINUE_TAG: String = "host-bootstrap-continue"
public const val HOST_BOOTSTRAP_CLOSE_TAG: String = "host-bootstrap-close"
public const val HOST_BOOTSTRAP_INSTALLING_TAG: String = "host-bootstrap-installing"
public const val HOST_BOOTSTRAP_ROW_TAG_PREFIX: String = "host-bootstrap-row-"

/**
 * Compose modal that surfaces on host connect when `tmux` is missing.
 *
 * Acceptance-criterion mapping (issue #49):
 *
 * - "Bootstrap sheet appears on connect when tmux is missing" — caller
 *   shows this sheet conditionally on
 *   [com.pocketshell.app.hosts.HostListViewModel.bootstrapState].
 * - "tap install runs the command and reports outcome" — the Install
 *   button calls [onInstall], which the ViewModel handles. Success vs
 *   failure flips the [state] passed back in.
 *
 * The host name is surfaced in the title so the user can tell which host
 * they're acting on (a common slip when juggling multiple connects).
 *
 * `onSkip` and `onDismiss` are split: `onSkip` is fired when the user
 * deliberately chooses to skip ("just open the session anyway"), while
 * `onDismiss` is invoked on swipe-down / scrim tap. Callers may bind both
 * to the same handler — the API leaves the distinction available in case
 * later analytics or routing needs to differ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun HostBootstrapSheet(
    state: HostBootstrapSheetState,
    hostName: String,
    onInstall: () -> Unit,
    onInstallTool: (BootstrapTool) -> Unit = { onInstall() },
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
        shape = PocketShellShapes.large,
        modifier = modifier.testTag(HOST_BOOTSTRAP_SHEET_TAG),
    ) {
        when (state) {
            is HostBootstrapSheetState.Prompt -> PromptContent(
                hostName = hostName,
                state = state,
                onInstall = onInstall,
                onInstallTool = onInstallTool,
                onSkip = onSkip,
            )

            HostBootstrapSheetState.Installing -> InstallingContent(hostName = hostName)

            HostBootstrapSheetState.Success -> SuccessContent(
                hostName = hostName,
                onContinue = onDismiss,
            )

            is HostBootstrapSheetState.Failed -> FailedContent(
                hostName = hostName,
                message = state.message,
                onClose = onDismiss,
            )
        }
    }
}

@Composable
private fun PromptContent(
    hostName: String,
    state: HostBootstrapSheetState.Prompt,
    onInstall: () -> Unit,
    onInstallTool: (BootstrapTool) -> Unit,
    onSkip: () -> Unit,
) {
    SheetColumn {
        SheetHeader(
            title = "Host setup needed",
            subtitle = "$hostName · ${bootstrapPromptText(state)}",
        )
        SetupActions(
            state = state,
            onInstallTool = onInstallTool,
        )
        Spacer(modifier = Modifier.height(PocketShellSpacing.lg + PocketShellSpacing.xs))
        if (state.hasActionableSetup()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
            ) {
                PocketShellButton(
                    text = "Skip",
                    onClick = onSkip,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(HOST_BOOTSTRAP_SKIP_TAG),
                )
                PocketShellButton(
                    text = "Install all",
                    onClick = onInstall,
                    variant = ButtonVariant.Primary,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(HOST_BOOTSTRAP_INSTALL_ALL_TAG),
                )
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.weight(1f))
                PocketShellButton(
                    text = "Continue",
                    onClick = onSkip,
                    variant = ButtonVariant.Primary,
                    modifier = Modifier.testTag(HOST_BOOTSTRAP_CONTINUE_TAG),
                )
            }
        }
    }
}

@Composable
private fun SetupActions(
    state: HostBootstrapSheetState.Prompt,
    onInstallTool: (BootstrapTool) -> Unit,
) {
    val report = state.report ?: return
    val missingTools = report.missingTools
    if (!report.hasBootstrapSheetRows()) return

    Spacer(modifier = Modifier.height(PocketShellSpacing.lg))
    SectionHeader(
        label = "Setup actions",
        count = missingTools.size + report.versionMismatchedTools.size,
    )
    Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm)) {
        missingTools.forEach { tool ->
            SetupActionRow(
                title = tool.binaryName,
                detail = installCommand(report.installer, tool),
                statusLabel = "Missing",
                actionLabel = "Install",
                onClick = { onInstallTool(tool) },
            )
        }
        report.pocketshellVersionMismatch?.let { mismatch ->
            // One clear action (issue #779). The status word ("Outdated") and
            // the action verb ("Update") must NOT be near-synonyms, or the
            // red status badge reads as a second, competing button next to the
            // real action button. "Outdated" describes the state; "Update" is
            // the single thing the user can do about it.
            SetupActionRow(
                title = "pocketshell CLI update needed",
                detail = versionMismatchDetail(report.installer, mismatch, report.installerPath),
                statusLabel = "Outdated",
                actionLabel = "Update",
                onClick = { onInstallTool(BootstrapTool.Pocketshell) },
            )
        }
        // Mosh row intentionally not rendered. Spike #159 returned NO-GO
        // for Mosh + tmux -CC, so surfacing a permanent "unsupported" row
        // would only draw attention to a feature we do not ship. The
        // `MoshStatus` data model is kept in `HostBootstrapper.kt` so
        // re-adding the row later is a one-file UI change.
    }
}

internal fun HostBootstrapReport.hasBootstrapSheetRows(): Boolean =
    missingTools.isNotEmpty() ||
        versionMismatchedTools.isNotEmpty()

internal fun HostBootstrapSheetState.Prompt.hasActionableSetup(): Boolean =
    needsTmux ||
        report?.missingTools?.isNotEmpty() == true ||
        report?.versionMismatchedTools?.isNotEmpty() == true

internal fun HostBootstrapReport.needsPocketshellDaemonSetup(): Boolean {
    val daemonStatus = daemon
    return daemonStatus !is PocketshellDaemonStatus.Running || !daemonStatus.enabled
}

internal fun HostBootstrapReport.needsPocketshellDaemonAction(): Boolean {
    val daemonStatus = daemon
    return daemonStatus is PocketshellDaemonStatus.Missing ||
        daemonStatus is PocketshellDaemonStatus.InstalledStopped ||
        daemonStatus is PocketshellDaemonStatus.Running && !daemonStatus.enabled
}

@Composable
private fun SetupActionRow(
    title: String,
    detail: String,
    statusLabel: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    ListRow(
        title = title,
        subtitle = detail,
        modifier = Modifier
            .testTag(HOST_BOOTSTRAP_ROW_TAG_PREFIX + title)
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
            .border(1.dp, PocketShellColors.Border, PocketShellShapes.small),
        leading = {
            StatusDot(status = ConnectionStatus.Error)
        },
        trailing = {
            Badge(label = statusLabel, role = BadgeRole.Error, mono = false)
            PocketShellButton(
                text = actionLabel,
                onClick = onClick,
                variant = ButtonVariant.Secondary,
            )
        },
    )
}

@Composable
private fun SetupInfoRow(title: String, detail: String) {
    Column(
        modifier = Modifier
            .testTag(HOST_BOOTSTRAP_ROW_TAG_PREFIX + title)
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
            .border(1.dp, PocketShellColors.Border, PocketShellShapes.small)
            .padding(
                horizontal = PocketShellDensity.rowPadH,
                vertical = PocketShellDensity.rowPadV,
            ),
    ) {
        Text(
            text = title,
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(PocketShellSpacing.xs))
        Text(
            text = detail,
            color = PocketShellColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun InstallingContent(hostName: String) {
    SheetColumn {
        SheetHeader(
            title = "Setting up host…",
            subtitle = "$hostName · running installer and systemd commands. This can take a few seconds on a fresh host.",
        )
        Spacer(modifier = Modifier.height(PocketShellSpacing.lg + PocketShellSpacing.xs))
        ListRow(
            title = "working…",
            modifier = Modifier
                .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
                .border(1.dp, PocketShellColors.Border, PocketShellShapes.small),
            leading = {
                StatusDot(status = ConnectionStatus.Connecting)
            },
            trailing = {
                LoadingIndicator.Spinner(
                    size = SpinnerSize.Small,
                    modifier = Modifier.testTag(HOST_BOOTSTRAP_INSTALLING_TAG),
                )
            },
        )
    }
}

@Composable
private fun SuccessContent(
    hostName: String,
    onContinue: () -> Unit,
) {
    SheetColumn {
        SheetHeader(
            title = "Host ready",
            subtitle = hostBootstrapSuccessSubtitle(hostName),
        )
        Spacer(modifier = Modifier.height(PocketShellSpacing.lg + PocketShellSpacing.xs))
        // Issue #885 (hard cut, D22): after a successful install/update the
        // sheet just acknowledges "Host ready" and offers a single Continue —
        // tapping it proceeds to whatever the user was opening (the
        // session/folder list). The earlier "Open Usage" CTA (#117) was
        // removed: opening the usage panel after a host update was never what
        // the user wanted.
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.weight(1f))
            PocketShellButton(
                text = "Continue",
                onClick = onContinue,
                variant = ButtonVariant.Primary,
                modifier = Modifier.testTag(HOST_BOOTSTRAP_CONTINUE_TAG),
            )
        }
    }
}

internal fun hostBootstrapSuccessSubtitle(hostName: String): String =
    "$hostName · tmux and the pocketshell CLI are ready."

@Composable
private fun FailedContent(hostName: String, message: String, onClose: () -> Unit) {
    SheetColumn {
        SheetHeader(
            title = "Install failed",
            subtitle = "$hostName · the package manager reported an error.",
        )
        Spacer(modifier = Modifier.height(PocketShellSpacing.md))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 220.dp)
                .background(
                    color = PocketShellColors.SurfaceElev,
                    shape = PocketShellShapes.small,
                )
                .border(
                    width = 1.dp,
                    color = PocketShellColors.Border,
                    shape = PocketShellShapes.small,
                )
                .padding(PocketShellDensity.rowPadH)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = message.ifBlank { "(no error message returned)" },
                color = PocketShellColors.Text,
                style = PocketShellType.bodyMono,
            )
        }
        Spacer(modifier = Modifier.height(PocketShellSpacing.lg))
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.weight(1f))
            // Secondary so the visual matches the rest of the sheet.
            PocketShellButton(
                text = "Close",
                onClick = onClose,
                variant = ButtonVariant.Secondary,
                modifier = Modifier.testTag(HOST_BOOTSTRAP_CLOSE_TAG),
            )
        }
    }
}

private fun bootstrapPromptText(state: HostBootstrapSheetState.Prompt): String {
    val parts = mutableListOf<String>()
    if (state.needsTmux) {
        parts += "tmux"
    }
    val report = state.report
    report?.missingTools
        ?.mapTo(parts) { it.binaryName }
    if (report?.versionMismatchedTools?.isNotEmpty() == true) {
        parts += "pocketshell CLI update"
    }

    val missing = parts.distinct().joinToString()
    val installer = when (report?.installer) {
        PythonToolInstaller.Uv -> "uv tool"
        PythonToolInstaller.Pipx -> "pipx"
        null -> "uv tool or pipx"
    }
    return if (missing.isBlank()) {
        "finish server-side setup now?"
    } else {
        "install or enable $missing now? PocketShell uses $installer for Python tools."
    }
}

private fun installCommand(installer: PythonToolInstaller?, tool: BootstrapTool): String = when (installer) {
    PythonToolInstaller.Uv -> uvToolInstallCommand(tool, upgrade = false)
    PythonToolInstaller.Pipx -> "pipx install ${tool.packageName}"
    null -> "${uvToolInstallCommand(tool, upgrade = false)} or pipx install ${tool.packageName}"
}

internal fun upgradeCommand(installer: PythonToolInstaller?, tool: BootstrapTool): String = when (installer) {
    PythonToolInstaller.Uv -> uvToolInstallCommand(tool, upgrade = true)
    PythonToolInstaller.Pipx -> "pipx upgrade ${tool.packageName}"
    null -> "${uvToolInstallCommand(tool, upgrade = true)} or pipx upgrade ${tool.packageName}"
}

internal fun versionMismatchDetail(
    installer: PythonToolInstaller?,
    mismatch: ToolStatus.VersionMismatch,
    installerPath: String? = null,
): String =
    buildString {
        append("path ${mismatch.path}; remote ${mismatch.currentVersion}, expected ${mismatch.expectedVersion}; ")
        val installerName = when (installer) {
            PythonToolInstaller.Uv -> "uv"
            PythonToolInstaller.Pipx -> "pipx"
            null -> null
        }
        if (installerName != null && !installerPath.isNullOrBlank()) {
            append("$installerName at $installerPath; ")
        }
        append(upgradeCommand(installer, BootstrapTool.Pocketshell))
    }

internal fun cliUpdateFailureMessage(
    mismatch: ToolStatus.VersionMismatch?,
    installer: PythonToolInstaller?,
    stderr: String,
    exitCode: Int,
): String = buildString {
    append("PocketShell CLI update failed")
    if (exitCode >= 0) append(" (exit $exitCode)")
    append(".")
    if (mismatch != null) {
        append("\n\nRemote: ${mismatch.currentVersion}\nExpected: ${mismatch.expectedVersion}\nPath: ${mismatch.path}")
    }
    val error = stderr.ifBlank { if (exitCode >= 0) "exit $exitCode" else "" }
    if (error.isNotBlank()) {
        append("\n\n$error")
    }
    append("\n\nManual update:\n")
    append(upgradeCommand(installer, BootstrapTool.Pocketshell))
    append("\n\nIf that does not update the binary above, run the matching command over SSH and make sure ~/.local/bin is on PATH.")
}

/**
 * Issue #779: the update command ran and EXITED 0, but the host's
 * `pocketshell` is still on the same (too-old) version afterwards — the
 * upgrade was a silent no-op. Without this the sheet just re-renders the
 * same "update needed" row, so the user taps Update, sees a spinner, and is
 * dropped back on the identical prompt with no explanation ("pressing it
 * does nothing"). This turns that dead end into an explicit message naming
 * the still-installed version and the most likely cause (the host's package
 * index / installer cache hiding the newer release), plus the manual command.
 */
internal fun cliUpdateNoChangeMessage(
    mismatch: ToolStatus.VersionMismatch?,
    installer: PythonToolInstaller?,
): String = buildString {
    append("PocketShell CLI update ran but did not change the installed version.")
    if (mismatch != null) {
        append(
            "\n\nThe host still reports ${mismatch.currentVersion}; the app needs " +
                "${mismatch.expectedVersion}.\nPath: ${mismatch.path}",
        )
    }
    append(
        "\n\nThe installer reported success but found nothing newer to install. " +
            "This usually means the host's package index or installer cache does " +
            "not yet see the newer pocketshell release.",
    )
    append("\n\nTry again over SSH (this clears most installer caches):\n")
    append(upgradeCommand(installer, BootstrapTool.Pocketshell))
    append("\n\nIf it still reports nothing to update, make sure ~/.local/bin is on PATH and the host can reach PyPI.")
}

@Composable
private fun SheetColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                PaddingValues(
                    start = PocketShellSpacing.lg + PocketShellSpacing.xs,
                    end = PocketShellSpacing.lg + PocketShellSpacing.xs,
                    top = PocketShellSpacing.sm,
                    bottom = PocketShellSpacing.lg + PocketShellSpacing.md,
                ),
            ),
    ) {
        content()
    }
}
