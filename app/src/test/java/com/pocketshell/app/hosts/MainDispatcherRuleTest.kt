package com.pocketshell.app.hosts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRuleTest {

    @Test
    fun directMainOwnerCannotOverlapRuleOwner() {
        val ruleEntered = CountDownLatch(1)
        val releaseRule = CountDownLatch(1)
        val directOwnerEntered = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        val ruleThread = Thread {
            try {
                MainDispatcherRule(
                    StandardTestDispatcher(kotlinx.coroutines.test.TestCoroutineScheduler()),
                ).apply(
                    object : Statement() {
                        override fun evaluate() {
                            ruleEntered.countDown()
                            check(releaseRule.await(5, TimeUnit.SECONDS))
                        }
                    },
                    Description.createTestDescription(javaClass, "rule-owner"),
                ).evaluate()
            } catch (thrown: Throwable) {
                failure.compareAndSet(null, thrown)
            }
        }
        val directOwnerThread = Thread {
            try {
                check(ruleEntered.await(5, TimeUnit.SECONDS))
                MainDispatcherOwnershipRule().apply(
                    object : Statement() {
                        override fun evaluate() {
                            directOwnerEntered.countDown()
                            val directDispatcher =
                                StandardTestDispatcher(TestCoroutineScheduler())
                            Dispatchers.setMain(directDispatcher)
                            try {
                                Dispatchers.Main.immediate
                            } finally {
                                Dispatchers.resetMain()
                            }
                        }
                    },
                    Description.createTestDescription(javaClass, "direct-owner"),
                ).evaluate()
            } catch (thrown: Throwable) {
                failure.compareAndSet(null, thrown)
            }
        }

        ruleThread.start()
        directOwnerThread.start()
        assertTrue("the rule owner must enter", ruleEntered.await(5, TimeUnit.SECONDS))
        try {
            assertFalse(
                "a direct Main owner must wait until the active rule releases process-global Main",
                directOwnerEntered.await(250, TimeUnit.MILLISECONDS),
            )
        } finally {
            releaseRule.countDown()
            ruleThread.join(5_000)
            directOwnerThread.join(5_000)
        }
        assertFalse("rule owner thread must terminate", ruleThread.isAlive)
        assertFalse("direct owner thread must terminate", directOwnerThread.isAlive)
        assertTrue("direct owner must run after the rule releases", directOwnerEntered.await(1, TimeUnit.SECONDS))
        assertNull(failure.get()?.stackTraceToString(), failure.get())
    }

    @Test
    fun appTestsNeverConstructAZeroArgumentTestDispatcher() {
        val testRoot = appTestRoot()
        val zeroArgumentConstructor =
            Regex("""\b(?:Standard|Unconfined)TestDispatcher\(\)""")
        val offenders = testRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    if (zeroArgumentConstructor.containsMatchIn(line.substringBefore("//"))) {
                        "${file.relativeTo(testRoot)}:${index + 1}"
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertTrue(
            "zero-argument TestDispatcher construction can read process-global Main during " +
                "JUnit instance construction and recreate TestMainDispatcher:72; offenders=$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun appTestsRouteEveryMainSwapThroughTheSharedOwnershipSeam() {
        val ownershipMarkers = listOf(
            "MainDispatcherRule",
            "MainDispatcherOwnershipRule",
            "MainDispatcherTestIsolation",
            "StandaloneTmuxVmFixture",
        )
        val offenders = appTestRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val source = file.readText()
                val swapsMain =
                    "Dispatchers.setMain(" in source ||
                        "Dispatchers.resetMain(" in source ||
                        "Dispatchers::resetMain" in source
                swapsMain && ownershipMarkers.none(source::contains)
            }
            .map { it.relativeTo(appTestRoot()).path }
            .toList()

        assertTrue(
            "every process-global Main swap must share the full-statement ownership seam; " +
                "offenders=$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun defaultDispatcherDoesNotAdoptMainSchedulerInstalledDuringRuleConstruction() {
        // Issue #1892 recurrence: a focused ShareViewModelTest is green, but the
        // long-lived full-suite fork can construct its rule while another class
        // still owns process-global Main. The implicit dispatcher constructor
        // adopts that foreign scheduler, so runTest and ViewModel work no longer
        // share one live clock and the later test hits UncompletedCoroutinesError.
        repeat(100) { iteration ->
            MainDispatcherTestIsolation.withOwnership {
                val foreignDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
                Dispatchers.setMain(foreignDispatcher)
                val rule = try {
                    MainDispatcherRule()
                } finally {
                    Dispatchers.resetMain()
                }

                val assertion = object : Statement() {
                    override fun evaluate() {
                        assertNotSame(
                            "iteration $iteration: a default rule created while another test owns " +
                                "Main must not inherit that test's scheduler",
                            foreignDispatcher.scheduler,
                            rule.dispatcher.scheduler,
                        )
                    }
                }

                rule.apply(
                    assertion,
                    Description.createTestDescription(javaClass, "foreign-main-construction-$iteration"),
                ).evaluate()
            }
        }
    }

    private fun appTestRoot(): File =
        sequenceOf(
            File("app/src/test/java"),
            File("src/test/java"),
        ).firstOrNull(File::isDirectory)
            ?: error("cannot locate app src/test/java")
}
