package com.camm.mobile.transport

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class TransportMode {
    USB_AUTO,
    WIFI_UDP
}

data class TransportTelemetry(
    val currentFps: Float = 0f,
    val currentBitrateMbps: Float = 0f,
    val statusText: String = "Initializing...",
    val isConnected: Boolean = false,
    val mode: TransportMode = TransportMode.USB_AUTO
)

class TransportClient(
    private val scope: CoroutineScope
) {
    private val _telemetry = MutableStateFlow(TransportTelemetry())
    val telemetry: StateFlow<TransportTelemetry> = _telemetry.asStateFlow()

    private var activeMode: TransportMode = TransportMode.USB_AUTO
    private var tcpServer: TcpStreamServer? = null
    private var udpSender: UdpStreamSender? = null

    // Telemetry tracking
    private var frameCount = 0
    private var byteCount = 0L
    private var lastStatsTimestamp = SystemClock.elapsedRealtime()

    init {
        initTcpServer()
    }

    private fun initTcpServer() {
        tcpServer = TcpStreamServer(port = 5555) { connected, status ->
            _telemetry.value = _telemetry.value.copy(
                isConnected = connected,
                statusText = status
            )
        }.also { it.start(scope) }
    }

    private fun initUdpSender(ip: String, port: Int) {
        udpSender = UdpStreamSender(targetIp = ip, targetPort = port) { status ->
            _telemetry.value = _telemetry.value.copy(
                isConnected = true,
                statusText = status
            )
        }.also { it.start() }
    }

    fun setMode(mode: TransportMode, wifiIp: String = "192.168.1.100", wifiPort: Int = 5556) {
        activeMode = mode
        if (mode == TransportMode.USB_AUTO) {
            udpSender?.stop()
            udpSender = null
            if (tcpServer == null) {
                initTcpServer()
            }
        } else {
            tcpServer?.stop()
            tcpServer = null
            if (udpSender == null) {
                initUdpSender(wifiIp, wifiPort)
            } else {
                udpSender?.updateDestination(wifiIp, wifiPort)
            }
        }
        _telemetry.value = _telemetry.value.copy(mode = mode)
    }

    fun updateWifiDestination(ip: String, port: Int) {
        if (activeMode == TransportMode.WIFI_UDP) {
            udpSender?.updateDestination(ip, port)
        }
    }

    fun dispatchFrame(data: ByteArray, isKeyFrame: Boolean, isConfig: Boolean) {
        // Track statistics
        frameCount++
        byteCount += data.size
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastStatsTimestamp
        if (elapsed >= 1000) {
            val fps = (frameCount * 1000f) / elapsed
            val bitrateMbps = (byteCount * 8f) / (elapsed * 1000f)
            frameCount = 0
            byteCount = 0L
            lastStatsTimestamp = now

            _telemetry.value = _telemetry.value.copy(
                currentFps = (fps * 10).toInt() / 10f,
                currentBitrateMbps = (bitrateMbps * 100).toInt() / 100f
            )
        }

        when (activeMode) {
            TransportMode.USB_AUTO -> {
                tcpServer?.sendFrame(data, isKeyFrame, isConfig)
            }
            TransportMode.WIFI_UDP -> {
                udpSender?.sendFrame(data)
            }
        }
    }

    fun release() {
        tcpServer?.stop()
        tcpServer = null
        udpSender?.stop()
        udpSender = null
    }
}
