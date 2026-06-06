package com.pocketshell.app.systemsurfaces

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.hosts.isSshKeyUnlockRequired
import com.pocketshell.app.hosts.launchSshKeyUnlock
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Composable
fun ForwardingChooserScreen(
    onBack: () -> Unit,
    onOpenPortForwardPanel: (HostEntity, keyPath: String, passphrase: CharArray?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ForwardingChooserViewModel = hiltViewModel(),
) {
    val hosts by viewModel.hosts.collectAsState()
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var pendingKey by remember { mutableStateOf<ForwardingHostKey?>(null) }
    var passphraseText by remember { mutableStateOf("") }
    var passphraseUnlockError by remember { mutableStateOf<String?>(null) }

    fun openResolvedHost(resolved: ForwardingHostKey) {
        passphraseUnlockError = null
        if (!resolved.hasPassphrase) {
            onOpenPortForwardPanel(resolved.host, resolved.keyPath, null)
            return
        }
        val showPrompt = {
            passphraseText = ""
            passphraseUnlockError = null
            pendingKey = resolved
        }
        if (!isSshKeyUnlockRequired(context)) {
            showPrompt()
            return
        }
        launchSshKeyUnlock(
            activity = activity,
            title = "Unlock SSH key passphrase",
            subtitle = "Confirm it is you before entering this key's passphrase",
            onSuccess = showPrompt,
            onError = { passphraseUnlockError = it },
        )
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        ScreenHeader(
            title = "Port Forwarding",
            subtitle = "Choose a saved host",
            modifier = Modifier.background(PocketShellColors.Surface),
            leading = { TextButtonBox(label = "<", onClick = onBack) },
        )

        passphraseUnlockError?.let { error ->
            Text(
                text = error,
                color = PocketShellColors.Red,
                style = PocketShellType.bodyDense,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        if (hosts.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "No saved hosts.",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(hosts, key = { it.id }) { host ->
                    ForwardingHostRow(
                        host = host,
                        onClick = {
                            viewModel.openHost(host) { resolved ->
                                openResolvedHost(resolved)
                            }
                        },
                    )
                }
            }
        }
    }

    pendingKey?.let { resolved ->
        ForwardingPassphraseDialog(
            keyName = resolved.keyName,
            passphrase = passphraseText,
            onPassphraseChange = { passphraseText = it },
            onDismiss = {
                pendingKey = null
                passphraseText = ""
                passphraseUnlockError = null
            },
            onOpen = {
                val passphrase = passphraseText.toCharArray()
                pendingKey = null
                passphraseText = ""
                passphraseUnlockError = null
                onOpenPortForwardPanel(resolved.host, resolved.keyPath, passphrase)
            },
        )
    }
}

@HiltViewModel
class ForwardingChooserViewModel @Inject constructor(
    hostDao: HostDao,
    private val sshKeyDao: SshKeyDao,
) : ViewModel() {
    val hosts: StateFlow<List<HostEntity>> = hostDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun openHost(host: HostEntity, onResolved: (ForwardingHostKey) -> Unit) {
        viewModelScope.launch {
            val key = sshKeyDao.getById(host.keyId) ?: return@launch
            onResolved(host.toForwardingHostKey(key))
        }
    }
}

data class ForwardingHostKey(
    val host: HostEntity,
    val keyPath: String,
    val keyName: String,
    val hasPassphrase: Boolean,
)

@Composable
private fun ForwardingHostRow(host: HostEntity, onClick: () -> Unit) {
    // Slice E1b (#539): the bespoke raw-`sp` row adopts the shared `ListRow`.
    // Host name is the dense title; `user@host:port` is the mono subtitle
    // (path/connection data); the enabled/open state is the trailing label.
    ListRow(
        title = host.name,
        subtitle = "${host.username}@${host.hostname}:${host.port}",
        modifier = Modifier.background(PocketShellColors.Surface),
        onClick = onClick,
        trailing = {
            Text(
                text = if (host.enabled) "Enabled" else "Open",
                color = if (host.enabled) PocketShellColors.Green else PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

@Composable
private fun TextButtonBox(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(PocketShellColors.SurfaceElev)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ForwardingPassphraseDialog(
    keyName: String,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SSH key passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter the passphrase for $keyName. It is used for this forwarding session and is not saved.")
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = onPassphraseChange,
                    singleLine = true,
                    label = { Text("Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpen, enabled = passphrase.isNotEmpty()) {
                Text("Open")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun HostEntity.toForwardingHostKey(key: SshKeyEntity): ForwardingHostKey =
    ForwardingHostKey(
        host = this,
        keyPath = key.privateKeyPath,
        keyName = key.name,
        hasPassphrase = key.hasPassphrase,
    )
