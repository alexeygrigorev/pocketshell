package com.pocketshell.app.proof

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

/** Stable same-run file/PNG/timing contract for outbound connected acceptance. */
internal class OutboundAcceptanceArtifacts(
    private val deviceDirName: String,
    private val testMethodName: () -> String,
) {
    private val timings = mutableListOf<String>()

    fun file(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$deviceDirName")
        check(dir.exists() || dir.mkdirs()) { "could not create artifact directory ${dir.absolutePath}" }
        return File(dir, name)
    }

    fun writeText(name: String, text: String): File = file(name).also {
        it.writeText(text)
        println("ISSUE1526_TEXT ${it.absolutePath}")
    }

    fun writeViewport(name: String, bitmap: Bitmap, event: String = "ISSUE1944_VIEWPORT"): File {
        check(bitmap.width == 1080 && bitmap.height == 2400) {
            "$name must be the reviewer-required 1080x2400 viewport; got ${bitmap.width}x${bitmap.height}"
        }
        return file("$name.png").also { output ->
            FileOutputStream(output).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "failed to encode ${output.absolutePath}"
                }
            }
            println("$event ${output.absolutePath}")
        }
    }

    fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE1526_TIMING $line")
    }

    fun writeTimings(): File = file("timings-${testMethodName()}.txt").also {
        it.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE1526_TIMINGS ${it.absolutePath}")
    }
}
