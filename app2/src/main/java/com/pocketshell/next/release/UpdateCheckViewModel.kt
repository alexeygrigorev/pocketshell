package com.pocketshell.next.release

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin screen wrapper over the process-wide [UpdateCheckScheduler]. Host list
 * and Settings share the same singleton so a poll from either surface is the
 * result both see.
 */
@HiltViewModel
class UpdateCheckViewModel @Inject constructor(
    private val scheduler: UpdateCheckScheduler,
) : ViewModel() {

    val available: StateFlow<ReleaseInfo?> = scheduler.updateAvailable
    val failed: StateFlow<String?> = scheduler.updateCheckFailed
    val lastResult: StateFlow<ReleaseCheckResult?> = scheduler.lastResult
    val checking: StateFlow<Boolean> = scheduler.checking

    fun refreshNow() = scheduler.refreshNow()

    fun dismissUpdate() = scheduler.dismissCurrentUpdate()

    fun dismissFailure() = scheduler.dismissUpdateCheckFailure()

    fun installedVersionLabel(): String = scheduler.installedVersionLabel()
}
