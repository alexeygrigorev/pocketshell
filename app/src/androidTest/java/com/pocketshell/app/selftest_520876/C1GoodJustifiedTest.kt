package com.pocketshell.app.validityselftest
import org.junit.Assume.assumeFalse
class C1GoodJustifiedTest {
    fun journey() {
        assumeFalse(isRunningOnCi()) // JUSTIFIED: real soft IME never raises on swiftshader
    }
    private fun isRunningOnCi() = false
}
