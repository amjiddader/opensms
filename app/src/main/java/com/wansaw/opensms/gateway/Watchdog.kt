package com.wansaw.opensms.gateway

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wansaw.opensms.OpenSmsApp
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Every 15 minutes: if the gateway should be running but is not, try to bring the service back. */
class Watchdog(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (ctx.applicationContext as OpenSmsApp).container
        if (c.prefs.setupDone.first() && c.prefs.gatewayEnabled.first() && !c.gateway.isRunning) {
            // Android may refuse a foreground start from the background; the next boot/launch recovers then.
            runCatching { GatewayService.start(ctx.applicationContext) }
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<Watchdog>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("gateway-watchdog", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
