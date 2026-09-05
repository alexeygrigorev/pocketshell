package com.pocketshell.next

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Pins the shipping launcher identity (issue #2517) and install id
 * (issue #2519): this is PocketShell under `com.pocketshell.app`, so an
 * upgrade replaces the v0.4.x install (same signature). Kotlin namespace
 * stays `com.pocketshell.next`.
 */
class LauncherBrandingTest {

    @Test
    fun shippingApplicationIdIsTheOriginalPackage() {
        val gradle = locate("app2/build.gradle.kts").readText()
        assertTrue(
            "applicationId must be com.pocketshell.app so adb install -r upgrades v0.4.x",
            Regex("""applicationId\s*=\s*"com\.pocketshell\.app"""").containsMatchIn(gradle),
        )
        assertTrue(
            "applicationId must not be the rewrite side-by-side id",
            !Regex("""applicationId\s*=\s*"com\.pocketshell\.next"""").containsMatchIn(gradle),
        )
    }

    @Test
    fun launcherLabelIsPocketShellNotNext() {
        val document = parseXml(locate("app2/src/main/res/values/strings.xml"))
        val strings = document.getElementsByTagName("string")
        var appName: String? = null
        for (i in 0 until strings.length) {
            val el = strings.item(i) as Element
            if (el.getAttribute("name") == "app_name") {
                appName = el.textContent.trim()
                break
            }
        }
        assertEquals("PocketShell", appName)
        assertTrue(
            "launcher label must not carry the rewrite's side-by-side 'Next' suffix",
            appName != null && !appName.contains("Next"),
        )
    }

    @Test
    fun applicationUsesTheOriginalAdaptiveLauncherIcon() {
        val document = parseXml(locate("app2/src/main/AndroidManifest.xml"))
        val applications = document.getElementsByTagName("application")
        assertEquals(1, applications.length)
        val application = applications.item(0) as Element
        assertEquals("@mipmap/ic_launcher", application.androidAttr("icon"))
        assertEquals("@mipmap/ic_launcher_round", application.androidAttr("roundIcon"))
        assertTrue(
            "adaptive launcher entries must exist",
            locate("app2/src/main/res/mipmap-anydpi-v26/ic_launcher.xml").isFile &&
                locate("app2/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml").isFile,
        )
    }

    @Test
    fun foregroundArtworkStaysInsideCircularMaskSafeArea() {
        assertArtworkStaysInsideSafeArea(
            locate("app2/src/main/res/drawable/ic_launcher_foreground.xml"),
            "launcher foreground",
        )
    }

    @Test
    fun monochromeArtworkStaysInsideCircularMaskSafeArea() {
        assertArtworkStaysInsideSafeArea(
            locate("app2/src/main/res/drawable/ic_launcher_monochrome.xml"),
            "launcher monochrome",
        )
    }

    private fun assertArtworkStaysInsideSafeArea(file: File, label: String) {
        val document = parseXml(file)
        val paths = document.getElementsByTagName("path")
        assertTrue("$label should contain vector paths", paths.length > 0)
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
                "$label path $index should stay within x=32..76 after stroke; " +
                    "bounds=$bounds strokeInset=$strokeInset",
                inkLeft >= 32f && inkRight <= 76f,
            )
            assertTrue(
                "$label path $index should stay within y=30..80 after stroke; " +
                    "bounds=$bounds strokeInset=$strokeInset",
                inkTop >= 30f && inkBottom <= 80f,
            )
        }
    }

    private fun pathDataBounds(pathData: String): Bounds {
        val numbers = NUMBER.findAll(pathData).map { it.value.toFloat() }.toList()
        require(numbers.size >= 2 && numbers.size % 2 == 0) {
            "pathData must use absolute x,y coordinate pairs: $pathData"
        }
        val xs = numbers.filterIndexed { index, _ -> index % 2 == 0 }
        val ys = numbers.filterIndexed { index, _ -> index % 2 == 1 }
        return Bounds(
            left = xs.minOrNull() ?: error("pathData has no x coordinates"),
            top = ys.minOrNull() ?: error("pathData has no y coordinates"),
            right = xs.maxOrNull() ?: error("pathData has no x coordinates"),
            bottom = ys.maxOrNull() ?: error("pathData has no y coordinates"),
        )
    }

    private fun parseXml(file: File) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)

    private fun Element.androidAttr(name: String): String =
        getAttributeNS(ANDROID_NS, name).ifBlank { getAttribute("android:$name") }

    private fun locate(relative: String): File {
        val stripped = relative.removePrefix("app2/")
        val candidates = listOf(
            File(relative),
            File(stripped),
            File("../$relative"),
            File("../../$relative"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
    }

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
