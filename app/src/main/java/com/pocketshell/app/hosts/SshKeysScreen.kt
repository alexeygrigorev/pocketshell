package com.pocketshell.app.hosts

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Manage SSH keys: list, add (import or generate), delete.
 *
 * Visually consistent with the dashboard: same app bar shape, same
 * surface/border treatment for rows. The "Add" affordance is two buttons
 * (Import / Generate) rather than a FAB — the action set is larger than
 * a single primary, and the mockup's FAB is reserved for the host-add
 * flow.
 *
 * Delete shows a confirmation dialog because the FK CASCADE on
 * `hosts.keyId` means deleting an in-use key also wipes the dependent
 * host rows. The dialog body surfaces this fact.
 */
@Composable
fun SshKeysScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SshKeysViewModel = hiltViewModel(),
) {
    val keys by viewModel.keys.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current

    var pendingDelete: SshKeyEntity? by remember { mutableStateOf(null) }
    var unlocked by remember { mutableStateOf(!isKeyUnlockRequired(context)) }
    var unlockError: String? by remember { mutableStateOf(null) }

    // Issue #38 item 3: intercept system-back. SshKeysScreen has no
    // form-style unsaved state — every add / delete commits immediately
    // through the DAO — so the handler unconditionally delegates to
    // `onBack`. The interception itself matters: without a BackHandler
    // the system back bypasses any per-screen logic (e.g. dismissing
    // the pending-delete dialog) the screen might layer on later. If a
    // delete-confirmation is showing we close it first instead of
    // popping the whole screen, mirroring how the dialog's Cancel
    // button behaves.
    BackHandler {
        if (pendingDelete != null) {
            pendingDelete = null
        } else {
            onBack()
        }
    }

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
            KeysAppBar(onBack = onBack)

            if (!unlocked) {
                KeyUnlockPanel(
                    error = unlockError,
                    onUnlock = {
                        launchSshKeyUnlock(
                            activity = context as? FragmentActivity,
                            onSuccess = {
                                unlockError = null
                                unlocked = true
                            },
                            onError = { unlockError = it },
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
private fun KeysAppBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(PocketShellColors.Background)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "‹",
                color = PocketShellColors.TextSecondary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = "SSH keys",
            color = PocketShellColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun KeyUnlockPanel(error: String?, onUnlock: () -> Unit) {
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
            colors = ButtonDefaults.buttonColors(
                containerColor = PocketShellColors.Accent,
                contentColor = PocketShellColors.OnAccent,
            ),
        ) {
            Text("Unlock")
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

internal fun launchSshKeyUnlock(
    activity: FragmentActivity?,
    title: String = "Unlock SSH key management",
    subtitle: String = "Confirm it is you before managing local private keys",
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    if (activity == null) {
        onError("Device unlock is unavailable from this screen")
        return
    }
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errString.toString())
            }

            override fun onAuthenticationFailed() {
                onError("Unlock failed")
            }
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .build(),
    )
}

/**
 * A single key card. Mirrors the host-row shape from `docs/mockups/styles.css`:
 * surface background, 14dp corner radius, 1dp soft border. Trailing
 * "Delete" affordance opens the confirmation dialog.
 */
@Composable
private fun KeyRow(
    key: SshKeyEntity,
    onDeleteRequest: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = PocketShellColors.SurfaceElev,
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "K",
                color = PocketShellColors.TextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = key.name,
                color = PocketShellColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = key.privateKeyPath,
                color = PocketShellColors.TextMuted,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (key.hasPassphrase) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Passphrase-protected; passphrase is not stored",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        TextButton(onClick = onDeleteRequest) {
            Text("Delete", color = PocketShellColors.Red, fontSize = 13.sp)
        }
    }
}
