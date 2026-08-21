package com.cath.screencast.net

import com.cath.screencast.log.AppLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

import com.cath.screencast.model.VideoCodec

class UdpFecStreamer : IStreamer {
    private val TAG = "UdpFecStreamer"
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 0
    
    @Volatile
    var isStreaming = false
        private set
    // Set to true once socket + targetAddress are both initialised.
    // sendThread gates on this to avoid busy-waiting on a null socket.
    @Volatile
    private var ready = false

    private val MAX_PAYLOAD = 1300
    private val FEC_GROUP_SIZE = 10
    private val FEC_LONGS = (MAX_PAYLOAD + 7) / 8
    
    override var onRequestKeyframe: (() -> Unit)? = null
    
    private var sendThread: Thread? = null

    private class FrameTask {
        var data: ByteArray = ByteArray(0)
        var size: Int = 0
        var offset: Int = 0
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
        if (isStreaming) return
        isStreaming = true
        ready = false
        this.targetPort = port
        taskQueue.clear()

        thread(name = "UdpStreamerThread") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            try {
                val s = DatagramSocket()
                s.sendBufferSize = 8 * 1024 * 1024
                try {
                    s.trafficClass = 0xB8 // DSCP EF (Expedited Forwarding) -> WMM AC_VO (Highest priority Wi-Fi EDCA queue)
                } catch (_: Exception) {}
                socket = s
                targetAddress = InetAddress.getByName(targetIp)
                ready = true
                AppLogger.i(TAG, "UDP Streamer started targeting $targetIp:$port (trafficClass=0xB8)")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error starting UDP streamer", e)
                isStreaming = false
            }
        }

        sendThread = thread(start = true, name = "UdpStreamerSendThread") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val packetBuf = ByteBuffer.allocate(28 + MAX_PAYLOAD)
            val fecPayload = ByteArray(MAX_PAYLOAD)
            val fecLongArray = LongArray(FEC_LONGS)
            // Single fragment scratch buffer (frames are processed
            // sequentially in this thread).
            val fragData = ByteArray(28 + MAX_PAYLOAD)
            val fragBuf = ByteBuffer.wrap(fragData)
            val reusablePacket = DatagramPacket(fragData, 0)

            while (isStreaming) {
                try {
                    val task = taskQueue.take()
                    val sock = socket
                    val addr = targetAddress
                    if (ready && sock != null && addr != null) {
                        try {
                            reusablePacket.address = addr
                            reusablePacket.port = targetPort

                            if (task.isStreamStop) {
                                packetBuf.clear()
                                packetBuf.put(PacketProtocol.buildStreamStopPacket())
                                reusablePacket.setData(packetBuf.array(), 0, PacketProtocol.HEADER_SIZE)
                                for (i in 0 until 10) {
                                    sock.send(reusablePacket)
                                }
                            } else if (task.isBeacon) {
                                val flags = 64 // 64 = Beacon
                                packetBuf.clear()
                                packetBuf.putInt(0x55445056)
                                packetBuf.putInt(0)
                                packetBuf.putLong(0L)
                                packetBuf.put(flags.toByte())
                                packetBuf.putShort(0.toShort())
                                packetBuf.putShort(1.toShort())
                                packetBuf.putInt(8)
                                packetBuf.put(0.toByte())
                                packetBuf.put(0.toByte())
                                packetBuf.put(0.toByte())
                                
                                packetBuf.putInt(task.rttMs.coerceAtLeast(0))
                                val lossBp = (task.lossPercent * 100f).toInt().coerceIn(0, 10000)
                                packetBuf.putInt(lossBp)
                                
                                reusablePacket.setData(packetBuf.array(), 0, 28 + 8)
                                sock.send(reusablePacket)
                            } else if (task.isAudio) {
                                var flags = 16 // 16 = Audio
                                if (task.isCodecConfig) flags = flags or 32
                                
                                val seq = audioSeqCounter++
                                
                                packetBuf.clear()
                                packetBuf.putInt(0x55445056)
                                packetBuf.putInt(seq)
                                packetBuf.putLong(task.timestampMs)
                                packetBuf.put(flags.toByte())
                                packetBuf.putShort(0.toShort()) // fragIndex
                                packetBuf.putShort(1.toShort()) // totalFragments
                                packetBuf.putInt(task.size) // frameSize
                                packetBuf.put(0.toByte())
                                packetBuf.put(0.toByte())
                                packetBuf.put(0.toByte()) // pad
                                packetBuf.put(task.data, 0, task.size)
                                
                                reusablePacket.setData(packetBuf.array(), 0, 28 + task.size)
                                sock.send(reusablePacket)
                            } else {
                                val frameSize = task.size
                                val totalFragments = (frameSize + MAX_PAYLOAD - 1) / MAX_PAYLOAD
                                var flags = 0
                                if (task.isKeyframe) flags = flags or 1
                                if (task.isCodecConfig) flags = flags or 2
                                if (task.codec == VideoCodec.H265) flags = flags or 4
                                
                                val seq = videoSeqCounter++
                                var fecGroupId = 0
                                
                                for (i in 0 until totalFragments) {
                                    val fragOffset = task.offset + i * MAX_PAYLOAD
                                    val length = Math.min(MAX_PAYLOAD, frameSize - i * MAX_PAYLOAD)

                                    // Accumulate FEC across FEC_GROUP_SIZE fragments using 64-bit Long SIMD operations
                                    if (i % FEC_GROUP_SIZE == 0) {
                                        fecLongArray.fill(0L)
                                    }
                                    val longsCount = length / 8
                                    var srcIdx = fragOffset
                                    for (k in 0 until longsCount) {
                                        val word = ((task.data[srcIdx].toLong() and 0xFF) shl 56) or
                                                   ((task.data[srcIdx + 1].toLong() and 0xFF) shl 48) or
                                                   ((task.data[srcIdx + 2].toLong() and 0xFF) shl 40) or
                                                   ((task.data[srcIdx + 3].toLong() and 0xFF) shl 32) or
                                                   ((task.data[srcIdx + 4].toLong() and 0xFF) shl 24) or
                                                   ((task.data[srcIdx + 5].toLong() and 0xFF) shl 16) or
                                                   ((task.data[srcIdx + 6].toLong() and 0xFF) shl 8) or
                                                   (task.data[srcIdx + 7].toLong() and 0xFF)
                                        fecLongArray[k] = fecLongArray[k] xor word
                                        srcIdx += 8
                                    }
                                    val rem = length % 8
                                    if (rem > 0) {
                                        var tailWord = 0L
                                        for (b in 0 until rem) {
                                            tailWord = tailWord or ((task.data[srcIdx + b].toLong() and 0xFF) shl ((7 - b) * 8))
                                        }
                                        fecLongArray[longsCount] = fecLongArray[longsCount] xor tailWord
                                    }

                                    // 1. Send Data Fragment
                                    fragBuf.clear()
                                    fragBuf.putInt(0x55445056) // magic
                                    fragBuf.putInt(seq)
                                    fragBuf.putLong(task.timestampMs)
                                    fragBuf.put(flags.toByte())
                                    fragBuf.putShort(i.toShort())
                                    fragBuf.putShort(totalFragments.toShort())
                                    fragBuf.putInt(frameSize)
                                    fragBuf.put(FEC_GROUP_SIZE.toByte()) // byte 25: fecGroupSize
                                    fragBuf.put(0.toByte())
                                    fragBuf.put(0.toByte()) // pad to 28 bytes
                                    fragBuf.put(task.data, fragOffset, length)

                                    reusablePacket.setData(fragData, 0, 28 + length)
                                    sock.send(reusablePacket)

                                    // Sub-burst micro-pacing (25µs) to prevent Wi-Fi router AP queue overflow on large motion frames (>16 frags)
                                    if (totalFragments > 16 && (i + 1) % 8 == 0 && (i + 1) < totalFragments) {
                                        java.util.concurrent.locks.LockSupport.parkNanos(25_000)
                                    }

                                    // 2. Interleave FEC: emit FEC packet immediately upon group completion
                                    val isEndOfGroup = ((i + 1) % FEC_GROUP_SIZE == 0) || (i == totalFragments - 1)
                                    if (isEndOfGroup) {
                                        for (k in 0 until FEC_LONGS) {
                                            val word = fecLongArray[k]
                                            val byteOffset = k * 8
                                            if (byteOffset + 7 < MAX_PAYLOAD) {
                                                fecPayload[byteOffset] = (word ushr 56).toByte()
                                                fecPayload[byteOffset + 1] = (word ushr 48).toByte()
                                                fecPayload[byteOffset + 2] = (word ushr 40).toByte()
                                                fecPayload[byteOffset + 3] = (word ushr 32).toByte()
                                                fecPayload[byteOffset + 4] = (word ushr 24).toByte()
                                                fecPayload[byteOffset + 5] = (word ushr 16).toByte()
                                                fecPayload[byteOffset + 6] = (word ushr 8).toByte()
                                                fecPayload[byteOffset + 7] = (word and 0xFF).toByte()
                                            } else {
                                                for (b in 0 until (MAX_PAYLOAD - byteOffset)) {
                                                    fecPayload[byteOffset + b] = (word ushr ((7 - b) * 8)).toByte()
                                                }
                                            }
                                        }

                                        packetBuf.clear()
                                        packetBuf.putInt(0x55445056)
                                        packetBuf.putInt(seq)
                                        packetBuf.putLong(task.timestampMs)
                                        val fecFlags = flags or 8 // FEC flag
                                        packetBuf.put(fecFlags.toByte())
                                        packetBuf.putShort(fecGroupId.toShort())
                                        packetBuf.putShort(totalFragments.toShort())
                                        packetBuf.putInt(frameSize)
                                        packetBuf.put(FEC_GROUP_SIZE.toByte()) // byte 25: fecGroupSize
                                        packetBuf.put(0.toByte())
                                        packetBuf.put(0.toByte()) // pad
                                        packetBuf.put(fecPayload)

                                        reusablePacket.setData(packetBuf.array(), 0, 28 + MAX_PAYLOAD)
                                        sock.send(reusablePacket)
                                        fecGroupId++
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // AppLogger.e(TAG, "Error sending frame", e)
                        }
                    }
                    recycleTask(task)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }
    
    private var videoSeqCounter = 0
    private var audioSeqCounter = 0
    
    override fun sendFrame(
        frameData: ByteArray,
        offset: Int,
        size: Int,
        timestampMs: Long,
        isKeyframe: Boolean,
        isCodecConfig: Boolean,
        codec: VideoCodec
    ) {
        if (!isStreaming) return
        
        val task = obtainTask(size)
        System.arraycopy(frameData, offset, task.data, 0, size)
        task.offset = 0
        task.size = size
        task.timestampMs = timestampMs
        task.isKeyframe = isKeyframe
        task.isCodecConfig = isCodecConfig
        task.codec = codec
        task.isAudio = false
        task.isBeacon = false
        
        if (!taskQueue.offer(task)) {
            recycleTask(task)
        }
    }
    
    override fun sendAudioFrame(
        frameData: ByteArray,
        size: Int,
        timestampMs: Long,
        isCodecConfig: Boolean
    ) {
        if (!isStreaming) return
        
        val task = obtainTask(size)
        System.arraycopy(frameData, 0, task.data, 0, size)
        task.size = size
        task.timestampMs = timestampMs
        task.isCodecConfig = isCodecConfig
        task.isAudio = true
        task.isBeacon = false
        
        if (!taskQueue.offer(task)) {
            recycleTask(task)
        }
    }
    
    override fun sendStatsBeacon(rttMs: Int, lossPercent: Float) {
        if (!isStreaming) return
        
        val task = obtainTask(0)
        task.isBeacon = true
        task.rttMs = rttMs
        task.lossPercent = lossPercent
        
        if (!taskQueue.offer(task)) {
            recycleTask(task)
        }
    }
    
    override fun sendStreamStopSignal() {
        if (!isStreaming) return
        taskQueue.clear()
        val task = obtainTask(0)
        task.isStreamStop = true
        if (!taskQueue.offer(task)) {
            recycleTask(task)
        }
    }

    private fun spinPacingNanos(nanos: Long) {
        val target = System.nanoTime() + nanos
        while (System.nanoTime() < target) {
            Thread.onSpinWait()
        }
    }
    
    override fun stop() {
        isStreaming = false
        ready = false
        sendThread?.interrupt()
        val sendThreadRef = sendThread
        if (sendThreadRef != null && sendThreadRef.isAlive) {
            try {
                sendThreadRef.join(2_000L)
                if (sendThreadRef.isAlive) {
                    AppLogger.w(TAG, "UdpFecStreamer sendThread did not terminate within 2s; closing socket anyway")
                }
            } catch (_: InterruptedException) {
                // Caller interrupted; proceed with cleanup.
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error joining UdpFecStreamer sendThread: ${e.message}")
            }
        }
        sendThread = null
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        taskQueue.clear()
    }
}
