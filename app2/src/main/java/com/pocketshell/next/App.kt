package com.pocketshell.next

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * app2's Application.
 *
 * Deliberately empty beyond the Hilt entry point (plan §M-1 non-goal: "no DI
 * beyond `@HiltAndroidApp`"). No eager initialisation, no process-wide
 * schedulers, no background work — D21 stands in the rewrite.
 */
@HiltAndroidApp
class App : Application()
