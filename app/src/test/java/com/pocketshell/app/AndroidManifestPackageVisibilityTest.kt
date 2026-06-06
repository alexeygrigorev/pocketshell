package com.pocketshell.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AndroidManifestPackageVisibilityTest {

    @Test
    fun manifestDeclaresSpeechRecognitionServiceQuery() {
        val manifest = projectRoot().resolve("app/src/main/AndroidManifest.xml").toFile()
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)

        val queries = document.getElementsByTagName("queries")
        val hasSpeechRecognitionServiceQuery = (0 until queries.length).any { queryIndex ->
            val query = queries.item(queryIndex) as Element
            val actions = query.getElementsByTagName("action")
            (0 until actions.length).any { actionIndex ->
                val action = actions.item(actionIndex) as Element
                action.getAttributeNS(ANDROID_NS, "name") == SPEECH_RECOGNITION_SERVICE
            }
        }

        assertTrue(
            "Android 11+ package visibility must query RecognitionService for SpeechRecognizer availability",
            hasSpeechRecognitionServiceQuery,
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
        const val SPEECH_RECOGNITION_SERVICE = "android.speech.RecognitionService"
    }
}
