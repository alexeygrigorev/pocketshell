package com.pocketshell.app.snippets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.CommandTemplateDao
import com.pocketshell.core.storage.entity.CommandTemplateEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
public open class CommandTemplatesViewModel @Inject constructor(
    internal val commandTemplateDao: CommandTemplateDao,
) : ViewModel() {

    private val _hostId: MutableStateFlow<Long?> = MutableStateFlow(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    public val templates: StateFlow<List<CommandTemplateEntity>> = _hostId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else commandTemplateDao.getByHostId(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val _error: MutableStateFlow<String?> = MutableStateFlow(null)
    public val error: StateFlow<String?> = _error.asStateFlow()

    public fun bindHost(hostId: Long) {
        _hostId.value = hostId
    }

    public fun addTemplate(label: String, commands: String) {
        val hostId = _hostId.value ?: run {
            _error.value = "No host selected"
            return
        }
        val normalised = normaliseCommandTemplateCommands(commands)
        val storedLabel = label.trim()
        if (storedLabel.isEmpty()) {
            _error.value = "Name is required"
            return
        }
        if (normalised.isBlank()) {
            _error.value = "At least one command is required"
            return
        }
        viewModelScope.launch {
            try {
                commandTemplateDao.insert(
                    CommandTemplateEntity(
                        hostId = hostId,
                        label = storedLabel,
                        commands = normalised,
                    ),
                )
                _error.value = null
            } catch (t: Throwable) {
                _error.value = "Failed to save macro: ${t.message}"
            }
        }
    }

    public fun updateTemplate(template: CommandTemplateEntity) {
        val normalised = normaliseCommandTemplateCommands(template.commands)
        val storedLabel = template.label.trim()
        if (storedLabel.isEmpty()) {
            _error.value = "Name is required"
            return
        }
        if (normalised.isBlank()) {
            _error.value = "At least one command is required"
            return
        }
        viewModelScope.launch {
            try {
                commandTemplateDao.update(template.copy(label = storedLabel, commands = normalised))
                _error.value = null
            } catch (t: Throwable) {
                _error.value = "Failed to update macro: ${t.message}"
            }
        }
    }

    public fun deleteTemplate(template: CommandTemplateEntity) {
        viewModelScope.launch {
            try {
                commandTemplateDao.delete(template)
                _error.value = null
            } catch (t: Throwable) {
                _error.value = "Failed to delete macro: ${t.message}"
            }
        }
    }

    public fun clearError() {
        _error.value = null
    }

    internal fun templatesFlowFor(hostId: Long): Flow<List<CommandTemplateEntity>> =
        commandTemplateDao.getByHostId(hostId)
}

internal fun normaliseCommandTemplateCommands(commands: String): String =
    commands
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
