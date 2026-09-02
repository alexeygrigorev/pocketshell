package com.pocketshell.app.ssh

import java.io.File
import org.junit.Assert.assertEquals
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

    /**
     * Issue #2433's contract was "every host-key failure source reaches the ONE
     * shared Trust screen"; issue #2463 narrowed the *navigating* half of that
     * to foreground, user-initiated connects only. The invariant this test still
     * pins is that every source is WIRED to the shared router — the connectors
     * report every typed failure, and the navigator's single trust destination
     * is raised from the router's non-replaying prompt stream. What a given
     * report is allowed to DO (navigate vs. annotate the card) is #2463's
     * decision, covered behaviourally by `HostKeyTrustPromptRouterTest` and
     * `Issue2463BackgroundHostKeyTrustNoNavJourneyE2eTest`; a pooled/background
     * failure deliberately annotates and does NOT reach the Trust screen.
     */
    @Test
    fun everyHostKeyFailureSourceIsWiredToTheSharedTrustRouter() {
        val navigator = source("app/src/main/java/com/pocketshell/app/MainActivity.kt")
        val connector = source(
            "app/src/main/java/com/pocketshell/app/testaccess/AuthoritativeSshLeaseConnector.kt",
        )
        val portForwardConnector = source(
            "app/src/main/java/com/pocketshell/app/portfwd/PortForwardConnector.kt",
        )
        assertTrue(navigator.contains("AppDestination.FirstHostTestConnect(hostId, firstRunGuided = false)"))
        // Issue #2463: the navigator consumes a non-replaying EVENT stream that
        // only a foreground, user-initiated connect can raise — never a retained
        // singleton StateFlow a fresh Activity replays into a navigation.
        assertTrue(navigator.contains("hostKeyTrustPromptRouter?.trustPrompts?.collect"))
        assertFalse(navigator.contains("pendingHostId"))
        assertTrue(connector.contains("trustPromptRouter?.report(target.hostIdOrNull(), failure)"))
        assertTrue(portForwardConnector.contains("trustPromptRouter.report(host.id, failure)"))
    }

    @Test
    fun tmuxResolvesPersistedTrustBeforeAnyWarmCacheOrFastSwitchDecision() {
        val vm = source("app/src/main/java/com/pocketshell/app/tmux/TmuxSessionViewModel.kt")
        val manager = source("shared/core-ssh/src/main/java/com/pocketshell/core/ssh/SshLeaseManager.kt")
        val requestOwner = vm.substring(
            vm.indexOf("private fun requestResolvedConnect("),
            vm.indexOf("private fun connectResolved("),
        )
        val resolvedOwner = vm.substring(
            vm.indexOf("private fun connectResolved("),
            vm.indexOf("private fun nextConnectGeneration("),
        )

        assertTrue(requestOwner.contains("sshLeaseManager.resolveTarget(target.toSshLeaseTarget())"))
        assertTrue(requestOwner.contains("runtimeCache.removeHostTrustMismatches("))
        assertTrue(requestOwner.contains("connectResolved(resolvedTarget"))
        assertTrue(
            requestOwner.indexOf("runtimeCache.removeHostTrustMismatches(") <
                requestOwner.indexOf("connectResolved(resolvedTarget"),
        )
        assertEquals(1, Regex("runtimeCache\\.").findAll(requestOwner).count())
        assertTrue(resolvedOwner.contains("runtimeCache.contains(target.toRuntimeKey())"))
        assertTrue(resolvedOwner.contains("takeCachedRuntimeForActivation("))
        assertTrue(resolvedOwner.contains("isSameHost(previousActiveTarget, target)"))
        assertFalse(vm.contains("host-key:unconfirmed"))
        assertTrue(manager.contains("public suspend fun resolveTarget("))
        assertTrue(manager.contains("val target = resolveTarget(requestedTarget)"))
    }

    @Test
    fun tmuxTargetIdentityAndEverySharedDedupePredicateIncludeTheResolvedFingerprint() {
        val models = source("app/src/main/java/com/pocketshell/app/tmux/TmuxSessionRuntimeModels.kt")
        val vm = source("app/src/main/java/com/pocketshell/app/tmux/TmuxSessionViewModel.kt")
        val cache = source("app/src/main/java/com/pocketshell/app/tmux/TmuxSessionRuntimeCache.kt")
        val equalityOwner = models.substring(
            models.indexOf("internal fun connectionTargetIdentityEquals("),
            models.indexOf("internal fun connectionTargetIdentityHashCode("),
        )
        val hashOwner = models.substring(
            models.indexOf("internal fun connectionTargetIdentityHashCode("),
            models.indexOf("internal fun ConnectionTarget.hasSameHostAndCredential("),
        )
        val sharedDedupeOwner = models.substring(
            models.indexOf("internal fun ConnectionTarget.hasSameHostAndCredential("),
            models.indexOf("internal fun ConnectionTarget.durableSessionKey("),
        )

        assertTrue(equalityOwner.contains("target.trustedHostKeySha256 == other.trustedHostKeySha256"))
        assertTrue(hashOwner.contains("target.trustedHostKeySha256?.hashCode()"))
        assertTrue(sharedDedupeOwner.contains("trustedHostKeySha256 == other.trustedHostKeySha256"))
        assertTrue(models.contains("if (!left.hasSameHostAndCredential(right)) return false"))
        val attachmentOwner = vm.substring(
            vm.indexOf("private fun isAttachmentOriginStillActive("),
            vm.indexOf("public suspend fun uploadQueuedAttachmentSidecars("),
        )
        assertEquals(
            "production attachment gate and its test mirror must both use fingerprint-aware identity",
            2,
            Regex("return sameSessionIdentity\\(active, originTarget\\)")
                .findAll(attachmentOwner)
                .count(),
        )
        val nameOnlyPromotion = cache.substring(
            cache.indexOf("private fun removeNameOnlyPrewarmLocked("),
            cache.indexOf("internal fun contains(key:"),
        )
        val nameOnlyContains = cache.substring(
            cache.indexOf("internal fun containsSession("),
            cache.indexOf("internal fun containsExact("),
        )
        assertTrue(
            nameOnlyPromotion.contains(
                "candidate.key.trustedHostKeySha256 == key.trustedHostKeySha256",
            ),
        )
        assertTrue(nameOnlyContains.contains("it.trustedHostKeySha256 == trustedHostKeySha256"))
        assertTrue(cache.contains("internal fun removeHostTrustMismatches("))
        assertEquals(
            2,
            Regex("containsSession\\([\\s\\S]*?trustedHostKeySha256")
                .findAll(vm.substring(vm.indexOf("public fun prewarmLikelySwitchTargets(")))
                .count(),
        )
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
