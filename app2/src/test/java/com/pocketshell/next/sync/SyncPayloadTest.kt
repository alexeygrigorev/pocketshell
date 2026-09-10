package com.pocketshell.next.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wire payload and the selection rules (issue #2633) — the Android port of
 * the desktop app's `tests/unit/syncMerge.test.ts`.
 *
 * Two properties carry the feature and are asserted here rather than assumed:
 *
 * 1. **Only ticked hosts leave the device.** [assembleSyncSet] is the single
 *    gate; if an unticked host could reach the payload, the encryption would be
 *    protecting data the user never agreed to upload in the first place.
 * 2. **A phone push must not degrade a laptop's entry.** The desktop's host
 *    entries carry directives this client has no model for; they ride through
 *    [SyncHostEntry.extras] instead of being silently dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SyncPayloadTest {

    private val local = listOf(
        SyncHostEntry("hetzner", "135.181.114.209", 22, "alexey"),
        SyncHostEntry("builder", "10.0.0.7", 2022, "root"),
    )

    @Test
    fun `serializes the documented payload shape`() {
        val json = JSONObject(serializeSyncPayload(local))
        assertEquals(setOf("hosts"), json.keys().asSequence().toSet())
        val first = json.getJSONArray("hosts").getJSONObject(0)
        assertEquals("hetzner", first.getString("name"))
        assertEquals("135.181.114.209", first.getString("hostname"))
        assertEquals(22, first.getInt("port"))
        assertEquals("alexey", first.getString("user"))
    }

    @Test
    fun `round-trips through serialize and parse`() {
        assertEquals(local, parseSyncPayload(serializeSyncPayload(local)))
    }

    @Test
    fun `parses a payload degraded rather than failing`() {
        assertEquals(emptyList<SyncHostEntry>(), parseSyncPayload("not json"))
        assertEquals(emptyList<SyncHostEntry>(), parseSyncPayload("[]"))
        assertEquals(emptyList<SyncHostEntry>(), parseSyncPayload("""{"other":1}"""))
        assertEquals(emptyList<SyncHostEntry>(), parseSyncPayload("""{"hosts":"nope"}"""))
    }

    @Test
    fun `drops entries that are not addressable hosts`() {
        val hosts = parseSyncPayload(
            """{"hosts":[
                 {"name":"","hostname":"a"},
                 {"name":"b","hostname":"  "},
                 "a string",
                 {"name":"good","hostname":"host.example"}
               ]}""",
        )
        assertEquals(listOf("good"), hosts.map { it.name })
    }

    @Test
    fun `normalises an out-of-range port to the default`() {
        val hosts = parseSyncPayload("""{"hosts":[{"name":"a","hostname":"h","port":99999}]}""")
        assertEquals(22, hosts.single().port)
    }

    @Test
    fun `carries desktop-only fields through a phone round-trip`() {
        // A laptop entry with directives this client does not model. A push
        // from the phone must re-emit them untouched, or one "Sync now" from a
        // phone quietly strips the laptop's jump host and forwards.
        val desktop = """{"hosts":[{
            "name":"hetzner","hostname":"135.181.114.209","port":22,"user":"alexey",
            "proxyJump":"bastion","forwardAgent":true,"identityFile":"~/.ssh/id_ed25519",
            "localForwards":[{"kind":"local","listenHost":"","listenPort":8080,"destHost":"127.0.0.1","destPort":80}]
        }]}"""
        val parsed = parseSyncPayload(desktop)
        val reEmitted = JSONObject(serializeSyncPayload(parsed)).getJSONArray("hosts").getJSONObject(0)

        assertEquals("bastion", reEmitted.getString("proxyJump"))
        assertTrue(reEmitted.getBoolean("forwardAgent"))
        assertEquals("~/.ssh/id_ed25519", reEmitted.getString("identityFile"))
        val forward = reEmitted.getJSONArray("localForwards").getJSONObject(0)
        assertEquals(8080, forward.getInt("listenPort"))
        assertEquals("127.0.0.1", forward.getString("destHost"))
    }

    @Test
    fun `assembles only the ticked hosts`() {
        val assembled = assembleSyncSet(local, remote = emptyList(), checked = listOf("hetzner"))
        assertEquals(listOf("hetzner"), assembled.map { it.name })
    }

    @Test
    fun `an unticked host never reaches the payload`() {
        val payload = serializeSyncPayload(
            assembleSyncSet(local, remote = emptyList(), checked = listOf("builder")),
        )
        assertTrue("hetzner leaked into the payload: $payload", !payload.contains("hetzner"))
        assertTrue(!payload.contains("135.181.114.209"))
    }

    @Test
    fun `an empty selection uploads nothing`() {
        assertEquals(
            """{"hosts":[]}""",
            serializeSyncPayload(assembleSyncSet(local, emptyList(), emptyList())),
        )
    }

    @Test
    fun `prefers the local entry over the account's for a ticked alias`() {
        val remote = listOf(SyncHostEntry("hetzner", "stale.example", 2222, "old"))
        val assembled = assembleSyncSet(local, remote, checked = listOf("hetzner"))
        assertEquals("135.181.114.209", assembled.single().hostname)
        assertEquals("alexey", assembled.single().user)
    }

    @Test
    fun `keeps the account entry for a ticked alias this device has lost`() {
        val remote = listOf(SyncHostEntry("restored", "backup.example", 22, "root"))
        val assembled = assembleSyncSet(local, remote, checked = listOf("restored"))
        assertEquals(listOf("restored"), assembled.map { it.name })
        assertEquals("backup.example", assembled.single().hostname)
    }

    @Test
    fun `a ticked alias with no entry anywhere contributes nothing`() {
        assertEquals(
            emptyList<SyncHostEntry>(),
            assembleSyncSet(local, emptyList(), checked = listOf("ghost")),
        )
    }

    @Test
    fun `de-duplicates a repeated tick`() {
        val assembled = assembleSyncSet(local, emptyList(), listOf("hetzner", "hetzner"))
        assertEquals(1, assembled.size)
    }

    @Test
    fun `a local entry inherits the account's extra directives`() {
        val remote = parseSyncPayload(
            """{"hosts":[{"name":"hetzner","hostname":"old","port":22,"user":"old","proxyJump":"bastion"}]}""",
        )
        val assembled = assembleSyncSet(local, remote, checked = listOf("hetzner")).single()
        assertEquals("135.181.114.209", assembled.hostname)
        assertEquals("bastion", assembled.extras["proxyJump"])
    }

    @Test
    fun `auto-ticks account aliases this device does not have`() {
        val remote = listOf(
            SyncHostEntry("hetzner", "135.181.114.209", 22, "alexey"),
            SyncHostEntry("laptop-only", "10.1.1.1", 22, "alexey"),
        )
        val added = aliasesToAutoCheck(
            remote = remote,
            checked = emptyList(),
            localAliases = local.map { it.name },
        )
        // `hetzner` exists locally, so the user has already decided about it —
        // their untick must stand. `laptop-only` has never materialised here.
        assertEquals(listOf("laptop-only"), added)
    }

    @Test
    fun `does not re-tick an alias already in the selection`() {
        val remote = listOf(SyncHostEntry("laptop-only", "10.1.1.1", 22, "alexey"))
        assertEquals(
            emptyList<String>(),
            aliasesToAutoCheck(remote, checked = listOf("laptop-only"), localAliases = emptyList()),
        )
    }

    @Test
    fun `a fresh device pulls, auto-ticks, and re-uploads the account intact`() {
        // The self-healing flow from docs/SYNC.md: a device with no hosts of
        // its own must not wipe the account on its first "Sync now".
        val account = parseSyncPayload(
            """{"hosts":[{"name":"a","hostname":"a.example","port":22,"user":"u"},
                        {"name":"b","hostname":"b.example","port":22,"user":"u"}]}""",
        )
        val ticked = aliasesToAutoCheck(account, checked = emptyList(), localAliases = emptyList())
        val assembled = assembleSyncSet(local = emptyList(), remote = account, checked = ticked)
        assertEquals(account, assembled)
    }
}
