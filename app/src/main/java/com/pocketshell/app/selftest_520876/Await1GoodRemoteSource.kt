package com.pocketshell.app.validityselftest
import kotlinx.coroutines.withTimeout
class Await1GoodRemoteSource {
    suspend fun getTree(session: FakeSession, host: String): String {
        // cold-start hydrate — BOUNDED so a non-returning exec cannot pin us
        return withTimeout(5_000) { session.exec("printf %s | pocketshell tree get") }
    }
    interface FakeSession { suspend fun exec(command: String): String }
}
