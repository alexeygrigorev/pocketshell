package com.pocketshell.app.validityselftest
import org.junit.Assume.assumeFalse
class C1GoodFaultFixtureTest {
    fun journey() {
        // toxiproxy is an opt-in Docker fixture; tests.yml does not start it
        assumeFalse(isRunningOnCi())
    }
    private fun isRunningOnCi() = false
}
