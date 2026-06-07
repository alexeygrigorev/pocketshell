package com.pocketshell.app.crash

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class CrashReportsFileProviderConfigTest {

    @Test
    fun manifestFileProviderUsesExportPathsResource() {
        val manifest = projectRoot().resolve("app/src/main/AndroidManifest.xml").toFile()
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)

        val providers = document.getElementsByTagName("provider")
        val fileProviderUsesExportPaths = (0 until providers.length).any { providerIndex ->
            val provider = providers.item(providerIndex) as Element
            if (provider.getAttributeNS(ANDROID_NS, "authorities") != "\${applicationId}.fileprovider") {
                return@any false
            }
            val metadata = provider.getElementsByTagName("meta-data")
            (0 until metadata.length).any { metadataIndex ->
                val item = metadata.item(metadataIndex) as Element
                item.getAttributeNS(ANDROID_NS, "resource") == "@xml/ai_costs_export_paths"
            }
        }

        assertTrue(
            "The production .fileprovider must use the paths resource that exposes report archives",
            fileProviderUsesExportPaths,
        )
    }

    @Test
    fun fileProviderExposesReportArchivesCacheDirectory() {
        val pathsXml = projectRoot().resolve("app/src/main/res/xml/ai_costs_export_paths.xml").toFile()
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(pathsXml)

        val cachePaths = document.getElementsByTagName("cache-path")
        val exposesReportArchives = (0 until cachePaths.length).any { index ->
            val element = cachePaths.item(index) as Element
            element.getAttribute("path") == "$REPORT_ARCHIVES_CACHE_DIR/"
        }

        assertTrue(
            "Crash report Share all archives must be covered by .fileprovider cache-paths",
            exposesReportArchives,
        )
    }

    private fun projectRoot(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir
            dir = dir.parent
        }
        error("Could not locate settings.gradle.kts from user.dir=${System.getProperty("user.dir")}")
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
