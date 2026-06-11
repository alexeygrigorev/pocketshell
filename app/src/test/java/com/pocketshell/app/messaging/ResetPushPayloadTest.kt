package com.pocketshell.app.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResetPushPayloadTest {

    @Test
    fun fromData_fullPayload_parsesAllFields() {
        val payload = ResetPushPayload.fromData(
            mapOf(
                "type" to "usage_reset",
                "provider" to "codex",
                "reset_key" to "codex|short_term|2026-06-11T17:00:00Z",
                "title" to "Codex limits reset",
                "body" to "Heavy work can resume.",
            ),
        )!!
        assertEquals("codex", payload.provider)
        assertEquals("codex|short_term|2026-06-11T17:00:00Z", payload.resetKey)
        assertEquals("Codex limits reset", payload.title)
        assertEquals("Heavy work can resume.", payload.body)
    }

    @Test
    fun fromData_missingCopy_fallsBackToDefaultProviderText() {
        val payload = ResetPushPayload.fromData(
            mapOf(
                "type" to "usage_reset",
                "provider" to "anthropic",
                "reset_key" to "anthropic|long_term|x",
            ),
        )!!
        assertEquals("Claude limits reset", payload.title)
        assertEquals(true, payload.body.contains("Claude"))
    }

    @Test
    fun fromData_nonResetType_returnsNull() {
        assertNull(
            ResetPushPayload.fromData(
                mapOf("type" to "something_else", "reset_key" to "k"),
            ),
        )
    }

    @Test
    fun fromData_missingType_returnsNull() {
        assertNull(ResetPushPayload.fromData(mapOf("reset_key" to "k")))
    }

    @Test
    fun fromData_missingResetKey_returnsNull() {
        // Without a reset_key the push can't be de-dup'd (#619) → dropped.
        assertNull(
            ResetPushPayload.fromData(
                mapOf("type" to "usage_reset", "provider" to "codex"),
            ),
        )
    }

    @Test
    fun fromData_blankResetKey_returnsNull() {
        assertNull(
            ResetPushPayload.fromData(
                mapOf("type" to "usage_reset", "reset_key" to "   "),
            ),
        )
    }
}
