package com.pocketshell.app.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.resolveLastSessionForStop
import com.pocketshell.app.tmux.TmuxConnectTrigger
import com.pocketshell.app.tmux.TmuxRestoreIntentSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #177: unit tests for [LastSessionStore] against Robolectric's
 * in-memory `SharedPreferences`. Mirrors [SettingsRepositoryTest]'s
 * conventions (Robolectric, manifest-less config).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LastSessionStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("last_session", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun sample(
        savedAtMillis: Long = 1_000L,
        draft: String = "deploy the thing",
        startDirectory: String? = "/home/me/project",
    ) = LastSessionStore.LastSession(
        hostId = 7L,
        hostName = "prod box",
        hostname = "10.0.0.5",
        port = 2222,
        username = "me",
        keyPath = "/data/keys/id_ed25519",
        sessionName = "claude-main",
        startDirectory = startDirectory,
        composerDraft = draft,
        savedAtMillis = savedAtMillis,
    )

    @Test
    fun `read on cold store returns null`() {
        val store = LastSessionStore(context)
        assertNull(store.read(nowMillis = 5_000L))
    }

    @Test
    fun `save then read round-trips every field`() {
        val store = LastSessionStore(context)
        store.save(sample(savedAtMillis = 1_000L))

        // Re-read from a fresh instance to prove the write reached disk.
        val read = LastSessionStore(context).read(nowMillis = 2_000L)
        assertNotNull(read)
        requireNotNull(read)
        assertEquals(7L, read.hostId)
        assertEquals("prod box", read.hostName)
        assertEquals("10.0.0.5", read.hostname)
        assertEquals(2222, read.port)
        assertEquals("me", read.username)
        assertEquals("/data/keys/id_ed25519", read.keyPath)
        assertEquals("claude-main", read.sessionName)
        assertEquals("/home/me/project", read.startDirectory)
        assertEquals("deploy the thing", read.composerDraft)
        assertEquals(1_000L, read.savedAtMillis)
    }

    @Test
    fun `null start directory round-trips`() {
        val store = LastSessionStore(context)
        store.save(sample(startDirectory = null))
        val read = store.read(nowMillis = 1_500L)
        assertNotNull(read)
        assertNull(read!!.startDirectory)
    }

    @Test
    fun `empty draft round-trips`() {
        val store = LastSessionStore(context)
        store.save(sample(draft = ""))
        val read = store.read(nowMillis = 1_500L)
        assertNotNull(read)
        assertEquals("", read!!.composerDraft)
    }

    @Test
    fun `snapshot older than max age is not restored`() {
        val store = LastSessionStore(context)
        // Use a non-zero base; `savedAt <= 0` is the "nothing saved"
        // sentinel inside read().
        val base = 100L
        store.save(sample(savedAtMillis = base))
        // Exactly one tick past the 24h window.
        val tooOld = base + LastSessionStore.DEFAULT_MAX_AGE_MILLIS + 1L
        assertNull(store.read(nowMillis = tooOld))
    }

    @Test
    fun `snapshot at the max-age boundary is still restored`() {
        val store = LastSessionStore(context)
        val base = 100L
        store.save(sample(savedAtMillis = base))
        // Exactly at the window edge counts as fresh.
        val atEdge = base + LastSessionStore.DEFAULT_MAX_AGE_MILLIS
        assertNotNull(store.read(nowMillis = atEdge))
    }

    @Test
    fun `clear removes the snapshot`() {
        val store = LastSessionStore(context)
        store.save(sample())
        store.clear()
        assertNull(store.read(nowMillis = 1_500L))
    }

    @Test
    fun `malformed blob missing required field returns null`() {
        // Simulate a partial / older-shaped blob: a timestamp but no
        // hostname. D22 — discard, never migrate.
        context.getSharedPreferences("last_session", Context.MODE_PRIVATE)
            .edit()
            .putLong("saved_at", 1_000L)
            .putLong("host_id", 7L)
            .putString("session_name", "claude-main")
            .commit()
        val store = LastSessionStore(context)
        assertNull(store.read(nowMillis = 1_500L))
    }

    @Test
    fun `wrong typed restored blob returns null`() {
        context.getSharedPreferences("last_session", Context.MODE_PRIVATE)
            .edit()
            .putString("saved_at", "yesterday")
            .putString("host_id", "seven")
            .putString("port", "ssh")
            .putString("hostname", "10.0.0.5")
            .putString("username", "me")
            .putString("key_path", "/k")
            .putString("session_name", "s")
            .commit()

        val store = LastSessionStore(context)

        assertNull(store.read(nowMillis = 1_500L))
    }

    @Test
    fun `non-positive host id returns null`() {
        context.getSharedPreferences("last_session", Context.MODE_PRIVATE)
            .edit()
            .putLong("saved_at", 1_000L)
            .putLong("host_id", 0L)
            .putString("hostname", "10.0.0.5")
            .putString("username", "me")
            .putString("key_path", "/k")
            .putString("session_name", "s")
            .commit()
        val store = LastSessionStore(context)
        assertNull(store.read(nowMillis = 1_500L))
    }

    @Test
    fun `toDestination rebuilds a TmuxSession with a null passphrase`() {
        val store = LastSessionStore(context)
        val dest = with(store) { sample().toDestination() }
        assertEquals(7L, dest.hostId)
        assertEquals("prod box", dest.hostName)
        assertEquals("10.0.0.5", dest.hostname)
        assertEquals(2222, dest.port)
        assertEquals("me", dest.username)
        assertEquals("/data/keys/id_ed25519", dest.keyPath)
        assertEquals("claude-main", dest.sessionName)
        assertEquals("/home/me/project", dest.startDirectory)
        // The passphrase is never persisted — the reattach resolves the
        // key from disk by path, same as a cold attach.
        assertNull(dest.passphrase)
    }

    @Test
    fun `latest save overwrites the previous snapshot`() {
        val store = LastSessionStore(context)
        store.save(sample(savedAtMillis = 1_000L, draft = "first"))
        store.save(
            sample(savedAtMillis = 2_000L, draft = "second").copyWith(sessionName = "codex"),
        )
        val read = store.read(nowMillis = 2_500L)
        assertNotNull(read)
        assertEquals("second", read!!.composerDraft)
        assertEquals("codex", read.sessionName)
        assertEquals(2_000L, read.savedAtMillis)
    }

    @Test
    fun `onStop save uses intended tmux target over stale route destination`() {
        val store = LastSessionStore(context)
        val staleRoute = AppDestination.TmuxSession(
            hostId = 7L,
            hostName = "prod box",
            hostname = "10.0.0.5",
            port = 2222,
            username = "me",
            keyPath = "/data/keys/id_ed25519",
            passphrase = null,
            sessionName = "session-a",
            startDirectory = null,
        )
        val intended = TmuxRestoreIntentSnapshot(
            hostId = 7L,
            hostName = "prod box",
            hostname = "10.0.0.5",
            port = 2222,
            username = "me",
            keyPath = "/data/keys/id_ed25519",
            sessionName = "session-b",
            startDirectory = "/home/me/project-b",
            trigger = TmuxConnectTrigger.UserTap,
            generation = 42L,
        )

        val session = resolveLastSessionForStop(
            currentDestination = staleRoute,
            tmuxIntent = intended,
            composerDraft = "draft while switching",
            savedAtMillis = 5_000L,
        )
        assertNotNull(session)
        store.save(requireNotNull(session))

        val restored = store.read(nowMillis = 6_000L)
        assertNotNull(restored)
        requireNotNull(restored)
        assertEquals("session-b", restored.sessionName)
        assertEquals("/home/me/project-b", restored.startDirectory)
        assertEquals("draft while switching", restored.composerDraft)
        assertEquals(5_000L, restored.savedAtMillis)
    }

    @Test
    fun `onStop save falls back to route destination when no tmux intent exists`() {
        val route = AppDestination.TmuxSession(
            hostId = 7L,
            hostName = "prod box",
            hostname = "10.0.0.5",
            port = 2222,
            username = "me",
            keyPath = "/data/keys/id_ed25519",
            passphrase = null,
            sessionName = "session-a",
            startDirectory = null,
        )

        val session = resolveLastSessionForStop(
            currentDestination = route,
            tmuxIntent = null,
            composerDraft = "draft",
            savedAtMillis = 5_000L,
        )

        assertNotNull(session)
        requireNotNull(session)
        assertEquals("session-a", session.sessionName)
        assertEquals("draft", session.composerDraft)
    }

    @Test
    fun `onStop clears restore when current destination is not a tmux session`() {
        val session = resolveLastSessionForStop(
            currentDestination = AppDestination.HostList,
            tmuxIntent = TmuxRestoreIntentSnapshot(
                hostId = 7L,
                hostName = "prod box",
                hostname = "10.0.0.5",
                port = 2222,
                username = "me",
                keyPath = "/data/keys/id_ed25519",
                sessionName = "session-b",
                startDirectory = null,
                trigger = TmuxConnectTrigger.UserTap,
                generation = 42L,
            ),
            composerDraft = "draft",
            savedAtMillis = 5_000L,
        )

        assertNull(session)
    }

    private fun LastSessionStore.LastSession.copyWith(
        sessionName: String,
    ): LastSessionStore.LastSession = copy(sessionName = sessionName)
}

/**
 * Pin the rebuilt destination type so a refactor that changes
 * [AppDestination.TmuxSession]'s shape forces this test to be revisited.
 */
private val tmuxDestinationContract: Class<*> = AppDestination.TmuxSession::class.java
