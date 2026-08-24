package com.pocketshell.app.validityselftest
class Await1BadRemoteSource {
    suspend fun getTree(session: FakeSession, host: String): String {
        // cold-start hydrate — UNBOUNDED warm-session exec (no withTimeout)
        val result = session.exec("printf %s | pocketshell tree get")
        return result
    }
    interface FakeSession { suspend fun exec(command: String): String }
}
