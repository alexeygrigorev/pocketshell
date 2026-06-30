package com.pocketshell.app.env

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.HostDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One row in the env-key list, carrying the revealed value (when the
 * user has explicitly tapped "Reveal") alongside the masked default.
 */
data class EnvKeyUiRow(
    /**
     * Stable, list-unique identity used as the LazyColumn item key. Derived
     * from `file:key` but disambiguated by occurrence so two rows that share
     * the same (file, key) — a real `.env` may legitimately repeat a key, and
     * an in-place edit transiently surfaces the old + edited entry — never
     * collide on the LazyColumn key (Compose hard-crashes on duplicate keys).
     */
    val id: String,
    val key: String,
    val file: String,
    val hasValue: Boolean,
    /** Non-null only after a successful `env get` for this key (plain). */
    val revealedValue: String? = null,
    /** True while an `env get` for this key is in flight. */
    val revealing: Boolean = false,
)

/**
 * A folder the copy-source picker can read from — sourced from the
 * already-discovered folder set the user saw on the folder list
 * (passed through the nav destination), per D24.
 */
data class EnvCopySourceFolder(val path: String, val label: String)

/** Keys discovered in a candidate copy-source folder. */
sealed interface EnvCopySourceKeys {
    data object Loading : EnvCopySourceKeys
    data class Ready(val keys: List<EnvKeyRow>) : EnvCopySourceKeys
    data class Failed(val message: String) : EnvCopySourceKeys
}

sealed interface EnvListState {
    data object Loading : EnvListState
    data class Ready(val keys: List<EnvKeyUiRow>) : EnvListState
    data class Failed(val message: String) : EnvListState
    data object ToolUnavailable : EnvListState
}

/**
 * In-place editor for an existing key's value (issue #1092).
 *
 * Hidden by default. Opening it fetches the current value via the same
 * `env get` reveal path ([EnvViewModel.revealKey] / [EnvGateway.getValue])
 * so the user can tweak the secret instead of retyping it blind — and that
 * fetch happens only on the explicit Edit action, consistent with the D24
 * write-only-by-default contract. Renaming is out of scope (#1092
 * non-goal): the key name and the file it lives in are fixed; only the
 * value changes, and Save routes it back through [EnvGateway.setKeys]
 * (stdin upload, never argv).
 */
sealed interface EnvEditorState {
    data object Hidden : EnvEditorState

    /** The current value is being fetched via `env get`. */
    data class LoadingValue(val key: String, val file: EnvFileTarget) : EnvEditorState

    /** Value loaded; the field is pre-populated and editable. */
    data class Editing(
        val key: String,
        val file: EnvFileTarget,
        val currentValue: String,
    ) : EnvEditorState
}

/**
 * UI state for [EnvScreen] — issue #264.
 *
 * @property folderLabel user-visible folder label shown in the header.
 * @property directory the absolute remote folder path being managed.
 * @property list the env-key list state.
 * @property busy true while a mutating op (set / copy) is in flight.
 * @property transientMessage one-shot status/error banner text.
 * @property copySources the discovered folders the copy flow can read
 *   from (the current folder is excluded).
 */
data class EnvUiState(
    val folderLabel: String = "",
    val directory: String = "",
    val list: EnvListState = EnvListState.Loading,
    val busy: Boolean = false,
    val transientMessage: String? = null,
    val copySources: List<EnvCopySourceFolder> = emptyList(),
    val editor: EnvEditorState = EnvEditorState.Hidden,
)

/**
 * Backs [EnvScreen] — issue #264.
 *
 * Read-only orchestrator over [EnvGateway] (`pocketshell env ...` over
 * SSH) + [HostDao] for the host row. Write-only by default per D24: the
 * list shows masked values; [revealKey] is the only path that fetches a
 * plain value, and only on explicit user action.
 */
@HiltViewModel
class EnvViewModel @Inject constructor(
    private val gateway: EnvGateway,
    private val hostDao: HostDao,
) : ViewModel() {

    private val _state = MutableStateFlow(EnvUiState())
    val state: StateFlow<EnvUiState> = _state.asStateFlow()

    private var params: BoundParams? = null

    fun bind(
        hostId: Long,
        keyPath: String,
        passphrase: CharArray?,
        directory: String,
        folderLabel: String,
        copySources: List<EnvCopySourceFolder>,
    ) {
        val next = BoundParams(hostId, keyPath, passphrase, directory)
        if (params == next && _state.value.directory == directory) return
        params = next
        _state.value = EnvUiState(
            folderLabel = folderLabel,
            directory = directory,
            list = EnvListState.Loading,
            // Never let the copy-source list include the folder we are
            // editing — copying a folder into itself is a no-op the UI
            // should not offer.
            copySources = copySources.filter { it.path != directory },
        )
        refresh()
    }

    fun refresh() {
        val p = params ?: return
        viewModelScope.launch {
            val host = hostDao.getById(p.hostId) ?: run {
                _state.value = _state.value.copy(list = EnvListState.Failed("Host not found."))
                return@launch
            }
            _state.value = _state.value.copy(list = EnvListState.Loading)
            when (val result = gateway.listKeys(host, p.keyPath, p.passphrase, p.directory)) {
                is EnvListResult.Keys -> {
                    _state.value = _state.value.copy(
                        list = EnvListState.Ready(result.keys.toUiRows()),
                    )
                }
                EnvListResult.ToolUnavailable -> {
                    _state.value = _state.value.copy(list = EnvListState.ToolUnavailable)
                }
                is EnvListResult.Failed -> {
                    _state.value = _state.value.copy(list = EnvListState.Failed(result.message))
                }
                is EnvListResult.ConnectFailed -> {
                    _state.value = _state.value.copy(
                        list = EnvListState.Failed(
                            result.cause.message ?: "Couldn't connect to the host.",
                        ),
                    )
                }
            }
        }
    }

    /** Add or update a single key. Value travels via stdin (D24). */
    fun setKey(key: String, value: String, file: EnvFileTarget) {
        val p = params ?: return
        val trimmedKey = key.trim()
        if (trimmedKey.isEmpty()) {
            _state.value = _state.value.copy(transientMessage = "Key name is required.")
            return
        }
        if (!isValidKey(trimmedKey)) {
            _state.value = _state.value.copy(
                transientMessage = "Key must be a shell identifier (letters, digits, underscore; not starting with a digit).",
            )
            return
        }
        viewModelScope.launch {
            val host = hostDao.getById(p.hostId) ?: run {
                _state.value = _state.value.copy(transientMessage = "Host not found.")
                return@launch
            }
            _state.value = _state.value.copy(busy = true, transientMessage = null)
            val result = gateway.setKeys(
                host = host,
                keyPath = p.keyPath,
                passphrase = p.passphrase,
                directory = p.directory,
                file = file,
                updates = mapOf(trimmedKey to value),
            )
            _state.value = _state.value.copy(busy = false)
            applyOpResult(result, successMessage = "Saved $trimmedKey to ${file.fileName}.")
        }
    }

    /**
     * Begin editing [key] in place (#1092): fetch its current value via the
     * same `env get` reveal path and open the editor pre-loaded with it, so
     * the user can tweak the secret instead of retyping it blind. Renaming is
     * out of scope — the key name and the file it lives in are fixed.
     */
    fun beginEdit(key: String) {
        val p = params ?: return
        val current = _state.value.list as? EnvListState.Ready ?: return
        val row = current.keys.firstOrNull { it.key == key } ?: return
        val file = EnvFileTarget.fromFileName(row.file)
        _state.value = _state.value.copy(editor = EnvEditorState.LoadingValue(key, file))
        viewModelScope.launch {
            val host = hostDao.getById(p.hostId) ?: run {
                editorFailed("Host not found.")
                return@launch
            }
            when (val result = gateway.getValue(host, p.keyPath, p.passphrase, p.directory, key)) {
                is EnvOpResult.Values -> {
                    // Ignore a late fetch if the user already closed / switched
                    // the editor while the value was loading.
                    val editor = _state.value.editor
                    if (editor is EnvEditorState.LoadingValue && editor.key == key) {
                        _state.value = _state.value.copy(
                            editor = EnvEditorState.Editing(
                                key = key,
                                file = file,
                                // An empty key (has_value=false) has no value in
                                // the map — open the field blank so the user can
                                // fill it.
                                currentValue = result.values[key] ?: "",
                            ),
                        )
                    }
                }
                EnvOpResult.ToolUnavailable -> editorFailed("pocketshell is not installed on this host.")
                is EnvOpResult.Failed -> editorFailed(result.message)
                is EnvOpResult.ConnectFailed ->
                    editorFailed(result.cause.message ?: "Couldn't connect to the host.")
                EnvOpResult.Success ->
                    _state.value = _state.value.copy(editor = EnvEditorState.Hidden)
            }
        }
    }

    /**
     * Save the in-place edit: route the new value back through [setKey]
     * (which uploads it via stdin, never argv — D24) for the editor's fixed
     * key + file, then close the editor.
     */
    fun saveEdit(value: String) {
        val editor = _state.value.editor
        if (editor !is EnvEditorState.Editing) return
        _state.value = _state.value.copy(editor = EnvEditorState.Hidden)
        setKey(editor.key, value, editor.file)
    }

    /** Close the in-place editor without saving. */
    fun dismissEditor() {
        _state.value = _state.value.copy(editor = EnvEditorState.Hidden)
    }

    private fun editorFailed(message: String) {
        _state.value = _state.value.copy(
            editor = EnvEditorState.Hidden,
            transientMessage = message,
        )
    }

    /** Reveal a single key's plain value via `env get` (D24: no biometric). */
    fun revealKey(key: String) {
        val p = params ?: return
        updateRow(key) { it.copy(revealing = true) }
        viewModelScope.launch {
            val host = hostDao.getById(p.hostId) ?: run {
                updateRow(key) { it.copy(revealing = false) }
                _state.value = _state.value.copy(transientMessage = "Host not found.")
                return@launch
            }
            when (val result = gateway.getValue(host, p.keyPath, p.passphrase, p.directory, key)) {
                is EnvOpResult.Values -> {
                    val value = result.values[key]
                    if (value == null) {
                        updateRow(key) { it.copy(revealing = false) }
                        _state.value = _state.value.copy(transientMessage = "No value found for $key.")
                    } else {
                        updateRow(key) { it.copy(revealing = false, revealedValue = value) }
                    }
                }
                EnvOpResult.ToolUnavailable -> {
                    updateRow(key) { it.copy(revealing = false) }
                    _state.value = _state.value.copy(transientMessage = "pocketshell is not installed on this host.")
                }
                is EnvOpResult.Failed -> {
                    updateRow(key) { it.copy(revealing = false) }
                    _state.value = _state.value.copy(transientMessage = result.message)
                }
                is EnvOpResult.ConnectFailed -> {
                    updateRow(key) { it.copy(revealing = false) }
                    _state.value = _state.value.copy(
                        transientMessage = result.cause.message ?: "Couldn't connect to the host.",
                    )
                }
                EnvOpResult.Success -> updateRow(key) { it.copy(revealing = false) }
            }
        }
    }

    /** Re-mask an already-revealed key (drop the plain value from UI state). */
    fun hideKey(key: String) {
        updateRow(key) { it.copy(revealedValue = null) }
    }

    /**
     * Load the keys available in a candidate copy-source folder so the
     * copy flow can offer a multi-select. Returns through [onResult] so
     * the screen can drive the sheet without holding the result in the
     * primary state machine.
     */
    fun loadCopySourceKeys(
        sourceDirectory: String,
        onResult: (EnvCopySourceKeys) -> Unit,
    ) {
        val p = params ?: return
        onResult(EnvCopySourceKeys.Loading)
        viewModelScope.launch {
            val host = hostDao.getById(p.hostId) ?: run {
                onResult(EnvCopySourceKeys.Failed("Host not found."))
                return@launch
            }
            when (val result = gateway.listKeys(host, p.keyPath, p.passphrase, sourceDirectory)) {
                is EnvListResult.Keys -> onResult(EnvCopySourceKeys.Ready(result.keys))
                EnvListResult.ToolUnavailable ->
                    onResult(EnvCopySourceKeys.Failed("pocketshell is not installed on this host."))
                is EnvListResult.Failed -> onResult(EnvCopySourceKeys.Failed(result.message))
                is EnvListResult.ConnectFailed ->
                    onResult(EnvCopySourceKeys.Failed(result.cause.message ?: "Couldn't connect to the host."))
            }
        }
    }

    /** Copy [keys] from [sourceDirectory] into the current folder's [file]. */
    fun copyKeys(sourceDirectory: String, keys: List<String>, file: EnvFileTarget) {
        val p = params ?: return
        if (keys.isEmpty()) {
            _state.value = _state.value.copy(transientMessage = "Pick at least one key to copy.")
            return
        }
        viewModelScope.launch {
            val host = hostDao.getById(p.hostId) ?: run {
                _state.value = _state.value.copy(transientMessage = "Host not found.")
                return@launch
            }
            _state.value = _state.value.copy(busy = true, transientMessage = null)
            val result = gateway.copyKeys(
                host = host,
                keyPath = p.keyPath,
                passphrase = p.passphrase,
                sourceDirectory = sourceDirectory,
                destinationDirectory = p.directory,
                file = file,
                keys = keys,
            )
            _state.value = _state.value.copy(busy = false)
            applyOpResult(
                result,
                successMessage = "Copied ${keys.size} key${if (keys.size == 1) "" else "s"} into ${file.fileName}.",
            )
        }
    }

    fun consumeTransientMessage() {
        _state.value = _state.value.copy(transientMessage = null)
    }

    private fun applyOpResult(result: EnvOpResult, successMessage: String) {
        when (result) {
            EnvOpResult.Success, is EnvOpResult.Values -> {
                _state.value = _state.value.copy(transientMessage = successMessage)
                refresh()
            }
            EnvOpResult.ToolUnavailable ->
                _state.value = _state.value.copy(transientMessage = "pocketshell is not installed on this host.")
            is EnvOpResult.Failed ->
                _state.value = _state.value.copy(transientMessage = result.message)
            is EnvOpResult.ConnectFailed ->
                _state.value = _state.value.copy(
                    transientMessage = result.cause.message ?: "Couldn't connect to the host.",
                )
        }
    }

    private fun updateRow(key: String, transform: (EnvKeyUiRow) -> EnvKeyUiRow) {
        val current = _state.value.list
        if (current !is EnvListState.Ready) return
        _state.value = _state.value.copy(
            list = EnvListState.Ready(
                current.keys.map { if (it.key == key) transform(it) else it },
            ),
        )
    }

    private data class BoundParams(
        val hostId: Long,
        val keyPath: String,
        val passphrase: CharArray?,
        val directory: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BoundParams) return false
            if (hostId != other.hostId) return false
            if (keyPath != other.keyPath) return false
            if (directory != other.directory) return false
            if (passphrase != null) {
                if (other.passphrase == null) return false
                if (!passphrase.contentEquals(other.passphrase)) return false
            } else if (other.passphrase != null) {
                return false
            }
            return true
        }

        override fun hashCode(): Int {
            var result = hostId.hashCode()
            result = 31 * result + keyPath.hashCode()
            result = 31 * result + directory.hashCode()
            result = 31 * result + (passphrase?.contentHashCode() ?: 0)
            return result
        }
    }

    companion object {
        private val KEY_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        /** POSIX-ish shell identifier check, matching the CLI's `_is_valid_key`. */
        fun isValidKey(key: String): Boolean = KEY_PATTERN.matches(key)

        /**
         * Map gateway rows to UI rows, assigning each a list-unique [id]. The
         * first occurrence of a `file:key` tuple keeps the bare `file:key` id
         * (stable across refreshes for the common no-duplicate case); any
         * further occurrence is suffixed `#1`, `#2`, … so the LazyColumn item
         * key is always unique even when the same (file, key) appears twice.
         */
        fun List<EnvKeyRow>.toUiRows(): List<EnvKeyUiRow> {
            val occurrences = HashMap<String, Int>()
            return map { row ->
                val base = "${row.file}:${row.key}"
                val seen = occurrences.getOrDefault(base, 0)
                occurrences[base] = seen + 1
                EnvKeyUiRow(
                    id = if (seen == 0) base else "$base#$seen",
                    key = row.key,
                    file = row.file,
                    hasValue = row.hasValue,
                )
            }
        }
    }
}
