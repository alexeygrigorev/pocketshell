package com.pocketshell.app.hosts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Manage SSH keys: list, add (import or generate), delete.
 *
 * Embedded under Add/Edit host's "Manage keys" tab. The "Add" affordance
 * is two buttons (Import / Generate) rather than a FAB because the action
 * set is larger than a single primary, and the host-add FAB remains the
 * only top-level create affordance.
 *
 * Delete shows a confirmation dialog because the FK CASCADE on
 * `hosts.keyId` means deleting an in-use key also wipes the dependent
 * host rows. The dialog body surfaces this fact.
 */
@Composable
fun SshKeysManagementPane(
    modifier: Modifier = Modifier,
    viewModel: SshKeysViewModel = hiltViewModel(),
    requiresUnlock: (android.content.Context) -> Boolean = ::isSshKeyUnlockRequired,
) {
    val keys by viewModel.keys.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current

    var pendingDelete: SshKeyEntity? by remember { mutableStateOf(null) }
    var unlocked by remember(context) { mutableStateOf(!requiresUnlock(context)) }
    var unlockError: String? by remember { mutableStateOf(null) }
    var unlockInFlight by remember { mutableStateOf(false) }
    val unlockGate = remember { SshKeyUnlockInFlightGate() }

    // SAF launcher for "Import key". Uses GetContent rather than
    // OpenDocument because the user often picks from Downloads / Drive
    // (a freshly-pasted key) rather than a persistent location — we make
    // our own copy under filesDir/ssh-keys/ anyway, so URI permission
    // longevity is not a concern.
    val keyPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let { viewModel.importKey(context, it) } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!unlocked) {
                KeyUnlockPanel(
                    error = unlockError,
                    inFlight = unlockInFlight,
                    onUnlock = {
                        if (!unlockGate.tryMarkInFlight()) return@KeyUnlockPanel
                        unlockInFlight = true
                        unlockError = null

                        fun finishUnlockRequest() {
                            unlockGate.clear()
                            unlockInFlight = false
                        }

                        launchSshKeyUnlock(
                            activity = context as? FragmentActivity,
                            onSuccess = {
                                finishUnlockRequest()
                                unlockError = null
                                unlocked = true
                            },
                            onFailure = { unlockError = it },
                            onError = {
                                finishUnlockRequest()
                                unlockError = it
                            },
                        )
                    },
                )
                return@Column
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { keyPickerLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Import key", color = PocketShellColors.Accent)
                }
                Button(
                    onClick = { viewModel.generateKey(context) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PocketShellColors.Accent,
                        contentColor = PocketShellColors.OnAccent,
                    ),
                ) {
                    Text("Generate", fontWeight = FontWeight.SemiBold)
                }
            }

            error?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PocketShellColors.Surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = msg,
                        color = PocketShellColors.Red,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::clearError) {
                        Text("Dismiss", color = PocketShellColors.Accent, fontSize = 12.sp)
                    }
                }
            }

            if (keys.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No keys yet",
                            color = PocketShellColors.Text,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Import a key file or generate one on-device.",
                            color = PocketShellColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(keys, key = { it.id }) { key ->
                        KeyRow(
                            key = key,
                            onDeleteRequest = { pendingDelete = key },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this key?", color = PocketShellColors.Text) },
            text = {
                Text(
                    text = "“${target.name}” will be removed. Any hosts that " +
                        "reference this key are deleted too (foreign-key cascade).",
                    color = PocketShellColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteKey(target)
                    pendingDelete = null
                }) {
                    Text("Delete", color = PocketShellColors.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", color = PocketShellColors.Accent)
                }
            },
            containerColor = PocketShellColors.Surface,
        )
    }
}

@Composable
private fun KeyUnlockPanel(error: String?, inFlight: Boolean, onUnlock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "Unlock key management",
            color = PocketShellColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Device unlock gates adding, deleting, and viewing key paths. PocketShell does not store SSH key passphrases yet.",
            color = PocketShellColors.TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onUnlock,
            enabled = !inFlight,
            modifier = Modifier.testTag(SSH_KEYS_UNLOCK_BUTTON_TAG),
            colors = ButtonDefaults.buttonColors(
                containerColor = PocketShellColors.Accent,
                contentColor = PocketShellColors.OnAccent,
            ),
        ) {
            Text(if (inFlight) "Unlocking..." else "Unlock")
        }
        error?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = it, color = PocketShellColors.Red, fontSize = 12.sp)
        }
    }
}

private fun isKeyUnlockRequired(context: android.content.Context): Boolean {
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    return BiometricManager.from(context).canAuthenticate(authenticators) ==
        BiometricManager.BIOMETRIC_SUCCESS
}

internal fun isSshKeyUnlockRequired(context: android.content.Context): Boolean = isKeyUnlockRequired(context)

internal const val SSH_KEYS_UNLOCK_BUTTON_TAG = "ssh-keys-unlock-button"

internal class SshKeyUnlockInFlightGate {
    var isInFlight: Boolean = false
        private set

    fun tryMarkInFlight(): Boolean {
        if (isInFlight) return false
        isInFlight = true
        return true
    }

    fun clear() {
        isInFlight = false
    }
}

internal interface SshKeyUnlockPromptLauncher {
    fun launch(
        activity: FragmentActivity,
        promptInfo: BiometricPrompt.PromptInfo,
        callback: BiometricPrompt.AuthenticationCallback,
    )
}

private object AndroidSshKeyUnlockPromptLauncher : SshKeyUnlockPromptLauncher {
    override fun launch(
        activity: FragmentActivity,
        promptInfo: BiometricPrompt.PromptInfo,
        callback: BiometricPrompt.AuthenticationCallback,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            callback,
        )
        prompt.authenticate(promptInfo)
    }
}

internal fun launchSshKeyUnlock(
    activity: FragmentActivity?,
    title: String = "Unlock SSH key management",
    subtitle: String = "Confirm it is you before managing local private keys",
    promptLauncher: SshKeyUnlockPromptLauncher = AndroidSshKeyUnlockPromptLauncher,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
    onFailure: (String) -> Unit = onError,
) {
    if (activity == null) {
        onError("Device unlock is unavailable from this screen")
        return
    }
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    runCatching {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .build()
        promptLauncher.launch(
            activity = activity,
            promptInfo = promptInfo,
            callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    onFailure("Unlock failed")
                }
            },
        )
    }.onFailure { throwable ->
        onError(formatSshKeyUnlockLaunchError(throwable))
    }
}

internal fun formatSshKeyUnlockLaunchError(throwable: Throwable): String {
    val detail = throwable.message?.takeIf { it.isNotBlank() }
        ?: throwable::class.java.simpleName
    return "Could not start device unlock: $detail"
}

/**
 * A single key row, composed from the shared dense-row primitives (#479
 * Slice B1): [ListRow] carries the key name (`bodyDense`) + the private-key
 * path (`bodyMono` via [ListRow]'s subtitle slot) and a `K` avatar tile in
 * the leading slot. The lone destructive action moves into a per-row [Kebab]
 * (§4 decision 4) so the row has one overflow affordance instead of an inline
 * "Delete" text button.
 *
 * The passphrase note is appended to the mono subtitle (it is incidental
 * metadata, not a second scan line) so the row stays single-subtitle like the
 * folder-tree / host-list rows it now matches.
 */
@Composable
private fun KeyRow(
    key: SshKeyEntity,
    onDeleteRequest: () -> Unit,
) {
    val subtitle = if (key.hasPassphrase) {
        "${key.privateKeyPath}  · passphrase not stored"
    } else {
        key.privateKeyPath
    }
    ListRow(
        title = key.name,
        subtitle = subtitle,
        leading = {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = PocketShellColors.SurfaceElev,
                        shape = RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "K",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        trailing = {
            Kebab(
                items = listOf(
                    KebabItem(
                        label = "Delete",
                        onClick = onDeleteRequest,
                    ),
                ),
            )
        },
    )
}
