package com.pocketshell.app.branding

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class LauncherIconVectorTest {

    @Test
    fun foregroundArtworkStaysInsideCircularMaskSafeArea() {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(launcherForegroundFile())
        val paths = document.getElementsByTagName("path")
        assertTrue("launcher foreground should contain vector paths", paths.length > 0)

        for (index in 0 until paths.length) {
            val path = paths.item(index) as Element
            val pathData = path.androidAttr("pathData")
            val bounds = pathDataBounds(pathData)
            val strokeInset = path.androidAttr("strokeWidth").toFloatOrNull()?.div(2f) ?: 0f
            val inkLeft = bounds.left - strokeInset
            val inkTop = bounds.top - strokeInset
            val inkRight = bounds.right + strokeInset
            val inkBottom = bounds.bottom + strokeInset

            assertTrue(
                "launcher foreground path $index should stay within x=32..76 after stroke; " +
                    "bounds=$bounds strokeInset=$strokeInset",
                inkLeft >= 32f && inkRight <= 76f,
            )
            assertTrue(
                "launcher foreground path $index should stay within y=30..80 after stroke; " +
                    "bounds=$bounds strokeInset=$strokeInset",
                inkTop >= 30f && inkBottom <= 80f,
            )
        }
    }

    private fun launcherForegroundFile(): File {
        return listOf(
            File("app/src/main/res/drawable/ic_launcher_foreground.xml"),
            File("src/main/res/drawable/ic_launcher_foreground.xml"),
        ).firstOrNull { it.isFile }
            ?: error("Could not locate ic_launcher_foreground.xml from ${File(".").absolutePath}")
    }

    private fun pathDataBounds(pathData: String): Bounds {
        val numbers = NUMBER.findAll(pathData).map { it.value.toFloat() }.toList()
        require(numbers.size >= 2 && numbers.size % 2 == 0) {
            "pathData must use absolute x,y coordinate pairs: $pathData"
        }

        val xs = numbers.filterIndexed { index, _ -> index % 2 == 0 }
        val ys = numbers.filterIndexed { index, _ -> index % 2 == 1 }
        return Bounds(
            left = xs.minOrNull() ?: error("pathData has no x coordinates: $pathData"),
            top = ys.minOrNull() ?: error("pathData has no y coordinates: $pathData"),
            right = xs.maxOrNull() ?: error("pathData has no x coordinates: $pathData"),
            bottom = ys.maxOrNull() ?: error("pathData has no y coordinates: $pathData"),
        )
    }

    private fun Element.androidAttr(name: String): String =
        getAttributeNS(ANDROID_NS, name).ifBlank { getAttribute("android:$name") }

    private data class Bounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        val NUMBER = Regex("-?\\d+(?:\\.\\d+)?")
    }
}
