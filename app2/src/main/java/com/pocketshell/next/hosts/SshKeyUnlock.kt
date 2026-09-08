package com.pocketshell.next.hosts

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellSpacing

/** Native biometric/device-credential action used by the production key gate. */
const val SSH_KEYS_UNLOCK_BUTTON_TAG: String = "ssh-keys-unlock-button"
const val SSH_KEYS_PASSPHRASE_FALLBACK_TAG: String = "ssh-keys-passphrase-fallback"
const val SSH_KEYS_FALLBACK_FIELD_TAG: String = "ssh-keys-fallback-field"
const val SSH_KEYS_FALLBACK_SUBMIT_TAG: String = "ssh-keys-fallback-submit"

fun sshKeyFallbackRowTag(keyId: Long): String = "ssh-keys-fallback-key-$keyId"

/** True when Android can present the native strong-biometric/device prompt. */
fun isSshKeyUnlockRequired(context: android.content.Context): Boolean {
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    return BiometricManager.from(context).canAuthenticate(authenticators) ==
        BiometricManager.BIOMETRIC_SUCCESS
}

/** Small testable guard against launching two native prompts for one tap. */
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
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            callback,
        ).authenticate(promptInfo)
    }
}

/**
 * Launch the real Android prompt. The app never paints a fake biometric
 * dialog, reads biometric data, or treats a failed callback as success.
 */
internal fun launchSshKeyUnlock(
    activity: FragmentActivity?,
    title: String = "Unlock SSH keys",
    subtitle: String = "Confirm it is you before viewing local key details",
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
        val detail = throwable.message?.takeIf { it.isNotBlank() }
            ?: throwable::class.java.simpleName
        onError("Could not start device unlock: $detail")
    }
}

@Composable
fun SshKeyUnlockPanel(
    error: String?,
    inFlight: Boolean,
    onUnlock: () -> Unit,
    deviceUnlockAvailable: Boolean,
    protectedKeys: List<SshKeyRow>,
    selectedKeyId: Long?,
    fallbackPassphrase: String,
    fallbackInFlight: Boolean,
    onSelectKey: (Long) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onUnlockWithPassphrase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .imePadding()
            .padding(PocketShellSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
    ) {
        ScreenHeader(title = "Unlock SSH keys")
        Banner(
            text = "Device authentication protects private-key details. " +
                "If device unlock is unavailable, canceled, or fails, verify the passphrase " +
                "for one encrypted key below.",
            role = BannerRole.Info,
        )
        if (deviceUnlockAvailable) {
            PocketShellButton(
                text = if (inFlight) "Waiting for device unlock…" else "Unlock with device",
                onClick = onUnlock,
                enabled = !inFlight,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SSH_KEYS_UNLOCK_BUTTON_TAG),
                variant = ButtonVariant.Primary,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SSH_KEYS_PASSPHRASE_FALLBACK_TAG),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        ) {
            Text(text = "Passphrase fallback")
            if (protectedKeys.isEmpty()) {
                Text(
                    text = "No passphrase-protected SSH key is available for fallback.",
                )
            } else {
                Text(
                    text = "Choose the encrypted key whose passphrase you want to verify.",
                )
                protectedKeys.forEach { key ->
                    ListRow(
                        title = key.name,
                        subtitle = "Passphrase protected",
                        trailing = {
                            RadioButton(
                                selected = selectedKeyId == key.id,
                                onClick = { onSelectKey(key.id) },
                            )
                        },
                        onClick = { onSelectKey(key.id) },
                        modifier = Modifier.testTag(sshKeyFallbackRowTag(key.id)),
                    )
                }
                OutlinedTextField(
                    value = fallbackPassphrase,
                    onValueChange = onPassphraseChange,
                    label = { Text("Key passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = selectedKeyId != null && !fallbackInFlight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SSH_KEYS_FALLBACK_FIELD_TAG),
                )
                PocketShellButton(
                    text = if (fallbackInFlight) "Checking passphrase…" else "Unlock with passphrase",
                    onClick = onUnlockWithPassphrase,
                    enabled = selectedKeyId != null && fallbackPassphrase.isNotEmpty() && !fallbackInFlight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SSH_KEYS_FALLBACK_SUBMIT_TAG),
                )
            }
        }
        error?.let {
            Banner(text = it, role = BannerRole.Warning)
        }
    }
}
