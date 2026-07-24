package com.example.net

import android.util.Log
import com.example.model.StreamStats
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class UdpReceiver {
    private var socket: DatagramSocket? = null
    @Volatile
    private var isListening = false
    private var listenerThread: Thread? = null

    // Callback for decoded frames: (frameBytes, isKeyframe, isCodecConfig, isHevc)
    var onFrameAssembled: ((ByteArray, Boolean, Boolean, Boolean) -> Unit)? = null
    var onStatsUpdated: ((StreamStats) -> Unit)? = null

    private val frameBuffers = ConcurrentHashMap<Int, FrameAssembly>()

    // Statistics tracking
    private var receivedFrameCount = 0L
    private var droppedFrameCount = 0L
    private var totalBytesReceived = 0L
    private var lastStatsResetTime = System.currentTimeMillis()
    private var currentFps = 0f
    private var currentBitrateMbps = 0f
    private var currentLatencyMs = 0L

    private class FrameAssembly(
        val frameSeq: Int,
        val totalPackets: Int,
        val isKeyframe: Boolean,
        val isCodecConfig: Boolean,
        val isHevc: Boolean,
        val timestampMs: Long
    ) {
        val packets = Array<ByteArray?>(totalPackets) { null }
        var receivedCount = 0

        fun addPacket(idx: Int, payload: ByteArray): Boolean {
            if (idx in 0 until totalPackets && packets[idx] == null) {
                packets[idx] = payload
                receivedCount++
            }
            return receivedCount == totalPackets
        }

        fun assemble(): ByteArray {
            val baos = ByteArrayOutputStream()
            for (p in packets) {
                if (p != null) {
                    baos.write(p)
                }
            }
            return baos.toByteArray()
        }
    }

    fun start(port: Int) {
        stop()
        isListening = true
        listenerThread = thread(start = true, name = "UdpReceiverThread") {
            try {
                val ds = DatagramSocket(port)
                ds.receiveBufferSize = 2 * 1024 * 1024 // 2MB receive socket buffer for high throughput
                socket = ds
                Log.d(TAG, "UdpReceiver listening on port $port")

                val receiveBuffer = ByteArray(65535)
                val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)

                while (isListening && !ds.isClosed) {
                    ds.receive(packet)
                    val parsed = PacketProtocol.parsePacket(packet.data, packet.length)
                    if (parsed != null) {
                        totalBytesReceived += packet.length
                        processParsedPacket(parsed)
                    }
                    updateStatsIfNeeded()
                }
            } catch (e: Exception) {
                if (isListening) {
                    Log.e(TAG, "Error in UdpReceiver listener", e)
                }
            }
        }
    }

    private fun processParsedPacket(parsed: PacketProtocol.ParsedPacket) {
        val now = System.currentTimeMillis()
        if (parsed.timestampMs > 0 && parsed.timestampMs <= now) {
            currentLatencyMs = (now - parsed.timestampMs).coerceIn(1, 1000)
        }

        val assembly = frameBuffers.getOrPut(parsed.frameSeq) {
            FrameAssembly(
                frameSeq = parsed.frameSeq,
                totalPackets = parsed.totalPackets,
                isKeyframe = parsed.isKeyframe,
                isCodecConfig = parsed.isCodecConfig,
                isHevc = parsed.isHevc,
                timestampMs = parsed.timestampMs
            )
        }

        if (assembly.addPacket(parsed.packetIndex, parsed.payload)) {
            val completeFrame = assembly.assemble()
            frameBuffers.remove(parsed.frameSeq)
            receivedFrameCount++

            // Clean up old incomplete frames to prevent memory leaks
            if (frameBuffers.size > 20) {
                val oldestKeys = frameBuffers.keys().toList().sorted().take(10)
                for (k in oldestKeys) {
                    frameBuffers.remove(k)
                    droppedFrameCount++
                }
            }

            onFrameAssembled?.invoke(
                completeFrame,
                assembly.isKeyframe,
                assembly.isCodecConfig,
                assembly.isHevc
            )
        }
    }

    private fun updateStatsIfNeeded() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastStatsResetTime
        if (elapsed >= 1000) {
            currentFps = (receivedFrameCount * 1000f) / elapsed
            currentBitrateMbps = (totalBytesReceived * 8f) / (elapsed * 1000f)

            val totalExpected = (receivedFrameCount + droppedFrameCount).coerceAtLeast(1)
            val lossPercent = (droppedFrameCount * 100f) / totalExpected

            val stats = StreamStats(
                isReceiving = true,
                fps = currentFps,
                bitrateMbps = currentBitrateMbps,
                latencyMs = currentLatencyMs,
                totalFrames = receivedFrameCount,
                droppedFrames = droppedFrameCount,
                packetLossPercent = lossPercent
            )

            onStatsUpdated?.invoke(stats)

            // Reset periodic windows
            receivedFrameCount = 0
            droppedFrameCount = 0
            totalBytesReceived = 0
            lastStatsResetTime = now
        }
    }

    fun stop() {
        isListening = false
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        socket = null
        frameBuffers.clear()
    }

    companion object {
        private const val TAG = "UdpReceiver"
    }
}
