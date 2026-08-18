package com.pocketshell.app.proof

import com.pocketshell.app.settings.AppSettings
import com.pocketshell.app.settings.OutboundDeliveryAuthority
import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.app.tmux.HostAckSendProbe
import com.pocketshell.app.tmux.OutboundLegacyStackProbe
import com.pocketshell.app.tmux.TmuxSessionViewModelTestBase
import com.pocketshell.core.agents.AgentKind
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #2189 — the three pinned journeys have recorded decisions, and the
 * HostAck sibling assertions are load-bearing.
 *
 * A re-point that passes because the new signal never fires is the defect
 * this issue exists to prevent. [hostAckProbeStaysZeroOnTheLegacyPin] is the
 * mutation: asserting `HostAckSendProbe.count() >= 1` on a TerminalInference
 * send REDS because the probe stays 0.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue2189HostAckSiblingContractTest : TmuxSessionViewModelTestBase() {

    @Before
    fun resetProbes() {
        HostAckSendProbe.reset()
        OutboundLegacyStackProbe.reset()
    }

    @Test
    fun eachPinnedJourneyHasARecordedReplaceDecisionAndAHostAckSibling() {
        DECISIONS.forEach { (legacyClass, siblingFile) ->
            val legacy = source("app/src/androidTest/java/com/pocketshell/app/proof/$legacyClass.kt")
            val sibling = source(siblingFile)
            assertTrue(
                "$legacyClass must record the #2189 REPLACE decision so #2125 cannot " +
                    "delete it as 'legacy' without a sibling",
                legacy.contains("Issue #2189 decision: REPLACE"),
            )
            assertTrue(
                "$legacyClass must still pin TerminalInference until #2125",
                legacy.contains("pinOutboundDeliveryAuthority(") &&
                    legacy.contains("OutboundDeliveryAuthority.TerminalInference"),
            )
            assertFalse(
                "$siblingFile must run on the shipped default, not pin the legacy authority",
                sibling.contains("pinOutboundDeliveryAuthority"),
            )
            assertTrue(
                "$siblingFile must bind the shipped default via clearOutboundDeliveryAuthorityPin",
                sibling.contains("clearOutboundDeliveryAuthorityPin()"),
            )
            assertTrue(
                "$siblingFile must re-bind HostAck after launch (ensureShippedHostAckAuthority)",
                sibling.contains("ensureShippedHostAckAuthority()"),
            )
            assertTrue(
                "$siblingFile must assert HostAckSendProbe (the mutation that reddens " +
                    "a vacuous re-point)",
                sibling.contains("HostAckSendProbe"),
            )
            assertTrue(
                "$siblingFile must assert the outbound_host_ack_send diagnostic",
                sibling.contains("outbound_host_ack_send"),
            )
            assertTrue(
                "$siblingFile must be wired into the per-push journey gate",
                suite.contains(siblingFile.substringAfterLast('/').removeSuffix(".kt")),
            )
        }
        assertEquals(
            "the shipped default must still be HostAck — a default flip is what " +
                "orphaned the three pinned journeys",
            OutboundDeliveryAuthority.HostCliAck,
            AppSettings.DEFAULT_OUTBOUND_DELIVERY_AUTHORITY,
        )
    }

    @Test
    fun pinOutboundDeliveryAuthorityCallersAreOnlyTheKnownLegacySet() {
        val androidTest = File(projectRoot(), "app/src/androidTest/java")
        val callers = androidTest.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (line.contains("pinOutboundDeliveryAuthority") &&
                        !line.trimStart().startsWith("*") &&
                        !line.trimStart().startsWith("//")
                    ) {
                        file.relativeTo(projectRoot()).invariantSeparatorsPath + ":" + (index + 1)
                    } else {
                        null
                    }
                }
            }
            .toList()
        val unexpected = callers.filter { call ->
            KNOWN_PIN_CALLERS.none { call.contains(it) }
        }
        assertEquals(
            "new pinOutboundDeliveryAuthority callers must not be added — HostAck " +
                "siblings run on the shipped default, and #2125 deletes the helper. " +
                "callers=$callers",
            emptyList<String>(),
            unexpected,
        )
        KNOWN_PIN_CALLERS.forEach { name ->
            assertTrue(
                "the recorded leftover pin in $name must still exist until #2125",
                callers.any { it.contains(name) },
            )
        }
    }

    /**
     * AC5 mutation. A sibling asserting `HostAckSendProbe.count() >= 1` REDS
     * under TerminalInference because production never enters `deliver()`.
     * The same send on HostAck increments the probe even with no transport
     * (attempt, not exec) — so the assertion is live on the new path.
     */
    @Test
    fun hostAckProbeStaysZeroOnTheLegacyPinAndIncrementsOnTheShippedDefault() =
        runTest(scheduler) {
            val legacyVm = newVm()
            legacyVm.attachClientForTest(FakeTmuxClient())
            legacyVm.hostAck.authorityOverrideForTest =
                OutboundDeliveryAuthority.TerminalInference
            legacyVm.setAgentSubmitEnterDelayForTest(0)
            legacyVm.setAgentSubmitAckTimeoutForTest(50)

            val legacy = async {
                legacyVm.sendAgentPayloadToPaneResult(
                    "%0",
                    "legacy pin must not increment the HostAck probe",
                    AgentKind.ClaudeCode,
                )
            }
            advanceUntilIdle()
            legacy.await()

            assertEquals(
                "MUTATION that must redden the sibling HostAckSendProbe.count() >= 1 " +
                    "assertion: a TerminalInference send never enters deliver(), so " +
                    "the probe stays 0 — a re-point that does not assert this is the " +
                    "vacuous-green #2189 exists to prevent",
                0L,
                HostAckSendProbe.count(),
            )

            HostAckSendProbe.reset()
            val ackVm = newVm()
            ackVm.attachClientForTest(FakeTmuxClient())
            ackVm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck

            val ack = async {
                ackVm.sendAgentPayloadToPaneResult(
                    "%0",
                    "shipped default must increment the HostAck probe",
                    AgentKind.ClaudeCode,
                )
            }
            advanceUntilIdle()
            ack.await()

            assertEquals(
                "on HostAck the sibling assertion is live: the probe increments even " +
                    "when the transport is down (attempt, not exec)",
                1L,
                HostAckSendProbe.count(),
            )
        }

    private fun source(relativePath: String): String {
        val file = File(projectRoot(), relativePath)
        assertTrue("missing $relativePath", file.isFile)
        return file.readText()
    }

    private fun projectRoot(): File {
        var cursor = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Cannot locate project root from ${System.getProperty("user.dir")}")
    }

    private val suite: String
        get() = source("scripts/ci-journey-suite.sh")

    private companion object {
        val DECISIONS: Map<String, String> = mapOf(
            "AgentSubmitAckJourneyE2eTest" to
                "app/src/androidTest/java/com/pocketshell/app/proof/" +
                    "Issue2189HostAckSubmitJourneyE2eTest.kt",
            "OutboundExactlyOnceAcrossFlapE2eTest" to
                "app/src/androidTest/java/com/pocketshell/app/proof/" +
                    "Issue2189HostAckExactlyOnceAcrossFlapE2eTest.kt",
            "SendWithAttachmentStaysVisibleE2eTest" to
                "app/src/androidTest/java/com/pocketshell/app/proof/" +
                    "Issue2189HostAckSendHealJourneyE2eTest.kt",
        )

        val KNOWN_PIN_CALLERS: List<String> = listOf(
            "AgentSubmitAckJourneyE2eTest.kt",
            "OutboundExactlyOnceAcrossFlapE2eTest.kt",
            "SendWithAttachmentStaysVisibleE2eTest.kt",
            "Issue2124OutboundAuthorityVisibleInSettingsE2eTest.kt",
            "OutboundDeliveryAuthorityPin.kt",
        )
    }
}
