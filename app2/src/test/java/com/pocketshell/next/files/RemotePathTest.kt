package com.pocketshell.next.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Path arithmetic for the file screens (task P-3a).
 *
 * Pure JVM — no Robolectric, no Room. These are the functions every navigation
 * on the explorer goes through, and each of the cases below is a real shape the
 * host or the user produces: a trailing slash from a typed path, a `..` from a
 * "go up", a doubled separator from a naive join, and the root, which is the
 * only path that is its own parent.
 */
class RemotePathTest {

    @Test
    fun `normalize makes a path absolute, separator-clean and trailing-slash-free`() {
        assertEquals("/home/alexey", RemotePath.normalize("/home/alexey"))
        assertEquals("/home/alexey", RemotePath.normalize("/home/alexey/"))
        assertEquals("/home/alexey", RemotePath.normalize("//home//alexey//"))
        assertEquals("/home/alexey", RemotePath.normalize("home/alexey"))
        assertEquals("/home/alexey", RemotePath.normalize("/home/./alexey"))
        assertEquals("/home", RemotePath.normalize("/home/alexey/.."))
    }

    @Test
    fun `normalize clamps above the root instead of producing a negative path`() {
        assertEquals("/", RemotePath.normalize("/.."))
        assertEquals("/", RemotePath.normalize("/../../.."))
        assertEquals("/etc", RemotePath.normalize("/../etc"))
    }

    @Test
    fun `a blank path is the root, not an error`() {
        assertEquals("/", RemotePath.normalize(""))
        assertEquals("/", RemotePath.normalize("/"))
        assertEquals("/", RemotePath.normalize("   ".trim()))
    }

    @Test
    fun `join keeps a server-supplied name inside the directory being browsed`() {
        assertEquals("/home/alexey/notes.md", RemotePath.join("/home/alexey", "notes.md"))
        assertEquals("/home/alexey/notes.md", RemotePath.join("/home/alexey/", "notes.md"))
        assertEquals("/notes.md", RemotePath.join("/", "notes.md"))
        // A name that is only separators cannot silently retarget the join.
        assertEquals("/home/alexey", RemotePath.join("/home/alexey", "/"))
    }

    @Test
    fun `parent walks up and stops at the root`() {
        assertEquals("/home/alexey", RemotePath.parent("/home/alexey/git"))
        assertEquals("/home", RemotePath.parent("/home/alexey"))
        assertEquals("/", RemotePath.parent("/home"))
        assertEquals("/", RemotePath.parent("/"))
    }

    @Test
    fun `nameOf is the display name, and the root names itself`() {
        assertEquals("notes.md", RemotePath.nameOf("/home/alexey/notes.md"))
        assertEquals("alexey", RemotePath.nameOf("/home/alexey/"))
        assertEquals("/", RemotePath.nameOf("/"))
    }

    @Test
    fun `crumbs trail every ancestor with the path a tap opens`() {
        assertEquals(
            listOf(
                RemotePath.Crumb("/", "/"),
                RemotePath.Crumb("home", "/home"),
                RemotePath.Crumb("alexey", "/home/alexey"),
                RemotePath.Crumb("git", "/home/alexey/git"),
            ),
            RemotePath.crumbs("/home/alexey/git"),
        )
        assertEquals(listOf(RemotePath.Crumb("/", "/")), RemotePath.crumbs("/"))
    }

    @Test
    fun `home-relative shapes are recognised`() {
        assertTrue(RemotePath.isHomeRelative("~"))
        assertTrue(RemotePath.isHomeRelative("~/git"))
        assertTrue(RemotePath.isHomeRelative("\$HOME"))
        assertTrue(RemotePath.isHomeRelative("\$HOME/git"))
        assertFalse(RemotePath.isHomeRelative("/home/alexey/git"))
        assertFalse(RemotePath.isHomeRelative("git"))
        // A directory literally named `~x` is not a home reference.
        assertFalse(RemotePath.isHomeRelative("~git"))
    }

    @Test
    fun `expandHome resolves both alias spellings against the host home`() {
        assertEquals("/home/alexey", RemotePath.expandHome("~", "/home/alexey"))
        assertEquals("/home/alexey/git", RemotePath.expandHome("~/git", "/home/alexey"))
        assertEquals("/home/alexey", RemotePath.expandHome("\$HOME", "/home/alexey"))
        assertEquals("/home/alexey/git", RemotePath.expandHome("\$HOME/git", "/home/alexey"))
        // A trailing slash on the home must not produce a double separator.
        assertEquals("/home/alexey/git", RemotePath.expandHome("~/git", "/home/alexey/"))
    }

    @Test
    fun `expandHome passes absolute paths through untouched`() {
        assertEquals("/home/alexey/git", RemotePath.expandHome("/home/alexey/git", "/home/alexey"))
        assertEquals("/srv/data", RemotePath.expandHome("/srv/data", null))
        assertEquals("/srv/data", RemotePath.expandHome("/srv/data", ""))
    }

    @Test
    fun `expandHome without a host home has no honest answer`() {
        assertNull(RemotePath.expandHome("~", null))
        assertNull(RemotePath.expandHome("~/git", null))
        assertNull(RemotePath.expandHome("\$HOME", ""))
    }
}
