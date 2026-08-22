package com.cath.screencast.net

import com.cath.screencast.log.AppLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

import com.cath.screencast.model.VideoCodec

class UdpFecStreamer(
    @Volatile private var targetBitrateKbps: Int = 16000,
    @Volatile private var targetFps: Int = 90
) : IStreamer {
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
    private val FEC_GROUP_SIZE = 8
    private val FEC_LONGS = (MAX_PAYLOAD + 7) / 8
    
    override var onRequestKeyframe: (() -> Unit)? = null
    
    private var sendThread: Thread? = null

    override fun setBitrate(bitrateKbps: Int, fps: Int) {
        if (bitrateKbps > 0) {
            this.targetBitrateKbps = bitrateKbps
        }
        if (fps > 0) {
            this.targetFps = fps
        }
        AppLogger.i(TAG, "Global Pacer updated: targetBitrate=${targetBitrateKbps}Kbps, targetFps=$targetFps")
    }

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

    private var recvThread: Thread? = null

    // Pacer state: scheduled nanosecond timestamp when next packet may be transmitted
    private var nextSendTimeNs = 0L
    private val BURST_WINDOW_NS = 500_000L // 0.5ms burst credit (~6KB burst headroom to protect Wi-Fi hardware FIFO)

    /**
     * Dynamically computes the target pacing transmission rate (in bits per second) for a frame or task.
     * Incorporates baseline rate (2.5x headroom) and adapts for large keyframes or congested queues.
     */
    private fun computePacingRateBps(frameTotalWireBytes: Int, queueDepth: Int): Long {
        val bitrateBps = targetBitrateKbps.toLong() * 1000L
        val fps = targetFps.coerceIn(30, 144)
        val frameIntervalMs = (1000f / fps).coerceAtLeast(6f)

        // Baseline pacing rate: 2.5x target bitrate with a 50 Mbps floor
        val basePacingRateBps = maxOf(50_000_000L, (bitrateBps * 2.5).toLong())
        // Burst rate ceiling (up to 200 Mbps)
        val maxPacingRateBps = maxOf(100_000_000L, minOf(200_000_000L, (bitrateBps * 4.0).toLong()))

        val isLargeOrCongested = frameTotalWireBytes > 40 * 1024 || queueDepth > 0
        if (!isLargeOrCongested) {
            return basePacingRateBps
        }

        // Target drain time: 50% of frame duration, clamped between 3ms and 10ms
        val targetDrainSec = ((frameIntervalMs * 0.50f).coerceIn(3f, 10f)) / 1000.0
        val neededDrainRateBps = (frameTotalWireBytes * 8L / targetDrainSec).toLong()

        return neededDrainRateBps.coerceIn(basePacingRateBps, maxPacingRateBps)
    }

    /**
     * Token Bucket / Burst Pacer: transmits a UDP datagram while honoring burst credit
     * and avoiding syscall duration accumulation.
     */
    private fun paceAndSend(
        sock: DatagramSocket,
        packet: DatagramPacket,
        payloadSize: Int,
        pacingRateBps: Long
    ) {
        val nowNs = System.nanoTime()
        // Clamp nextSendTimeNs so idle gaps between frames provide burst credit up to BURST_WINDOW_NS
        val minAllowedTimeNs = nowNs - BURST_WINDOW_NS
        if (nextSendTimeNs < minAllowedTimeNs) {
            nextSendTimeNs = minAllowedTimeNs
        }

        val waitNanos = nextSendTimeNs - nowNs
        if (waitNanos > 0) {
            paceSleepNanos(waitNanos)
        }

        sock.send(packet)

        // Account for total on-the-wire UDP frame: payload + 28 bytes IP/UDP headers
        val totalWireBytes = payloadSize + 28
        val packetIntervalNanos = (totalWireBytes * 8L * 1_000_000_000L) / pacingRateBps.coerceAtLeast(10_000_000L)
        
        // Virtual schedule progression: advances time without stacking syscall latency
        nextSendTimeNs += packetIntervalNanos
    }

    /**
     * High-precision hybrid pacer sleep:
     * - Uses LockSupport.parkNanos for large delays (>1.5ms) to conserve CPU.
     * - Uses Thread.onSpinWait() for sub-millisecond precision without OS scheduler latency.
     */
    private fun paceSleepNanos(nanos: Long) {
        if (nanos <= 0) return
        val target = System.nanoTime() + nanos
        if (nanos > 1_500_000L) {
            LockSupport.parkNanos(nanos - 1_000_000L)
        }
        while (System.nanoTime() < target) {
            Thread.onSpinWait()
        }
    }

    override fun start(targetIp: String, port: Int) {
        if (isStreaming) return
        isStreaming = true
        ready = false
        this.targetPort = port
        taskQueue.clear()
        nextSendTimeNs = System.nanoTime()

        thread(name = "UdpStreamerThread") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            try {
                val s = DatagramSocket()
                s.sendBufferSize = 8 * 1024 * 1024
                try {
                    s.trafficClass = 0xA0 // DSCP AF41 -> WMM AC_VI (Video Access Category, 256-packet buffer, prevents Voice queue tail-drops)
                } catch (_: Exception) {}
                socket = s
                targetAddress = InetAddress.getByName(targetIp)
                ready = true
                AppLogger.i(TAG, "UDP Streamer started targeting $targetIp:$port (trafficClass=0xA0 AC_VI, FEC_GROUP_SIZE=$FEC_GROUP_SIZE, targetBitrate=${targetBitrateKbps}Kbps, targetFps=$targetFps)")

                // Start control packet listener (IDR request / Ping echo) on stream socket
                recvThread = thread(start = true, name = "UdpFecStreamerRecvThread") {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    val buf = ByteArray(128)
                    val dp = DatagramPacket(buf, buf.size)
                    while (isStreaming && !s.isClosed) {
                        try {
                            dp.length = buf.size
                            s.receive(dp)
                            val len = dp.length
                            if (len >= PacketProtocol.HEADER_SIZE) {
                                val probe = PacketProtocol.readProbeSequence(buf, len)
                                if (probe != null) {
                                    if (probe.isIdrRequest) {
                                        AppLogger.i(TAG, "Received instant IDR Keyframe Request (PLI) on stream socket! Forcing keyframe...")
                                        onRequestKeyframe?.invoke()
                                    } else if (!probe.isReply) {
                                        val reply = PacketProtocol.buildPingReplyPacket(probe.seq, probe.echoedNanos)
                                        s.send(DatagramPacket(reply, reply.size, dp.address, dp.port))
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            if (!isStreaming) break
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error starting UDP streamer", e)
                isStreaming = false
            }
        }

        sendThread = thread(start = true, name = "UdpStreamerSendThread") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val packetBuf = ByteBuffer.allocate(28 + MAX_PAYLOAD).order(java.nio.ByteOrder.nativeOrder())
            val MAX_FEC_GROUPS = 64
            val fecPayloads = Array(MAX_FEC_GROUPS) { ByteArray(MAX_PAYLOAD) }
            val fecBufs = Array(MAX_FEC_GROUPS) { ByteBuffer.wrap(fecPayloads[it]).order(java.nio.ByteOrder.nativeOrder()) }
            val fecGroupInitialized = BooleanArray(MAX_FEC_GROUPS)
            val fecGroupLengths = IntArray(MAX_FEC_GROUPS)

            // Single fragment scratch buffer (frames are processed sequentially in this thread).
            val fragData = ByteArray(28 + MAX_PAYLOAD)
            val fragBuf = ByteBuffer.wrap(fragData)
            val reusablePacket = DatagramPacket(fragData, 0)

            var frameCount = 0L
            var lastLogTime = System.currentTimeMillis()

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
                                packetBuf.order(java.nio.ByteOrder.BIG_ENDIAN)
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
                                packetBuf.order(java.nio.ByteOrder.BIG_ENDIAN)
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
                                val numFecGroups = if (totalFragments > 1) {
                                    ((totalFragments + FEC_GROUP_SIZE - 1) / FEC_GROUP_SIZE).coerceIn(1, MAX_FEC_GROUPS)
                                } else {
                                    0
                                }

                                val taskBuf = ByteBuffer.wrap(task.data).order(java.nio.ByteOrder.nativeOrder())
                                val totalFrameWireBytes = frameSize + (numFecGroups * MAX_PAYLOAD) + ((totalFragments + numFecGroups) * 28)
                                val taskPacingRateBps = computePacingRateBps(totalFrameWireBytes, taskQueue.size)

                                for (g in 0 until numFecGroups) {
                                    fecGroupInitialized[g] = false
                                }

                                // 1. Send all data fragments and accumulate Interleaved XOR parity: group = i % numFecGroups
                                for (i in 0 until totalFragments) {
                                    val fragOffset = task.offset + i * MAX_PAYLOAD
                                    val length = Math.min(MAX_PAYLOAD, frameSize - i * MAX_PAYLOAD)

                                    if (numFecGroups > 0) {
                                        val g = i % numFecGroups
                                        val fecPayload = fecPayloads[g]
                                        val fecBuf = fecBufs[g]

                                        if (!fecGroupInitialized[g]) {
                                            System.arraycopy(task.data, fragOffset, fecPayload, 0, length)
                                            if (length < MAX_PAYLOAD) {
                                                fecPayload.fill(0, length, MAX_PAYLOAD)
                                            }
                                            fecGroupInitialized[g] = true
                                        } else {
                                            val longsCount = length ushr 3
                                            for (k in 0 until longsCount) {
                                                val pos = k shl 3
                                                fecBuf.putLong(pos, fecBuf.getLong(pos) xor taskBuf.getLong(fragOffset + pos))
                                            }
                                            val remStart = longsCount shl 3
                                            for (b in remStart until length) {
                                                fecPayload[b] = (fecPayload[b].toInt() xor task.data[fragOffset + b].toInt()).toByte()
                                            }
                                        }
                                    }

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
                                    paceAndSend(sock, reusablePacket, length, taskPacingRateBps)
                                }

                                // 2. Send Interleaved FEC Parity Packets immediately following data fragments
                                if (numFecGroups > 0) {
                                    for (g in 0 until numFecGroups) {
                                        if (!fecGroupInitialized[g]) continue
                                        packetBuf.clear()
                                        packetBuf.order(java.nio.ByteOrder.BIG_ENDIAN)
                                        packetBuf.putInt(0x55445056)
                                        packetBuf.putInt(seq)
                                        packetBuf.putLong(task.timestampMs)
                                        val fecFlags = flags or 8 // FEC flag
                                        packetBuf.put(fecFlags.toByte())
                                        packetBuf.putShort(g.toShort()) // fragIndex = groupId
                                        packetBuf.putShort(totalFragments.toShort())
                                        packetBuf.putInt(frameSize)
                                        packetBuf.put(FEC_GROUP_SIZE.toByte()) // byte 25: fecGroupSize
                                        packetBuf.put(0.toByte())
                                        packetBuf.put(0.toByte()) // pad
                                        packetBuf.put(fecPayloads[g], 0, MAX_PAYLOAD)

                                        reusablePacket.setData(packetBuf.array(), 0, 28 + MAX_PAYLOAD)
                                        paceAndSend(sock, reusablePacket, MAX_PAYLOAD, taskPacingRateBps)
                                    }
                                }

                                frameCount++
                                val now = System.currentTimeMillis()
                                if (now - lastLogTime >= 3000) {
                                    lastLogTime = now
                                    AppLogger.i(TAG, "Pacer Stats: frames=$frameCount, targetRate=${targetBitrateKbps}Kbps, activePacingRate=${taskPacingRateBps / 1000}Kbps, queue=${taskQueue.size}")
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
    
    override fun stop() {
        isStreaming = false
        ready = false
        recvThread?.interrupt()
        recvThread = null
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
