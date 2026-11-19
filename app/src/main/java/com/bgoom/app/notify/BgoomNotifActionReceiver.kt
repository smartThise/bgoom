package com.bgoom.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the "关闭" dismiss button on promoted Android 16 Live Update arrival capsules.
 */
class BgoomNotifActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_DISMISS_CAPSULE) {
            BgoomNotifier.cancelTransferCapsule(context)
        }
    }

    companion object {
        const val ACTION_DISMISS_CAPSULE = "com.bgoom.app.action.DISMISS_CAPSULE"
    }
}
