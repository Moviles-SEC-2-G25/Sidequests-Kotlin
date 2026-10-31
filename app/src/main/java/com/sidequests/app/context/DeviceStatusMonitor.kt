package com.sidequests.app.context

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Snapshot of the phone context the UI and the context refresh policy adapt to.
 */
data class DeviceStatus(
    val isOnline: Boolean = true,
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
) {
    val isLowBattery: Boolean
        get() = batteryPercent != null && batteryPercent <= LOW_BATTERY_PERCENT && !isCharging

    companion object {
        const val LOW_BATTERY_PERCENT = 20
    }
}

/**
 * Context-aware feature (Sprint 2, Carlos): observes connectivity and battery.
 *
 * - Connectivity: [ConnectivityManager.NetworkCallback] on the default network. The app is
 *   considered online only when the network has INTERNET and VALIDATED capabilities, so a
 *   connected Wi-Fi without internet is still reported as offline.
 * - Battery: the sticky ACTION_BATTERY_CHANGED broadcast.
 *
 * Callbacks are registered in [start] and unregistered in [stop] (Activity onStart/onStop),
 * so nothing keeps running while the app is in the background.
 */
class DeviceStatusMonitor(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _status = MutableStateFlow(DeviceStatus())
    val status: StateFlow<DeviceStatus> = _status.asStateFlow()

    private var started = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _status.update { it.copy(isOnline = capabilities.hasInternet()) }
        }

        override fun onLost(network: Network) {
            _status.update { it.copy(isOnline = false) }
        }

        override fun onUnavailable() {
            _status.update { it.copy(isOnline = false) }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateBattery(intent)
        }
    }

    fun start() {
        if (started) return
        started = true

        _status.update { it.copy(isOnline = currentlyOnline()) }
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }

        val sticky = ContextCompat.registerReceiver(
            context,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sticky?.let(::updateBattery)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        runCatching { context.unregisterReceiver(batteryReceiver) }
    }

    private fun currentlyOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasInternet()
    }

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else null
        _status.update { it.copy(batteryPercent = percent, isCharging = plugged != 0) }
    }

    private fun NetworkCapabilities.hasInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
