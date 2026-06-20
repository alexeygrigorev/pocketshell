package com.pocketshell.uikit.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #762 slice C — pins the shared name → [FileIconClass] map that is the
 * ONE source of truth for the leading file-type glyph in BOTH the file explorer
 * rows and the file viewer header (design-consistency). The same file name must
 * resolve to the same bucket on both surfaces; this test pins every bucket plus
 * the no-extension / dotfile / case / multi-dot edge cases.
 */
class FileTypeIconMappingTest {

    @Test
    fun imageExtensionsMapToImage() {
        for (name in listOf("photo.png", "a.JPG", "b.jpeg", "c.gif", "d.webp", "e.svg", "f.bmp", "g.ico")) {
            assertEquals("$name should be IMAGE", FileIconClass.IMAGE, fileIconClassForName(name))
        }
    }

    @Test
    fun codeAndTextExtensionsMapToCode() {
        for (name in listOf(
            "Main.kt", "App.java", "index.js", "types.ts", "view.tsx",
            "script.py", "run.sh", "build.gradle", "settings.kts", "notes.md",
            "data.json", "config.yaml", "config.yml", "libs.toml", "page.html",
            "style.css", "app.log", "fix.patch", "change.diff", "schema.sql",
            "app.properties", "settings.ini", "nginx.conf", "readme.txt",
        )) {
            assertEquals("$name should be CODE", FileIconClass.CODE, fileIconClassForName(name))
        }
    }

    @Test
    fun archiveExtensionsMapToArchive() {
        for (name in listOf("bundle.zip", "src.tar", "data.gz", "app.tgz", "x.bz2", "y.xz", "z.7z", "w.rar", "lib.jar")) {
            assertEquals("$name should be ARCHIVE", FileIconClass.ARCHIVE, fileIconClassForName(name))
        }
    }

    @Test
    fun unknownExtensionMapsToBinary() {
        for (name in listOf("blob.bin", "core.dump", "image.dat", "mystery.xyz")) {
            assertEquals("$name should be BINARY", FileIconClass.BINARY, fileIconClassForName(name))
        }
    }

    @Test
    fun noExtensionNamesMapToBinary() {
        // Makefile / LICENSE / a dotfile / a trailing-dot name all have no usable
        // extension and fall through to the generic BINARY glyph.
        for (name in listOf("Makefile", "LICENSE", ".bashrc", ".gitignore", "weird.")) {
            assertEquals("$name should be BINARY", FileIconClass.BINARY, fileIconClassForName(name))
        }
    }

    @Test
    fun usesFinalExtensionOfTheBasenameNotThePath() {
        // The extension is taken from the LAST dot of the final path segment, so a
        // dotted directory name in the path never confuses the bucket.
        assertEquals(FileIconClass.CODE, fileIconClassForName("/home/me/project.git/README.md"))
        assertEquals(FileIconClass.IMAGE, fileIconClassForName("a/b.c/photo.png"))
        assertEquals(FileIconClass.ARCHIVE, fileIconClassForName("backups.2024/data.tar"))
    }

    @Test
    fun extensionMatchIsCaseInsensitive() {
        assertEquals(FileIconClass.IMAGE, fileIconClassForName("PIC.PNG"))
        assertEquals(FileIconClass.CODE, fileIconClassForName("Build.GRADLE"))
        assertEquals(FileIconClass.ARCHIVE, fileIconClassForName("Bundle.ZIP"))
    }
}
