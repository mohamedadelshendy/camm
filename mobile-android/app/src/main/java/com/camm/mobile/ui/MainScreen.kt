package com.camm.mobile.ui

import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.camm.mobile.camera.CameraLens
import com.camm.mobile.transport.TransportMode
import com.camm.mobile.transport.TransportTelemetry
import com.camm.mobile.ui.components.CameraPreviewView
import com.camm.mobile.ui.components.GlassmorphicControlPill
import com.camm.mobile.ui.components.TelemetryBadge
import com.camm.mobile.ui.theme.DarkBackground
import kotlinx.coroutines.flow.StateFlow

@Composable
fun MainScreen(
    telemetryFlow: StateFlow<TransportTelemetry>,
    currentLens: CameraLens,
    onLensToggle: (CameraLens) -> Unit,
    currentWidth: Int,
    currentHeight: Int,
    onResolutionToggle: (Int, Int) -> Unit,
    currentFps: Int,
    onFpsToggle: (Int) -> Unit,
    currentBitrateMbps: Float,
    onBitrateChanged: (Int) -> Unit,
    isTorchOn: Boolean,
    onTorchToggle: (Boolean) -> Unit,
    activeTransportMode: TransportMode,
    onTransportModeChanged: (TransportMode, String, Int) -> Unit,
    onPreviewSurfaceReady: (Surface) -> Unit,
    onPreviewSurfaceDestroyed: () -> Unit
) {
    val telemetry by telemetryFlow.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Camera Viewfinder
        CameraPreviewView(
            onSurfaceReady = onPreviewSurfaceReady,
            onSurfaceDestroyed = onPreviewSurfaceDestroyed,
            modifier = Modifier.fillMaxSize()
        )

        // Top Telemetry Badge
        TelemetryBadge(
            telemetry = telemetry,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp, start = 16.dp, end = 16.dp)
        )

        // Bottom Glassmorphic Control Pill
        GlassmorphicControlPill(
            currentLens = currentLens,
            onLensToggle = onLensToggle,
            currentWidth = currentWidth,
            currentHeight = currentHeight,
            onResolutionToggle = onResolutionToggle,
            currentFps = currentFps,
            onFpsToggle = onFpsToggle,
            currentBitrateMbps = currentBitrateMbps,
            onBitrateChanged = onBitrateChanged,
            isTorchOn = isTorchOn,
            onTorchToggle = onTorchToggle,
            activeTransportMode = activeTransportMode,
            onTransportModeChanged = onTransportModeChanged,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp)
        )
    }
}
