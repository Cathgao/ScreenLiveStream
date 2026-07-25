package com.example.net

import android.util.Log
import com.example.log.AppLogger as SessionLog
import com.example.model.VideoCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class UdpStreamer {
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 8888
    private val executor = Executors.newSingleThreadExecutor()
    private var videoSeqCounter = 0
    private var audioSeqCounter = 0
    private var firstVideoQueuedLogged = false
    private var firstAudioQueuedLogged = false

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
            SessionLog.i(TAG, "UdpStreamer targeting $targetIp:$port")
        } catch (e: Exception) {
            SessionLog.e(TAG, "Failed to start UDP socket", e)
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
                if (!firstVideoQueuedLogged) {
                    firstVideoQueuedLogged = true
                    SessionLog.i(TAG, "first video frame queued: size=$size timestampMs=$timestampMs key=$isKeyframe config=$isCodecConfig")
                }
                val frameSeq = videoSeqCounter++
                val maxPayload = PacketProtocol.MAX_PAYLOAD_SIZE
                val totalPackets = ((size + maxPayload - 1) / maxPayload).coerceAtLeast(1)

                var remaining = size
                var currOffset = offset

                // Packets are sent back-to-back without inter-packet delays.
                // The previous LOCK_SUPPORT-parkNanos pacing was a defensive "spread the burst"
                // measure, but on a LAN/Wi-Fi link the worst case is a ~200 KB keyframe at 1300 B
                // (≈150 packets) sent over a 1ms–5ms window — well within the 4 MB receive buffer
                // of UdpReceiver and the kernel SO_SNDBUF default. The pacing only added 5–15 ms
                // of end-to-end latency while not actually preventing drops, so it has been removed.
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
                SessionLog.e(TAG, "Error sending UDP packet", e)
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

    /**
     * Broadcast a one-way stats beacon carrying the sender's rolling
     * RTT / network-loss numbers. The receiver reads it via
     * [PacketProtocol.readPingStatsPayload] and feeds it into the HUD.
     * Beacons share the same UDP socket as video so no extra port is
     * needed and no firewall changes are required.
     */
    fun sendStatsBeacon(rttMs: Int, lossPercent: Float) {
        if (!isConnected) return
        val currentSocket = socket ?: return
        val address = targetAddress ?: return
        // lossPercent is a Float in [0..100]. Convert to basis-points
        // (×100) so we keep two decimal digits of precision inside an
        // int field, clamped to [0..10000] = 0.00%..100.00%.
        val lossBp = (lossPercent * 100f).toInt().coerceIn(0, 10000)
        executor.execute {
            try {
                val packetBytes = PacketProtocol.buildPingStatsPacket(rttMs, lossBp)
                currentSocket.send(DatagramPacket(packetBytes, packetBytes.size, address, targetPort))
            } catch (e: Exception) {
                // Stats beacons are best-effort. Dropping one is harmless.
                SessionLog.w(TAG, "sendStatsBeacon failed: ${e.message}")
            }
        }
    }

    /**
     * Sends an audio chunk over the same UDP socket as video, tagged with FLAG_AUDIO.
     * Audio chunks are small (<2 KB at 128 kbps × ~10 ms) so we still split into MTU-safe
     * payloads for parity with video packets, even though most fit in a single packet.
     */
    fun sendAudioFrame(
        frameData: ByteArray,
        size: Int,
        timestampMs: Long,
        isCodecConfig: Boolean
    ) {
        if (!isConnected) return
        val currentSocket = socket ?: return
        val address = targetAddress ?: return

        executor.execute {
            try {
                if (!firstAudioQueuedLogged) {
                    firstAudioQueuedLogged = true
                    SessionLog.i(TAG, "first audio frame queued: size=$size timestampMs=$timestampMs config=$isCodecConfig")
                }
                val frameSeq = audioSeqCounter++
                val maxPayload = PacketProtocol.MAX_PAYLOAD_SIZE
                val totalPackets = ((size + maxPayload - 1) / maxPayload).coerceAtLeast(1)

                var remaining = size
                var currOffset = 0

                // Audio chunks are tiny (~250 B at 128 kbps × 16 ms) and almost always fit
                // in a single packet, so no pacing is required. Sent back-to-back, same as video.
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
                        isKeyframe = false,
                        isCodecConfig = isCodecConfig,
                        isHevc = false,
                        isAudio = true
                    )

                    val packet = DatagramPacket(packetBytes, packetBytes.size, address, targetPort)
                    currentSocket.send(packet)

                    currOffset += chunkSize
                    remaining -= chunkSize
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending UDP audio packet", e)
                SessionLog.e(TAG, "Error sending UDP audio packet", e)
            }
        }
    }

    companion object {
        private const val TAG = "UdpStreamer"
    }
}
