package com.pocketshell.testssh

import com.pocketshell.core.ssh.KnownHostsPolicy

internal val TEST_ACCEPT_ALL_HOST_KEYS: KnownHostsPolicy =
    Class.forName("com.pocketshell.core.ssh.TestOnlyAcceptAll")
        .getField("INSTANCE")
        .get(null) as KnownHostsPolicy
