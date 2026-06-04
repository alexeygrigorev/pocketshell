package com.pocketshell.app.fileviewer

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cache-file-name sanitisation for the file viewer (issue #497).
 */
class FileViewerCacheNameTest {

    @Test
    fun `name keeps the basename and extension`() {
        val name = FileViewerViewModel.sanitizeCacheName("/tmp/terrain/alpine-ground.png")
        assertTrue(name, name.endsWith("alpine-ground.png"))
    }

    @Test
    fun `unsafe characters are replaced`() {
        val name = FileViewerViewModel.sanitizeCacheName("/tmp/weird name (1)/a b.png")
        // No spaces or parentheses survive.
        assertTrue(name, name.none { it == ' ' || it == '(' || it == ')' })
    }

    @Test
    fun `same basename in different dirs yields different cache names`() {
        val a = FileViewerViewModel.sanitizeCacheName("/proj/a/out.png")
        val b = FileViewerViewModel.sanitizeCacheName("/proj/b/out.png")
        assertNotEquals(a, b)
    }

    @Test
    fun `path ending in slash still yields a usable name`() {
        val name = FileViewerViewModel.sanitizeCacheName("/proj/dir/")
        assertTrue(name, name.isNotBlank())
    }
}
