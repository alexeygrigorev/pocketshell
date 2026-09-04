package com.pocketshell.next.connect

import com.pocketshell.core.transport.TrustDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping the trust sheet renders. The load-bearing case is that a
 * CHANGED key is never presented as an ordinary first-contact prompt.
 */
class TrustPromptStateTest {

    private val hostId = 7L

    @Test
    fun `an unknown key is a first-contact prompt`() {
        val prompt = TrustPromptState.from(hostId, TrustDecision.Unknown("SHA256:new"))!!

        assertEquals(hostId, prompt.hostId)
        assertEquals("SHA256:new", prompt.fingerprintSha256)
        assertFalse(prompt.isMismatch)
        assertNull(prompt.previousFingerprintSha256)
    }

    @Test
    fun `a mismatch carries both fingerprints and is flagged`() {
        val prompt = TrustPromptState.from(
            hostId,
            TrustDecision.Mismatch(storedSha256 = "SHA256:old", presentedSha256 = "SHA256:new"),
        )!!

        assertTrue(prompt.isMismatch)
        assertEquals("SHA256:new", prompt.fingerprintSha256)
        assertEquals("SHA256:old", prompt.previousFingerprintSha256)
    }

    @Test
    fun `a trusted key raises no prompt`() {
        assertNull(TrustPromptState.from(hostId, TrustDecision.Trusted))
    }
}
