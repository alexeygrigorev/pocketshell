package com.pocketshell.testssh

import com.pocketshell.core.ssh.KnownHostsPolicy

/** Test-source bridge: unavailable to every app production compilation. */
internal val TEST_ACCEPT_ALL_HOST_KEYS: KnownHostsPolicy = testOnlyAcceptAllPolicy()

private fun testOnlyAcceptAllPolicy(): KnownHostsPolicy =
    Class.forName("com.pocketshell.core.ssh.TestOnlyAcceptAll")
        .getField("INSTANCE")
        .get(null) as KnownHostsPolicy
