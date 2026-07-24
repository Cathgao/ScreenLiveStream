package com.example.net

import android.util.Log
import com.example.log.SessionLog
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
    private var frameSeqCounter = 0
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
                val frameSeq = frameSeqCounter++
                val maxPayload = PacketProtocol.MAX_PAYLOAD_SIZE
                val totalPackets = ((size + maxPayload - 1) / maxPayload).coerceAtLeast(1)

                var remaining = size
                var currOffset = offset

                // Mild intra-frame pacing: the protocol doesn't change, but we
                // briefly yield between fragments so a single large frame
                // (codec-config + keyframe in particular) doesn't fire tens of
                // MTU-sized packets back-to-back and overflow the receiver's
                // reassembly buffers or the kernel send queue. The delay is
                // calibrated so a ~150 KB keyframe at 1300 B MTU (~115
                // fragments) costs <15 ms total — negligible vs the ~11 ms
                // frame interval — but enough to spread load when the executor
                // is busy with several concurrent frames.
                val pacedPackets = totalPackets > PACKET_PACING_THRESHOLD
                val pacingDelayNs = if (pacedPackets) PACKET_PACING_DELAY_NS else 0L

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

                    if (pacingDelayNs > 0 && idx + 1 < totalPackets) {
                        // LockSupport.parkNanos is cheaper than Thread.sleep
                        // and doesn't reset thread-interrupt state, which
                        // matters because this executor may also be used to
                        // send audio frames.
                        java.util.concurrent.locks.LockSupport.parkNanos(pacingDelayNs)
                    }
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
                val frameSeq = frameSeqCounter++
                val maxPayload = PacketProtocol.MAX_PAYLOAD_SIZE
                val totalPackets = ((size + maxPayload - 1) / maxPayload).coerceAtLeast(1)

                var remaining = size
                var currOffset = 0

                // Same intra-frame pacing as the video path. Audio chunks
                // are small enough that this is normally a no-op; the
                // threshold keeps us from adding latency on tiny single-packet
                // AAC frames.
                val pacedPackets = totalPackets > PACKET_PACING_THRESHOLD
                val pacingDelayNs = if (pacedPackets) PACKET_PACING_DELAY_NS else 0L

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

                    if (pacingDelayNs > 0 && idx + 1 < totalPackets) {
                        java.util.concurrent.locks.LockSupport.parkNanos(pacingDelayNs)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending UDP audio packet", e)
                SessionLog.e(TAG, "Error sending UDP audio packet", e)
            }
        }
    }

    companion object {
        private const val TAG = "UdpStreamer"

        // Frames with more than this many fragments get intra-frame pacing.
        // Codec-config + keyframe is the only case that crosses this
        // threshold in practice; ~150 KB / 1300 B ≈ 115 fragments.
        private const val PACKET_PACING_THRESHOLD = 8
        // ~120 µs per fragment. For a 115-fragment frame that's ~14 ms
        // total — about one 90 fps frame interval.
        private const val PACKET_PACING_DELAY_NS = 120_000L
    }
}
