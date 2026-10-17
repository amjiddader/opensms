package com.wansaw.opensms

import android.app.Application
import com.wansaw.opensms.core.AppContainer
import com.wansaw.opensms.gateway.Watchdog

class OpenSmsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Watchdog.schedule(this)
    }
}
