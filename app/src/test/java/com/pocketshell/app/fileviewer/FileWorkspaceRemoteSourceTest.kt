package com.pocketshell.app.fileviewer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1715 — parse/encode of the host workspace envelope.
 *
 * G6 mutation: if parseWorkspace treated a non-JSON / missing-tabs payload as
 * an available empty workspace, the old-CLI unavailable path would silently
 * look like "no files open" instead of "update PocketShell on this host".
 */
class FileWorkspaceRemoteSourceTest {

    private val source = FileWorkspaceRemoteSource()

    @Test
    fun parseWorkspaceRoundTripsTabsAndActivePath() {
        val json = """
            {"tabs":[
              {"path":"/home/u/a.md","last_activated_ms":10},
              {"path":"/home/u/b.kt","last_activated_ms":20}
            ],"active_path":"/home/u/b.kt"}
        """.trimIndent()
        val parsed = source.parseWorkspace(json)!!
        assertTrue(parsed.available)
        assertEquals(
            listOf("/home/u/a.md", "/home/u/b.kt"),
            parsed.workspace.orderedTabs.map { it.absolutePath },
        )
        assertEquals("/home/u/b.kt", parsed.workspace.activePath)
    }

    @Test
    fun parseWorkspaceDropsRelativeRowsAndRecoversActive() {
        val json = """
            {"tabs":[
              {"path":"relative.md","last_activated_ms":1},
              {"path":"/ok/a.txt","last_activated_ms":2}
            ],"active_path":"/gone.txt"}
        """.trimIndent()
        val parsed = source.parseWorkspace(json)!!
        assertEquals(listOf("/ok/a.txt"), parsed.workspace.orderedTabs.map { it.absolutePath })
        assertEquals("/ok/a.txt", parsed.workspace.activePath)
    }

    @Test
    fun parseWorkspaceRejectsNonJsonAsUnavailableSignal() {
        assertNull(
            "old CLI help text must not parse as an empty available workspace",
            source.parseWorkspace("pocketshell tree fixture supports: workspace-get"),
        )
        assertNull(source.parseWorkspace(""))
        assertNull(source.parseWorkspace("[]"))
    }

    @Test
    fun parseWorkspaceRejectsJsonWithoutTabsArrayAsUnavailableSignal() {
        assertNull(
            "a malformed/old response must not erase a durable workspace as empty",
            source.parseWorkspace("{\"active_path\":null}"),
        )
        assertNull(source.parseWorkspace("{\"tabs\":null,\"active_path\":null}"))
    }

    @Test
    fun buildUpsertRequestOmitsCredentialsAndEncodesNullActive() {
        val json = source.buildUpsertRequest(
            FileWorkspace(
                orderedTabs = listOf(OpenFileTab("/a.md", 3)),
                activePath = null,
            ),
        )
        val root = JSONObject(json)
        assertEquals("/a.md", root.getJSONArray("tabs").getJSONObject(0).getString("path"))
        assertTrue(root.isNull("active_path"))
        assertFalse("tab store must never persist credentials", json.contains("passphrase"))
        assertFalse(json.contains("keyPath"))
    }

    @Test
    fun twoSourceInstancesDoNotShareWorkspaceState() {
        val a = FileWorkspaceRemoteSource()
        val b = FileWorkspaceRemoteSource()
        val parsedA = a.parseWorkspace(
            """{"tabs":[{"path":"/host-a/x.md","last_activated_ms":1}],"active_path":"/host-a/x.md"}""",
        )!!
        val parsedB = b.parseWorkspace("""{"tabs":[],"active_path":null}""")!!
        assertEquals("/host-a/x.md", parsedA.workspace.activePath)
        assertTrue(
            "a second remote (different SSH session / Unix account) must not inherit host A's tabs",
            parsedB.workspace.orderedTabs.isEmpty(),
        )
    }
}
