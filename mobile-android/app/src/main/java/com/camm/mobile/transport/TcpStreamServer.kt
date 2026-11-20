package com.camm.mobile.transport

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class TcpStreamServer(
    private val port: Int = 5555,
    private val onConnectionChanged: (Boolean, String) -> Unit
) {
    private val tag = "TcpStreamServer"
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null

    private val isRunning = AtomicBoolean(false)
    private var serverJob: Job? = null
    private val sendLock = Any()
    private val headerBuffer = ByteBuffer.allocate(PacketHeader.TCP_HEADER_SIZE)

    fun start(scope: CoroutineScope) {
        if (isRunning.getAndSet(true)) return

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                    receiveBufferSize = 128 * 1024
                }
                Log.i(tag, "TCP Server listening on port $port (awaiting ADB port forward)")
                onConnectionChanged(false, "Listening on USB port $port")

                while (isActive && isRunning.get()) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        socket.tcpNoDelay = true
                        socket.sendBufferSize = 256 * 1024
                        socket.keepAlive = true

                        synchronized(sendLock) {
                            closeClientSocket()
                            clientSocket = socket
                            outputStream = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
                        }

                        val clientInfo = "${socket.inetAddress.hostAddress}:${socket.port}"
                        Log.i(tag, "Desktop client connected via USB: $clientInfo")
                        onConnectionChanged(true, "Connected via USB ($clientInfo)")

                        // Keep socket monitored until it closes
                        monitorClient(socket)
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        Log.w(tag, "Client socket loop exception: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Fatal TCP server error: ${e.message}", e)
                onConnectionChanged(false, "Error: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    private fun monitorClient(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val dummyBuf = ByteArray(64)
            while (isRunning.get() && socket.isConnected && !socket.isClosed) {
                val read = input.read(dummyBuf)
                if (read == -1) break
            }
        } catch (_: Exception) {
        } finally {
            Log.i(tag, "Desktop client disconnected")
            synchronized(sendLock) {
                closeClientSocket()
            }
            onConnectionChanged(false, "Listening on USB port $port")
        }
    }

    fun sendFrame(data: ByteArray, isKeyFrame: Boolean, isConfig: Boolean): Boolean {
        if (!isRunning.get()) return false

        synchronized(sendLock) {
            val out = outputStream ?: return false
            return try {
                val frameType = when {
                    isConfig -> PacketHeader.FRAME_TYPE_CONFIG
                    isKeyFrame -> PacketHeader.FRAME_TYPE_KEY
                    else -> PacketHeader.FRAME_TYPE_DELTA
                }
                val nowMs = System.currentTimeMillis()

                headerBuffer.clear()
                PacketHeader.writeTcpHeader(headerBuffer, data.size, frameType, nowMs)

                out.write(headerBuffer.array(), 0, PacketHeader.TCP_HEADER_SIZE)
                out.write(data)
                out.flush()
                true
            } catch (e: SocketException) {
                Log.w(tag, "Socket write failure, dropping client: ${e.message}")
                closeClientSocket()
                onConnectionChanged(false, "Listening on USB port $port")
                false
            } catch (e: Exception) {
                Log.e(tag, "Send error: ${e.message}")
                false
            }
        }
    }

    private fun closeClientSocket() {
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        outputStream = null

        try {
            clientSocket?.close()
        } catch (_: Exception) {}
        clientSocket = null
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(tag, "Stopping TCP Server")

        synchronized(sendLock) {
            closeClientSocket()
        }

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        serverJob?.cancel()
        serverJob = null
        onConnectionChanged(false, "Disconnected")
    }

    fun isClientConnected(): Boolean = clientSocket?.isConnected == true && outputStream != null
}
