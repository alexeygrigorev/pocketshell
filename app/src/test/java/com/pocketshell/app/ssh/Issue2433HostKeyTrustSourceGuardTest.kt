package com.pocketshell.app.ssh

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural hard-cut guard for #2433: app production may never opt into permissive trust. */
class Issue2433HostKeyTrustSourceGuardTest {
    @Test
    fun everyProductionSshCallSiteIsFreeOfPermissiveHostKeyVerification() {
        val main = sourceDir("app/src/main/java")
        val kotlinSources = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("guard must inspect the complete app production tree", kotlinSources.size > 100)
        val offenders = kotlinSources.filter { file ->
            val text = file.readText()
            text.contains("KnownHostsPolicy.AcceptAll") || text.contains("\"accept-all\"")
        }
        assertTrue(
            "production must not contain permissive SSH host-key policy: ${offenders.map { it.path }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun permissiveVerifierIsInternalAndBridgedOnlyFromTestSourceSets() {
        val policy = source("shared/core-ssh/src/main/java/com/pocketshell/core/ssh/KnownHostsPolicy.kt")
        val connection = source("shared/core-ssh/src/main/java/com/pocketshell/core/ssh/SshConnection.kt")
        val allProduction = sequenceOf(
            sourceDir("app/src/main/java"),
            sourceDir("shared/core-ssh/src/main/java"),
        ).flatMap { it.walkTopDown() }.filter { it.isFile && it.extension == "kt" }.toList()

        assertFalse(policy.contains("public data object AcceptAll"))
        assertFalse(policy.contains("data object AcceptAll"))
        assertTrue(policy.contains("internal data object TestOnlyAcceptAll"))
        assertTrue(connection.contains("is TestOnlyAcceptAll"))
        val fixtureBridgeUsers = allProduction.filter { it.readText().contains("TEST_ACCEPT_ALL_HOST_KEYS") }
        assertTrue("test bridge leaked into production: ${fixtureBridgeUsers.map { it.path }}", fixtureBridgeUsers.isEmpty())
    }

    @Test
    fun coreDefaultsFailClosedAndAppLeaseIdentityCarriesTheVerifiedFingerprint() {
        val connection = source("shared/core-ssh/src/main/java/com/pocketshell/core/ssh/SshConnection.kt")
        val lease = source("shared/core-ssh/src/main/java/com/pocketshell/core/ssh/SshLeaseManager.kt")
        val trust = source("app/src/main/java/com/pocketshell/app/ssh/HostKeyTrust.kt")

        assertTrue(connection.contains("knownHosts: KnownHostsPolicy = KnownHostsPolicy.RejectAll"))
        assertTrue(lease.contains("knownHosts: KnownHostsPolicy = KnownHostsPolicy.RejectAll"))
        assertFalse(lease.contains("knownHostsId: String = \"accept-all\""))
        assertTrue(trust.contains("leaseIdentity = fingerprint?.let { \"host-key:${'$'}it\" }"))
        assertTrue(trust.contains("KnownHostsPolicy.VerifiedFingerprint(fingerprint)"))
    }

    @Test
    fun ordinaryAddEditAndPooledFailuresReachTheSharedTrustScreen() {
        val navigator = source("app/src/main/java/com/pocketshell/app/MainActivity.kt")
        val connector = source(
            "app/src/main/java/com/pocketshell/app/testaccess/AuthoritativeSshLeaseConnector.kt",
        )
        val portForwardConnector = source(
            "app/src/main/java/com/pocketshell/app/portfwd/PortForwardConnector.kt",
        )
        assertTrue(navigator.contains("AppDestination.FirstHostTestConnect(hostId, firstRunGuided = false)"))
        assertTrue(navigator.contains("hostKeyTrustPromptRouter?.pendingHostId?.collect"))
        assertTrue(connector.contains("trustPromptRouter?.report(target.hostIdOrNull(), failure)"))
        assertTrue(portForwardConnector.contains("trustPromptRouter.report(host.id, failure)"))
    }

    private fun sourceDir(path: String): File = locate(path).also {
        check(it.isDirectory) { "$path is not a directory" }
    }

    private fun source(path: String): String = locate(path).readText()

    private fun locate(path: String): File {
        var cursor = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val candidate = File(cursor, path)
            if (candidate.exists()) return candidate
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Cannot locate $path from ${System.getProperty("user.dir")}")
    }
}
