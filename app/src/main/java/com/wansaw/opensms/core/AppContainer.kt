package com.wansaw.opensms.core

import android.app.Application
import androidx.room.Room
import com.wansaw.opensms.BuildConfig
import com.wansaw.opensms.data.AppDatabase
import com.wansaw.opensms.data.Prefs
import com.wansaw.opensms.data.SecureStore
import com.wansaw.opensms.gateway.Gateway
import com.wansaw.opensms.net.ApiClient
import com.wansaw.opensms.sms.SimInfo
import com.wansaw.opensms.sms.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-wired dependency container (no DI framework: the app is small and start-up time matters). */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = Prefs(app)
    val secure = SecureStore(app)
    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "opensms.db").build()
    val api = ApiClient(BuildConfig.API_BASE_URL, secret = { secure.deviceSecret })
    val sims = SimInfo(app)
    val sender = SmsSender(app, sims)
    val gateway = Gateway(app, prefs, secure, db, api, sims, sender, scope)
}
