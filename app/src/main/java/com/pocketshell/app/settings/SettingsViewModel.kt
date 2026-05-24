package com.pocketshell.app.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Backs [SettingsScreen]. Thin pass-through over [SettingsRepository] —
 * the repository owns persistence and emits the snapshot; the view model
 * exists so the screen can call into the same surface other ViewModels
 * use (Hilt-injected `hiltViewModel()`) without exposing the repository
 * directly to Compose code.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<AppSettings> = repository.settings

    fun setTheme(theme: ThemePreference) = repository.setTheme(theme)

    fun setTerminalFontSizeSp(sizeSp: Float) = repository.setTerminalFontSizeSp(sizeSp)

    fun setTmuxOnAttachByDefault(enabled: Boolean) =
        repository.setTmuxOnAttachByDefault(enabled)
}
