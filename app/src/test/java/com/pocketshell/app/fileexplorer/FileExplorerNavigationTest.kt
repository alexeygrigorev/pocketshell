package com.pocketshell.app.fileexplorer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests over the explorer's path-navigation helpers — issue #528.
 * No Android, no SSH. Pins the descend / up / breadcrumb logic the screen
 * relies on.
 */
class FileExplorerNavigationTest {

    @Test
    fun `joinPath appends a child onto an absolute base`() {
        assertEquals("/home/u/sub", FileExplorerViewModel.joinPath("/home/u", "sub"))
    }

    @Test
    fun `joinPath collapses a trailing slash`() {
        assertEquals("/home/u/sub", FileExplorerViewModel.joinPath("/home/u/", "sub"))
    }

    @Test
    fun `joinPath onto root keeps a single slash`() {
        assertEquals("/etc", FileExplorerViewModel.joinPath("/", "etc"))
    }

    @Test
    fun `parentOf walks up one level`() {
        assertEquals("/home/u", FileExplorerViewModel.parentOf("/home/u/proj"))
    }

    @Test
    fun `parentOf of a top-level dir is root`() {
        assertEquals("/", FileExplorerViewModel.parentOf("/home"))
    }

    @Test
    fun `parentOf of root is root`() {
        assertEquals("/", FileExplorerViewModel.parentOf("/"))
    }

    @Test
    fun `parentOf tolerates a trailing slash`() {
        assertEquals("/home/u", FileExplorerViewModel.parentOf("/home/u/proj/"))
    }

    @Test
    fun `breadcrumbSegments splits an absolute path into clickable ancestors`() {
        val crumbs = FileExplorerViewModel.breadcrumbSegments("/home/u/proj")
        assertEquals(
            listOf(
                "/" to "/",
                "home" to "/home",
                "u" to "/home/u",
                "proj" to "/home/u/proj",
            ),
            crumbs,
        )
    }

    @Test
    fun `breadcrumbSegments of root is a single root crumb`() {
        assertEquals(listOf("/" to "/"), FileExplorerViewModel.breadcrumbSegments("/"))
    }

    @Test
    fun `breadcrumbSegments renders a non-absolute path as one crumb`() {
        assertEquals(listOf("~" to "~"), FileExplorerViewModel.breadcrumbSegments("~"))
    }
}
