package com.pocketshell.app.sessions

import com.pocketshell.app.tmux.FakeTmuxClient
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveTmuxClientsTest {

    @Test
    fun staleRegistrationCannotUnregisterNewerSameHostClient() {
        val registry = ActiveTmuxClients()
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()
        val oldRegistration = registry.register(
            hostId = 7L,
            hostName = "alpha",
            hostname = "alpha.example",
            port = 22,
            username = "alex",
            keyPath = "/keys/a",
            client = oldClient,
        )
        val newRegistration = registry.register(
            hostId = 7L,
            hostName = "alpha",
            hostname = "alpha.example",
            port = 22,
            username = "alex",
            keyPath = "/keys/a",
            client = newClient,
        )

        registry.unregister(oldRegistration)

        assertSame(newClient, registry.clients.value[7L]?.client)

        registry.unregister(newRegistration)

        assertTrue(registry.clients.value.isEmpty())
    }

    @Test
    fun staleLifecycleRegistrationCannotUnregisterNewerSameHostHooks() {
        val registry = ActiveTmuxClients()
        val oldHooks = ActiveTmuxClients.LifecycleHooks(
            onBackground = {},
            onForeground = {},
        )
        val newHooks = ActiveTmuxClients.LifecycleHooks(
            onBackground = {},
            onForeground = {},
        )
        val oldRegistration = registry.registerLifecycleHooks(
            hostId = 7L,
            hooks = oldHooks,
        )
        val newRegistration = registry.registerLifecycleHooks(
            hostId = 7L,
            hooks = newHooks,
        )

        registry.unregisterLifecycleHooks(oldRegistration)

        assertSame(newHooks, registry.lifecycleHooksSnapshot().single())

        registry.unregisterLifecycleHooks(newRegistration)

        assertTrue(registry.lifecycleHooksSnapshot().isEmpty())
    }
}
