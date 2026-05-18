package com.wbooks.transfer

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * State and start/stop control for the upload server. Owned by the Application
 * so the UI can collect it from anywhere, and the foreground service writes
 * status back here when its lifecycle changes.
 */
class TransferController(private val appContext: Context) {

    private val _state = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = _state.asStateFlow()

    fun start() {
        val intent = Intent(appContext, UploadServerService::class.java).setAction(UploadServerService.ACTION_START)
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun stop() {
        val intent = Intent(appContext, UploadServerService::class.java).setAction(UploadServerService.ACTION_STOP)
        appContext.startService(intent)
    }

    /** Called by the service after it has bound the server socket. */
    internal fun publishRunning(port: Int, pin: String) {
        _state.value = TransferState(running = true, url = buildUrl(port), pin = pin)
    }

    /** Called by the service when it stops. */
    internal fun publishStopped() {
        _state.value = TransferState()
    }

    private fun buildUrl(port: Int): String {
        val ip = firstUsableIpv4() ?: "?.?.?.?"
        return "http://$ip:$port"
    }

    /**
     * Walk active network interfaces and return the first non-loopback, non-link-local
     * IPv4 address. Works on Wear OS without holding the (deprecated)
     * WifiManager.connectionInfo path, and stays correct when the watch is on Ethernet
     * via dock or paired-phone tethering instead of plain Wi-Fi.
     */
    private fun firstUsableIpv4(): String? {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
            for (addr in iface.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }
}

data class TransferState(
    val running: Boolean = false,
    val url: String? = null,
    val pin: String? = null,
)
