package com.pocketshell.app.messaging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FcmTokenRegistrarTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FcmTokenRegistrar.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun freshInstall_noPendingToken_noRegistration() {
        val registrar = FcmTokenRegistrar(context)
        assertNull(registrar.pendingToken())
        assertFalse(registrar.needsRegistration())
    }

    @Test
    fun onTokenRefreshed_cachesToken_andFlagsForRegistration() {
        val registrar = FcmTokenRegistrar(context)
        registrar.onTokenRefreshed("token-123")
        assertEquals("token-123", registrar.pendingToken())
        assertTrue(registrar.needsRegistration())
    }

    @Test
    fun markRegistered_clearsTheNeedsRegistrationFlag_butKeepsToken() {
        val registrar = FcmTokenRegistrar(context)
        registrar.onTokenRefreshed("token-123")
        registrar.markRegistered()
        assertFalse(registrar.needsRegistration())
        assertEquals("token-123", registrar.pendingToken())
    }

    @Test
    fun rotatedToken_reArmsRegistration() {
        val registrar = FcmTokenRegistrar(context)
        registrar.onTokenRefreshed("token-A")
        registrar.markRegistered()
        assertFalse(registrar.needsRegistration())
        // FCM rotated the token → must be re-delivered to the host.
        registrar.onTokenRefreshed("token-B")
        assertTrue(registrar.needsRegistration())
        assertEquals("token-B", registrar.pendingToken())
    }

    @Test
    fun sameTokenAgain_doesNotReArmRegistration() {
        val registrar = FcmTokenRegistrar(context)
        registrar.onTokenRefreshed("token-A")
        registrar.markRegistered()
        registrar.onTokenRefreshed("token-A")
        assertFalse(registrar.needsRegistration())
    }

    @Test
    fun blankToken_isIgnored() {
        val registrar = FcmTokenRegistrar(context)
        registrar.onTokenRefreshed("   ")
        assertNull(registrar.pendingToken())
    }

    @Test
    fun registerCommand_buildsPathRobustPocketshellInvocation() {
        val command = FcmTokenRegistrar.registerCommand("token-123")
        assertTrue(command.contains("push register-token 'token-123'"))
        // PATH-robust: the wrapper exports PATH + resolves the binary.
        assertTrue(command.contains("command -v pocketshell"))
    }
}
