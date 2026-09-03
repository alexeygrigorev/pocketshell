package com.pocketshell.next.connect

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The connect state machine over the real registry / real trust store / real
 * Room database (rewrite task U-2).
 *
 * The load-bearing assertions here are the two that involve PERSISTENCE, not
 * the state flow: trusting must write the presented fingerprint to the host
 * row, and rejecting must leave it null. A state-only test would pass with a
 * trust button that records nothing (the user would then be re-prompted
 * forever) or, far worse, with a reject that records anyway.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ConnectViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var stack: TestConnectStack

    @Before
    fun setUp() {
        // `viewModelScope` is Dispatchers.Main; runTest's scheduler drives it.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        if (::stack.isInitialized) stack.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `an already-trusted host connects and signals navigation`() = runTest(dispatcher) {
        stack = TestConnectStack(presentedFingerprint = null)
        val hostId = stack.seedHost()

        stack.viewModel.connect(hostId)
        advanceUntilIdle()

        val state = stack.viewModel.state.value
        assertEquals(hostId, state.navigateToHostId)
        assertNull(state.prompt)
        assertNull(state.error)
        assertNull(state.busyHostId)
        assertEquals(1, stack.factory.dialCount)
    }

    @Test
    fun `the navigation signal fires once`() = runTest(dispatcher) {
        stack = TestConnectStack()
        val hostId = stack.seedHost()

        stack.viewModel.connect(hostId)
        advanceUntilIdle()
        assertEquals(hostId, stack.viewModel.state.value.navigateToHostId)

        stack.viewModel.consumeNavigation()

        assertNull(stack.viewModel.state.value.navigateToHostId)
    }

    @Test
    fun `an unknown host key raises a first-contact prompt and stores nothing`() =
        runTest(dispatcher) {
            stack = TestConnectStack(presentedFingerprint = "SHA256:presented")
            val hostId = stack.seedHost(username = "testuser", hostname = "10.0.2.2", port = 2222)

            stack.viewModel.connect(hostId)
            advanceUntilIdle()

            val prompt = requireNotNull(stack.viewModel.state.value.prompt)
            assertEquals("SHA256:presented", prompt.state.fingerprintSha256)
            assertFalse(prompt.state.isMismatch)
            assertNull(prompt.state.previousFingerprintSha256)
            assertEquals("testuser@10.0.2.2:2222", prompt.hostLabel)
            // Raising the prompt must not itself be a trust decision.
            assertNull(stack.storedFingerprint(hostId))
        }

    @Test
    fun `a changed host key raises a mismatch prompt carrying both fingerprints`() =
        runTest(dispatcher) {
            stack = TestConnectStack(presentedFingerprint = "SHA256:new")
            val hostId = stack.seedHost(trustedHostKeySha256 = "SHA256:old")

            stack.viewModel.connect(hostId)
            advanceUntilIdle()

            val prompt = requireNotNull(stack.viewModel.state.value.prompt)
            assertTrue(prompt.state.isMismatch)
            assertEquals("SHA256:new", prompt.state.fingerprintSha256)
            assertEquals("SHA256:old", prompt.state.previousFingerprintSha256)
            // The previously trusted key is untouched until the user answers.
            assertEquals("SHA256:old", stack.storedFingerprint(hostId))
        }

    @Test
    fun `trust records the presented fingerprint and the retry connects`() =
        runTest(dispatcher) {
            stack = TestConnectStack(presentedFingerprint = "SHA256:presented")
            val hostId = stack.seedHost()

            stack.viewModel.connect(hostId)
            advanceUntilIdle()
            val dialsBeforeTrust = stack.factory.dialCount

            stack.viewModel.trust()
            advanceUntilIdle()

            assertEquals("SHA256:presented", stack.storedFingerprint(hostId))
            val state = stack.viewModel.state.value
            assertEquals(hostId, state.navigateToHostId)
            assertNull(state.prompt)
            // The retry is a FULL re-dial, not a resumed handshake.
            assertTrue(stack.factory.dialCount > dialsBeforeTrust)
        }

    /**
     * The acceptance criterion, at the Room level: rejecting must leave the
     * host exactly as untrusted as it was.
     */
    @Test
    fun `reject stores no key and leaves the user on the host list`() = runTest(dispatcher) {
        stack = TestConnectStack(presentedFingerprint = "SHA256:presented")
        val hostId = stack.seedHost()

        stack.viewModel.connect(hostId)
        advanceUntilIdle()

        stack.viewModel.reject()
        advanceUntilIdle()

        assertNull(stack.storedFingerprint(hostId))
        val state = stack.viewModel.state.value
        assertNull(state.prompt)
        assertNull(state.navigateToHostId)
        assertNull(state.error)
    }

    /** A rejected key must not be quietly accepted by the NEXT tap either. */
    @Test
    fun `reject then reconnect prompts again instead of connecting`() = runTest(dispatcher) {
        stack = TestConnectStack(presentedFingerprint = "SHA256:presented")
        val hostId = stack.seedHost()

        stack.viewModel.connect(hostId)
        advanceUntilIdle()
        stack.viewModel.reject()
        advanceUntilIdle()

        stack.viewModel.connect(hostId)
        advanceUntilIdle()

        assertEquals(
            "SHA256:presented",
            stack.viewModel.state.value.prompt?.state?.fingerprintSha256,
        )
        assertNull(stack.viewModel.state.value.navigateToHostId)
        assertNull(stack.storedFingerprint(hostId))
    }

    @Test
    fun `a failed dial surfaces the transport message and retry re-dials`() =
        runTest(dispatcher) {
            stack = TestConnectStack()
            val hostId = stack.seedHost()
            stack.factory.failWith = "connection refused"

            stack.viewModel.connect(hostId)
            advanceUntilIdle()

            assertEquals(
                ConnectError(hostId, "connection refused"),
                stack.viewModel.state.value.error,
            )
            assertNull(stack.viewModel.state.value.navigateToHostId)

            stack.factory.failWith = null
            stack.viewModel.retry()
            advanceUntilIdle()

            assertNull(stack.viewModel.state.value.error)
            assertEquals(hostId, stack.viewModel.state.value.navigateToHostId)
        }

    @Test
    fun `a missing host row fails instead of throwing`() = runTest(dispatcher) {
        stack = TestConnectStack()

        stack.viewModel.connect(hostId = 4_242)
        advanceUntilIdle()

        assertEquals(4_242L, stack.viewModel.state.value.error?.hostId)
        assertEquals(0, stack.factory.dialCount)
    }

    /** A second tap while a prompt is open must not start a competing dial. */
    @Test
    fun `a tap while the trust prompt is open is ignored`() = runTest(dispatcher) {
        stack = TestConnectStack(presentedFingerprint = "SHA256:presented")
        val hostId = stack.seedHost()
        val otherId = stack.seedHost(name = "other")

        stack.viewModel.connect(hostId)
        advanceUntilIdle()
        val dials = stack.factory.dialCount

        stack.viewModel.connect(otherId)
        advanceUntilIdle()

        assertEquals(dials, stack.factory.dialCount)
        assertEquals(hostId, stack.viewModel.state.value.prompt?.state?.hostId)
    }
}
