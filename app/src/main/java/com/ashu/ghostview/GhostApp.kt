package com.ashu.ghostview

import android.app.Application

/** Runs first whenever the app process starts, whichever screen opens. */
class GhostApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Ig.init(this)
    }
}
