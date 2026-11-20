# CAMM - Ultra-Low-Latency (<20ms) Mobile-to-PC Virtual Webcam

<div align="center">
  <h3>Zero-Latency Wired (ADB) & Fast Wireless (UDP) Virtual Webcam Ecosystem</h3>
  <p>Turn your Android phone into an ultra-high quality, 60 FPS hardware-accelerated Windows Virtual Webcam.</p>
</div>

---

## Architecture Overview

```
+---------------------------------------------------------------------------------------+
|                                    ANDROID MOBILE APP                                 |
|                                                                                       |
|  +------------------+     +-------------------+     +-------------------------------+ |
|  |   Camera2 API    | --> | Hardware MediaCodec| --> | Transport Layer               | |
|  | 1080p/720p @60fps|     | video/avc (H.264) |     | - USB: TCP 5555 Server        | |
|  | Ultra-Wide/Front |     | CBR, 0 B-Frames   |     | - Wi-Fi: UDP Custom Framing   | |
|  +------------------+     | SPS/PPS injection |     +-------------------------------+ |
+---------------------------+-------------------+---------------------------------------+
                                      |
                           USB (ADB) or Wi-Fi (UDP)
                                      v
+---------------------------------------------------------------------------------------+
|                                  WINDOWS DESKTOP APP                                  |
|                                                                                       |
|  +-----------------------+     +-----------------------+     +----------------------+ |
|  | ADB Auto-Manager      |     | Hardware HW Decoder   |     | DirectShow Sink      | |
|  | - adb forward tcp:5555| --> | - D3D11VA / NVDEC     | --> | - UnityCapture / OBS | |
|  | - UDP Reassembly      |     | - Zero-copy surface   |     |   Shared Memory Sink | |
|  +-----------------------+     +-----------------------+     +----------------------+ |
|                                                                         |             |
|                                                                         v             |
|                                                       Zoom / Discord / OBS / Teams    |
+---------------------------------------------------------------------------------------+
```

---

## 3-Step Quick Start

### 1. Register the Virtual Camera Filter (Once on Windows)
Open PowerShell as Administrator and run:
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\register-virtual-cam.ps1
```
This registers the 64-bit DirectShow filter so Windows programs (Zoom, Discord, OBS, Teams, Chrome) detect **"Unity Video Capture"** as a physical webcam.

### 2. Connect Your Phone
- **Wired (Zero-Latency USB - Recommended):**
  1. Enable **USB Debugging** in Android Developer Options.
  2. Plug your phone into your PC via USB cable.
  3. Open the **CAMM** mobile app and leave it on **USB Auto** mode.
  4. Launch the desktop app. It automatically runs `adb forward tcp:5555 tcp:5555` and connects in milliseconds!
- **Wireless (Wi-Fi UDP):**
  1. Ensure both devices are on the same Wi-Fi or Mobile Hotspot.
  2. On the mobile app, toggle to **Wi-Fi UDP** and enter your PC's IP address.
  3. The desktop app receives and reassembles frames instantly over UDP port `5556`.

### 3. Select Webcam in Your Video Conference App
In Zoom, Discord, Google Meet, OBS Studio, or Microsoft Teams:
- Video device: Select **"Unity Video Capture"**
- Enjoy buttery-smooth 1080p/720p @ 60 FPS with **<20 ms** latency!

---

## Core Specifications

### Mobile Application (`/mobile-android`)
- **Language & Framework:** Kotlin, Jetpack Compose, Material 3 Dark theme.
- **Camera Engine:** Camera2 API with multi-lens enumeration (Back, Front, Ultra-Wide), auto-focus, continuous auto-exposure, and torch control.
- **Hardware Encoder:** Android `MediaCodec` hardware encoder (`video/avc`):
  - `KEY_LATENCY = 0` (Immediate output, no queuing)
  - `KEY_MAX_B_FRAMES = 0` (Zero decoding reordering latency)
  - `BITRATE_MODE_CBR` (Constant Bitrate mode)
  - `KEY_I_FRAME_INTERVAL = 1`
  - Inline SPS/PPS injection before every IDR keyframe for instant client sync.
- **Transports:**
  - **USB Mode:** Non-blocking TCP Server with `TCP_NODELAY` listening on port `5555`.
  - **Wi-Fi Mode:** UDP Datagram sender with custom 12-byte binary packet framing `[Magic 4B][FrameID 4B][PacketIndex 1B][TotalPackets 1B][PayloadLen 2B][Payload]` to avoid MTU fragmentation and eliminate TCP head-of-line blocking.
- **Foreground Service:** Persistent `CameraService` with `WakeLock` to prevent streaming dropouts when screen locks.

### Desktop Application (`/desktop-windows`)
- **Backend (Rust + Tauri v2):**
  - `adb_manager.rs`: Auto-detects connected devices, executes port forwarding, monitors connection drops.
  - `stream_receiver.rs`: Handles USB TCP client and Wi-Fi UDP reassembly with rolling telemetry calculations (FPS, Bitrate, Latency, Dropped Frames).
  - `hw_decoder.rs`: Hardware-accelerated decoding via `ffmpeg-next` (D3D11VA / NVDEC) with ultra-low latency flags (`AV_CODEC_FLAG_LOW_DELAY`) and zero-copy fallback.
  - `virtual_cam.rs`: High-performance Win32 named shared memory sink (`UnityCapture_Memory_0` and `OBSVirtualCam_SharedMemory`) synchronized via kernel Mutex and Event primitives.
  - `commands.rs`: Tauri IPC handlers for live configuration, rotation, mirror flip, and GPU-saving preview toggle.
- **Frontend Dashboard (TypeScript + Tailwind CSS):**
  - Ultra-fast dark glassmorphic control center.
  - Real-time latency gauge (<20ms indicator), FPS counter, and Bitrate monitor.
  - Quick video transforms: Mirror Horizontal, Rotate 90°, Freeze Frame.
  - Preview GPU-saver toggle to eliminate rendering overhead while streaming into calls.

---

## Automated Cloud CI/CD
This repository includes a production-grade GitHub Actions workflow (`.github/workflows/build-all.yml`) that compiles both applications on push:
- **`build-android` (Ubuntu):** Sets up JDK 17, compiles release APK (`./gradlew assembleRelease`), and uploads signed `app-release.apk`.
- **`build-desktop` (Windows):** Sets up Rust, Node.js, downloads prebuilt FFmpeg shared dev libraries, builds Tauri release binary, and uploads the Windows executable.

---

## Manual Local Build Instructions

### Android App
```bash
cd mobile-android
./gradlew assembleRelease
```
The output APK is at `mobile-android/app/build/outputs/apk/release/app-release.apk`.

### Desktop App
```bash
cd desktop-windows
npm install
npm run tauri build
```
The output installer is at `desktop-windows/src-tauri/target/release/camm-desktop.exe`.
