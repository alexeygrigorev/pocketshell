package com.pocketshell.app.systemsurfaces

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.initialDestinationFromIntent
import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.session.LastSessionStore
import com.pocketshell.app.shareSessionDestinationFromIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1239 (AC2): the Active-Sessions home-screen widget tap must deep-link
 * straight into a session surface, not the host-list top.
 *
 * The widget builds its launch intent via
 * [ActiveSessionsWidgetProvider.widgetLaunchIntent]. This suite proves that,
 * given a persisted last-session snapshot, that intent carries the same
 * `EXTRA_OPEN_SESSION_*` deep-link extras the production launch path consumes —
 * so `MainActivity.initialDestinationFromIntent` routes it to
 * [AppDestination.TmuxSession] (the live session screen) rather than
 * [AppDestination.HostList]. With no snapshot it degrades to a bare
 * host-list launch (the non-dead-end fallback).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ActiveSessionsWidgetIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun sample() = LastSessionStore.LastSession(
        hostId = 11L,
        hostName = "hetzner",
        hostname = "65.108.42.11",
        port = 2222,
        username = "alex",
        keyPath = "/data/keys/id_ed25519",
        sessionName = "claude-main",
        startDirectory = "/home/alex/project",
        composerDraft = "",
        savedAtMillis = 1_000L,
    )

    @Test
    fun widgetIntent_withSnapshot_deepLinksIntoTheSession() {
        val intent = ActiveSessionsWidgetProvider.widgetLaunchIntent(context, sample())

        // The production launch path resolves it to the live session, not the
        // host list — the widget tap lands the user directly in their session.
        val destination = initialDestinationFromIntent(intent)
        check(destination is AppDestination.TmuxSession) {
            "widget tap must deep-link into a TmuxSession, was $destination"
        }
        assertEquals(11L, destination.hostId)
        assertEquals("hetzner", destination.hostName)
        assertEquals("65.108.42.11", destination.hostname)
        assertEquals(2222, destination.port)
        assertEquals("alex", destination.username)
        assertEquals("/data/keys/id_ed25519", destination.keyPath)
        assertEquals("claude-main", destination.sessionName)
        // Passphrase is never carried in an intent — reattach resolves the key
        // from disk by path.
        assertNull(destination.passphrase)
    }

    @Test
    fun widgetIntent_withSnapshot_roundTripsThroughShareSessionParser() {
        val intent = ActiveSessionsWidgetProvider.widgetLaunchIntent(context, sample())

        val parsed = shareSessionDestinationFromIntent(intent)
        requireNotNull(parsed) { "widget deep-link extras must parse to a TmuxSession" }
        assertEquals(11L, parsed.hostId)
        assertEquals("claude-main", parsed.sessionName)
        assertEquals("65.108.42.11", parsed.hostname)
    }

    @Test
    fun widgetIntent_withNoSnapshot_fallsBackToHostList() {
        val intent = ActiveSessionsWidgetProvider.widgetLaunchIntent(context, lastSession = null)

        // No deep-link extras → the launch path opens the host list (non-dead-end
        // fallback), never a broken/partial session route.
        assertNull(shareSessionDestinationFromIntent(intent))
        assertEquals(AppDestination.HostList, initialDestinationFromIntent(intent))
    }
}
