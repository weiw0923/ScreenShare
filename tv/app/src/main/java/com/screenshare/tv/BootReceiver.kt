package com.screenshare.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val svc = Intent(context, RendererService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        }
    }
}
