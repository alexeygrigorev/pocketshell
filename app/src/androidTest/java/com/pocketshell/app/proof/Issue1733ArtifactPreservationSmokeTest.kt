package com.pocketshell.app.proof

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

internal object Issue1733ArtifactPreserver {
    fun preserve(
        instrumentation: Instrumentation,
        phase: String,
        sources: List<File>,
        destination: String,
    ) {
        require(sources.isNotEmpty()) { "phase $phase must preserve at least one artifact" }
        sources.forEach { source ->
            assertTrue(
                "phase $phase source artifact must exist and be nonempty: ${source.absolutePath}",
                source.isFile && source.length() > 0L,
            )
        }

        val script = buildList {
            add("set -eu")
            add("mkdir -p ${shellQuoteForPreservation(destination)}")
            sources.forEach { source ->
                val copied = "$destination/${source.name}"
                add(
                    "cp ${shellQuoteForPreservation(source.absolutePath)} " +
                        shellQuoteForPreservation(copied),
                )
                add("test -s ${shellQuoteForPreservation(copied)}")
            }
            add("printf '%s\\n' ${shellQuoteForPreservation("PRESERVED_OK $destination")}")
        }.joinToString("; ")
        val encoded = Base64.encodeToString(
            script.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )

        // UiAutomation delegates to Runtime.exec(String), which tokenizes on
        // whitespace and does not add a shell. Keep the third argv token free
        // of literal whitespace, then let sh expand IFS before decoding the
        // safely opaque base64 payload into the real script.
        val command = "/system/bin/sh -c " +
            "echo\${IFS}$encoded|/system/bin/base64\${IFS}-d|/system/bin/sh"
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
        assertTrue(
            "phase $phase preservation failed: destination=$destination output=$output",
            output.lineSequence().any { it == "PRESERVED_OK $destination" },
        )
    }
}

@RunWith(AndroidJUnit4::class)
class Issue1733ArtifactPreservationSmokeTest {
    @Test
    fun shellPreserverCopiesAndChecksNonemptySentinel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val sourceDir = File(
            root,
            "additional_test_output/issue1733-preserver-smoke",
        )
        assertTrue(sourceDir.exists() || sourceDir.mkdirs())
        val sentinel = File(sourceDir, "sentinel.txt")
        val payload = "issue1733-preserver-sentinel\n"
        sentinel.writeText(payload)
        assertTrue(sentinel.isFile && sentinel.length() > 0L)

        val runId = "run-${System.currentTimeMillis()}"
        val destination = "/sdcard/Download/pocketshell-issue1733-smoke/$runId/smoke"
        Issue1733ArtifactPreserver.preserve(
            instrumentation = instrumentation,
            phase = "smoke",
            sources = listOf(sentinel),
            destination = destination,
        )

        val copied = "$destination/${sentinel.name}"
        val copiedSize = readShellOutput(
            instrumentation,
            "/system/bin/stat -c %s $copied",
        ).trim().toLong()
        assertEquals(sentinel.length(), copiedSize)
        assertEquals(
            payload,
            readShellOutput(instrumentation, "/system/bin/cat $copied"),
        )
        println("ISSUE1733_PRESERVER_SMOKE destination=$destination size=$copiedSize")
    }
}

private fun readShellOutput(instrumentation: Instrumentation, command: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

private fun shellQuoteForPreservation(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
