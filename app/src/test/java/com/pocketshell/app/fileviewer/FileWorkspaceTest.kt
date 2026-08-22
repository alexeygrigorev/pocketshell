package com.pocketshell.app.fileviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1715 — pure reducer for the host-durable open-file workspace.
 *
 * G6 mutations (each assertion names the edit that must redden it):
 *  - drop absolute-path validation → relative rows would survive recover()
 *  - skip dedupe → two aliases of the same path would both remain
 *  - evict the active tab at cap → `/f/00.txt` would vanish on the 13th open
 *  - close-active picks left first → nearest-right contract fails
 *  - uniqueLabels always uses basename → `src/App.kt` vs `test/App.kt` collides
 */
class FileWorkspaceTest {

    @Test
    fun normalizeDropsRelativeAndCollapsesDotDot() {
        assertNull(
            "relative paths must not become durable identity",
            FileWorkspaceReducer.normalizeAbsolutePath("notes.md"),
        )
        assertNull(FileWorkspaceReducer.normalizeAbsolutePath(""))
        assertNull(FileWorkspaceReducer.normalizeAbsolutePath(null))
        assertEquals(
            "/home/u/src/App.kt",
            FileWorkspaceReducer.normalizeAbsolutePath("/home/u/../u/src/./App.kt"),
        )
        assertEquals(
            "/home/u/notes.md",
            FileWorkspaceReducer.normalizeAbsolutePath("//home//u//notes.md"),
        )
    }

    @Test
    fun normalizeDoesNotLetRootParentEscapeTheRemoteRoot() {
        assertEquals(
            " /etc/hosts".trim(),
            FileWorkspaceReducer.normalizeAbsolutePath("/../../etc/./hosts"),
        )
    }

    @Test
    fun openDedupesResolvedAliasesAndKeepsInsertionSlot() {
        var ws = FileWorkspace.Empty
        ws = FileWorkspaceReducer.open(ws, "/home/u/a.md", nowMillis = 1)
        ws = FileWorkspaceReducer.open(ws, "/home/u/b.md", nowMillis = 2)
        ws = FileWorkspaceReducer.open(ws, "/home/u/../u/a.md", nowMillis = 9)
        assertEquals(listOf("/home/u/a.md", "/home/u/b.md"), ws.orderedTabs.map { it.absolutePath })
        assertEquals(9L, ws.orderedTabs[0].lastActivatedAtMillis)
        assertEquals("/home/u/a.md", ws.activePath)
    }

    @Test
    fun recoverDropsMalformedAndRecoversActive() {
        val recovered = FileWorkspaceReducer.recover(
            tabs = listOf(
                OpenFileTab("relative.md", 1),
                OpenFileTab("/ok/a.txt", 1),
                OpenFileTab("/ok/b.txt", 5),
                OpenFileTab("/ok/../ok/a.txt", 9),
            ),
            activePath = "/gone.txt",
        )
        assertEquals(listOf("/ok/a.txt", "/ok/b.txt"), recovered.orderedTabs.map { it.absolutePath })
        assertEquals(9L, recovered.orderedTabs[0].lastActivatedAtMillis)
        assertEquals(
            "missing active recovers to most recently activated",
            "/ok/a.txt",
            recovered.activePath,
        )
    }

    @Test
    fun thirteenthOpenEvictsOldestInactiveNeverActive() {
        var ws = FileWorkspace.Empty
        repeat(12) { i ->
            ws = FileWorkspaceReducer.open(ws, "/f/%02d.txt".format(i), nowMillis = i.toLong())
        }
        // Re-activate 00 so it is recently used, then open a 13th. Cap must
        // drop the oldest inactive (01), never 00 or the new tab.
        ws = FileWorkspaceReducer.activate(ws, "/f/00.txt", nowMillis = 50)
        ws = FileWorkspaceReducer.open(ws, "/f/new.txt", nowMillis = 100)
        val paths = ws.orderedTabs.map { it.absolutePath }
        assertEquals(12, paths.size)
        assertTrue("active tab must survive eviction", "/f/00.txt" in paths)
        assertTrue("/f/new.txt" in paths)
        assertTrue(
            "oldest inactive (/f/01.txt) is the tab the cap must drop",
            "/f/01.txt" !in paths,
        )
        assertEquals("/f/new.txt", ws.activePath)
    }

    @Test
    fun recoverCapsAt12WithoutEvictingActive() {
        val tabs = (0 until 12).map { i ->
            OpenFileTab("/f/%02d.txt".format(i), i.toLong())
        } + OpenFileTab("/f/new.txt", 100)
        val recovered = FileWorkspaceReducer.recover(tabs, activePath = "/f/00.txt")
        val paths = recovered.orderedTabs.map { it.absolutePath }
        assertEquals(12, paths.size)
        assertTrue("/f/00.txt" in paths)
        assertTrue("/f/new.txt" in paths)
        assertTrue("/f/01.txt" !in paths)
        assertEquals("/f/00.txt", recovered.activePath)
    }

    @Test
    fun closeInactiveLeavesActiveUnchanged() {
        var ws = FileWorkspace.Empty
        ws = FileWorkspaceReducer.open(ws, "/a.md", 1)
        ws = FileWorkspaceReducer.open(ws, "/b.md", 2)
        ws = FileWorkspaceReducer.open(ws, "/c.md", 3)
        ws = FileWorkspaceReducer.close(ws, "/b.md")
        assertEquals(listOf("/a.md", "/c.md"), ws.orderedTabs.map { it.absolutePath })
        assertEquals("/c.md", ws.activePath)
    }

    @Test
    fun closeActiveSelectsNearestRightElseLeft() {
        var ws = FileWorkspace.Empty
        ws = FileWorkspaceReducer.open(ws, "/a.md", 1)
        ws = FileWorkspaceReducer.open(ws, "/b.md", 2)
        ws = FileWorkspaceReducer.open(ws, "/c.md", 3)
        ws = FileWorkspaceReducer.activate(ws, "/b.md", 4)
        val closedB = FileWorkspaceReducer.close(ws, "/b.md")
        assertEquals(
            "active close prefers the tab that was to the right",
            "/c.md",
            closedB.activePath,
        )

        val closedC = FileWorkspaceReducer.close(closedB, "/c.md")
        assertEquals(
            "no right neighbour → nearest left",
            "/a.md",
            closedC.activePath,
        )
    }

    @Test
    fun lastCloseYieldsEmptyWorkspace() {
        var ws = FileWorkspaceReducer.open(FileWorkspace.Empty, "/solo.md", 1)
        ws = FileWorkspaceReducer.close(ws, "/solo.md")
        assertEquals(FileWorkspace.Empty, ws)
        assertNull(ws.activePath)
        assertTrue(ws.orderedTabs.isEmpty())
    }

    @Test
    fun uniqueLabelsUseShortestParentSuffixForDuplicates() {
        val tabs = listOf(
            OpenFileTab("/home/u/src/App.kt", 1),
            OpenFileTab("/home/u/test/App.kt", 2),
            OpenFileTab("/home/u/README.md", 3),
        )
        val labels = FileWorkspaceReducer.uniqueLabels(tabs)
        assertEquals("src/App.kt", labels["/home/u/src/App.kt"])
        assertEquals("test/App.kt", labels["/home/u/test/App.kt"])
        assertEquals("README.md", labels["/home/u/README.md"])
    }

    @Test
    fun activateDoesNotReorderTheStrip() {
        var ws = FileWorkspace.Empty
        ws = FileWorkspaceReducer.open(ws, "/a.md", 1)
        ws = FileWorkspaceReducer.open(ws, "/b.md", 2)
        ws = FileWorkspaceReducer.open(ws, "/c.md", 3)
        ws = FileWorkspaceReducer.activate(ws, "/a.md", 9)
        assertEquals(
            "activation must keep visual insertion order",
            listOf("/a.md", "/b.md", "/c.md"),
            ws.orderedTabs.map { it.absolutePath },
        )
        assertEquals("/a.md", ws.activePath)
        assertEquals(9L, ws.orderedTabs[0].lastActivatedAtMillis)
    }
}
