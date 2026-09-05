package com.pocketshell.next.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.next.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One host row in the Workspace section — the picker into its roots screen. */
data class SettingsHostRow(val id: Long, val name: String, val subtitle: String)

/**
 * Backs [SettingsScreen] (rewrite task P-6).
 *
 * Thin by construction: [SettingsRepository] is the `@Singleton` that owns both
 * the persisted values and the observable snapshot, so this class holds no
 * settings state of its own — it forwards taps and republishes the repository's
 * flow. That is what lets `MainActivity` provide the SAME flow through
 * [LocalAppSettings] without the two ever disagreeing.
 *
 * The host list is here rather than in the repository because it comes from
 * Room, not preferences, and is needed by exactly one section.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    hostDao: HostDao,
    @IoDispatcher dispatcher: CoroutineDispatcher,
) : ViewModel() {

    /** The live settings snapshot. Same instance the whole app reads. */
    val state: StateFlow<AppSettings> = repository.settings

    /** Saved hosts, for the per-host workspace-roots picker. */
    val hosts: StateFlow<List<SettingsHostRow>> =
        hostDao.getAll()
            .map { hosts -> hosts.map { toRow(it) } }
            .flowOn(dispatcher)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyList(),
            )

    fun setTerminalTextSizePx(sizePx: Int) = repository.setTerminalTextSizePx(sizePx)

    fun setVoiceLanguage(code: String) = repository.setVoiceLanguage(code)

    fun setUsageWarnThresholdPercent(percent: Int) =
        repository.setUsageWarnThresholdPercent(percent)

    fun setBackgroundGraceMillis(millis: Long) = repository.setBackgroundGraceMillis(millis)

    fun setAgentSubmitEnterDelayMs(delayMs: Int) = repository.setAgentSubmitEnterDelayMs(delayMs)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        fun toRow(host: HostEntity): SettingsHostRow = SettingsHostRow(
            id = host.id,
            name = host.name.ifBlank { host.hostname },
            subtitle = "${host.username}@${host.hostname}:${host.port}",
        )
    }
}
