package com.sidequests.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.sidequests.app.telemetry.Bq1Telemetry

/**
 * BQ1 (#21): app-wide foreground/background detection. ProcessLifecycleOwner reports when the
 * whole app (not a single Activity) goes to the background and comes back.
 */
class SidequestsApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Bq1Telemetry.init(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = Bq1Telemetry.onAppForeground()
                override fun onStop(owner: LifecycleOwner) = Bq1Telemetry.onAppBackground()
            }
        )
    }
}
