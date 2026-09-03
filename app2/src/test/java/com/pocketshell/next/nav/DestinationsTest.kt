package com.pocketshell.next.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke test for the app2 skeleton (task M-1).
 *
 * Pins the two things the navigation graph can silently get wrong without the
 * compiler noticing: a route template that no longer matches the route the
 * builder produces (dead screen), and an argument that isn't encoded (a session
 * name with a space or a `/` in a remote path splitting into extra segments).
 */
class DestinationsTest {

    @Test
    fun `argument-free destinations build their own pattern`() {
        assertEquals("hosts", Destination.Hosts.route())
        assertEquals("settings", Destination.Settings.route())
        assertEquals("usage", Destination.Usage.route())
    }

    @Test
    fun `start destination is the host list`() {
        assertEquals(Destination.Hosts, Destination.start)
    }

    @Test
    fun `every destination pattern is unique and non-blank`() {
        // Touch a leaf destination BEFORE reading the aggregate, so this test
        // pins the class-initialization order too and not just the contents:
        // a nested object's initializer re-enters `Destination.<clinit>`, so an
        // eagerly-initialized `all` would capture a null for whichever
        // destination was touched first. Doing the touch here makes the check
        // independent of JUnit's method ordering.
        Destination.Files.route(hostId = 1)

        val patterns = Destination.all.map { it.pattern }
        // 8 = the plan's fixed six, plus Ports (task P-4) and FileViewer (P-3b).
        assertEquals(8, patterns.size)
        assertEquals(patterns.size, patterns.toSet().size)
        assertTrue(patterns.none { it.isBlank() })
    }

    @Test
    fun `built routes match their patterns`() {
        assertMatchesPattern(Destination.Tree.pattern, Destination.Tree.route(hostId = 7))
        assertMatchesPattern(
            Destination.Session.pattern,
            Destination.Session.route(hostId = 7, sessionName = "git-pocketshell"),
        )
        assertMatchesPattern(
            Destination.Files.pattern,
            Destination.Files.route(hostId = 7, path = "/home/alexey/notes.md"),
        )
        assertMatchesPattern(
            Destination.FileViewer.pattern,
            Destination.FileViewer.route(hostId = 7, path = "/home/alexey/notes.md"),
        )
        assertMatchesPattern(Destination.Ports.pattern, Destination.Ports.route(hostId = 7))
    }

    @Test
    fun `viewer route encodes the file path into its query argument`() {
        val route = Destination.FileViewer.route(hostId = 3, path = "/home/alexey/my notes.md")

        assertEquals("file/3?path=%2Fhome%2Falexey%2Fmy%20notes.md", route)
        assertEquals(2, route.substringBefore('?').split("/").size)
    }

    @Test
    fun `tree route carries the host id`() {
        assertEquals("tree/42", Destination.Tree.route(hostId = 42))
    }

    @Test
    fun `session name is percent-encoded into a single path segment`() {
        val route = Destination.Session.route(hostId = 1, sessionName = "my project:review")

        // One space -> %20 (NOT `+`, which navigation would not decode back),
        // one `:` left alone, and exactly three segments so the name cannot
        // leak into the route structure.
        assertEquals("session/1/my%20project%3Areview", route)
        assertEquals(3, route.split("/").size)
    }

    @Test
    fun `remote path is encoded so its slashes stay inside the query argument`() {
        val route = Destination.Files.route(hostId = 3, path = "/home/alexey/git/pocketshell")

        assertEquals("files/3?path=%2Fhome%2Falexey%2Fgit%2Fpocketshell", route)
        assertEquals(2, route.substringBefore('?').split("/").size)
    }

    @Test
    fun `files route without a path omits the optional argument`() {
        assertEquals("files/3", Destination.Files.route(hostId = 3))
    }

    /**
     * Structural check that a concrete route is an instance of its template:
     * same shape once every `{arg}` placeholder is replaced by "some value".
     */
    private fun assertMatchesPattern(pattern: String, route: String) {
        val regex = Regex(
            pattern.split(Regex("\\{[^}]+}"))
                .joinToString("[^/?]+") { Regex.escape(it) },
        )
        assertTrue("route '$route' does not match pattern '$pattern'", regex.matches(route))
    }
}
