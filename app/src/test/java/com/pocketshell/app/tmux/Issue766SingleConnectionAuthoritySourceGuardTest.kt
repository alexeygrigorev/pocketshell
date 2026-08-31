package com.pocketshell.app.tmux

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #766 S7 hard-cut guard.
 *
 * The behavioral controller/projection suites pin the lifecycle costs. This
 * source guard pins the architectural result: a second VM connection machine
 * cannot quietly return one field or one gate at a time in this high-churn file.
 */
class Issue766SingleConnectionAuthoritySourceGuardTest {

    @Test
    fun vmHasNoInlineConnectionStateOrWriter() {
        val vm = source(VM)
        val projection = source(PROJECTION)
        val production = tmuxProductionSources()
        listOf(
            "_connectionState",
            "inlineConnectionStatus",
            "setConnectionState(",
            "driveControllerIntent(",
            "connectionStatusFor(",
            "connectionStatusForController(",
            "hostPortUserFor(",
            "withinGraceRecovery =",
        ).forEach { forbidden ->
            assertFalse("production must not retain `$forbidden`", production.contains(forbidden))
        }
        assertFalse(
            "the deleted inline state vocabulary must not survive as a separate source file",
            projectRoot().resolve(INLINE_STATE).isFile,
        )
        assertTrue(vm.contains("ControllerStatusProjector"))
        assertTrue(projection.contains("val state = controllerState()"))
        assertTrue(projection.contains("publish(ConnectionStatusProjection.project(state"))
        assertFalse(
            "the projection must not receive a VM grace boolean",
            projection.contains("withinGraceRecovery"),
        )
        assertEquals(
            "only the controller projection may publish the view-facing status",
            1,
            Regex("_connectionStatus\\.value\\s*=").findAll(vm).count(),
        )
    }

    @Test
    fun attachRevealControllerCallStaysInsideTheRealSeedFence() {
        val vm = source(VM)
        assertEquals(
            "blank attach must not call revealControllerLive outside activePaneSeeded",
            0,
            Regex(
                """if \(activePaneSeeded\) promoteRevealLiveForActiveTarget\(\)\s+revealControllerLive\(\)""",
            ).findAll(vm).count(),
        )
        assertEquals(
            "both attach reveal paths must keep controller reveal inside the seed fence",
            2,
            Regex(
                """if \(activePaneSeeded\) \{\s+promoteRevealLiveForActiveTarget\(\)\s+revealControllerLive\(\)\s+\}""",
            ).findAll(vm).count(),
        )
        // Mutation that must redden this assertion: move either
        // `revealControllerLive()` below its `if (activePaneSeeded)` block.
    }

    @Test
    fun displayedStatusProjectionHasOnlyTheControllerLifecycleInput() {
        val projection = source(PROJECTION)
        assertTrue(
            "$PROJECTION must project the typed controller state",
            projection.contains("controllerState: ConnectionState"),
        )
        listOf(
            "inlineStatus",
            "connectionStatusFor(",
            "ConnectionStatusProjection.HostPortUser",
        ).forEach { forbidden ->
            assertFalse("$PROJECTION must not retain dual input `$forbidden`", projection.contains(forbidden))
        }
        assertTrue(projection.contains("val targetId = controllerState.targetIdOrNull()"))
        assertTrue(projection.contains("it.targetId == targetId"))
    }

    /**
     * The VM must never AUTHOR a view-facing lifecycle value; it may only publish what
     * [ConnectionStatusProjection] returned for a controller state.
     *
     * This is the assertion that stays load-bearing after the hard cut. Lifecycle shape
     * is already selected by the typed controller. The projection may resolve display
     * payloads, but it must not receive a VM grace boolean or synthesize Reattaching
     * from Attaching.
     *
     * Mutations that must redden this test:
     *  - restore any `_connectionStatus.value = ConnectionStatus.<Shape>(...)` write;
     *  - add a VM-side grace argument or lifecycle rewrite to the projector;
     *  - project a state other than the controller value read at the method boundary.
     */
    @Test
    fun theViewModelPublishesOnlyProjectedControllerStatesAndAuthorsNone() {
        val vm = source(VM)
        val projection = source(PROJECTION)
        val authored =
            Regex("ConnectionStatus\\.(Connecting|Switching|Connected|Reconnecting|Failed)\\s*\\(")
                .findAll(vm)
                .map { it.groupValues[1] }
                .toList()
        assertEquals(
            "the VM must not construct a lifecycle-bearing status; every one is projected",
            emptyList<String>(),
            authored,
        )
        assertEquals(
            "the only VM-authored status is the two payload-less Idle seeds of the facade flow",
            2,
            Regex("ConnectionStatus\\.Idle(?!\\s*\\()").findAll(vm).count(),
        )
        assertTrue(
            "the projector must read the typed controller state once",
            projection.contains("val state = controllerState()"),
        )
        assertTrue(
            "endpoint payload must read the same controller state",
            Regex("endpointFor\\(\\s*controllerState = state")
                .containsMatchIn(projection),
        )
        assertTrue(
            "status shape must read the same controller state",
            projection.contains("ConnectionStatusProjection.project(state"),
        )
        assertFalse(
            "the projection must not reconstruct Reattaching from VM-supplied grace",
            projection.contains("ConnectionState.Reattaching(state.host, state.targetId)"),
        )
    }

    @Test
    fun effectGatesReadTypedControllerStateAndTransitionHistory() {
        val vm = source(VM)
        val network = source(NETWORK_EFFECTS)
        val driver = source(DRIVER)
        val background = source(BACKGROUND_EFFECTS)

        assertTrue(vm.contains("controllerLive = { target != null && controllerIsLiveFor(target) }"))
        assertTrue(network.contains("controllerLive: Boolean"))
        assertFalse(network.contains("inlineConnected"))
        assertTrue(driver.contains("backgroundedEffect: (ConnectionState) -> Unit"))
        // The edge must hand the effect the PRE-transition state; the behavioural
        // proof is ConnectionEffectDriverTest.backgroundedEffectReceivesTheExactTypedPreTransitionState.
        assertTrue(
            "the Backgrounded edge must pass a captured pre-transition state, not re-read one",
            Regex("backgroundedEffect\\((?!\\))\\w+\\)").containsMatchIn(driver),
        )
        assertTrue(background.contains("controllerStateBeforeBackground is ConnectionState.Reconnecting"))
        assertTrue(
            "a passive drop in the controller-owned switch window must not be swallowed",
            vm.contains("state !is CoreConnectionState.Attaching"),
        )
    }

    @Test
    fun deletedEquivalenceCoverageHasAnHonestControllerReplacement() {
        val replacement = projectRoot().resolve(EQUIVALENCE_REPLACEMENT)
        assertFalse(
            "the deleted controller-vs-inline parity test must not be restored under its old name",
            projectRoot().resolve(EQUIVALENCE_TEST).isFile,
        )
        assertTrue("controller equivalence coverage must be retained under the new authority", replacement.isFile)
        val equivalence = replacement.readText()
        listOf("statusName", "matchesInline", "fun inline(").forEach { vacuousHelper ->
            assertFalse(
                "replacement authority coverage must not retain vacuous `$vacuousHelper` helpers",
                equivalence.contains(vacuousHelper),
            )
        }
        listOf(
            "ConnectionEffectDriver",
            "transportUp",
            "transportDropped",
            "targetGoneProjectsTheControllerGoneStateAsTheHonestTerminalStatus",
            "projectionIsTheExplicitControllerOnlyLifecycleMapping",
            "M766-LIFECYCLE-001",
        ).forEach { loadBearing ->
            assertTrue(
                "replacement equivalence coverage must retain `$loadBearing`",
                equivalence.contains(loadBearing),
            )
        }
    }

    private fun source(path: String): String = projectRoot().resolve(path).readText()

    private fun tmuxProductionSources(): String =
        projectRoot()
            .resolve("app/src/main/java/com/pocketshell/app/tmux")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString(separator = "\n") { it.readText() }

    private fun projectRoot(): File {
        var cursor = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (cursor.resolve("settings.gradle.kts").isFile) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Cannot locate project root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val VM = "app/src/main/java/com/pocketshell/app/tmux/TmuxSessionViewModel.kt"
        const val INLINE_STATE = "app/src/main/java/com/pocketshell/app/tmux/TmuxConnectionState.kt"
        const val PROJECTION =
            "app/src/main/java/com/pocketshell/app/tmux/connection/ConnectionStatusProjection.kt"
        const val DRIVER =
            "app/src/main/java/com/pocketshell/app/tmux/connection/ConnectionEffectDriver.kt"
        const val BACKGROUND_EFFECTS =
            "app/src/main/java/com/pocketshell/app/tmux/connection/BackgroundEffects.kt"
        const val NETWORK_EFFECTS =
            "app/src/main/java/com/pocketshell/app/tmux/connection/NetworkChangeEffects.kt"
        const val EQUIVALENCE_TEST =
            "app/src/test/java/com/pocketshell/app/tmux/connection/ConnectionManagerEquivalenceTest.kt"
        const val EQUIVALENCE_REPLACEMENT =
            "app/src/test/java/com/pocketshell/app/tmux/connection/ConnectionControllerAuthorityCoverageTest.kt"
    }
}
