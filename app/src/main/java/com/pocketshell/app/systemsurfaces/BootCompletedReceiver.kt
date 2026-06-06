package com.pocketshell.app.systemsurfaces

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                runCatching {
                    ActiveSessionsWidgetProvider.updateAll(context.applicationContext)
                }.onFailure {
                    Log.w(SYSTEM_SURFACES_TAG, "boot/package widget update failed", it)
                }
            }
        }
    }
}
