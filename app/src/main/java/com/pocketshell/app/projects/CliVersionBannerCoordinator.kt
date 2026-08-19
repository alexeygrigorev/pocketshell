package com.pocketshell.app.projects

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Issue #2033: persist-on-dismiss, payload-version observe gate, and
 * classify-and-apply for the host-CLI mismatch banner.
 *
 * Lives outside [FolderListViewModel] so that file can stay at its
 * file-size baseline. Prefs open lazily on first use (#1087 / #1292).
 */
internal class CliVersionBannerCoordinator(
    private val expectedVersion: () -> String,
    private val hostId: () -> Long?,
    dismissStore: CliVersionBannerDismissStore,
) {
    private var dismissStore: CliVersionBannerDismissStore = dismissStore

    private val _mismatch =
        MutableStateFlow<PayloadVersionCheck.Verdict.HostOutdated?>(null)
    val mismatch: StateFlow<PayloadVersionCheck.Verdict.HostOutdated?> =
        _mismatch.asStateFlow()

    private val _updateState =
        MutableStateFlow<FolderListViewModel.CliVersionUpdateState>(
            FolderListViewModel.CliVersionUpdateState.Idle,
        )
    val updateState: StateFlow<FolderListViewModel.CliVersionUpdateState> =
        _updateState.asStateFlow()

    fun setDismissStoreForTest(store: CliVersionBannerDismissStore) {
        dismissStore = store
    }

    fun dismiss() {
        dismissStore.persist(hostId(), _mismatch.value)
        _mismatch.value = null
        _updateState.value = FolderListViewModel.CliVersionUpdateState.Idle
    }

    fun setUpdateState(state: FolderListViewModel.CliVersionUpdateState) {
        _updateState.value = state
    }

    fun clearAfterTrustedSuccess() {
        _mismatch.value = null
        _updateState.value = FolderListViewModel.CliVersionUpdateState.Idle
    }

    fun applyUpgradeOutcome(outcome: HostCliUpgradeOutcome.Verdict) {
        _updateState.value = outcome.toUpdateFailure()
    }

    fun classifyAndApply(
        requestedVersion: String,
        resolvedVersion: String?,
        exitCode: Int,
        output: String,
    ) {
        applyUpgradeOutcome(
            HostCliUpgradeOutcome.classify(
                requestedVersion = requestedVersion,
                resolvedVersion = resolvedVersion,
                exitCode = exitCode,
                output = output,
            ),
        )
    }

    fun observePayloadCliVersion(hostCliVersion: String?) {
        if (hostCliVersion.isNullOrBlank()) return
        when (val verdict = PayloadVersionCheck.evaluate(hostCliVersion, expectedVersion())) {
            is PayloadVersionCheck.Verdict.HostOutdated -> {
                _mismatch.value = dismissStore.takeIfNotDismissed(hostId(), verdict)
            }
            else -> _mismatch.value = null
        }
    }

    /**
     * After a fresh `tree get`, re-evaluate. A match (or dismissed triple)
     * returns to Idle; a still-outdated host is classified from requested
     * vs resolved + installer output — never guessed as a cap (#2033).
     */
    fun applyRecheck(hostCliVersion: String?, installerOutput: String) {
        observePayloadCliVersion(hostCliVersion)
        val mismatch = _mismatch.value
        if (mismatch == null) {
            _updateState.value = FolderListViewModel.CliVersionUpdateState.Idle
        } else {
            classifyAndApply(
                requestedVersion = mismatch.expectedVersion.ifBlank { expectedVersion() },
                resolvedVersion = mismatch.hostVersion,
                exitCode = 0,
                output = installerOutput,
            )
        }
    }
}
