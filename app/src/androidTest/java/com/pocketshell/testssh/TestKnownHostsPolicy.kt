package com.pocketshell.testssh

import com.pocketshell.core.ssh.KnownHostsPolicy

/** Instrumentation-only bridge: unavailable to the installed app sources. */
internal val TEST_ACCEPT_ALL_HOST_KEYS: KnownHostsPolicy = testOnlyAcceptAllPolicy()

private fun testOnlyAcceptAllPolicy(): KnownHostsPolicy =
    Class.forName("com.pocketshell.core.ssh.TestOnlyAcceptAll")
        .getField("INSTANCE")
        .get(null) as KnownHostsPolicy
