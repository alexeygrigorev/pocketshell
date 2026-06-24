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
        tmuxSessionId: String? = null,
        sessionCreated: Long? = null,
    ) = LastSessionStore.LastSession(
        hostId = 7L,
        hostName = "prod box",
        hostname = "10.0.0.5",
        port = 2222,
        username = "me",
        keyPath = "/data/keys/id_ed25519",
        sessionName = "claude-main",
        startDirectory = startDirectory,
        tmuxSessionId = tmuxSessionId,
        sessionCreated = sessionCreated,
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
        assertNull(read.tmuxSessionId)
        assertNull(read.sessionCreated)
        assertEquals("deploy the thing", read.composerDraft)
        assertEquals(1_000L, read.savedAtMillis)
    }

    @Test
    fun `save then read round-trips durable tmux identity`() {
        val store = LastSessionStore(context)
        store.save(
            sample(
                savedAtMillis = 1_000L,
                tmuxSessionId = "\$3",
                sessionCreated = 1700000000L,
            ),
        )

        val read = LastSessionStore(context).read(nowMillis = 2_000L)

        assertNotNull(read)
        requireNotNull(read)
        assertEquals("\$3", read.tmuxSessionId)
        assertEquals(1700000000L, read.sessionCreated)
        val destination = with(store) { read.toDestination() }
        assertEquals("\$3", destination.tmuxSessionId)
        assertEquals(1700000000L, destination.sessionCreated)
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
            tmuxSessionId = "\$4",
            sessionCreated = 1700000004L,
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
        assertEquals("\$4", restored.tmuxSessionId)
        assertEquals(1700000004L, restored.sessionCreated)
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
            tmuxSessionId = "\$7",
            sessionCreated = 1700000007L,
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
        assertEquals("\$7", session.tmuxSessionId)
        assertEquals(1700000007L, session.sessionCreated)
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

    // ---------------------------------------------------------------- #834
    // Deleting a session must drop it as a restore target so the next
    // foreground/process-death resume never re-opens the deleted session
    // (which #818 lands on its Conversation tab — the #686 hazard). These
    // tests pin the in-memory tombstone + record-invalidation logic
    // `MainActivity` drives off `SessionLifecycleSignals.killedSessions`.

    @Test
    fun `onSessionKilled clears a stored record that points at the killed session`() {
        // Repro of #834: the killed agent session is the persisted "last
        // active". On d63b6a63 nothing cleared it, so a restore re-opened it.
        val store = LastSessionStore(context)
        store.save(sample(savedAtMillis = 1_000L)) // sessionName = "claude-main", hostId = 7

        store.onSessionKilled(hostId = 7L, sessionName = "claude-main")

        // Read from a fresh instance to prove the clear reached disk.
        assertNull(
            "a deleted session must not survive as a restore target",
            LastSessionStore(context).read(nowMillis = 2_000L),
        )
    }

    @Test
    fun `onSessionKilled does NOT clear a record for a different session`() {
        // Guard: killing session A never invalidates a stored session B.
        val store = LastSessionStore(context)
        store.save(sample(savedAtMillis = 1_000L)) // "claude-main"

        store.onSessionKilled(hostId = 7L, sessionName = "codex-side")

        val read = LastSessionStore(context).read(nowMillis = 2_000L)
        assertNotNull("an unrelated stored session must remain restorable", read)
        assertEquals("claude-main", read!!.sessionName)
    }

    @Test
    fun `onSessionKilled does NOT clear a same-name session on a different host`() {
        // Identity is (hostId, sessionName): a same-named session on host 99
        // must not be invalidated by a kill on host 7.
        val store = LastSessionStore(context)
        store.save(sample(savedAtMillis = 1_000L)) // host 7, "claude-main"

        store.onSessionKilled(hostId = 99L, sessionName = "claude-main")

        val read = LastSessionStore(context).read(nowMillis = 2_000L)
        assertNotNull("a same-name session on another host must remain", read)
        assertEquals(7L, read!!.hostId)
    }

    @Test
    fun `save of a killed session is refused so onStop cannot re-arm a deleted restore`() {
        // The user kills the session, then backgrounds the app while still
        // parked on the now-dead session screen: `onStop` would otherwise
        // re-`save()` the dead session and re-arm the restore. The tombstone
        // makes that save a no-op (clears instead).
        val store = LastSessionStore(context)
        store.onSessionKilled(hostId = 7L, sessionName = "claude-main")

        store.save(sample(savedAtMillis = 5_000L)) // same identity as the kill

        assertNull(
            "a save for the just-killed session must not persist a restore target",
            LastSessionStore(context).read(nowMillis = 6_000L),
        )
    }

    @Test
    fun `save of a different session still works after a kill`() {
        // Guard: the tombstone only suppresses the exact killed identity;
        // opening + saving a DIFFERENT session still arms a normal restore.
        val store = LastSessionStore(context)
        store.onSessionKilled(hostId = 7L, sessionName = "claude-main")

        store.save(sample(savedAtMillis = 5_000L).copyWith(sessionName = "codex"))

        val read = LastSessionStore(context).read(nowMillis = 6_000L)
        assertNotNull("a different session must still be restorable after a kill", read)
        assertEquals("codex", read!!.sessionName)
    }

    @Test
    fun `onSessionKilled with a blank session name is a no-op`() {
        val store = LastSessionStore(context)
        store.save(sample(savedAtMillis = 1_000L))

        store.onSessionKilled(hostId = 7L, sessionName = "   ")

        assertNotNull(
            "a blank kill name must not clear an unrelated stored session",
            LastSessionStore(context).read(nowMillis = 2_000L),
        )
    }

    @Test
    fun `recreated same-name session restores after a kill once reopened`() {
        // Reviewer blocker: the kill tombstone must NOT permanently poison a
        // session NAME. tmux names are habitually reused, so after deleting
        // `claude-main` the user may create a NEW `claude-main` on the SAME
        // host, open it, and background — that live session MUST restore.
        val store = LastSessionStore(context)

        // 1. Delete the original same-identity session.
        store.onSessionKilled(hostId = 7L, sessionName = "claude-main")

        // 2. The user recreates + OPENS a new session of that identity
        //    (navigator routes to the TmuxSession destination → onSessionOpened).
        store.onSessionOpened(hostId = 7L, sessionName = "claude-main")

        // 3. Background → onStop saves the now-live recreated session.
        store.save(sample(savedAtMillis = 5_000L)) // host 7, "claude-main"

        // 4. Next foreground must restore it — the tombstone is gone.
        val read = LastSessionStore(context).read(nowMillis = 6_000L)
        assertNotNull(
            "a recreated same-name session must restore after a kill+reopen — " +
                "the tombstone must not permanently suppress the name",
            read,
        )
        assertEquals("claude-main", read!!.sessionName)
        assertEquals(7L, read.hostId)
    }

    @Test
    fun `opening a different session does NOT clear an unrelated kill tombstone`() {
        // Guard: onSessionOpened only clears the tombstone for the SAME
        // identity. Opening session B must not un-suppress a just-killed
        // session A, so A still cannot re-arm a restore.
        val store = LastSessionStore(context)
        store.onSessionKilled(hostId = 7L, sessionName = "claude-main")

        store.onSessionOpened(hostId = 7L, sessionName = "codex-side")

        // A's tombstone survives, so a save of A is still suppressed.
        store.save(sample(savedAtMillis = 5_000L)) // host 7, "claude-main"
        assertNull(
            "killing A then opening B must not let A re-arm a restore",
            LastSessionStore(context).read(nowMillis = 6_000L),
        )
    }

    @Test
    fun `opening a session with no tombstone is a harmless no-op`() {
        val store = LastSessionStore(context)
        store.save(sample(savedAtMillis = 1_000L))

        // No kill happened; opening must not disturb the stored record.
        store.onSessionOpened(hostId = 7L, sessionName = "claude-main")

        val read = LastSessionStore(context).read(nowMillis = 2_000L)
        assertNotNull(read)
        assertEquals("claude-main", read!!.sessionName)
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
