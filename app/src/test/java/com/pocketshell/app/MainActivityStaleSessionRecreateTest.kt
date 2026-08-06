package com.pocketshell.app

import com.pocketshell.app.projects.SessionCreateOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityStaleSessionRecreateTest {
    @Test
    fun failedStaleRecreateReportsReasonAndDoesNotNavigate() = runTest {
        var navigatedTo: String? = null
        var visibleFailure: String? = null

        recreateStaleSession(
            create = { Result.failure(IllegalStateException("SSH create refused")) },
            onSuccess = { navigatedTo = it },
            onFailure = { visibleFailure = it },
        )

        assertNull("failed create must not navigate as if the stale session exists", navigatedTo)
        assertEquals("SSH create refused", visibleFailure)
    }

    @Test
    fun thrownStaleRecreateFailureIsAlsoReported() = runTest {
        var navigatedTo: String? = null
        var visibleFailure: String? = null

        recreateStaleSession(
            create = { throw IllegalStateException("host lookup crashed") },
            onSuccess = { navigatedTo = it },
            onFailure = { visibleFailure = it },
        )

        assertNull("thrown create failure must not navigate", navigatedTo)
        assertEquals("host lookup crashed", visibleFailure)
    }

    @Test
    fun successfulStaleRecreateNavigatesWithoutFailure() = runTest {
        var navigatedTo: String? = null
        var visibleFailure: String? = null

        recreateStaleSession(
            create = { Result.success(SessionCreateOutcome.Created("work-2")) },
            onSuccess = { navigatedTo = it },
            onFailure = { visibleFailure = it },
        )

        assertEquals("work-2", navigatedTo)
        assertNull(visibleFailure)
    }

    /**
     * Issue #1928 — the stale-session-recovery half of the caller sweep.
     *
     * Recovery must not treat a PARTIAL success as a full one. If it navigated
     * on [SessionCreateOutcome.LaunchFailed] the user would be attached to a
     * session that is not the agent they were recovering, with nothing on screen
     * saying so — the same lie the folder tree and the in-session sheet used to
     * tell. The visible reason must name the session AND the host's cause.
     */
    @Test
    fun launchFailedStaleRecreateReportsInsteadOfNavigating() = runTest {
        var navigatedTo: String? = null
        var visibleFailure: String? = null

        recreateStaleSession(
            create = {
                Result.success(
                    SessionCreateOutcome.LaunchFailed("work-2", "can't find pane: =work-2:"),
                )
            },
            onSuccess = { navigatedTo = it },
            onFailure = { visibleFailure = it },
        )

        assertNull("a launch failure must not navigate as if the agent started", navigatedTo)
        val message = visibleFailure.orEmpty()
        assertTrue("must name the created session: $message", message.contains("work-2"))
        assertTrue(
            "must carry the host's reason: $message",
            message.contains("can't find pane: =work-2:"),
        )
    }
}
