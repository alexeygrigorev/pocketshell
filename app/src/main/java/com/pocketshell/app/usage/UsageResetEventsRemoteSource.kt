package com.pocketshell.app.usage

import com.pocketshell.app.pocketshell.PocketshellCommand
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException

/**
 * Reads the host's detected usage-reset events (issue #690) over SSH.
 *
 * Runs `pocketshell usage --reset-events`, which the merged server slice
 * implements to print `{"reset_events": [...]}` (the de-dup'd reset log) and
 * always exit 0. Parsed into [UsageResetEvent]s for the in-app reset banner —
 * the non-push fallback that surfaces "limits reset at <time>" on app open even
 * before/without FCM being wired.
 *
 * Every "no usable events" case (the CLI is older and rejects the flag, the
 * host is unreachable, the document is malformed) collapses to an empty list so
 * the banner simply doesn't show; it never blocks or errors the usage screen.
 */
public class UsageResetEventsRemoteSource(
    private val parser: UsageResetEventsParser = UsageResetEventsParser,
) {

    public suspend fun fetchResetEvents(session: SshSession): List<UsageResetEvent> = try {
        val result = session.exec(PocketshellCommand.wrap(RESET_EVENTS_ARGS))
        if (result.exitCode != 0) {
            emptyList()
        } else {
            parser.parse(result.stdout)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        emptyList()
    }

    public companion object {
        /** The `pocketshell` arguments for the reset-events read (#690). */
        public const val RESET_EVENTS_ARGS: String = "usage --reset-events"
    }
}
