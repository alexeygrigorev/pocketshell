package com.pocketshell.app.projects

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2033 — the composed banner text must name the real outcome and
 * must not quote a command that will fail while the package is unpublished.
 *
 * G6 mutation: if [CliVersionBannerCopy.unpublishedMessage] appended
 * [PayloadVersionCheck.UPDATE_COMMAND], `unpublished_doesNotQuoteFailingCommand`
 * reddens. If Idle copy dropped the command, `idle_stillNamesUpdateCommand`
 * reddens (we still want the command before an attempt).
 */
class CliVersionBannerCopyTest {

    private val mismatch = PayloadVersionCheck.Verdict.HostOutdated(
        hostVersion = "0.4.39",
        expectedVersion = "0.4.40",
    )

    @Test
    fun idle_stillNamesUpdateCommand() {
        val text = CliVersionBannerCopy.bannerMessage(
            mismatch,
            FolderListViewModel.CliVersionUpdateState.Idle,
        )
        assertTrue(text.contains("0.4.39"))
        assertTrue(text.contains("0.4.40"))
        assertTrue(text.contains(PayloadVersionCheck.UPDATE_COMMAND))
    }

    @Test
    fun unpublished_doesNotQuoteFailingCommand() {
        val text = CliVersionBannerCopy.bannerMessage(
            mismatch,
            FolderListViewModel.CliVersionUpdateState.Failure(
                message = "pocketshell 0.4.40 is not on PyPI yet",
                kind = FolderListViewModel.CliVersionUpdateState.Failure.Kind.Unpublished,
                offerRetry = true,
            ),
        )
        assertTrue(text.contains("not on PyPI"))
        assertTrue(text.contains("0.4.40"))
        assertFalse(text.contains("uv tool install"))
        assertFalse(text.contains("pipx upgrade"))
        assertFalse(text.contains("pip install"))
        assertFalse(text.contains("capped", ignoreCase = true))
    }

    @Test
    fun capped_doesNotQuoteFailingCommand() {
        val text = CliVersionBannerCopy.bannerMessage(
            mismatch,
            FolderListViewModel.CliVersionUpdateState.Failure(
                message = "date-capped",
                kind = FolderListViewModel.CliVersionUpdateState.Failure.Kind.Capped,
                offerRetry = false,
            ),
        )
        assertTrue(text.contains("date-capped") || text.contains("capped"))
        assertFalse(text.contains("uv tool install"))
        assertFalse(text.contains("pipx upgrade"))
        assertFalse(text.contains("pip install"))
    }

    @Test
    fun failed_keepsTheManualCommand() {
        val text = CliVersionBannerCopy.bannerMessage(
            mismatch,
            FolderListViewModel.CliVersionUpdateState.Failure(
                message = "Update failed (exit 1):\nerror: network unreachable",
                kind = FolderListViewModel.CliVersionUpdateState.Failure.Kind.Failed,
                offerRetry = true,
            ),
        )
        assertTrue(text.contains(PayloadVersionCheck.UPDATE_COMMAND))
    }
}
