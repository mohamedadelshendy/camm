package com.camm.mobile.transport

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UdpStreamSender(
    private var targetIp: String = "192.168.1.100",
    private var targetPort: Int = 5556,
    private val onStatusChanged: (String) -> Unit
) {
    private val tag = "UdpStreamSender"
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private val frameCounter = AtomicInteger(0)
    private val isRunning = AtomicBoolean(false)

    // Reusable packet buffer
    private val maxPacketSize = PacketHeader.UDP_HEADER_SIZE + PacketHeader.MAX_UDP_PAYLOAD_CHUNK
    private val sendBuffer = ByteArray(maxPacketSize)
    private val byteBuffer = ByteBuffer.wrap(sendBuffer)

    fun start() {
        if (isRunning.getAndSet(true)) return
        try {
            socket = DatagramSocket().apply {
                sendBufferSize = 512 * 1024
            }
            updateDestination(targetIp, targetPort)
            Log.i(tag, "UDP Sender started targeting $targetIp:$targetPort")
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize UDP socket: ${e.message}", e)
            onStatusChanged("UDP Error: ${e.message}")
        }
    }

    fun updateDestination(ip: String, port: Int) {
        targetIp = ip
        targetPort = port
        Thread {
            try {
                targetAddress = InetAddress.getByName(ip)
                Log.i(tag, "UDP Destination updated to $ip:$port")
                onStatusChanged("Streaming UDP to $ip:$port")
            } catch (e: Exception) {
                Log.w(tag, "Invalid IP address: $ip (${e.message})")
                onStatusChanged("Invalid Target IP: $ip")
            }
        }.start()
    }

    fun sendFrame(data: ByteArray): Boolean {
        if (!isRunning.get()) return false
        val sock = socket ?: return false
        val addr = targetAddress ?: return false

        val frameId = frameCounter.incrementAndGet()
        val totalBytes = data.size
        val chunkSize = PacketHeader.MAX_UDP_PAYLOAD_CHUNK
        val totalPackets = (totalBytes + chunkSize - 1) / chunkSize

        if (totalPackets > 255) {
            Log.w(tag, "Frame size too large for 1-byte packet index: $totalBytes bytes")
            return false
        }

        var offset = 0
        for (packetIndex in 0 until totalPackets) {
            val length = minOf(chunkSize, totalBytes - offset)

            synchronized(sendBuffer) {
                byteBuffer.clear()
                PacketHeader.writeUdpHeader(
                    buffer = byteBuffer,
                    frameId = frameId,
                    packetIndex = packetIndex,
                    totalPackets = totalPackets,
                    payloadLen = length
                )
                System.arraycopy(data, offset, sendBuffer, PacketHeader.UDP_HEADER_SIZE, length)

                val packet = DatagramPacket(
                    sendBuffer,
                    PacketHeader.UDP_HEADER_SIZE + length,
                    addr,
                    targetPort
                )

                try {
                    sock.send(packet)
                } catch (e: Exception) {
                    Log.w(tag, "UDP send failure: ${e.message}")
                    return false
                }
            }
            offset += length
        }
        return true
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(tag, "Stopping UDP sender")
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        onStatusChanged("UDP Stopped")
    }
}
