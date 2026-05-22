package com.pocketshell.app

import android.app.Application
import com.pocketshell.app.crash.CrashReporter
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
