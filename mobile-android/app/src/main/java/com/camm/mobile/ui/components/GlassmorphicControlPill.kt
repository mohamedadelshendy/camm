package com.camm.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camm.mobile.camera.CameraLens
import com.camm.mobile.transport.TransportMode
import com.camm.mobile.ui.theme.CyanAccent
import com.camm.mobile.ui.theme.DarkSurface
import com.camm.mobile.ui.theme.GlassBackground
import com.camm.mobile.ui.theme.GlassBorder
import com.camm.mobile.ui.theme.TextPrimary
import com.camm.mobile.ui.theme.TextSecondary

@Composable
fun GlassmorphicControlPill(
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
    modifier: Modifier = Modifier
) {
    var showWifiDialog by remember { mutableStateOf(false) }
    var wifiIpInput by remember { mutableStateOf("192.168.1.100") }
    var wifiPortInput by remember { mutableStateOf("5556") }
    var bitrateSliderValue by remember(currentBitrateMbps) { mutableFloatStateOf(currentBitrateMbps) }

    if (showWifiDialog) {
        AlertDialog(
            onDismissRequest = { showWifiDialog = false },
            title = { Text("Configure Wi-Fi Target", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = wifiIpInput,
                        onValueChange = { wifiIpInput = it },
                        label = { Text("PC IP Address") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = GlassBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    OutlinedTextField(
                        value = wifiPortInput,
                        onValueChange = { wifiPortInput = it },
                        label = { Text("UDP Port") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = GlassBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val port = wifiPortInput.toIntOrNull() ?: 5556
                        onTransportModeChanged(TransportMode.WIFI_UDP, wifiIpInput, port)
                        showWifiDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                ) {
                    Text("Apply & Stream", color = DarkSurface)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWifiDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(GlassBackground)
            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Row 1: Mode, Lens, Res, FPS, Flash
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Transport Mode Selector
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface.copy(alpha = 0.9f))
                        .padding(4.dp)
                ) {
                    // USB button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (activeTransportMode == TransportMode.USB_AUTO) CyanAccent else Color.Transparent)
                            .clickable { onTransportModeChanged(TransportMode.USB_AUTO, "", 5555) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Usb,
                                contentDescription = "USB",
                                tint = if (activeTransportMode == TransportMode.USB_AUTO) DarkSurface else TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "USB",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeTransportMode == TransportMode.USB_AUTO) DarkSurface else TextSecondary
                            )
                        }
                    }

                    // Wi-Fi button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (activeTransportMode == TransportMode.WIFI_UDP) CyanAccent else Color.Transparent)
                            .clickable {
                                if (activeTransportMode == TransportMode.WIFI_UDP) {
                                    showWifiDialog = true
                                } else {
                                    showWifiDialog = true
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = "Wi-Fi",
                                tint = if (activeTransportMode == TransportMode.WIFI_UDP) DarkSurface else TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Wi-Fi",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeTransportMode == TransportMode.WIFI_UDP) DarkSurface else TextSecondary
                            )
                        }
                    }
                }

                // Lens cycle button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface.copy(alpha = 0.9f))
                        .clickable {
                            val nextLens = when (currentLens) {
                                CameraLens.BACK -> CameraLens.FRONT
                                CameraLens.FRONT -> CameraLens.ULTRA_WIDE
                                CameraLens.ULTRA_WIDE -> CameraLens.BACK
                            }
                            onLensToggle(nextLens)
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = when (currentLens) {
                            CameraLens.BACK -> "Back"
                            CameraLens.FRONT -> "Front"
                            CameraLens.ULTRA_WIDE -> "0.5x Wide"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent
                    )
                }

                // Resolution toggle (1080p / 720p)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface.copy(alpha = 0.9f))
                        .clickable {
                            if (currentHeight == 1080) {
                                onResolutionToggle(1280, 720)
                            } else {
                                onResolutionToggle(1920, 1080)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "${currentHeight}p",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                // FPS toggle (60 / 30)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface.copy(alpha = 0.9f))
                        .clickable {
                            onFpsToggle(if (currentFps == 60) 30 else 60)
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "${currentFps} FPS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                // Torch Toggle
                IconButton(
                    onClick = { onTorchToggle(!isTorchOn) },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isTorchOn) CyanAccent else DarkSurface.copy(alpha = 0.9f))
                ) {
                    Icon(
                        imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Torch",
                        tint = if (isTorchOn) DarkSurface else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Row 2: Bitrate Slider (2 Mbps to 20 Mbps)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Hardware Bitrate",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Text(
                        text = "%.1f Mbps".format(bitrateSliderValue),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = CyanAccent
                    )
                }
                Slider(
                    value = bitrateSliderValue,
                    onValueChange = { bitrateSliderValue = it },
                    onValueChangeFinished = {
                        onBitrateChanged((bitrateSliderValue * 1_000_000).toInt())
                    },
                    valueRange = 2f..20f,
                    steps = 17,
                    colors = SliderDefaults.colors(
                        thumbColor = CyanAccent,
                        activeTrackColor = CyanAccent,
                        inactiveTrackColor = DarkSurface
                    ),
                    modifier = Modifier.height(30.dp)
                )
            }
        }
    }
}
