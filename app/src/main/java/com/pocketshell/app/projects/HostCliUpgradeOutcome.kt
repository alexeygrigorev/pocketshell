package com.pocketshell.app.projects

/**
 * Issue #2033: classify a host-CLI upgrade attempt from the **actual**
 * installer signal — requested version vs resolved version, plus the
 * installer output — instead of guessing "it may be capped".
 *
 * The three outcomes the banner must distinguish:
 *
 *  - [Kind.Unpublished] — the installer resolved something older than
 *    requested (or said the requested version does not exist). The
 *    package is not on the index the host can see.
 *  - [Kind.Capped] — the installer output names a date / `exclude-newer`
 *    cap. This is independent of the `--exclude-newer 2099-12-31`
 *    override we already pass; if that override worked, this kind is
 *    not selected.
 *  - [Kind.Failed] — any other non-success (network, no installer, …).
 *
 * [Kind.Success] is the matching-version case so callers can share one
 * function; the banner never shows it.
 */
object HostCliUpgradeOutcome {

    enum class Kind { Success, Unpublished, Capped, Failed }

    data class Verdict(
        val kind: Kind,
        val message: String,
        val offerRetry: Boolean,
        val requestedVersion: String,
        val resolvedVersion: String?,
    ) {
        val failureKind: FolderListViewModel.CliVersionUpdateState.Failure.Kind
            get() = when (kind) {
                Kind.Unpublished ->
                    FolderListViewModel.CliVersionUpdateState.Failure.Kind.Unpublished
                Kind.Capped ->
                    FolderListViewModel.CliVersionUpdateState.Failure.Kind.Capped
                Kind.Failed, Kind.Success ->
                    FolderListViewModel.CliVersionUpdateState.Failure.Kind.Failed
            }

        fun toUpdateFailure(): FolderListViewModel.CliVersionUpdateState.Failure =
            FolderListViewModel.CliVersionUpdateState.Failure(
                message = message,
                kind = failureKind,
                offerRetry = offerRetry,
            )
    }

    fun classify(
        requestedVersion: String,
        resolvedVersion: String?,
        exitCode: Int,
        output: String,
    ): Verdict {
        val requested = requestedVersion.trim()
        val resolved = resolvedVersion?.trim()?.takeIf { it.isNotEmpty() }
        val combined = output

        // Cap signal first: only when the installer itself named a date cap.
        // "Nothing to upgrade" is NOT a cap — that is what uv/pipx/pip print
        // when the newest published version is already installed (the
        // unpublished-N case this issue exists to stop misdiagnosing).
        if (looksLikeCapped(combined)) {
            return Verdict(
                kind = Kind.Capped,
                message = cappedMessage(requested, resolved),
                offerRetry = false,
                requestedVersion = requested,
                resolvedVersion = resolved,
            )
        }
        if (looksLikeUnpublished(combined, requested)) {
            return unpublished(requested, resolved)
        }
        if (exitCode == 0) {
            if (resolved != null && requested.isNotEmpty()) {
                when (PayloadVersionCheck.evaluate(resolved, requested)) {
                    is PayloadVersionCheck.Verdict.HostOutdated ->
                        return unpublished(requested, resolved)
                    else -> { /* resolved matches or is newer — success */ }
                }
            }
            return Verdict(
                kind = Kind.Success,
                message = "",
                offerRetry = false,
                requestedVersion = requested,
                resolvedVersion = resolved,
            )
        }
        return Verdict(
            kind = Kind.Failed,
            message = failedMessage(exitCode, combined),
            offerRetry = true,
            requestedVersion = requested,
            resolvedVersion = resolved,
        )
    }

    internal fun looksLikeCapped(output: String): Boolean {
        if (output.isBlank()) return false
        val lower = output.lowercase()
        return CAP_MARKERS.any { lower.contains(it) }
    }

    internal fun looksLikeUnpublished(output: String, requestedVersion: String): Boolean {
        if (output.isBlank()) return false
        val lower = output.lowercase()
        if (UNPUBLISHED_MARKERS.any { lower.contains(it) }) return true
        val requested = requestedVersion.trim()
        if (requested.isEmpty()) return false
        // uv: "because there is no version of pocketshell==0.4.40"
        // pip: "no matching distribution found for pocketshell==0.4.40"
        return lower.contains("no version") && lower.contains(requested.lowercase())
    }

    private fun unpublished(requested: String, resolved: String?): Verdict =
        Verdict(
            kind = Kind.Unpublished,
            message = unpublishedMessage(requested, resolved),
            offerRetry = true,
            requestedVersion = requested,
            resolvedVersion = resolved,
        )

    internal fun unpublishedMessage(requested: String, resolved: String?): String {
        val resolvedBit = resolved?.takeIf { it.isNotBlank() }?.let { " (host still reports $it)" }.orEmpty()
        return "pocketshell $requested is not on PyPI yet$resolvedBit. " +
            "The in-app update cannot install it until the package is published."
    }

    internal fun cappedMessage(requested: String, resolved: String?): String {
        val resolvedBit = resolved?.takeIf { it.isNotBlank() }?.let { " The host still reports $it." }.orEmpty()
        return "The host installer is date-capped and could not resolve " +
            "pocketshell $requested.$resolvedBit"
    }

    private fun failedMessage(exitCode: Int, output: String): String {
        val detail = output.lines().filter { it.isNotBlank() }.takeLast(MAX_ERROR_LINES)
            .joinToString("\n")
            .take(MAX_ERROR_CHARS)
        return if (detail.isBlank()) {
            "Update failed (exit $exitCode)."
        } else {
            "Update failed (exit $exitCode):\n$detail"
        }
    }

    private val CAP_MARKERS: List<String> = listOf(
        "exclude-newer",
        "excluded because of",
        "because of the exclude",
        "date-capped",
        "date cap",
    )

    private val UNPUBLISHED_MARKERS: List<String> = listOf(
        "not found in the registry",
        "could not find a version that satisfies",
        "no matching distribution found",
        "no packages found matching",
        "could not find a version",
        "because there is no version",
        "is not available on",
        "404 client error",
    )

    private const val MAX_ERROR_LINES: Int = 6
    private const val MAX_ERROR_CHARS: Int = 600
}
