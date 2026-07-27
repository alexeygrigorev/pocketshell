package com.pocketshell.app.usage

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UsageResetCreditFormatTest {

    @Test
    fun futureExpiryUsesExpiryVocabularyAndUtcAbsoluteTime() {
        val text = formatCreditExpiry(
            now = Instant.parse("2026-07-27T10:00:00Z"),
            expiresAt = Instant.parse("2026-07-27T12:15:00Z"),
            zoneId = ZoneId.of("UTC"),
        )

        assertEquals("expires in 2h 15m", text.primary)
        assertEquals("Mon Jul 27, 12:15", text.absolute)
        assertNoResetVocabulary(text)
    }

    @Test
    fun futureExpiryUsesDeviceLocalBerlinTime() {
        val text = formatCreditExpiry(
            now = Instant.parse("2026-07-27T10:00:00Z"),
            expiresAt = Instant.parse("2026-07-31T19:09:12Z"),
            zoneId = ZoneId.of("Europe/Berlin"),
        )

        assertEquals("expires in 4 days", text.primary)
        assertEquals("Fri Jul 31, 21:09", text.absolute)
        assertNoResetVocabulary(text)
    }

    @Test
    fun relativeExpiryUsesBerlinCalendarAcrossDstSpringForward() {
        val text = formatCreditExpiry(
            now = Instant.parse("2026-03-28T00:30:00Z"),
            expiresAt = Instant.parse("2026-03-29T23:30:00Z"),
            zoneId = ZoneId.of("Europe/Berlin"),
        )

        assertEquals("expires in 2 days", text.primary)
        assertEquals("Mon Mar 30, 01:30", text.absolute)
        assertNoResetVocabulary(text)
    }

    @Test
    fun pastExpiryReadsExpiredAndKeepsAbsoluteAuditTime() {
        val text = formatCreditExpiry(
            now = Instant.parse("2026-07-27T10:00:00Z"),
            expiresAt = Instant.parse("2026-07-26T23:52:15Z"),
            zoneId = ZoneId.of("Europe/Berlin"),
        )

        assertEquals("expired", text.primary)
        assertEquals("Mon Jul 27, 01:52", text.absolute)
        assertNoResetVocabulary(text)
    }

    @Test
    fun nullExpiryReadsUnavailableWithoutFabricatedTime() {
        val text = formatCreditExpiry(
            now = Instant.parse("2026-07-27T10:00:00Z"),
            expiresAt = null,
            zoneId = ZoneId.of("UTC"),
        )

        assertEquals("Expiry unavailable", text.primary)
        assertEquals(null, text.absolute)
        assertNoResetVocabulary(text)
    }

    private fun assertNoResetVocabulary(text: CreditExpiryText) {
        val rendered = listOfNotNull(text.primary, text.absolute).joinToString(" ")
        listOf("resets", "limits reset", "next reset", "Heavy work can resume.").forEach { forbidden ->
            assertFalse("credit copy must not contain '$forbidden': $rendered", rendered.contains(forbidden))
        }
    }
}
