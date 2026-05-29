package com.pocketshell.app.systemsurfaces

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pocketshell.core.storage.dao.HostDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        runCatching {
                            val enabledHostCount = EntryPointAccessors
                                .fromApplication(appContext, BootCompletedReceiverEntryPoint::class.java)
                                .hostDao()
                                .getEnabled()
                                .first()
                                .size
                            val message = bootForwardingMessage(enabledHostCount)
                            SystemSurfaceStateStore(appContext).recordBootForwardingRequest(message)
                            PendingBootForwardingNotification.show(appContext, message)
                            ActiveSessionsWidgetProvider.updateAll(appContext)
                        }.onFailure {
                            Log.w(SYSTEM_SURFACES_TAG, "boot/package restore surface update failed", it)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface BootCompletedReceiverEntryPoint {
    fun hostDao(): HostDao
}
