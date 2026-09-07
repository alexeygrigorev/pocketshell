package com.pocketshell.next.ports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val ADD_TUNNEL_SCREEN_TAG = "add_tunnel_screen"
const val ADD_TUNNEL_FORM_SCROLL_TAG = "add_tunnel_form_scroll"
const val ADD_TUNNEL_NAME_TAG = "add_tunnel_name"
const val ADD_TUNNEL_REMOTE_TAG = "add_tunnel_remote_port"
const val ADD_TUNNEL_LOCAL_TAG = "add_tunnel_local_port"
const val ADD_TUNNEL_SUBMIT_TAG = "add_tunnel_submit"

@Composable
fun AddTunnelRoute(
    initialRemotePort: Int?,
    onDone: () -> Unit,
    viewModel: PortForwardViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val suggestedName = state.discoveredRows
        .firstOrNull { it.remotePort == initialRemotePort }
        ?.process
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: initialRemotePort?.let { "Port $it" }
        ?: ""
    var nameText by rememberSaveable { mutableStateOf("") }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    var remoteText by rememberSaveable { mutableStateOf(initialRemotePort?.toString().orEmpty()) }
    var localText by rememberSaveable { mutableStateOf(initialRemotePort?.toString().orEmpty()) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var localPortCollision by rememberSaveable { mutableStateOf<String?>(null) }
    var collisionChecked by rememberSaveable { mutableStateOf(false) }
    val remotePort = remoteText.toIntOrNull()
    val localPort = localText.toIntOrNull()
    LaunchedEffect(suggestedName) {
        if (!nameEdited && nameText.isBlank() && suggestedName.isNotBlank()) {
            nameText = suggestedName
        }
    }
    // The collision belongs to the local bind, but both route fields are part
    // of the form's validation lifecycle. A remote-only edit must not leave a
    // previous check invalidated forever while the effect remains keyed only
    // by localPort.
    LaunchedEffect(remotePort, localPort) {
        if (!localPort.isValidPort()) {
            localPortCollision = null
            collisionChecked = true
        } else {
            collisionChecked = false
            localPortCollision = viewModel.localPortCollision(localPort!!)
            collisionChecked = true
        }
    }
    val valid = remotePort.isValidPort() &&
        localPort.isValidPort() &&
        nameText.trim().isNotEmpty() &&
        collisionChecked &&
        localPortCollision == null
    LaunchedEffect(submitted) {
        if (submitted && valid) {
            try {
                viewModel.addManualTunnel(remotePort!!, localPort!!, nameText.trim())
                // Remounting a live supervisor briefly publishes an empty snapshot;
                // the foreground service may stop itself during that transition.
                // Re-trigger the real service after the durable mount completes.
                ForwardService.resume(context)
                withContext(Dispatchers.Main.immediate) { onDone() }
            } catch (collision: LocalPortCollisionException) {
                localPortCollision = collision.message
                collisionChecked = true
                submitted = false
            }
        }
    }
    AddTunnelScreen(
        name = nameText,
        remotePort = remoteText,
        localPort = localText,
        valid = valid,
        localPortCollision = localPortCollision,
        onNameChange = {
            nameEdited = true
            nameText = it.take(MAX_NAME_LENGTH)
        },
        onRemotePortChange = {
            remoteText = it.filter(Char::isDigit).take(5)
            localPortCollision = null
            collisionChecked = false
        },
        onLocalPortChange = { localText = it.filter(Char::isDigit).take(5) },
        onSubmit = { submitted = true },
        onBack = onDone,
    )
}

@Composable
fun AddTunnelScreen(
    name: String,
    remotePort: String,
    localPort: String,
    valid: Boolean,
    localPortCollision: String? = null,
    onNameChange: (String) -> Unit,
    onRemotePortChange: (String) -> Unit,
    onLocalPortChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .testTag(ADD_TUNNEL_SCREEN_TAG),
    ) {
        ScreenHeader(
            title = "Add tunnel",
            leading = {
                PocketShellButton(
                    text = "Back",
                    onClick = onBack,
                    variant = ButtonVariant.Text,
                    compact = true,
                )
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .testTag(ADD_TUNNEL_FORM_SCROLL_TAG)
                .padding(PocketShellSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("Name") },
                supportingText = { Text("Shown in the tunnel list") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ADD_TUNNEL_NAME_TAG),
            )
            OutlinedTextField(
                value = remotePort,
                onValueChange = onRemotePortChange,
                label = { Text("Remote port") },
                supportingText = { Text("The listening port on the host") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ADD_TUNNEL_REMOTE_TAG),
            )
            OutlinedTextField(
                value = localPort,
                onValueChange = onLocalPortChange,
                label = { Text("Local port") },
                supportingText = {
                    Text(localPortCollision ?: "127.0.0.1 only on this phone")
                },
                isError = localPortCollision != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ADD_TUNNEL_LOCAL_TAG),
            )
            Text(
                text = "The tunnel uses loopback-only exposure. A service is not started until you save this mapping.",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
            )
            Text(
                text = "Valid ports are 1–65535.",
                color = PocketShellColors.TextMuted,
                style = PocketShellType.labelMono,
            )
            PocketShellButton(
                text = "Start tunnel",
                onClick = onSubmit,
                enabled = valid && name.trim().isNotEmpty() && localPortCollision == null,
                variant = ButtonVariant.Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ADD_TUNNEL_SUBMIT_TAG),
            )
        }
    }
}

private const val MAX_NAME_LENGTH = 80

private fun Int?.isValidPort(): Boolean = this != null && this in 1..65_535
