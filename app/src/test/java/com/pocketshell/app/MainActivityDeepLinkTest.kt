package com.pocketshell.app

import android.content.Intent
import android.net.Uri
import com.pocketshell.app.nav.AppDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #129: verify the deep-link extractor pulls the payload out of
 * a `pocketshell://import?payload=...` intent and ignores unrelated
 * intents. Robolectric is needed because [Uri.parse] is part of the
 * Android stdlib stubs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MainActivityDeepLinkTest {

    @Test
    fun importPayloadFromIntent_pullsPayloadFromQuery() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("pocketshell://import?payload=hello%20world")
        }
        assertEquals("hello world", importPayloadFromIntent(intent))
    }

    @Test
    fun importPayloadFromIntent_handlesJsonPayload() {
        val payload = """{"type":"pocketshell.ssh-import.v1","version":1}"""
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(
                "pocketshell://import?payload=" + Uri.encode(payload),
            )
        }
        assertEquals(payload, importPayloadFromIntent(intent))
    }

    @Test
    fun importPayloadFromIntent_ignoresOtherSchemes() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://example.com/?payload=x")
        }
        assertNull(importPayloadFromIntent(intent))
    }

    @Test
    fun importPayloadFromIntent_ignoresOtherHosts() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("pocketshell://session?payload=x")
        }
        assertNull(importPayloadFromIntent(intent))
    }

    @Test
    fun importPayloadFromIntent_ignoresNonViewIntent() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            data = Uri.parse("pocketshell://import?payload=x")
        }
        assertNull(importPayloadFromIntent(intent))
    }

    @Test
    fun importPayloadFromIntent_returnsNullForMissingPayload() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("pocketshell://import")
        }
        assertNull(importPayloadFromIntent(intent))
    }

    // ----------------------------------------------------------------
    // Issue #177: resolveInitialDestination — the route-restore gate.
    // ----------------------------------------------------------------

    private fun restoredSession() = AppDestination.TmuxSession(
        hostId = 7L,
        hostName = "prod box",
        hostname = "10.0.0.5",
        port = 2222,
        username = "me",
        keyPath = "/data/keys/id_ed25519",
        passphrase = null,
        sessionName = "claude-main",
        startDirectory = "/home/me/project",
    )

    @Test
    fun resolveInitialDestination_freshColdLaunch_landsOnHostList() {
        // savedInstanceState == null (deliberate close + relaunch). Even
        // with a recent snapshot available, the user must land on the host
        // list — this is the ColdInstall / RealAgentReleaseGate /
        // EmulatorWorkflow close+relaunch contract.
        val dest = resolveInitialDestination(
            intentDestination = AppDestination.HostList,
            resumingFromProcessDeath = false,
            restoredDestination = restoredSession(),
        )
        assertEquals(AppDestination.HostList, dest)
    }

    @Test
    fun resolveInitialDestination_freshColdLaunch_usesDefaultHostDestination() {
        val defaultHost = AppDestination.FolderList(
            hostId = 9L,
            hostName = "default",
            hostname = "10.0.0.9",
            port = 22,
            username = "me",
            keyPath = "/data/keys/default",
            passphrase = null,
        )
        val dest = resolveInitialDestination(
            intentDestination = AppDestination.HostList,
            resumingFromProcessDeath = false,
            restoredDestination = restoredSession(),
            defaultHostDestination = defaultHost,
        )
        assertEquals(defaultHost, dest)
    }

    @Test
    fun resolveInitialDestination_processDeathResume_restoresSession() {
        // savedInstanceState != null (system re-created the activity after
        // reaping the backgrounded process) AND a fresh snapshot exists.
        val restored = restoredSession()
        val dest = resolveInitialDestination(
            intentDestination = AppDestination.HostList,
            resumingFromProcessDeath = true,
            restoredDestination = restored,
            defaultHostDestination = AppDestination.FolderList(
                hostId = 9L,
                hostName = "default",
                hostname = "10.0.0.9",
                port = 22,
                username = "me",
                keyPath = "/data/keys/default",
                passphrase = null,
            ),
        )
        assertEquals(restored, dest)
    }

    @Test
    fun resolveInitialDestination_processDeathResume_noSnapshot_landsOnHostList() {
        // Process-death resume but the snapshot was absent / stale (read()
        // returned null), so we still land on the host list.
        val dest = resolveInitialDestination(
            intentDestination = AppDestination.HostList,
            resumingFromProcessDeath = true,
            restoredDestination = null,
        )
        assertEquals(AppDestination.HostList, dest)
    }

    @Test
    fun resolveInitialDestination_explicitIntentRoute_alwaysWins() {
        // A QS-tile / deep-link route is never overridden by a restored
        // session, even on a process-death resume with a snapshot present.
        val dest = resolveInitialDestination(
            intentDestination = AppDestination.PortForwardChooser,
            resumingFromProcessDeath = true,
            restoredDestination = restoredSession(),
        )
        assertEquals(AppDestination.PortForwardChooser, dest)
    }

    // ----------------------------------------------------------------
    // Issue #560: share-into-session launch intent decoding.
    // ----------------------------------------------------------------

    private fun shareSessionIntent(): Intent = Intent().apply {
        putExtra(MainActivity.EXTRA_OPEN_SESSION_HOST_ID, 7L)
        putExtra(MainActivity.EXTRA_OPEN_SESSION_HOST_NAME, "hetzner")
        putExtra(MainActivity.EXTRA_OPEN_SESSION_HOSTNAME, "10.0.0.5")
        putExtra(MainActivity.EXTRA_OPEN_SESSION_PORT, 2222)
        putExtra(MainActivity.EXTRA_OPEN_SESSION_USERNAME, "me")
        putExtra(MainActivity.EXTRA_OPEN_SESSION_KEY_PATH, "/data/keys/id")
        putExtra(MainActivity.EXTRA_OPEN_SESSION_NAME, "scratch")
        putExtra(
            MainActivity.EXTRA_OPEN_SESSION_ATTACHMENTS,
            arrayOf("~/.pocketshell/attachments/host-7-scratch/x.png"),
        )
    }

    @Test
    fun shareSessionDestinationFromIntent_buildsTmuxSession() {
        val dest = shareSessionDestinationFromIntent(shareSessionIntent())
        val expected = AppDestination.TmuxSession(
            hostId = 7L,
            hostName = "hetzner",
            hostname = "10.0.0.5",
            port = 2222,
            username = "me",
            keyPath = "/data/keys/id",
            passphrase = null,
            sessionName = "scratch",
        )
        assertEquals(expected, dest)
    }

    @Test
    fun initialDestinationFromIntent_routesShareSessionLaunch() {
        assertEquals(
            shareSessionDestinationFromIntent(shareSessionIntent()),
            initialDestinationFromIntent(shareSessionIntent()),
        )
    }

    @Test
    fun initialDestinationFromIntent_routesUsageNotificationLaunch() {
        val intent = Intent().putExtra(MainActivity.EXTRA_OPEN_USAGE, true)

        assertEquals(AppDestination.Usage, initialDestinationFromIntent(intent))
    }

    @Test
    fun shareSessionDestinationFromIntent_nullWhenMissingRequiredExtras() {
        // Missing session name -> not a share-session launch.
        val intent = shareSessionIntent().apply {
            removeExtra(MainActivity.EXTRA_OPEN_SESSION_NAME)
        }
        assertNull(shareSessionDestinationFromIntent(intent))
        assertEquals(AppDestination.HostList, initialDestinationFromIntent(intent))
    }

    @Test
    fun composerAttachmentsFromIntent_pullsStagedPaths() {
        assertEquals(
            listOf("~/.pocketshell/attachments/host-7-scratch/x.png"),
            composerAttachmentsFromIntent(shareSessionIntent()),
        )
    }

    @Test
    fun composerAttachmentsFromIntent_emptyWhenAbsent() {
        assertEquals(emptyList<String>(), composerAttachmentsFromIntent(Intent()))
    }
}
