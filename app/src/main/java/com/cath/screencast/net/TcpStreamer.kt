package com.cath.screencast.net

import com.cath.screencast.log.AppLogger as SessionLog
import com.cath.screencast.model.VideoCodec
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class TcpStreamer : IStreamer {
    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var dataOutputStream: DataOutputStream? = null
    private var targetIp: String = "127.0.0.1"
    private var targetPort: Int = 8888

    private var videoSeqCounter = 0
    private var audioSeqCounter = 0
    private var firstVideoQueuedLogged = false
    private var firstAudioQueuedLogged = false

    @Volatile
    private var cachedAudioCodecConfig: ByteArray? = null
    @Volatile
    private var cachedAudioCodecConfigSize: Int = 0

    @Volatile
    private var cachedVideoCodecConfig: ByteArray? = null
    @Volatile
    private var cachedVideoCodecConfigSize: Int = 0
    @Volatile
    private var cachedVideoCodecConfigCodec: VideoCodec = VideoCodec.H264

    @Volatile
    private var isConnected = false
    private var connectThread: Thread? = null
    private var sendThread: Thread? = null

    override var onRequestKeyframe: (() -> Unit)? = null

    private class FrameTask {
        var data: ByteArray = ByteArray(0)
        var size: Int = 0
        var timestampMs: Long = 0
        var isKeyframe: Boolean = false
        var isCodecConfig: Boolean = false
        var isAudio: Boolean = false
        var codec: VideoCodec = VideoCodec.H264
        var isBeacon: Boolean = false
        var rttMs: Int = 0
        var lossPercent: Float = 0f
        var isStreamStop: Boolean = false
    }

    private val taskQueue = ArrayBlockingQueue<FrameTask>(100)
    private val taskPool = ConcurrentLinkedQueue<FrameTask>()

    private fun obtainTask(minSize: Int): FrameTask {
        val task = taskPool.poll() ?: FrameTask()
        if (task.data.size < minSize) {
            task.data = ByteArray(Math.max(minSize, 512 * 1024))
        }
        return task
    }

    private fun recycleTask(task: FrameTask) {
        taskPool.offer(task)
    }

    override fun start(targetIp: String, port: Int) {
        stop()
        this.targetIp = targetIp
        this.targetPort = port
        this.isConnected = true
        taskQueue.clear()

        connectThread = thread(start = true, name = "TcpStreamerConnectThread") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            while (isConnected) {
                if (socket == null || socket?.isClosed == true || socket?.isConnected == false) {
                    try {
                        SessionLog.i(TAG, "Connecting TCP stream to $targetIp:$targetPort...")
                        val s = Socket()
                        s.tcpNoDelay = true
                        s.sendBufferSize = 512 * 1024
                        s.connect(InetSocketAddress(targetIp, targetPort), 3000)
                        val dos = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 32 * 1024))
                        socket = s
                        dataOutputStream = dos
                        SessionLog.i(TAG, "TCP Streamer connected successfully to $targetIp:$targetPort (sndBuf=${s.sendBufferSize})")

                        // Immediately send cached Audio CodecConfig if available
                        val audioCfg = cachedAudioCodecConfig
                        if (audioCfg != null) {
                            try {
                                SessionLog.i(TAG, "TCP connected: sending cached audio CodecConfig (${cachedAudioCodecConfigSize} bytes)")
                                dos.writeByte(PacketProtocol.MAGIC_0.toInt())
                                dos.writeByte(PacketProtocol.MAGIC_1.toInt())
                                dos.writeByte(PacketProtocol.VERSION.toInt())
                                dos.writeByte((PacketProtocol.FLAG_AUDIO.toInt() or PacketProtocol.FLAG_CODEC_CONFIG.toInt()))
                                dos.writeInt(audioSeqCounter++)
                                dos.writeLong(0L)
                                dos.writeInt(cachedAudioCodecConfigSize)
                                dos.write(audioCfg, 0, cachedAudioCodecConfigSize)
                                dos.flush()
                            } catch (e: Exception) {
                                SessionLog.w(TAG, "Error sending cached audio CodecConfig: ${e.message}")
                            }
                        }

                        // Immediately send cached Video CodecConfig if available
                        val videoCfg = cachedVideoCodecConfig
                        if (videoCfg != null) {
                            try {
                                SessionLog.i(TAG, "TCP connected: sending cached video CodecConfig (${cachedVideoCodecConfigSize} bytes)")
                                var flags = PacketProtocol.FLAG_CODEC_CONFIG.toInt()
                                if (cachedVideoCodecConfigCodec == VideoCodec.H265) {
                                    flags = flags or PacketProtocol.FLAG_CODEC_HEVC.toInt()
                                }
                                dos.writeByte(PacketProtocol.MAGIC_0.toInt())
                                dos.writeByte(PacketProtocol.MAGIC_1.toInt())
                                dos.writeByte(PacketProtocol.VERSION.toInt())
                                dos.writeByte(flags)
                                dos.writeInt(videoSeqCounter++)
                                dos.writeLong(0L)
                                dos.writeInt(cachedVideoCodecConfigSize)
                                dos.write(videoCfg, 0, cachedVideoCodecConfigSize)
                                dos.flush()
                            } catch (e: Exception) {
                                SessionLog.w(TAG, "Error sending cached video CodecConfig: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        SessionLog.w(TAG, "TCP connection to $targetIp:$targetPort failed: ${e.message}, retrying in 1s")
                        closeSocketQuietly()
                        try { Thread.sleep(1000) } catch (_: Exception) {}
                    }
                } else {
                    try { Thread.sleep(1000) } catch (_: Exception) {}
                }
            }
        }

        sendThread = thread(start = true, name = "TcpStreamerSendThread") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            while (isConnected) {
                try {
                    val task = taskQueue.take()
                    val dos = dataOutputStream
                    if (dos != null) {
                        try {
                            if (task.isStreamStop) {
                                val flags = PacketProtocol.FLAG_STREAM_STOP
                                dos.writeByte(PacketProtocol.MAGIC_0.toInt())
                                dos.writeByte(PacketProtocol.MAGIC_1.toInt())
                                dos.writeByte(PacketProtocol.VERSION.toInt())
                                dos.writeByte(flags.toInt())
                                dos.writeInt(0)
                                dos.writeLong(0L)
                                dos.writeInt(0)
                                dos.flush()
                            } else if (task.isBeacon) {
                                val flags = PacketProtocol.FLAG_PING_STATS
                                dos.writeByte(PacketProtocol.MAGIC_0.toInt())
                                dos.writeByte(PacketProtocol.MAGIC_1.toInt())
                                dos.writeByte(PacketProtocol.VERSION.toInt())
                                dos.writeByte(flags.toInt())
                                dos.writeInt(0)
                                dos.writeLong(0L)
                                dos.writeInt(8)
                                dos.writeInt(task.rttMs)
                                val lossBp = (task.lossPercent * 100f).toInt().coerceIn(0, 10000)
                                dos.writeInt(lossBp)
                                dos.flush()
                            } else if (task.isAudio) {
                                val frameSeq = audioSeqCounter++
                                var flags: Byte = PacketProtocol.FLAG_AUDIO
                                if (task.isCodecConfig) flags = (flags.toInt() or PacketProtocol.FLAG_CODEC_CONFIG.toInt()).toByte()

                                dos.writeByte(PacketProtocol.MAGIC_0.toInt())
                                dos.writeByte(PacketProtocol.MAGIC_1.toInt())
                                dos.writeByte(PacketProtocol.VERSION.toInt())
                                dos.writeByte(flags.toInt())
                                dos.writeInt(frameSeq)
                                dos.writeLong(task.timestampMs)
                                dos.writeInt(task.size)
                                dos.write(task.data, 0, task.size)
                                dos.flush()
                            } else {
                                val frameSeq = videoSeqCounter++
                                if (frameSeq <= 5) {
                                    SessionLog.i(TAG, "TCP send video frame: seq=$frameSeq size=${task.size}")
                                }
                                var flags: Byte = 0
                                if (task.isKeyframe) flags = (flags.toInt() or PacketProtocol.FLAG_KEYFRAME.toInt()).toByte()
                                if (task.isCodecConfig) flags = (flags.toInt() or PacketProtocol.FLAG_CODEC_CONFIG.toInt()).toByte()
                                if (task.codec == VideoCodec.H265) flags = (flags.toInt() or PacketProtocol.FLAG_CODEC_HEVC.toInt()).toByte()

                                dos.writeByte(PacketProtocol.MAGIC_0.toInt())
                                dos.writeByte(PacketProtocol.MAGIC_1.toInt())
                                dos.writeByte(PacketProtocol.VERSION.toInt())
                                dos.writeByte(flags.toInt())
                                dos.writeInt(frameSeq)
                                dos.writeLong(task.timestampMs)
                                dos.writeInt(task.size)
                                dos.write(task.data, 0, task.size)
                                dos.flush()
                            }
                        } catch (e: Exception) {
                            SessionLog.e(TAG, "Error writing frame over TCP", e)
                            closeSocketQuietly()
                        }
                    }
                    recycleTask(task)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    SessionLog.e(TAG, "Exception in sendThread", e)
                }
            }
        }
    }

    private fun closeSocketQuietly() {
        // Close socket FIRST to unblock any pending writes/flushes.
        try { socket?.close() } catch (_: Exception) {}
        try { dataOutputStream?.close() } catch (_: Exception) {}
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
        if (isCodecConfig) {
            val copy = ByteArray(size)
            System.arraycopy(frameData, offset, copy, 0, size)
            cachedVideoCodecConfig = copy
            cachedVideoCodecConfigSize = size
            cachedVideoCodecConfigCodec = codec
        }

        if (!isConnected) return
        
        if (!firstVideoQueuedLogged) {
            firstVideoQueuedLogged = true
            SessionLog.i(TAG, "first video frame queued over TCP: size=$size timestampMs=$timestampMs key=$isKeyframe config=$isCodecConfig")
        }

        val task = obtainTask(size)
        System.arraycopy(frameData, offset, task.data, 0, size)
        task.size = size
        task.timestampMs = timestampMs
        task.isKeyframe = isKeyframe
        task.isCodecConfig = isCodecConfig
        task.codec = codec
        task.isAudio = false
        task.isBeacon = false

        if (!taskQueue.offer(task)) {
            SessionLog.w(TAG, "TCP task queue full, dropping video frame!")
            recycleTask(task)
        }
    }

    override fun sendAudioFrame(
        frameData: ByteArray,
        size: Int,
        timestampMs: Long,
        isCodecConfig: Boolean
    ) {
        if (isCodecConfig) {
            val copy = ByteArray(size)
            System.arraycopy(frameData, 0, copy, 0, size)
            cachedAudioCodecConfig = copy
            cachedAudioCodecConfigSize = size
        }

        if (!isConnected) return

        if (!firstAudioQueuedLogged) {
            firstAudioQueuedLogged = true
            SessionLog.i(TAG, "first audio frame queued over TCP: size=$size timestampMs=$timestampMs config=$isCodecConfig")
        }

        val task = obtainTask(size)
        System.arraycopy(frameData, 0, task.data, 0, size)
        task.size = size
        task.timestampMs = timestampMs
        task.isCodecConfig = isCodecConfig
        task.isAudio = true
        task.isBeacon = false

        if (!taskQueue.offer(task)) {
            SessionLog.w(TAG, "TCP task queue full, dropping audio frame!")
            recycleTask(task)
        }
    }

    override fun sendStatsBeacon(rttMs: Int, lossPercent: Float) {
        if (!isConnected) return

        val task = obtainTask(0)
        task.isBeacon = true
        task.rttMs = rttMs
        task.lossPercent = lossPercent

        if (!taskQueue.offer(task)) {
            recycleTask(task)
        }
    }

    override fun sendStreamStopSignal() {
        if (!isConnected) return
        taskQueue.clear()
        val task = obtainTask(0)
        task.isStreamStop = true
        if (!taskQueue.offer(task)) {
            recycleTask(task)
        }
    }

    override fun stop() {
        isConnected = false
        connectThread?.interrupt()
        connectThread = null
        sendThread?.interrupt()
        val sendThreadRef = sendThread
        if (sendThreadRef != null && sendThreadRef.isAlive) {
            try {
                sendThreadRef.join(2_000L)
                if (sendThreadRef.isAlive) {
                    SessionLog.w(TAG, "TcpStreamer sendThread did not terminate within 2s; closing socket anyway")
                }
            } catch (_: InterruptedException) {
                // Caller interrupted; proceed with cleanup.
            } catch (e: Exception) {
                SessionLog.w(TAG, "Error joining TcpStreamer sendThread: ${e.message}")
            }
        }
        sendThread = null
        closeSocketQuietly()
        taskQueue.clear()
    }

    companion object {
        private const val TAG = "TcpStreamer"
    }
}
