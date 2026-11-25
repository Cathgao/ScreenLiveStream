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
    private val socketLock = Object()
    @Volatile
    private var needSendCachedConfig = false
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

    private fun writeCachedConfigsLocked(dos: DataOutputStream) {
        val audioCfg = cachedAudioCodecConfig
        if (audioCfg != null) {
            try {
                SessionLog.i(TAG, "TCP connected: sending cached audio CodecConfig ($cachedAudioCodecConfigSize bytes)")
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

        val videoCfg = cachedVideoCodecConfig
        if (videoCfg != null) {
            try {
                SessionLog.i(TAG, "TCP connected: sending cached video CodecConfig ($cachedVideoCodecConfigSize bytes)")
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
    }

    override fun start(targetIp: String, port: Int) {
        stop()
        this.targetIp = targetIp
        this.targetPort = port
        this.isConnected = true
        taskQueue.clear()

        connectThread = thread(start = true, name = "TcpStreamerConnectThread") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            while (isConnected) {
                val currentSock = socket
                if (currentSock == null || currentSock.isClosed || !currentSock.isConnected) {
                    try {
                        SessionLog.i(TAG, "Connecting TCP stream to $targetIp:$targetPort...")
                        val s = Socket()
                        s.tcpNoDelay = true
                        s.sendBufferSize = 4 * 1024 * 1024
                        s.connect(InetSocketAddress(targetIp, targetPort), 3000)
                        val dos = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 512 * 1024))
                        synchronized(socketLock) {
                            if (!isConnected) {
                                try { s.close() } catch (_: Exception) {}
                                return@thread
                            }
                            socket = s
                            dataOutputStream = dos
                            needSendCachedConfig = true
                            socketLock.notifyAll()
                        }
                        SessionLog.i(TAG, "TCP Streamer connected successfully to $targetIp:$targetPort (sndBuf=${s.sendBufferSize})")
                    } catch (e: Exception) {
                        SessionLog.w(TAG, "TCP connection to $targetIp:$targetPort failed: ${e.message}, retrying in 1s")
                        closeSocketQuietly()
                        try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                    }
                } else {
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                }
            }
        }

        sendThread = thread(start = true, name = "TcpStreamerSendThread") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            val headerData = ByteArray(20)
            val headerBuf = java.nio.ByteBuffer.wrap(headerData)

            while (isConnected) {
                try {
                    // Wait until socket is ready so queued initial frames / CodecConfigs are not discarded during connect handshake
                    var dos = dataOutputStream
                    while (isConnected && dos == null) {
                        synchronized(socketLock) {
                            dos = dataOutputStream
                            if (isConnected && dos == null) {
                                socketLock.wait(200L)
                                dos = dataOutputStream
                            }
                        }
                    }
                    if (!isConnected || dos == null) break

                    val task = taskQueue.take()
                    val activeDos = dataOutputStream
                    if (activeDos != null) {
                        try {
                            if (needSendCachedConfig) {
                                needSendCachedConfig = false
                                writeCachedConfigsLocked(activeDos)
                            }
                            if (task.isStreamStop) {
                                val flags = PacketProtocol.FLAG_STREAM_STOP
                                headerBuf.clear()
                                headerBuf.put(PacketProtocol.MAGIC_0)
                                headerBuf.put(PacketProtocol.MAGIC_1)
                                headerBuf.put(PacketProtocol.VERSION)
                                headerBuf.put(flags)
                                headerBuf.putInt(0)
                                headerBuf.putLong(0L)
                                headerBuf.putInt(0)
                                activeDos.write(headerData, 0, 20)
                                activeDos.flush()
                            } else if (task.isBeacon) {
                                val flags = PacketProtocol.FLAG_PING_STATS
                                headerBuf.clear()
                                headerBuf.put(PacketProtocol.MAGIC_0)
                                headerBuf.put(PacketProtocol.MAGIC_1)
                                headerBuf.put(PacketProtocol.VERSION)
                                headerBuf.put(flags)
                                headerBuf.putInt(0)
                                headerBuf.putLong(0L)
                                headerBuf.putInt(8)
                                activeDos.write(headerData, 0, 20)
                                val lossBp = (task.lossPercent * 100f).toInt().coerceIn(0, 10000)
                                activeDos.writeInt(task.rttMs)
                                activeDos.writeInt(lossBp)
                                activeDos.flush()
                            } else if (task.isAudio) {
                                val frameSeq = audioSeqCounter++
                                var flags: Byte = PacketProtocol.FLAG_AUDIO
                                if (task.isCodecConfig) flags = (flags.toInt() or PacketProtocol.FLAG_CODEC_CONFIG.toInt()).toByte()

                                headerBuf.clear()
                                headerBuf.put(PacketProtocol.MAGIC_0)
                                headerBuf.put(PacketProtocol.MAGIC_1)
                                headerBuf.put(PacketProtocol.VERSION)
                                headerBuf.put(flags)
                                headerBuf.putInt(frameSeq)
                                headerBuf.putLong(task.timestampMs)
                                headerBuf.putInt(task.size)
                                activeDos.write(headerData, 0, 20)
                                if (task.size > 0) {
                                    activeDos.write(task.data, 0, task.size)
                                }
                                activeDos.flush()
                            } else {
                                val frameSeq = videoSeqCounter++
                                if (frameSeq <= 5) {
                                    SessionLog.i(TAG, "TCP send video frame: seq=$frameSeq size=${task.size}")
                                }
                                var flags: Byte = 0
                                if (task.isKeyframe) flags = (flags.toInt() or PacketProtocol.FLAG_KEYFRAME.toInt()).toByte()
                                if (task.isCodecConfig) flags = (flags.toInt() or PacketProtocol.FLAG_CODEC_CONFIG.toInt()).toByte()
                                if (task.codec == VideoCodec.H265) flags = (flags.toInt() or PacketProtocol.FLAG_CODEC_HEVC.toInt()).toByte()

                                headerBuf.clear()
                                headerBuf.put(PacketProtocol.MAGIC_0)
                                headerBuf.put(PacketProtocol.MAGIC_1)
                                headerBuf.put(PacketProtocol.VERSION)
                                headerBuf.put(flags)
                                headerBuf.putInt(frameSeq)
                                headerBuf.putLong(task.timestampMs)
                                headerBuf.putInt(task.size)
                                activeDos.write(headerData, 0, 20)
                                if (task.size > 0) {
                                    activeDos.write(task.data, 0, task.size)
                                }
                                activeDos.flush()
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
        synchronized(socketLock) {
            try { socket?.close() } catch (_: Exception) {}
            try { dataOutputStream?.close() } catch (_: Exception) {}
            dataOutputStream = null
            socket = null
            socketLock.notifyAll()
        }
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
        synchronized(socketLock) {
            socketLock.notifyAll()
        }
        val connectThreadRef = connectThread
        connectThreadRef?.interrupt()
        connectThread = null
        val sendThreadRef = sendThread
        sendThreadRef?.interrupt()
        closeSocketQuietly()
        if (sendThreadRef != null && sendThreadRef.isAlive) {
            try {
                sendThreadRef.join(2_000L)
                if (sendThreadRef.isAlive) {
                    SessionLog.w(TAG, "TcpStreamer sendThread did not terminate within 2s")
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                SessionLog.w(TAG, "Error joining TcpStreamer sendThread: ${e.message}")
            }
        }
        if (connectThreadRef != null && connectThreadRef.isAlive) {
            try {
                connectThreadRef.join(1_000L)
            } catch (_: Exception) {}
        }
        sendThread = null
        taskQueue.clear()
    }

    companion object {
        private const val TAG = "TcpStreamer"
    }
}
