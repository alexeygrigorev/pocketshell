package com.pocketshell.app.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.uikit.theme.PocketShellColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class StartDirectoryAutocompleteTarget(
    val hostname: String,
    val port: Int,
    val username: String,
    val keyPath: String,
    val passphrase: CharArray?,
)

class StartDirectoryAutocompleteRemoteSource @Inject constructor() {
    suspend fun suggestions(
        target: StartDirectoryAutocompleteTarget,
        typedPrefix: String,
        limit: Int = DEFAULT_START_DIRECTORY_AUTOCOMPLETE_LIMIT,
    ): List<String> {
        val request = StartDirectoryAutocompleteRequest.from(typedPrefix, limit) ?: return emptyList()
        SshOpenTelemetry.record(
            source = SSH_SOURCE_START_DIRECTORY_AUTOCOMPLETE,
            host = target.hostname,
            port = target.port,
            user = target.username,
        )
        val session = SshConnection.connect(
            host = target.hostname,
            port = target.port,
            user = target.username,
            key = SshKey.Path(File(target.keyPath)),
            passphrase = target.passphrase?.copyOf(),
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrElse {
            return emptyList()
        }

        return try {
            val result = session.exec(startDirectoryAutocompleteCommand(request))
            if (result.exitCode == 0) {
                parseStartDirectoryAutocompleteOutput(request, result.stdout)
            } else {
                emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        } finally {
            session.close()
        }
    }
}

data class StartDirectoryAutocompleteUiState(
    val suggestions: List<String> = emptyList(),
    val loading: Boolean = false,
    val highlightedIndex: Int = 0,
)

class StartDirectoryAutocompleteController(
    private val scope: CoroutineScope,
    private val suggest: suspend (String) -> List<String>,
    private val debounceMs: Long = START_DIRECTORY_AUTOCOMPLETE_DEBOUNCE_MS,
) {
    private val _state = MutableStateFlow(StartDirectoryAutocompleteUiState())
    val state: StateFlow<StartDirectoryAutocompleteUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var latestInput: String = ""

    fun onInputChanged(value: String) {
        latestInput = value
        job?.cancel()
        if (value.isBlank()) {
            _state.value = StartDirectoryAutocompleteUiState()
            return
        }
        _state.value = _state.value.copy(loading = true)
        job = scope.launch {
            delay(debounceMs)
            val query = value
            val suggestions = runCatching { suggest(query) }.getOrDefault(emptyList())
            if (latestInput == query) {
                _state.value = StartDirectoryAutocompleteUiState(
                    suggestions = suggestions.distinct(),
                    loading = false,
                    highlightedIndex = 0,
                )
            }
        }
    }

    fun acceptHighlighted(): String? {
        val current = _state.value
        val suggestion = current.suggestions.getOrNull(current.highlightedIndex) ?: return null
        acceptSuggestion(suggestion)
        return suggestion
    }

    fun acceptSuggestion(suggestion: String) {
        latestInput = suggestion
        job?.cancel()
        _state.value = StartDirectoryAutocompleteUiState()
    }

    fun moveHighlight(delta: Int) {
        val current = _state.value
        if (current.suggestions.isEmpty()) return
        val next = (current.highlightedIndex + delta)
            .coerceIn(0, current.suggestions.lastIndex)
        _state.value = current.copy(highlightedIndex = next)
    }

    fun dispose() {
        job?.cancel()
    }
}

@Composable
fun rememberStartDirectoryAutocompleteController(
    suggestStartDirectories: (suspend (String) -> List<String>)?,
    debounceMs: Long = START_DIRECTORY_AUTOCOMPLETE_DEBOUNCE_MS,
): StartDirectoryAutocompleteController? {
    val currentSuggest by rememberUpdatedState(suggestStartDirectories)
    val scope = rememberCoroutineScope()
    val enabled = suggestStartDirectories != null
    val controller = remember(enabled, debounceMs) {
        if (enabled) {
            StartDirectoryAutocompleteController(
                scope = scope,
                suggest = { query -> currentSuggest?.invoke(query).orEmpty() },
                debounceMs = debounceMs,
            )
        } else {
            null
        }
    }
    DisposableEffect(controller) {
        onDispose { controller?.dispose() }
    }
    return controller
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StartDirectoryAutocompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    textFieldTestTag: String? = null,
    autocompleteController: StartDirectoryAutocompleteController? = null,
    suggestionsMaxHeight: Dp = START_DIRECTORY_AUTOCOMPLETE_DEFAULT_MAX_HEIGHT,
) {
    val autocompleteState by autocompleteController?.state?.collectAsState()
        ?: remember { MutableStateFlow(StartDirectoryAutocompleteUiState()) }.collectAsState()
    val suggestionsBringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(autocompleteState.suggestions) {
        if (autocompleteState.suggestions.isNotEmpty()) {
            suggestionsBringIntoViewRequester.bringIntoView()
        }
    }
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { next ->
                onValueChange(next)
                autocompleteController?.onInputChanged(next)
            },
            singleLine = singleLine,
            label = label,
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
                        val accepted = autocompleteController?.acceptHighlighted()
                        if (accepted != null) {
                            onValueChange(accepted)
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
                .then(if (textFieldTestTag != null) Modifier.testTag(textFieldTestTag) else Modifier),
        )
        if (autocompleteState.suggestions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(suggestionsBringIntoViewRequester)
                    .heightIn(max = suggestionsMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp)
                    .background(PocketShellColors.SurfaceElev, RoundedCornerShape(8.dp))
                    .testTag(START_DIRECTORY_AUTOCOMPLETE_SUGGESTIONS_TAG),
            ) {
                autocompleteState.suggestions.forEachIndexed { index, suggestion ->
                    val highlighted = index == autocompleteState.highlightedIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (highlighted) PocketShellColors.AccentSoft else PocketShellColors.SurfaceElev,
                            )
                            .clickable(role = Role.Button) {
                                autocompleteController?.acceptSuggestion(suggestion)
                                onValueChange(suggestion)
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                            .testTag(startDirectoryAutocompleteSuggestionTag(suggestion)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = suggestion,
                            color = if (highlighted) PocketShellColors.Accent else PocketShellColors.Text,
                            fontSize = 13.sp,
                            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

private val START_DIRECTORY_AUTOCOMPLETE_DEFAULT_MAX_HEIGHT = 220.dp

internal data class StartDirectoryAutocompleteRequest(
    val parentDirectory: String,
    val childPrefix: String,
    val suggestionPrefix: String,
    val limit: Int,
) {
    companion object {
        fun from(
            typedPrefix: String,
            limit: Int = DEFAULT_START_DIRECTORY_AUTOCOMPLETE_LIMIT,
        ): StartDirectoryAutocompleteRequest? {
            val raw = typedPrefix.trim()
            if (raw.isBlank()) return null
            val boundedLimit = limit.coerceIn(1, 100)
            val normalised = raw.replace('\\', '/')
            val slashIndex = normalised.lastIndexOf('/')
            val parent = when {
                normalised == "~" || normalised == "\$HOME" -> normalised
                slashIndex < 0 -> "."
                slashIndex == 0 -> "/"
                else -> normalised.substring(0, slashIndex)
            }
            val prefix = when {
                normalised == "~" || normalised == "\$HOME" -> ""
                slashIndex < 0 -> normalised
                else -> normalised.substring(slashIndex + 1)
            }
            val suggestionPrefix = when {
                normalised == "~" -> "~/"
                normalised == "\$HOME" -> "\$HOME/"
                slashIndex < 0 -> ""
                parent == "/" -> "/"
                else -> "$parent/"
            }
            return StartDirectoryAutocompleteRequest(
                parentDirectory = parent,
                childPrefix = prefix,
                suggestionPrefix = suggestionPrefix,
                limit = boundedLimit,
            )
        }
    }
}

internal fun startDirectoryAutocompleteCommand(request: StartDirectoryAutocompleteRequest): String =
    """
        pocketshell_ac_parent=${shellQuote(request.parentDirectory)}
        pocketshell_ac_prefix=${shellQuote(request.childPrefix)}
        case "${'$'}pocketshell_ac_parent" in
          '~') pocketshell_ac_parent=${'$'}HOME ;;
          '~/'*) pocketshell_ac_parent=${'$'}HOME/${'$'}{pocketshell_ac_parent#~/} ;;
          '${'$'}HOME') pocketshell_ac_parent=${'$'}HOME ;;
          '${'$'}HOME/'*) pocketshell_ac_parent=${'$'}HOME/${'$'}{pocketshell_ac_parent#${'$'}HOME/} ;;
        esac
        [ -d "${'$'}pocketshell_ac_parent" ] || exit 0
        LC_ALL=C
        export LC_ALL
        pocketshell_ac_count=0
        for pocketshell_ac_path in "${'$'}pocketshell_ac_parent"/"${'$'}pocketshell_ac_prefix"*; do
          [ -d "${'$'}pocketshell_ac_path" ] || continue
          pocketshell_ac_name=${'$'}{pocketshell_ac_path##*/}
          printf '%s/\n' "${'$'}pocketshell_ac_name"
          pocketshell_ac_count=${'$'}((pocketshell_ac_count + 1))
          [ "${'$'}pocketshell_ac_count" -ge ${request.limit} ] && break
        done
    """.trimIndent()

internal fun parseStartDirectoryAutocompleteOutput(
    request: StartDirectoryAutocompleteRequest,
    stdout: String,
): List<String> =
    stdout.lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() && it.endsWith("/") && "/" !in it.dropLast(1) }
        .map { request.suggestionPrefix + it }
        .take(request.limit)
        .toList()

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"

const val START_DIRECTORY_AUTOCOMPLETE_SUGGESTIONS_TAG: String =
    "start-directory-autocomplete:suggestions"

fun startDirectoryAutocompleteSuggestionTag(path: String): String =
    "start-directory-autocomplete:suggestion:${path.hashCode()}"

private const val START_DIRECTORY_AUTOCOMPLETE_DEBOUNCE_MS: Long = 250L
private const val DEFAULT_START_DIRECTORY_AUTOCOMPLETE_LIMIT: Int = 30
