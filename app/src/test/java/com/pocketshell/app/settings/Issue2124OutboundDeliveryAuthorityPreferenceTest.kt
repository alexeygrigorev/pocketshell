package com.pocketshell.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #2124 — the flag the maintainer flips on-device, and the line that names
 * the CURRENTLY ACTIVE path so a field report is unambiguous about which one
 * produced it.
 *
 * The flag exists for exactly one purpose: run the acknowledged path in real use
 * and be able to say, from a screenshot, which authority answered. A flag that
 * does not persist, or a screen that does not name the active path, would make
 * the field run unreadable — so both are asserted here rather than eyeballed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue2124OutboundDeliveryAuthorityPreferenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun theAcknowledgedPathIsTheShippedDefault() = runTest {
        assertEquals(
            "the field test only happens if the acknowledged path is the default",
            OutboundDeliveryAuthority.HostCliAck,
            SettingsRepository(context).settings.first().outboundDeliveryAuthority,
        )
    }

    /** Flippable on-device without a reinstall, and it survives a restart. */
    @Test
    fun theFlagPersistsAcrossARepositoryRecreate() = runTest {
        SettingsRepository(context)
            .setOutboundDeliveryAuthority(OutboundDeliveryAuthority.TerminalInference)

        assertEquals(
            OutboundDeliveryAuthority.TerminalInference,
            SettingsRepository(context).settings.first().outboundDeliveryAuthority,
        )

        SettingsRepository(context)
            .setOutboundDeliveryAuthority(OutboundDeliveryAuthority.HostCliAck)
        assertEquals(
            OutboundDeliveryAuthority.HostCliAck,
            SettingsRepository(context).settings.first().outboundDeliveryAuthority,
        )
    }

    /** A hand-edited / future-unknown persisted value degrades to the default. */
    @Test
    fun anUnreadablePersistedValueFallsBackToTheDefault() = runTest {
        context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(SETTINGS_KEY_OUTBOUND_DELIVERY_AUTHORITY, "SomethingElse").commit()

        assertEquals(
            OutboundDeliveryAuthority.HostCliAck,
            SettingsRepository(context).settings.first().outboundDeliveryAuthority,
        )
    }

    /**
     * The shared constants the instrumentation pin uses must be the SAME strings
     * the repository writes — otherwise a connected test would pin a key nothing
     * reads and silently exercise the wrong path.
     */
    @Test
    fun theSharedPreferenceKeyIsTheOneTheRepositoryActuallyWrites() = runTest {
        SettingsRepository(context)
            .setOutboundDeliveryAuthority(OutboundDeliveryAuthority.TerminalInference)
        assertEquals(
            OutboundDeliveryAuthority.TerminalInference.name,
            context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(SETTINGS_KEY_OUTBOUND_DELIVERY_AUTHORITY, null),
        )
    }

    /**
     * The ACTIVE path must be nameable in the UI — the Settings section renders
     * `"Active: " + outboundDeliveryAuthorityLabel(...)` and one radio row per
     * authority, each with a stable tag.
     */
    @Test
    fun everyAuthorityHasADistinctUserFacingNameAndAStableTag() {
        val labels = OutboundDeliveryAuthority.entries.map(::outboundDeliveryAuthorityLabel)
        assertEquals(
            "every authority must be nameable in a field report",
            OutboundDeliveryAuthority.entries.size,
            labels.toSet().size,
        )
        assertTrue(labels.none { it.isBlank() })
        assertNotEquals(
            outboundDeliveryAuthorityOptionTag(OutboundDeliveryAuthority.HostCliAck),
            outboundDeliveryAuthorityOptionTag(OutboundDeliveryAuthority.TerminalInference),
        )
        assertTrue(
            OUTBOUND_DELIVERY_AUTHORITY_ACTIVE_TAG.startsWith("settings:terminal:"),
        )
    }
}
