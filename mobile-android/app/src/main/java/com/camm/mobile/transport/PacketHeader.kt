package com.camm.mobile.transport

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary Packet Framing Protocol for Ultra-Low Latency Video Streaming.
 *
 * UDP Packet Format (12-Byte Header):
 * [0..3]   Magic Bytes: 'C', 'A', 'M', 'M' (0x43414D4D)
 * [4..7]   FrameID: 32-bit unsigned integer (big-endian)
 * [8]      PacketIndex: 8-bit unsigned integer (0 .. TotalPackets - 1)
 * [9]      TotalPackets: 8-bit unsigned integer
 * [10..11] PayloadLen: 16-bit unsigned integer (big-endian)
 * [12..N]  Payload: H.264 NAL slice fragment
 *
 * TCP Frame Format (17-Byte Header):
 * [0..3]   Magic Bytes: 'C', 'A', 'M', 'M' (0x43414D4D)
 * [4..7]   PayloadLen: 32-bit unsigned integer (big-endian)
 * [8]      FrameType: 0=SPS/PPS Config, 1=IDR Keyframe, 2=P-Frame
 * [9..16]  TimestampMs: 64-bit integer timestamp for latency calculation
 * [17..N]  Payload: Raw Annex-B H.264 NAL units
 */
object PacketHeader {
    const val MAGIC: Int = 0x43414D4D // "CAMM"
    val MAGIC_BYTES = byteArrayOf('C'.code.toByte(), 'A'.code.toByte(), 'M'.code.toByte(), 'M'.code.toByte())

    const val UDP_HEADER_SIZE = 12
    const val TCP_HEADER_SIZE = 17

    // Keep UDP payload safely under typical 1500 byte Ethernet / Wi-Fi MTU
    const val MAX_UDP_PAYLOAD_CHUNK = 1300

    const val FRAME_TYPE_CONFIG: Byte = 0
    const val FRAME_TYPE_KEY: Byte = 1
    const val FRAME_TYPE_DELTA: Byte = 2

    fun writeUdpHeader(
        buffer: ByteBuffer,
        frameId: Int,
        packetIndex: Int,
        totalPackets: Int,
        payloadLen: Int
    ) {
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putInt(frameId)
        buffer.put(packetIndex.toByte())
        buffer.put(totalPackets.toByte())
        buffer.putShort(payloadLen.toShort())
    }

    fun writeTcpHeader(
        buffer: ByteBuffer,
        payloadLen: Int,
        frameType: Byte,
        timestampMs: Long
    ) {
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putInt(payloadLen)
        buffer.put(frameType)
        buffer.putLong(timestampMs)
    }
}
