package com.sidequests.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.sidequests.app.context.AndroidLocationProvider
import com.sidequests.app.context.AlternativeDemoContext
import com.sidequests.app.context.ContextManager
import com.sidequests.app.context.OpenMeteoWeatherProvider
import com.sidequests.app.ui.AppViewModel
import com.sidequests.app.ui.AppViewModelFactory
import com.sidequests.app.ui.SidequestsApp

class MainActivity : ComponentActivity() {

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
        val factory = AppViewModelFactory(contextManager, applicationContext, demoContext)
        val appViewModel = ViewModelProvider(this, factory)[AppViewModel::class.java]

        setContent {
            SidequestsApp(appViewModel = appViewModel)
        }
    }
}
