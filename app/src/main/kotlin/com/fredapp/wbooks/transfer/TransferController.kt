package com.fredapp.wbooks.transfer

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/**
 * State and start/stop control for the upload server. Owned by the Application
 * so the UI can collect it from anywhere, and the foreground service writes
 * status back here when its lifecycle changes.
 */
class TransferController(private val appContext: Context) {

    private val _state = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = _state.asStateFlow()

    fun start(): Boolean {
        val wifiAddress = activeWifiIpv4()
        if (wifiAddress == null) {
            publishWifiRequired()
            return false
        }
        val intent = Intent(appContext, UploadServerService::class.java)
            .setAction(UploadServerService.ACTION_START)
            .putExtra(UploadServerService.EXTRA_HOST_ADDRESS, wifiAddress)
        ContextCompat.startForegroundService(appContext, intent)
        return true
    }

    fun canStartOnWifi(): Boolean {
        if (activeWifiIpv4() != null) return true
        publishWifiRequired()
        return false
    }

    fun stop() {
        val intent = Intent(appContext, UploadServerService::class.java).setAction(UploadServerService.ACTION_STOP)
        appContext.startService(intent)
    }

    /** Called by the service after it has bound the server socket. */
    internal fun publishRunning(port: Int, pin: String, hostAddress: String) {
        _state.value = TransferState(running = true, url = "http://$hostAddress:$port", pin = pin)
    }

    /** Called by the service when it stops. */
    internal fun publishStopped() {
        _state.value = TransferState()
    }

    internal fun publishWifiRequired() {
        _state.value = TransferState(
            message = "Connect to Wi-Fi to start the web server. You might have to disable bluetooth for the watch to connect.",
        )
    }

    /**
     * Return the active Wi-Fi IPv4 only. Wear OS can expose paired-phone Bluetooth
     * or cellular networks with private IPv4 addresses that are not reachable from
     * the user's browser, so the web server is intentionally Wi-Fi-only.
     */
    private fun activeWifiIpv4(): String? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network = cm.allNetworks.firstOrNull { candidate ->
            val caps = cm.getNetworkCapabilities(candidate) ?: return@firstOrNull false
            val props = cm.getLinkProperties(candidate) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                props.interfaceName?.startsWith("wlan", ignoreCase = true) == true
        } ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return null
        return cm.getLinkProperties(network)
            ?.linkAddresses
            ?.asSequence()
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }
}

data class TransferState(
    val running: Boolean = false,
    val url: String? = null,
    val pin: String? = null,
    val message: String? = null,
)
