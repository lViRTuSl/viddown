package com.viddown

import android.app.Application

class App : Application() {
    lateinit var engine: DownloadEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = DownloadEngine(this)
        engine.start()
    }
}
