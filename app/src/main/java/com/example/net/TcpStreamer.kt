package com.example.net

import com.example.log.AppLogger as SessionLog
import com.example.model.VideoCodec
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class TcpStreamer : IStreamer {
    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var dataOutputStream: DataOutputStream? = null
    private var targetIp: String = "127.0.0.1"
    private var targetPort: Int = 8888
    private val sendLock = Object()

    private var videoSeqCounter = 0
    private var audioSeqCounter = 0
    private var firstVideoQueuedLogged = false
    private var firstAudioQueuedLogged = false

    @Volatile
    private var isConnected = false
    private var connectThread: Thread? = null

    override var onRequestKeyframe: (() -> Unit)? = null

    override fun start(targetIp: String, targetPort: Int) {
        stop()
        this.targetIp = targetIp
        this.targetPort = targetPort
        this.isConnected = true

        connectThread = thread(start = true, name = "TcpStreamerConnectThread") {
            while (isConnected) {
                if (socket == null || socket?.isClosed == true || socket?.isConnected == false) {
                    try {
                        SessionLog.i(TAG, "Connecting TCP stream to $targetIp:$targetPort...")
                        val s = Socket()
                        s.tcpNoDelay = true
                        s.sendBufferSize = 8 * 1024 * 1024
                        s.connect(InetSocketAddress(targetIp, targetPort), 3000)
                        val dos = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 2 * 1024 * 1024))
                        synchronized(sendLock) {
                            socket = s
                            dataOutputStream = dos
                        }
                        SessionLog.i(TAG, "TCP Streamer connected successfully to $targetIp:$targetPort (sndBuf=${s.sendBufferSize})")
                    } catch (e: Exception) {
                        SessionLog.w(TAG, "TCP connection to $targetIp:$targetPort failed: ${e.message}, retrying in 1s")
                        synchronized(sendLock) {
                            closeSocketQuietly()
                        }
                        try { Thread.sleep(1000) } catch (_: Exception) {}
                    }
                } else {
                    try { Thread.sleep(1000) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun closeSocketQuietly() {
        try { dataOutputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        dataOutputStream = null
        socket = null
    }

    override fun sendFrame(
        frameData: ByteArray,
        offset: Int,
        size: Int,
        timestampMs: Long,
        isKeyframe: Boolean,
        isCodecConfig: Boolean,
        codec: VideoCodec
    ) {
        if (!isConnected) return

        synchronized(sendLock) {
            val dos = dataOutputStream ?: return
            try {
                if (!firstVideoQueuedLogged) {
                    firstVideoQueuedLogged = true
                    SessionLog.i(TAG, "first video frame queued over TCP: size=$size timestampMs=$timestampMs key=$isKeyframe config=$isCodecConfig")
                }
                val frameSeq = videoSeqCounter++

                var flags: Byte = 0
                if (isKeyframe) flags = (flags.toInt() or PacketProtocol.FLAG_KEYFRAME.toInt()).toByte()
                if (isCodecConfig) flags = (flags.toInt() or PacketProtocol.FLAG_CODEC_CONFIG.toInt()).toByte()
                if (codec == VideoCodec.H265) flags = (flags.toInt() or PacketProtocol.FLAG_CODEC_HEVC.toInt()).toByte()

                // Header (20 Bytes)
                dos.writeByte(PacketProtocol.MAGIC_0.toInt())
                dos.writeByte(PacketProtocol.MAGIC_1.toInt())
                dos.writeByte(PacketProtocol.VERSION.toInt())
                dos.writeByte(flags.toInt())
                dos.writeInt(frameSeq)
                dos.writeLong(timestampMs)
                dos.writeInt(size)

                // Payload
                dos.write(frameData, offset, size)
                dos.flush()
            } catch (e: Exception) {
                SessionLog.e(TAG, "Error writing video frame over TCP", e)
                closeSocketQuietly()
            }
        }
    }

    override fun sendAudioFrame(
        frameData: ByteArray,
        size: Int,
        timestampMs: Long,
        isCodecConfig: Boolean
    ) {
        if (!isConnected) return

        synchronized(sendLock) {
            val dos = dataOutputStream ?: return
            try {
                if (!firstAudioQueuedLogged) {
                    firstAudioQueuedLogged = true
                    SessionLog.i(TAG, "first audio frame queued over TCP: size=$size timestampMs=$timestampMs config=$isCodecConfig")
                }
                val frameSeq = audioSeqCounter++

                var flags: Byte = PacketProtocol.FLAG_AUDIO
                if (isCodecConfig) flags = (flags.toInt() or PacketProtocol.FLAG_CODEC_CONFIG.toInt()).toByte()

                // Header (20 Bytes)
                dos.writeByte(PacketProtocol.MAGIC_0.toInt())
                dos.writeByte(PacketProtocol.MAGIC_1.toInt())
                dos.writeByte(PacketProtocol.VERSION.toInt())
                dos.writeByte(flags.toInt())
                dos.writeInt(frameSeq)
                dos.writeLong(timestampMs)
                dos.writeInt(size)

                // Payload
                dos.write(frameData, 0, size)
                dos.flush()
            } catch (e: Exception) {
                SessionLog.e(TAG, "Error writing audio frame over TCP", e)
                closeSocketQuietly()
            }
        }
    }

    override fun sendStatsBeacon(rttMs: Int, lossPercent: Float) {
        if (!isConnected) return
        val lossBp = (lossPercent * 100f).toInt().coerceIn(0, 10000)

        synchronized(sendLock) {
            val dos = dataOutputStream ?: return
            try {
                val flags = PacketProtocol.FLAG_PING_STATS

                // Header (20 Bytes)
                dos.writeByte(PacketProtocol.MAGIC_0.toInt())
                dos.writeByte(PacketProtocol.MAGIC_1.toInt())
                dos.writeByte(PacketProtocol.VERSION.toInt())
                dos.writeByte(flags.toInt())
                dos.writeInt(0)
                dos.writeLong(0L)
                dos.writeInt(8) // 8 bytes payload

                // Payload (8 Bytes)
                dos.writeInt(rttMs.coerceAtLeast(0))
                dos.writeInt(lossBp)
                dos.flush()
            } catch (e: Exception) {
                SessionLog.w(TAG, "sendStatsBeacon over TCP failed: ${e.message}")
                closeSocketQuietly()
            }
        }
    }

    override fun stop() {
        isConnected = false
        connectThread?.interrupt()
        connectThread = null
        synchronized(sendLock) {
            closeSocketQuietly()
        }
    }

    companion object {
        private const val TAG = "TcpStreamer"
    }
}
