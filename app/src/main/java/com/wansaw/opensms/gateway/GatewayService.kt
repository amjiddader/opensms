package com.wansaw.opensms.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wansaw.opensms.MainActivity
import com.wansaw.opensms.OpenSmsApp
import com.wansaw.opensms.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground service (type specialUse) that keeps the gateway alive. */
class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = getString(R.string.notif_channel_desc)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as OpenSmsApp).container
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                container.prefs.setGatewayEnabled(false)
                container.gateway.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        startInForeground(notification(getString(R.string.notif_starting)))
        acquireWakeLock()
        registerNetworkCallback()
        container.gateway.start()
        observer?.cancel()
        observer = scope.launch {
            container.gateway.state.collect { st ->
                getSystemService(NotificationManager::class.java).notify(ID, notification(describe(st)))
            }
        }
        return START_STICKY
    }

    private fun describe(st: GatewayState): String = when (st.phase) {
        Phase.ONLINE -> getString(R.string.notif_online)
        Phase.WAITING_APPROVAL -> getString(R.string.notif_waiting)
        Phase.CONNECTING, Phase.LOGGING_IN, Phase.STARTING -> getString(R.string.notif_connecting)
        Phase.OFFLINE -> getString(R.string.notif_offline)
        Phase.BLOCKED -> getString(R.string.notif_blocked)
        Phase.REJECTED -> getString(R.string.notif_rejected)
        Phase.UPDATE_REQUIRED -> getString(R.string.notif_update)
        Phase.SETUP_REQUIRED -> getString(R.string.notif_setup)
        Phase.PAUSED -> getString(R.string.notif_paused)
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, ID, n, type)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, GatewayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notif_pause), stop)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opensms:gateway").apply { setReferenceCounted(false); acquire() }
    }

    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                (application as OpenSmsApp).container.gateway.reconnectNow()
            }
        }
        netCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    override fun onDestroy() {
        observer?.cancel()
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "gateway"
        const val ID = 1001
        const val ACTION_STOP = "com.wansaw.opensms.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, GatewayService::class.java).setAction(ACTION_STOP))
        }
    }
}
