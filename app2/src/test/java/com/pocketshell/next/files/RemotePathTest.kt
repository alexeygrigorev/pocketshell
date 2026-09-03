package com.pocketshell.next.files

import org.junit.Assert.assertEquals
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
}
