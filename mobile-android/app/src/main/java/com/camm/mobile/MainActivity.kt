package com.camm.mobile

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.camm.mobile.camera.CameraLens
import com.camm.mobile.transport.TransportMode
import com.camm.mobile.ui.MainScreen
import com.camm.mobile.ui.theme.CAMMTheme
import com.camm.mobile.ui.theme.DarkBackground
import com.camm.mobile.ui.theme.TextPrimary

class MainActivity : ComponentActivity() {
    private var cameraService: CameraService? = null
    private var isServiceBound by mutableStateOf(false)

    // UI state mirrored from service
    private var currentLens by mutableStateOf(CameraLens.BACK)
    private var currentWidth by mutableIntStateOf(1920)
    private var currentHeight by mutableIntStateOf(1080)
    private var currentFps by mutableIntStateOf(60)
    private var currentBitrateMbps by mutableFloatStateOf(8.0f)
    private var isTorchOn by mutableStateOf(false)
    private var activeTransportMode by mutableStateOf(TransportMode.USB_AUTO)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CameraService.LocalBinder
            val s = binder.getService()
            cameraService = s
            isServiceBound = true

            // Sync initial state
            currentLens = s.currentLens
            currentWidth = s.currentWidth
            currentHeight = s.currentHeight
            currentFps = s.currentFps
            currentBitrateMbps = s.currentBitrateBps / 1_000_000f
            isTorchOn = s.isTorchOn
            activeTransportMode = s.activeTransportMode
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            isServiceBound = false
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        if (cameraGranted) {
            startAndBindService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        checkAndRequestPermissions()

        setContent {
            CAMMTheme {
                val service = cameraService
                if (isServiceBound && service != null && service.getTelemetryFlow() != null) {
                    MainScreen(
                        telemetryFlow = service.getTelemetryFlow()!!,
                        currentLens = currentLens,
                        onLensToggle = { newLens ->
                            currentLens = newLens
                            service.switchLens(newLens)
                        },
                        currentWidth = currentWidth,
                        currentHeight = currentHeight,
                        onResolutionToggle = { w, h ->
                            currentWidth = w
                            currentHeight = h
                            service.setResolution(w, h)
                        },
                        currentFps = currentFps,
                        onFpsToggle = { fps ->
                            currentFps = fps
                            service.setFps(fps)
                        },
                        currentBitrateMbps = currentBitrateMbps,
                        onBitrateChanged = { bps ->
                            currentBitrateMbps = bps / 1_000_000f
                            service.setBitrate(bps)
                        },
                        isTorchOn = isTorchOn,
                        onTorchToggle = { torch ->
                            isTorchOn = torch
                            service.setTorch(torch)
                        },
                        activeTransportMode = activeTransportMode,
                        onTransportModeChanged = { mode, ip, port ->
                            activeTransportMode = mode
                            service.setTransportMode(mode, ip, port)
                        },
                        onPreviewSurfaceReady = { surface ->
                            service.setPreviewSurface(surface)
                        },
                        onPreviewSurfaceDestroyed = {
                            service.setPreviewSurface(null)
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(DarkBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Initializing CAMM Streaming Engine...",
                            color = TextPrimary
                        )
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startAndBindService()
        } else {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, CameraService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onDestroy()
    }
}
