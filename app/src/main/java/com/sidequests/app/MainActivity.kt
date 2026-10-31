package com.sidequests.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sidequests.app.context.AndroidLocationProvider
import com.sidequests.app.context.AlternativeDemoContext
import com.sidequests.app.context.ContextManager
import com.sidequests.app.context.DeviceStatusMonitor
import com.sidequests.app.context.NominatimAddressProvider
import com.sidequests.app.context.OpenMeteoWeatherProvider
import com.sidequests.app.data.settings.DataStoreSettingsStore
import com.sidequests.app.sensor.light.AmbientLightMonitor
import com.sidequests.app.telemetry.Bq1Telemetry
import com.sidequests.app.ui.AppViewModel
import com.sidequests.app.ui.AppViewModelFactory
import com.sidequests.app.ui.SidequestsApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var deviceStatusMonitor: DeviceStatusMonitor
    private lateinit var ambientLightMonitor: AmbientLightMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val contextManager = ContextManager(
            locationProvider = AndroidLocationProvider(applicationContext),
            weatherProvider = OpenMeteoWeatherProvider(),
        )

        val demoContext = if (BuildConfig.DEBUG && intent.hasExtra("alternative_demo_hour")) {
            AlternativeDemoContext.parse(
                weather = intent.getStringExtra("alternative_demo_weather"),
                hour = intent.getIntExtra("alternative_demo_hour", -1),
            )
        } else null
        val factory = AppViewModelFactory(
            contextManager = contextManager,
            applicationContext = applicationContext,
            alternativeContextOverride = demoContext,
            addressProvider = NominatimAddressProvider(),
            settingsStore = DataStoreSettingsStore(applicationContext),
            failureReporter = Bq1Telemetry,
        )
        val appViewModel = ViewModelProvider(this, factory)[AppViewModel::class.java]
        // BQ1 (#21): lets the app-wide lifecycle observer know whether a Nearby quest is active.
        Bq1Telemetry.activeNearbyQuestProvider = { appViewModel.activeNearbyQuestId() }

        deviceStatusMonitor = DeviceStatusMonitor(applicationContext)
        ambientLightMonitor = AmbientLightMonitor(applicationContext)
        appViewModel.setLightSensorAvailable(ambientLightMonitor.isAvailable)

        // Forward connectivity/battery and ambient light changes only while the screen is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { deviceStatusMonitor.status.collect { appViewModel.onDeviceStatusChanged(it) } }
                launch { ambientLightMonitor.lux.collect { lux -> if (lux != null) appViewModel.onAmbientLux(lux) } }
                launch {
                    ambientLightMonitor.isDark.collect { isDark ->
                        if (isDark != null) appViewModel.onAmbientLightChanged(isDark)
                    }
                }
            }
        }

        deviceStatusMonitor = DeviceStatusMonitor(applicationContext)
        ambientLightMonitor = AmbientLightMonitor(applicationContext)
        appViewModel.setLightSensorAvailable(ambientLightMonitor.isAvailable)

        // Forward connectivity/battery and ambient light changes only while the screen is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { deviceStatusMonitor.status.collect { appViewModel.onDeviceStatusChanged(it) } }
                launch { ambientLightMonitor.lux.collect { lux -> if (lux != null) appViewModel.onAmbientLux(lux) } }
                launch {
                    ambientLightMonitor.isDark.collect { isDark ->
                        if (isDark != null) appViewModel.onAmbientLightChanged(isDark)
                    }
                }
            }
        }

        setContent {
            SidequestsApp(appViewModel = appViewModel)
        }
    }

    override fun onStart() {
        super.onStart()
        deviceStatusMonitor.start()
        ambientLightMonitor.start()
    }

    override fun onDestroy() {
        if (isFinishing) Bq1Telemetry.activeNearbyQuestProvider = { null }
        super.onDestroy()
    }

    override fun onStop() {
        ambientLightMonitor.stop()
        deviceStatusMonitor.stop()
        super.onStop()
    }
}
