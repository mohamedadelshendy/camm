package com.camm.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.camm.mobile.camera.CameraLens
import com.camm.mobile.camera.CameraStreamer
import com.camm.mobile.codec.H264Encoder
import com.camm.mobile.transport.TransportClient
import com.camm.mobile.transport.TransportMode
import com.camm.mobile.transport.TransportTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

class CameraService : Service() {
    private val tag = "CameraService"
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraStreamer: CameraStreamer? = null
    private var encoder: H264Encoder? = null
    private var transportClient: TransportClient? = null

    // State variables
    var currentLens: CameraLens = CameraLens.BACK
        private set
    var currentWidth: Int = 1920
        private set
    var currentHeight: Int = 1080
        private set
    var currentFps: Int = 60
        private set
    var currentBitrateBps: Int = 8_000_000
        private set
    var isTorchOn: Boolean = false
        private set
    var activeTransportMode: TransportMode = TransportMode.USB_AUTO
        private set

    private var localPreviewSurface: Surface? = null

    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "CameraService onCreate")
        acquireWakeLock()
        createNotificationChannel()
        startForegroundServiceNotification()

        transportClient = TransportClient(serviceScope)
        initPipeline()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CAMM::StreamingWakeLock").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24 hours max
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.camera_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.camera_service_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.camera_service_title))
            .setContentText(getString(R.string.camera_service_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initPipeline() {
        // 1. Initialize Hardware H264 MediaCodec
        encoder = H264Encoder(
            width = currentWidth,
            height = currentHeight,
            fps = currentFps,
            bitrateBps = currentBitrateBps
        ) { frameBytes, isKeyFrame, isConfig ->
            transportClient?.dispatchFrame(frameBytes, isKeyFrame, isConfig)
        }.apply { start() }

        val encoderSurface = encoder?.surface ?: run {
            Log.e(tag, "Encoder input surface is null!")
            return
        }

        // 2. Initialize Camera2 Streamer feeding the encoder input surface
        cameraStreamer = CameraStreamer(this) { _, _ ->
            // Resolution changed callback
        }.apply {
            start(
                lens = currentLens,
                width = currentWidth,
                height = currentHeight,
                fps = currentFps,
                encoderSurface = encoderSurface,
                previewSurface = localPreviewSurface
            )
        }
    }

    fun setPreviewSurface(surface: Surface?) {
        localPreviewSurface = surface
        val streamer = cameraStreamer ?: return
        val encSurface = encoder?.surface ?: return
        streamer.closeCamera()
        streamer.start(
            lens = currentLens,
            width = currentWidth,
            height = currentHeight,
            fps = currentFps,
            encoderSurface = encSurface,
            previewSurface = localPreviewSurface
        )
    }

    fun switchLens(lens: CameraLens) {
        if (currentLens == lens) return
        currentLens = lens
        val streamer = cameraStreamer ?: return
        val encSurface = encoder?.surface ?: return
        streamer.switchLens(lens, encSurface)
    }

    fun setResolution(width: Int, height: Int) {
        if (currentWidth == width && currentHeight == height) return
        currentWidth = width
        currentHeight = height
        restartPipeline()
    }

    fun setFps(fps: Int) {
        if (currentFps == fps) return
        currentFps = fps
        restartPipeline()
    }

    fun setBitrate(bitrateBps: Int) {
        currentBitrateBps = bitrateBps
        encoder?.updateBitrate(bitrateBps)
    }

    fun setTorch(enabled: Boolean) {
        isTorchOn = enabled
        cameraStreamer?.setTorch(enabled)
    }

    fun setTransportMode(mode: TransportMode, wifiIp: String = "192.168.1.100", wifiPort: Int = 5556) {
        activeTransportMode = mode
        transportClient?.setMode(mode, wifiIp, wifiPort)
    }

    private fun restartPipeline() {
        cameraStreamer?.stop()
        cameraStreamer = null
        encoder?.stop()
        encoder = null
        initPipeline()
    }

    fun getTelemetryFlow(): StateFlow<TransportTelemetry>? = transportClient?.telemetry

    override fun onDestroy() {
        Log.i(tag, "CameraService onDestroy")
        cameraStreamer?.stop()
        cameraStreamer = null
        encoder?.stop()
        encoder = null
        transportClient?.release()
        transportClient = null

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null

        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "camm_streaming_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
