package com.example.net

import com.example.log.AppLogger as SessionLog
import com.example.model.StreamStats
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class TcpReceiver : IReceiver {
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isListening = false
    private var listenerThread: Thread? = null

    // Callback for decoded frames: (frameBytes, isKeyframe, isCodecConfig, isHevc, timestampMs, seq)
    override var onFrameAssembled: ((ByteArray, Boolean, Boolean, Boolean, Long, Int) -> Unit)? = null
    // Callback for completed audio frames: (aacBytes, isCodecConfig, timestampMs)
    override var onAudioFrame: ((ByteArray, Boolean, Long) -> Unit)? = null
    override var onStatsUpdated: ((StreamStats) -> Unit)? = null
    override var onReferenceLost: (() -> Unit)? = null
    var onProbeReply: ((probeSeq: Int, originalSendTimeNanos: Long) -> Unit)? = null

    @Volatile
    override var jitterBufferMs: Int = 0

    fun sendNack(frameSeq: Int, packetIndex: Int) {
        // TCP guarantees 100% reliable ordered packet delivery; NACK is unnecessary.
    }

    fun sendPli() {
        onReferenceLost?.invoke()
    }

    // Per-window stats
    private var windowReceivedFrames = 0L
    private var windowBytesReceived = 0L
    private var lastStatsResetTime = System.currentTimeMillis()
    private var currentFps = 0f
    private var currentBitrateMbps = 0f

    @Volatile
    private var lastReportedNetworkLossPercent: Float = 0f
    @Volatile
    private var lastReportedRttMs: Int = 0

    fun setRttStats(rttMs: Int, lossPercent: Float) {
        lastReportedRttMs = rttMs
        lastReportedNetworkLossPercent = lossPercent
    }

    private var totalReceivedFrames = 0L
    private var totalBytesReceived = 0L

    override fun start(port: Int) {
        stop()
        isListening = true

        listenerThread = thread(start = true, name = "TcpReceiverListenerThread") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                SessionLog.i(TAG, "TCP Stream Receiver listening on port $port...")

                while (isListening && !ss.isClosed) {
                    var clientSocket: Socket? = null
                    try {
                        clientSocket = ss.accept()
                        clientSocket.tcpNoDelay = true
                        clientSocket.receiveBufferSize = 8 * 1024 * 1024
                        val clientIp = clientSocket.inetAddress.hostAddress
                        SessionLog.i(TAG, "TCP Receiver accepted stream connection from $clientIp")

                        val dis = DataInputStream(BufferedInputStream(clientSocket.getInputStream(), 2 * 1024 * 1024))
                        val headerBuf = ByteArray(20)

                        while (isListening && !clientSocket.isClosed) {
                            dis.readFully(headerBuf, 0, 20)

                            if (headerBuf[0] != PacketProtocol.MAGIC_0 || headerBuf[1] != PacketProtocol.MAGIC_1) {
                                SessionLog.w(TAG, "Header magic mismatch in TCP stream! Closing client socket.")
                                break
                            }

                            val flags = headerBuf[3].toInt() and 0xFF
                            val isKeyframe = (flags and PacketProtocol.FLAG_KEYFRAME.toInt()) != 0
                            val isCodecConfig = (flags and PacketProtocol.FLAG_CODEC_CONFIG.toInt()) != 0
                            val isHevc = (flags and PacketProtocol.FLAG_CODEC_HEVC.toInt()) != 0
                            val isAudio = (flags and PacketProtocol.FLAG_AUDIO.toInt()) != 0
                            val isPingStats = (flags and PacketProtocol.FLAG_PING_STATS.toInt()) != 0

                            val bb = ByteBuffer.wrap(headerBuf, 4, 16)
                            val frameSeq = bb.int
                            val timestampMs = bb.long
                            val payloadSize = bb.int

                            if (isPingStats) {
                                val rttMs = dis.readInt()
                                val lossBp = dis.readInt()
                                setRttStats(rttMs, lossBp / 100f)
                                tickStats()
                                continue
                            }

                            if (payloadSize < 0 || payloadSize > 10_000_000) {
                                SessionLog.w(TAG, "Invalid payload size $payloadSize in TCP stream!")
                                break
                            }

                            val payload = ByteArray(payloadSize)
                            dis.readFully(payload, 0, payloadSize)

                            totalBytesReceived += 20 + payloadSize
                            windowBytesReceived += 20 + payloadSize

                            if (isAudio) {
                                onAudioFrame?.invoke(payload, isCodecConfig, timestampMs)
                            } else {
                                if (!isCodecConfig) {
                                    totalReceivedFrames++
                                    windowReceivedFrames++
                                }
                                onFrameAssembled?.invoke(payload, isKeyframe, isCodecConfig, isHevc, timestampMs, frameSeq)
                            }

                            tickStats()
                        }
                    } catch (e: Exception) {
                        if (isListening) {
                            SessionLog.w(TAG, "TCP Receiver stream connection disconnected: ${e.message}")
                        }
                    } finally {
                        try { clientSocket?.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (isListening) {
                    SessionLog.e(TAG, "TCP Receiver listener thread exception", e)
                }
            }
        }
    }

    private fun tickStats() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastStatsResetTime
        if (elapsed >= 1000L) {
            currentFps = (windowReceivedFrames * 1000f) / elapsed
            currentBitrateMbps = (windowBytesReceived * 8f) / (elapsed * 1000f)

            val stats = StreamStats(
                isReceiving = true,
                fps = currentFps,
                bitrateMbps = currentBitrateMbps,
                latencyMs = 0L,
                totalFrames = totalReceivedFrames,
                droppedFrames = 0L,
                packetLossPercent = 0f,
                lossTimeoutPercent = 0f,
                lossEvictedPercent = 0f,
                lossNetworkPercent = lastReportedNetworkLossPercent,
                rttMs = lastReportedRttMs,
                inFlightBytes = 0L,
                statsTimestampMs = now
            )

            onStatsUpdated?.invoke(stats)

            windowReceivedFrames = 0
            windowBytesReceived = 0
            lastStatsResetTime = now
        }
    }

    override fun stop() {
        isListening = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serverSocket = null
        totalReceivedFrames = 0
        totalBytesReceived = 0
        windowReceivedFrames = 0
        windowBytesReceived = 0
    }

    companion object {
        private const val TAG = "TcpReceiver"
    }
}
