package com.camm.mobile.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class CameraLens {
    BACK,
    FRONT,
    ULTRA_WIDE
}

class CameraStreamer(
    private val context: Context,
    private val onResolutionChanged: (Int, Int) -> Unit
) {
    private val tag = "CameraStreamer"
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val cameraOpenCloseLock = Semaphore(1)
    private val isStreaming = AtomicBoolean(false)

    // Active configurations
    var currentLens: CameraLens = CameraLens.BACK
        private set
    var currentWidth: Int = 1920
        private set
    var currentHeight: Int = 1080
        private set
    var currentFps: Int = 60
        private set
    var isTorchEnabled: Boolean = false
        private set

    // Destination surfaces
    private var encoderSurface: Surface? = null
    private var previewSurface: Surface? = null

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackgroundThread", Process.THREAD_PRIORITY_URGENT_AUDIO).apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(tag, "Interrupted while stopping background thread: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun start(
        lens: CameraLens = CameraLens.BACK,
        width: Int = 1920,
        height: Int = 1080,
        fps: Int = 60,
        encoderSurface: Surface,
        previewSurface: Surface? = null
    ) {
        this.currentLens = lens
        this.currentWidth = width
        this.currentHeight = height
        this.currentFps = fps
        this.encoderSurface = encoderSurface
        this.previewSurface = previewSurface

        startBackgroundThread()
        openCamera()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cameraId = findCameraIdForLens(currentLens) ?: run {
            Log.e(tag, "Unable to find camera for lens $currentLens")
            return
        }

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    startCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    Log.e(tag, "Camera error callback received: $error")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(tag, "Exception opening camera: ${e.message}", e)
            cameraOpenCloseLock.release()
        }
    }

    private fun startCaptureSession() {
        val camera = cameraDevice ?: return
        val encSurface = encoderSurface ?: return

        try {
            val surfaces = mutableListOf<Surface>(encSurface)
            previewSurface?.let { surfaces.add(it) }

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    updateCaptureRequest()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(tag, "Camera capture session configuration failed")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(tag, "Failed to create capture session: ${e.message}", e)
        }
    }

    private fun updateCaptureRequest() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val encSurface = encoderSurface ?: return

        try {
            previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(encSurface)
                previewSurface?.let { addTarget(it) }

                // Continuous Auto-Focus for smooth video
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

                // High-framerate target range
                val fpsRange = selectBestFpsRange(camera.id, currentFps)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

                // Torch mode
                if (currentLens != CameraLens.FRONT) {
                    set(
                        CaptureRequest.FLASH_MODE,
                        if (isTorchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                    )
                }

                // Low latency stabilization
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }

            session.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
            isStreaming.set(true)
            onResolutionChanged(currentWidth, currentHeight)
            Log.i(tag, "Capture session repeating request active: ${currentWidth}x${currentHeight} @ ${currentFps}fps")
        } catch (e: Exception) {
            Log.e(tag, "Failed to set repeating capture request: ${e.message}", e)
        }
    }

    private fun selectBestFpsRange(cameraId: String, targetFps: Int): Range<Int> {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return Range(targetFps, targetFps)

        var bestRange = ranges[0]
        for (range in ranges) {
            if (range.upper == targetFps) {
                if (range.lower == targetFps) {
                    return range // Fixed 60 or 30 fps
                }
                bestRange = range
            }
        }
        return bestRange
    }

    private fun findCameraIdForLens(lens: CameraLens): String? {
        val cameraIds = cameraManager.cameraIdList

        when (lens) {
            CameraLens.FRONT -> {
                for (id in cameraIds) {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT) return id
                }
            }
            CameraLens.BACK -> {
                for (id in cameraIds) {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
                }
            }
            CameraLens.ULTRA_WIDE -> {
                // Look for back camera with minimum focal length (ultra-wide)
                var ultraWideId: String? = null
                var minFocalLength = Float.MAX_VALUE

                for (id in cameraIds) {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        if (focalLengths != null && focalLengths.isNotEmpty()) {
                            val focal = focalLengths.minOrNull() ?: Float.MAX_VALUE
                            if (focal < minFocalLength && focal < 3.0f) {
                                minFocalLength = focal
                                ultraWideId = id
                            }
                        }
                    }
                }
                return ultraWideId ?: findCameraIdForLens(CameraLens.BACK)
            }
        }
        return cameraIds.firstOrNull()
    }

    fun switchLens(lens: CameraLens, newEncoderSurface: Surface) {
        if (this.currentLens == lens && cameraDevice != null) return
        this.currentLens = lens
        this.encoderSurface = newEncoderSurface
        closeCamera()
        openCamera()
    }

    fun setTorch(enabled: Boolean) {
        if (currentLens == CameraLens.FRONT) return
        isTorchEnabled = enabled
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        try {
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            )
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.w(tag, "Failed to toggle torch: ${e.message}")
        }
    }

    fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            isStreaming.set(false)
        } catch (e: Exception) {
            Log.e(tag, "Error closing camera: ${e.message}")
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    fun stop() {
        closeCamera()
        stopBackgroundThread()
    }
}
