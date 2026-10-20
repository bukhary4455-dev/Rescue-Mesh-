package com.rescuemesh.app

import android.app.Application

class RescueApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Mesh.init(this)
    }
}
