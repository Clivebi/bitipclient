package com.kaopuip.app

import android.app.Application
import com.kaopuip.core.ServerAPIProvider

class MainApp : Application() {
    companion object {
        private var instance: MainApp? = null
        fun getInstance(): MainApp {
            return instance!!
        }
    }
    val api:ServerAPIProvider
    get() = ServerAPIProvider.getInstance()

    fun api(): ServerAPIProvider {
        return ServerAPIProvider.getInstance()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ServerAPIProvider.init(this, "cmnet.kaopuip.com", 6709)
    }
}

