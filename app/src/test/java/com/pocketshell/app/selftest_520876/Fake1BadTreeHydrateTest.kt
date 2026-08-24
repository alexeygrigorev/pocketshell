package com.pocketshell.app.validityselftest
class Fake1BadTreeHydrateTest {
    private class FakeTreeSshSession {
        fun exec(command: String): ExecResult {
            // tree get cold-start hydrate always answers OK -> Loading always resolves
            return ExecResult(stdout = "{\"nodes\":[]}", stderr = "", exitCode = 0)
        }
    }
    data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int)
}
