package com.kaopuip.app

import android.app.Application
import com.kaopuip.core.ServerAPIProvider

class MainApp : Application() {
    lateinit var mStorage: ServerAPIProvider
    companion object{
        private var instance: MainApp? = null
        fun getInstance(): MainApp {
            return instance!!
        }
    }
    override fun onCreate() {
        super.onCreate()
        instance = this
        mStorage = ServerAPIProvider(this)
    }
}

