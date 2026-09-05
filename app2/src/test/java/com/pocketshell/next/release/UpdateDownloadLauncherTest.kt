package com.pocketshell.next.release

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UpdateDownloadLauncherTest {

    @Test
    fun viewIntent_isActionView_withNewTask_againstTheUrl() {
        val apk = "https://github.com/alexeygrigorev/pocketshell/releases/download/v0.5.1/app.apk"
        val intent = viewIntent(apk)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(apk, intent.data.toString())
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun notesIntent_isActionView_againstHtmlUrl() {
        val html = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v0.5.1"
        val intent = viewIntent(html)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(html, intent.data.toString())
    }
}
