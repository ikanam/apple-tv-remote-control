package dev.atvremote.app

import android.app.Application
import dev.atvremote.app.di.AppGraph

class AtvRemoteApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
