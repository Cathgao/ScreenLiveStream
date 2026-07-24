package com.example.net

import android.util.Log
import com.example.model.VideoCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class UdpStreamer {
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 8888
    private val executor = Executors.newSingleThreadExecutor()
    private var frameSeqCounter = 0

    @Volatile
    private var isConnected = false

    fun start(targetIp: String, port: Int) {
        stop()
        try {
            socket = DatagramSocket()
            targetAddress = InetAddress.getByName(targetIp)
            targetPort = port
            isConnected = true
            Log.d(TAG, "UdpStreamer started targeting $targetIp:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UDP socket", e)
            stop()
        }
    }

    fun sendFrame(
        frameData: ByteArray,
        offset: Int,
        size: Int,
        timestampMs: Long,
        isKeyframe: Boolean,
        isCodecConfig: Boolean,
        codec: VideoCodec
    ) {
        if (!isConnected) return
        val currentSocket = socket ?: return
        val address = targetAddress ?: return

        executor.execute {
            try {
                val frameSeq = frameSeqCounter++
                val maxPayload = PacketProtocol.MAX_PAYLOAD_SIZE
                val totalPackets = ((size + maxPayload - 1) / maxPayload).coerceAtLeast(1)

                var remaining = size
                var currOffset = offset

                for (idx in 0 until totalPackets) {
                    val chunkSize = remaining.coerceAtMost(maxPayload)
                    val packetBytes = PacketProtocol.buildPacket(
                        buffer = frameData,
                        offset = currOffset,
                        payloadSize = chunkSize,
                        frameSeq = frameSeq,
                        timestampMs = timestampMs,
                        packetIndex = idx.toShort(),
                        totalPackets = totalPackets.toShort(),
                        isKeyframe = isKeyframe,
                        isCodecConfig = isCodecConfig,
                        isHevc = (codec == VideoCodec.H265)
                    )

                    val packet = DatagramPacket(packetBytes, packetBytes.size, address, targetPort)
                    currentSocket.send(packet)

                    currOffset += chunkSize
                    remaining -= chunkSize
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending UDP packet", e)
            }
        }
    }

    fun stop() {
        isConnected = false
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        socket = null
        targetAddress = null
    }

    companion object {
        private const val TAG = "UdpStreamer"
    }
}
