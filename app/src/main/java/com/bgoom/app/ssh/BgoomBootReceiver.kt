package com.bgoom.app.ssh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bgoom.app.BgoomApp

/**
 * Automatically resumes background SSH outbox reception after phone reboot or in-app update
 * when the user has enabled "后台常驻实时接收".
 */
class BgoomBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? BgoomApp ?: return
        if (app.deviceStore.settings.value.keepAliveReceiveEnabled && app.deviceStore.getActiveDevice() != null) {
            runCatching { BgoomSyncService.start(context.applicationContext) }
        }
    }
}
