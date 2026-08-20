package com.pocketshell.app.projects

/**
 * User-facing copy for the host-CLI version-mismatch banner (issues #885 / #947
 * / #2033).
 *
 * The Idle prompt still names the update command (the package may well be
 * published). After an upgrade attempt, [bannerMessage] must describe the
 * actual outcome — unpublished vs capped vs failed — and must not tell the
 * user to run a command that cannot succeed.
 */
object CliVersionBannerCopy {

    fun bannerMessage(
        mismatch: PayloadVersionCheck.Verdict.HostOutdated,
        updateState: FolderListViewModel.CliVersionUpdateState,
    ): String {
        val failure = updateState as? FolderListViewModel.CliVersionUpdateState.Failure
        return when (failure?.kind) {
            FolderListViewModel.CliVersionUpdateState.Failure.Kind.Unpublished ->
                unpublishedMessage(mismatch)
            FolderListViewModel.CliVersionUpdateState.Failure.Kind.Capped ->
                cappedMessage(mismatch)
            else -> PayloadVersionCheck.outdatedHostPrompt(mismatch)
        }
    }

    fun unpublishedMessage(mismatch: PayloadVersionCheck.Verdict.HostOutdated): String =
        "This host's pocketshell is ${mismatch.hostVersion}; the app expects " +
            "${mismatch.expectedVersion}. pocketshell ${mismatch.expectedVersion} " +
            "is not on PyPI yet, so this host cannot be updated to it. " +
            "Wait for the package to publish, then tap Retry."

    fun cappedMessage(mismatch: PayloadVersionCheck.Verdict.HostOutdated): String =
        "This host's pocketshell is ${mismatch.hostVersion}; the app expects " +
            "${mismatch.expectedVersion}. The host installer is date-capped and " +
            "could not resolve pocketshell ${mismatch.expectedVersion}."
}
