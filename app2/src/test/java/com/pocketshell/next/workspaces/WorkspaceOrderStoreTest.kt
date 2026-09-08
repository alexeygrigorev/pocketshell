package com.pocketshell.next.workspaces

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class WorkspaceOrderStoreTest {

    private lateinit var preferences: android.content.SharedPreferences

    @Before
    fun setUp() {
        preferences = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("workspace_order", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }

    @Test
    fun `home relative and absolute root spellings share one order`() {
        val store = WorkspaceOrderStore(ApplicationProvider.getApplicationContext())
        val order = listOf("/home/testuser/git/aplexer", "/home/testuser/git/pocketshell")

        store.put(hostId = 7L, rootPath = "/home/testuser/git", paths = order)

        assertEquals(order, store.get(hostId = 7L, rootPath = "~/git"))
    }
}
