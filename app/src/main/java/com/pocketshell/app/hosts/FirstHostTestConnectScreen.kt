package com.pocketshell.app.hosts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.bootstrap.BootstrapTool
import com.pocketshell.app.bootstrap.HostBootstrapSheet
import com.pocketshell.app.bootstrap.HostBootstrapSheetState
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

const val FIRST_HOST_TEST_CONNECT_SCREEN_TAG = "first-host-test-connect:screen"
const val FIRST_HOST_TEST_CONNECT_RETRY_TAG = "first-host-test-connect:retry"
const val FIRST_HOST_TEST_CONNECT_EDIT_TAG = "first-host-test-connect:edit"
const val FIRST_HOST_TEST_CONNECT_OPEN_TAG = "first-host-test-connect:open"

private const val FIRST_HOST_TEST_CONNECT_TIMEOUT_MS = 10_000

@Composable
fun FirstHostTestConnectScreen(
    hostId: Long,
    onBack: () -> Unit,
    onEditHost: (Long) -> Unit,
    onOpenHost: (HostEntity, keyPath: String, passphrase: CharArray?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FirstHostTestConnectViewModel = hiltViewModel(),
    bootstrapViewModel: HostListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val bootstrapState by bootstrapViewModel.bootstrapState.collectAsState()
    val bootstrapHostName by bootstrapViewModel.bootstrapHostName.collectAsState()
    val pendingNavigation by bootstrapViewModel.pendingNavigation.collectAsState()
    val currentOnOpenHost by rememberUpdatedState(onOpenHost)
    LaunchedEffect(hostId) {
        viewModel.start(hostId)
    }
    LaunchedEffect(pendingNavigation) {
        val pending = pendingNavigation
        if (pending != null && pending.ready) {
            currentOnOpenHost(pending.host, pending.keyPath, pending.passphrase)
            bootstrapViewModel.consumePendingNavigation()
        }
    }

    FirstHostTestConnectContent(
        state = state,
        hostId = hostId,
        onBack = onBack,
        onEditHost = onEditHost,
        onRetry = { viewModel.start(hostId, force = true) },
        onStartSetup = { host, keyPath -> bootstrapViewModel.bootstrapHost(host, keyPath, null) },
        bootstrapState = bootstrapState,
        bootstrapHostName = bootstrapHostName,
        onInstall = { bootstrapViewModel.installTmuxOnPendingHost() },
        onInstallTool = { tool -> bootstrapViewModel.installBootstrapTool(tool) },
        onEnableNotifications = { bootstrapViewModel.installTmuxOnPendingHost() },
        onDismissSetup = { bootstrapViewModel.dismissBootstrapAndOpen() },
        modifier = modifier,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun FirstHostTestConnectContent(
    state: FirstHostTestConnectState,
    hostId: Long,
    onBack: () -> Unit,
    onEditHost: (Long) -> Unit,
    onRetry: () -> Unit,
    onStartSetup: (HostEntity, keyPath: String) -> Unit,
    bootstrapState: HostBootstrapSheetState?,
    bootstrapHostName: String,
    onInstall: () -> Unit,
    onInstallTool: (BootstrapTool) -> Unit,
    onEnableNotifications: () -> Unit,
    onDismissSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .testTag(FIRST_HOST_TEST_CONNECT_SCREEN_TAG),
    ) {
        ScreenHeader(
            title = "Test connection",
            leading = {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(role = Role.Button, onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "‹",
                        color = PocketShellColors.TextSecondary,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            },
        )
        FirstHostWizardSteps(
            activeStep = if (state.status == FirstHostTestStatus.Success) {
                FirstHostWizardStep.Setup
            } else {
                FirstHostWizardStep.Test
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PocketShellSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
        ) {
            Text(
                text = state.host?.name ?: "First host",
                color = PocketShellColors.Text,
                style = MaterialTheme.typography.titleMedium,
            )
            when (val status = state.status) {
                FirstHostTestStatus.Idle,
                FirstHostTestStatus.Testing,
                -> TestingConnection()

                FirstHostTestStatus.Success -> {
                    Text(
                        text = "Connection works.",
                        color = PocketShellColors.Green,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PocketShellButton(
                        text = "Finish setup",
                        onClick = {
                            val host = state.host ?: return@PocketShellButton
                            val key = state.key ?: return@PocketShellButton
                            onStartSetup(host, key.privateKeyPath)
                        },
                        variant = ButtonVariant.Primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(FIRST_HOST_TEST_CONNECT_OPEN_TAG),
                    )
                }

                FirstHostTestStatus.NeedsPassphrase -> {
                    Text(
                        text = "This SSH key needs a passphrase. Open the host from the hosts list to unlock it.",
                        color = PocketShellColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PocketShellButton(
                        text = "Open from hosts",
                        onClick = onBack,
                        variant = ButtonVariant.Primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(FIRST_HOST_TEST_CONNECT_OPEN_TAG),
                    )
                }

                is FirstHostTestStatus.Failed -> {
                    Text(
                        text = status.message,
                        color = PocketShellColors.Red,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
                    ) {
                        PocketShellButton(
                            text = "Retry",
                            onClick = onRetry,
                            variant = ButtonVariant.Primary,
                            modifier = Modifier
                                .weight(1f)
                                .testTag(FIRST_HOST_TEST_CONNECT_RETRY_TAG),
                        )
                        PocketShellButton(
                            text = "Edit",
                            onClick = { onEditHost(hostId) },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier
                                .weight(1f)
                                .testTag(FIRST_HOST_TEST_CONNECT_EDIT_TAG),
                        )
                    }
                }
            }
        }
    }

    bootstrapState?.let { setupState ->
        HostBootstrapSheet(
            state = setupState,
            hostName = bootstrapHostName,
            onInstall = onInstall,
            onInstallTool = onInstallTool,
            onEnableNotifications = onEnableNotifications,
            onSkip = onDismissSetup,
            onDismiss = onDismissSetup,
        )
    }
}

@Composable
private fun TestingConnection() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoadingIndicator.Spinner(size = SpinnerSize.Small)
        Text(
            text = "Testing SSH connection...",
            color = PocketShellColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

data class FirstHostTestConnectState(
    val host: HostEntity? = null,
    val key: SshKeyEntity? = null,
    val status: FirstHostTestStatus = FirstHostTestStatus.Idle,
)

sealed interface FirstHostTestStatus {
    data object Idle : FirstHostTestStatus
    data object Testing : FirstHostTestStatus
    data object Success : FirstHostTestStatus
    data object NeedsPassphrase : FirstHostTestStatus
    data class Failed(val message: String) : FirstHostTestStatus
}

@HiltViewModel
class FirstHostTestConnectViewModel @Inject constructor(
    private val hostDao: HostDao,
    private val sshKeyDao: SshKeyDao,
    private val tester: FirstHostConnectionTester,
) : ViewModel() {
    private val _state = MutableStateFlow(FirstHostTestConnectState())
    val state: StateFlow<FirstHostTestConnectState> = _state.asStateFlow()

    private var testingHostId: Long? = null

    fun start(hostId: Long, force: Boolean = false) {
        val current = _state.value
        if (!force &&
            testingHostId == hostId &&
            current.status in setOf(FirstHostTestStatus.Testing, FirstHostTestStatus.Success)
        ) {
            return
        }
        testingHostId = hostId
        _state.value = current.copy(status = FirstHostTestStatus.Testing)
        viewModelScope.launch {
            val host = hostDao.getById(hostId)
            if (host == null) {
                _state.value = FirstHostTestConnectState(
                    status = FirstHostTestStatus.Failed("Host was not saved. Go back and add it again."),
                )
                return@launch
            }
            val key = sshKeyDao.getById(host.keyId)
            if (key == null) {
                _state.value = FirstHostTestConnectState(
                    host = host,
                    status = FirstHostTestStatus.Failed("SSH key is missing. Edit the host and choose a key."),
                )
                return@launch
            }

            _state.value = FirstHostTestConnectState(
                host = host,
                key = key,
                status = FirstHostTestStatus.Testing,
            )
            if (key.hasPassphrase) {
                _state.value = FirstHostTestConnectState(
                    host = host,
                    key = key,
                    status = FirstHostTestStatus.NeedsPassphrase,
                )
                return@launch
            }
            val result = tester.test(host, key)
            _state.value = if (result.isSuccess) {
                FirstHostTestConnectState(host = host, key = key, status = FirstHostTestStatus.Success)
            } else {
                FirstHostTestConnectState(
                    host = host,
                    key = key,
                    status = FirstHostTestStatus.Failed(
                        friendlyConnectFailure(result.exceptionOrNull()),
                    ),
                )
            }
        }
    }
}

open class FirstHostConnectionTester @Inject constructor() {
    open suspend fun test(host: HostEntity, key: SshKeyEntity): Result<Unit> {
        val file = File(key.privateKeyPath)
        val exists = withContext(Dispatchers.IO) { file.exists() }
        if (!exists) {
            return Result.failure(IllegalStateException("SSH key file is missing"))
        }
        val session = SshConnection.connect(
            host = host.hostname,
            port = host.port,
            user = host.username,
            key = SshKey.Path(file),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = FIRST_HOST_TEST_CONNECT_TIMEOUT_MS,
        ).getOrElse { return Result.failure(it) }
        session.close()
        return Result.success(Unit)
    }
}

internal fun friendlyConnectFailure(error: Throwable?): String {
    val detail = error?.message?.takeIf { it.isNotBlank() }
    val base = "Could not connect. Check the hostname, port, username, and SSH key, then retry."
    return if (detail == null) base else "$base\n\n$detail"
}
