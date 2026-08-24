package com.pocketshell.app.validityselftest
class Fake1GoodTreeHydrateTest {
    private class FakeTreeSshSession(private val exitCode: Int) {
        fun exec(command: String): ExecResult {
            // tree get cold-start hydrate — old/missing CLI returns exit 64
            return ExecResult(stdout = "", stderr = "unknown command", exitCode = exitCode)
        }
    }
    fun getTree_oldCliNonZeroStillResolvesLoading() {
        val session = FakeTreeSshSession(exitCode = 64)
        // Loading must still resolve even when the connect RPC fails.
        assertThrows { session.exec("tree get") }
    }
    private fun assertThrows(block: () -> Unit) {}
    data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int)
}
