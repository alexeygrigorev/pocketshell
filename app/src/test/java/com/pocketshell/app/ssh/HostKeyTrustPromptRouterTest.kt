package com.pocketshell.app.ssh

import com.pocketshell.core.ssh.ChangedHostKeyException
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.UnknownHostKeyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostKeyTrustPromptRouterTest {
    @Test
    fun unknownAndChangedFailuresRetainTheHostUntilNavigatorConsumesIt() {
        val router = HostKeyTrustPromptRouter()
        router.report(7, unknown())
        assertEquals(7L, router.pendingHostId.value)

        router.consume(6)
        assertEquals(7L, router.pendingHostId.value)
        router.consume(7)
        assertNull(router.pendingHostId.value)

        router.report(8, changed())
        assertEquals(8L, router.pendingHostId.value)
    }

    @Test
    fun ordinarySshFailuresAndUnownedCredentialsNeverOpenTrustUi() {
        val router = HostKeyTrustPromptRouter()
        router.report(7, SshException("offline"))
        router.report(null, unknown())
        assertNull(router.pendingHostId.value)
    }

    private fun unknown() = UnknownHostKeyException("host", 22, "ssh-ed25519", "SHA256:new")
    private fun changed() = ChangedHostKeyException(
        "host", 22, "ssh-ed25519", "SHA256:old", "SHA256:new",
    )
}
