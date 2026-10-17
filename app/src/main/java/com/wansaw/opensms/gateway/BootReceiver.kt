package com.wansaw.opensms.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wansaw.opensms.OpenSmsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts the gateway after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = (context.applicationContext as OpenSmsApp).container.prefs
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (prefs.setupDone.first() && prefs.gatewayEnabled.first()) {
                    runCatching { GatewayService.start(context.applicationContext) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
