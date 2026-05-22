package com.pocketshell.app.systemsurfaces

import android.content.Intent
import com.pocketshell.app.initialDestinationFromIntent
import com.pocketshell.app.nav.AppDestination
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ForwardingIntentTest {

    @Test
    fun forwardingTileIntentOpensForwardingChooser() {
        val intent = Intent()
        intent.putExtra(ForwardingTileService.EXTRA_OPEN_PORT_FORWARDING, true)

        assertEquals(AppDestination.PortForwardChooser, initialDestinationFromIntent(intent))
    }

    @Test
    fun defaultIntentOpensHostList() {
        assertEquals(AppDestination.HostList, initialDestinationFromIntent(Intent()))
        assertEquals(AppDestination.HostList, initialDestinationFromIntent(null))
    }
}
