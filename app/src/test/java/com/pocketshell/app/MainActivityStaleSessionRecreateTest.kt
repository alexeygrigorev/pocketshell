package com.pocketshell.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            create = { Result.success("work-2") },
            onSuccess = { navigatedTo = it },
            onFailure = { visibleFailure = it },
        )

        assertEquals("work-2", navigatedTo)
        assertNull(visibleFailure)
    }
}
