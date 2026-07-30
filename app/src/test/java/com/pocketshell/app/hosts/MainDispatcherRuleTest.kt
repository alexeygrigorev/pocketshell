package com.pocketshell.app.hosts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRuleTest {

    @Test
    fun defaultDispatcherDoesNotAdoptMainSchedulerInstalledDuringRuleConstruction() {
        // Issue #1892 recurrence: a focused ShareViewModelTest is green, but the
        // long-lived full-suite fork can construct its rule while another class
        // still owns process-global Main. The implicit dispatcher constructor
        // adopts that foreign scheduler, so runTest and ViewModel work no longer
        // share one live clock and the later test hits UncompletedCoroutinesError.
        repeat(100) { iteration ->
            val foreignDispatcher = StandardTestDispatcher()
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
