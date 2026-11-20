package com.camm.mobile.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class H264Encoder(
    private var width: Int = 1920,
    private var height: Int = 1080,
    private var fps: Int = 60,
    private var bitrateBps: Int = 8_000_000,
    private val onEncodedFrame: (ByteArray, Boolean, Boolean) -> Unit
) {
    private val tag = "H264Encoder"
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val isRunning = AtomicBoolean(false)
    private var drainThread: Thread? = null

    // Cache SPS and PPS NAL units to prepend before every IDR Keyframe
    private var cachedConfigData: ByteArray? = null

    val surface: Surface?
        get() = inputSurface

    fun start() {
        if (isRunning.getAndSet(true)) return

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second GOP

                // Ultra-low latency tuning
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                try {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                } catch (_: Exception) {}
                try {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                } catch (_: Exception) {}
                try {
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority
                } catch (_: Exception) {}
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            Log.i(tag, "MediaCodec started: ${width}x${height} @ ${fps}fps, ${(bitrateBps / 1_000_000f)} Mbps")

            startDrainThread()
        } catch (e: Exception) {
            Log.e(tag, "Failed to start MediaCodec H264 encoder: ${e.message}", e)
            stop()
        }
    }

    private fun startDrainThread() {
        drainThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val bufferInfo = MediaCodec.BufferInfo()

            while (isRunning.get()) {
                val mediaCodec = codec ?: break
                try {
                    val outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10_000) // 10ms timeout

                    if (outputBufferIndex >= 0) {
                        val outputBuffer: ByteBuffer? = mediaCodec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            val frameBytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(frameBytes)

                            if (isConfig) {
                                // Save SPS / PPS parameter sets
                                cachedConfigData = frameBytes
                                Log.i(tag, "Extracted SPS/PPS config header: ${frameBytes.size} bytes")
                                onEncodedFrame(frameBytes, false, true)
                            } else if (isKeyFrame) {
                                // Prepend SPS/PPS before IDR frame for zero-latency join/recovery
                                val completeFrame = if (cachedConfigData != null) {
                                    val prepended = ByteArray(cachedConfigData!!.size + frameBytes.size)
                                    System.arraycopy(cachedConfigData!!, 0, prepended, 0, cachedConfigData!!.size)
                                    System.arraycopy(frameBytes, 0, prepended, cachedConfigData!!.size, frameBytes.size)
                                    prepended
                                } else {
                                    frameBytes
                                }
                                onEncodedFrame(completeFrame, true, false)
                            } else {
                                onEncodedFrame(frameBytes, false, false)
                            }
                        }
                        mediaCodec.releaseOutputBuffer(outputBufferIndex, false)
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = mediaCodec.outputFormat
                        Log.i(tag, "Encoder output format changed: $newFormat")
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.w(tag, "MediaCodec drain loop error: ${e.message}")
                    }
                }
            }
        }.apply {
            name = "H264-Drain-Thread"
            isDaemon = true
            start()
        }
    }

    fun updateBitrate(newBitrateBps: Int) {
        if (this.bitrateBps == newBitrateBps) return
        this.bitrateBps = newBitrateBps
        val mediaCodec = codec ?: return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrateBps)
            }
            mediaCodec.setParameters(params)
            Log.i(tag, "Dynamic bitrate updated to $newBitrateBps bps")
        } catch (e: Exception) {
            Log.w(tag, "Failed to dynamically set bitrate: ${e.message}")
        }
    }

    fun requestKeyFrame() {
        val mediaCodec = codec ?: return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec.setParameters(params)
            Log.i(tag, "Requested on-demand IDR Sync Frame")
        } catch (e: Exception) {
            Log.w(tag, "Failed to request sync frame: ${e.message}")
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(tag, "Stopping H.264 Encoder")

        try {
            drainThread?.join(500)
        } catch (_: Exception) {}
        drainThread = null

        try {
            codec?.stop()
        } catch (_: Exception) {}
        try {
            codec?.release()
        } catch (_: Exception) {}
        codec = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null
    }
}
