package com.cath.screencast.net

import com.cath.screencast.log.AppLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

import com.cath.screencast.model.StreamStats

class UdpFecReceiver(private val listenPort: Int) : IReceiver {
    private val TAG = "UdpFecReceiver"
    
    @Volatile
    var isListening = false
        private set
        
    private var socket: DatagramSocket? = null
    override var onFrameAssembled: ((ByteArray, Boolean, Boolean, Boolean, Long, Int) -> Unit)? = null
    override var onAudioFrame: ((ByteArray, Boolean, Long) -> Unit)? = null
    override var onReferenceLost: (() -> Unit)? = null
    override var onStatsUpdated: ((StreamStats) -> Unit)? = null
    override var onStreamStop: (() -> Unit)? = null
    override var jitterBufferMs: Int = 0
    
    private val MAX_PAYLOAD = 1300
    
    // Stats
    private var totalReceivedFrames = 0L
    private var totalBytesReceived = 0L
    private var windowReceivedFrames = 0L
    private var windowBytesReceived = 0L
    private var totalFecRecovered = 0L
    private var windowFecRecovered = 0L
    private var totalDroppedFrames = 0L
    private var windowDroppedFrames = 0L
    private var lastStatsResetTime = System.currentTimeMillis()
    private var currentFps = 0f
    private var currentBitrateMbps = 0f
    
    private var lastReportedRttMs = 0
    private var lastReportedLossPercent = 0f

    private var lastSenderAddress: InetAddress? = null
    private var lastSenderPort: Int = 0
    private var lastKeyframeRequestTime = 0L
    
    private class FrameBuffer {
        var seq: Int = 0
        var totalFragments: Int = 0
        var frameSize: Int = 0
        var timestampMs: Long = 0
        var flags: Byte = 0
        var fecGroupSize: Int = 10
        var frameBytes: ByteArray = ByteArray(0)
        var receivedFragments: BooleanArray = BooleanArray(0)
        var receivedDataCount = 0
        var maxGroups = 0
        var fecPackets = Array(32) { ByteArray(1300) }
        var fecLengths = IntArray(32)
        var fecReceived = BooleanArray(32)
        var fecCount = 0
        var fecRecoveredCount = 0
        var lastUpdate = 0L
        var isCompleted = false

        fun init(seq: Int, totalFragments: Int, frameSize: Int, timestampMs: Long, flags: Byte, fecGroupSize: Int) {
            this.seq = seq
            this.totalFragments = totalFragments
            this.frameSize = frameSize
            this.timestampMs = timestampMs
            this.flags = flags
            this.fecGroupSize = fecGroupSize
            if (frameBytes.size < frameSize) {
                frameBytes = ByteArray(Math.max(frameSize, 256 * 1024))
            }
            if (receivedFragments.size < totalFragments) {
                receivedFragments = BooleanArray(Math.max(totalFragments, 128))
            } else {
                java.util.Arrays.fill(receivedFragments, 0, totalFragments, false)
            }
            this.receivedDataCount = 0
            val gSize = if (fecGroupSize > 0) fecGroupSize else 10
            this.maxGroups = if (totalFragments > 1) ((totalFragments + gSize - 1) / gSize).coerceAtLeast(1) else 0
            val neededGroups = Math.max(this.maxGroups, 32)
            if (fecPackets.size < neededGroups) {
                fecPackets = Array(neededGroups) { ByteArray(1300) }
                fecLengths = IntArray(neededGroups)
                fecReceived = BooleanArray(neededGroups)
            } else {
                java.util.Arrays.fill(fecLengths, 0, neededGroups, 0)
                java.util.Arrays.fill(fecReceived, 0, neededGroups, false)
            }
            this.fecCount = 0
            this.fecRecoveredCount = 0
            this.lastUpdate = System.currentTimeMillis()
            this.isCompleted = false
        }

        fun getExpectedFragLength(index: Int): Int {
            return if (index < totalFragments - 1) 1300 else frameSize - (totalFragments - 1) * 1300
        }
    }
    
    private val frameBuffers = HashMap<Int, FrameBuffer>()
    private val frameBufferPool = ConcurrentLinkedQueue<FrameBuffer>()
    private var lastAssembledSeq = -1
    private val fecScratch = ByteArray(MAX_PAYLOAD)
    private val fecScratchBuf = ByteBuffer.wrap(fecScratch).order(ByteOrder.nativeOrder())
    private var reusableAudioBuf = ByteArray(4096)

    private fun obtainFrameBuffer(seq: Int, totalFragments: Int, frameSize: Int, timestampMs: Long, flags: Byte, fecGroupSize: Int): FrameBuffer {
        val fb = frameBufferPool.poll() ?: FrameBuffer()
        fb.init(seq, totalFragments, frameSize, timestampMs, flags, fecGroupSize)
        return fb
    }

    private fun recycleFrameBuffer(fb: FrameBuffer) {
        frameBufferPool.offer(fb)
    }
    
    override fun start(port: Int) {
        if (isListening) return
        isListening = true
        
        kotlin.concurrent.thread(name = "UdpReceiverThread") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(java.net.InetSocketAddress(listenPort))
                socket = s
                socket?.receiveBufferSize = 16 * 1024 * 1024
                AppLogger.i(TAG, "UDP Receiver listening on port $listenPort (rcvBuf=${socket?.receiveBufferSize}, Interleaved FEC Enabled)")
                
                val buffer = ByteArray(2000)
                val dp = DatagramPacket(buffer, buffer.size)
                
                while (isListening) {
                    dp.length = buffer.size
                    socket?.receive(dp)
                    val length = dp.length
                    
                    lastSenderAddress = dp.address
                    lastSenderPort = dp.port
                    
                    if (length >= PacketProtocol.HEADER_SIZE) {
                        if (PacketProtocol.isStreamStopPacket(buffer, length)) {
                            AppLogger.i(TAG, "Received STREAM_STOP packet via UDP")
                            onStreamStop?.invoke()
                            // Clear state
                            lastAssembledSeq = -1
                            for (fb in frameBuffers.values) {
                                recycleFrameBuffer(fb)
                            }
                            frameBuffers.clear()
                            continue
                        }
                    }

                    if (length < 28) continue
                    
                    val magic = ((buffer[0].toInt() and 0xFF) shl 24) or
                                ((buffer[1].toInt() and 0xFF) shl 16) or
                                ((buffer[2].toInt() and 0xFF) shl 8) or
                                (buffer[3].toInt() and 0xFF)
                    if (magic != 0x55445056) continue
                    
                    val seq = ((buffer[4].toInt() and 0xFF) shl 24) or
                              ((buffer[5].toInt() and 0xFF) shl 16) or
                              ((buffer[6].toInt() and 0xFF) shl 8) or
                              (buffer[7].toInt() and 0xFF)
                    
                    val ts = ((buffer[8].toLong() and 0xFFL) shl 56) or
                             ((buffer[9].toLong() and 0xFFL) shl 48) or
                             ((buffer[10].toLong() and 0xFFL) shl 40) or
                             ((buffer[11].toLong() and 0xFFL) shl 32) or
                             ((buffer[12].toLong() and 0xFFL) shl 24) or
                             ((buffer[13].toLong() and 0xFFL) shl 16) or
                             ((buffer[14].toLong() and 0xFFL) shl 8) or
                             (buffer[15].toLong() and 0xFFL)
                    
                    val flags = buffer[16]
                    val fragIndex = ((buffer[17].toInt() and 0xFF) shl 8) or (buffer[18].toInt() and 0xFF)
                    val totalFragments = ((buffer[19].toInt() and 0xFF) shl 8) or (buffer[20].toInt() and 0xFF)
                    val frameSize = ((buffer[21].toInt() and 0xFF) shl 24) or
                                    ((buffer[22].toInt() and 0xFF) shl 16) or
                                    ((buffer[23].toInt() and 0xFF) shl 8) or
                                    (buffer[24].toInt() and 0xFF)
                    
                    val isKeyframe = (flags.toInt() and 1) != 0
                    val isCodecConfig = (flags.toInt() and 2) != 0
                    val isHevc = (flags.toInt() and 4) != 0
                    val isFec = (flags.toInt() and 8) != 0
                    val isAudio = (flags.toInt() and 16) != 0
                    val isAudioConfig = (flags.toInt() and 32) != 0
                    val isBeacon = (flags.toInt() and 64) != 0

                    if (isBeacon) {
                        if (length >= 36) {
                            val rtt = ((buffer[28].toInt() and 0xFF) shl 24) or
                                      ((buffer[29].toInt() and 0xFF) shl 16) or
                                      ((buffer[30].toInt() and 0xFF) shl 8) or
                                      (buffer[31].toInt() and 0xFF)
                            lastReportedRttMs = rtt
                            val lossBp = ((buffer[32].toInt() and 0xFF) shl 24) or
                                          ((buffer[33].toInt() and 0xFF) shl 16) or
                                          ((buffer[34].toInt() and 0xFF) shl 8) or
                                          (buffer[35].toInt() and 0xFF)
                            lastReportedLossPercent = lossBp / 100f
                            tickStats()
                        }
                        continue
                    }
                    
                    totalBytesReceived += length
                    windowBytesReceived += length
                    
                    if (isAudio) {
                        val payloadSize = length - 28
                        if (payloadSize > 0) {
                            if (reusableAudioBuf.size < payloadSize) {
                                reusableAudioBuf = ByteArray(Math.max(payloadSize, 4096))
                            }
                            System.arraycopy(buffer, 28, reusableAudioBuf, 0, payloadSize)
                            val payload = reusableAudioBuf.copyOf(payloadSize)
                            onAudioFrame?.invoke(payload, isAudioConfig, ts)
                            tickStats()
                        }
                        continue
                    }

                    // Bounds check for safety against corrupt UDP packets
                    if (totalFragments <= 0 || totalFragments > 2000) continue
                    if (fragIndex < 0 || fragIndex >= totalFragments) continue
                    if (frameSize <= 0 || frameSize > 16 * 1024 * 1024) continue
                    
                    if (seq <= lastAssembledSeq) {
                        val isRestart = (lastAssembledSeq - seq > 500)
                        if (isRestart) {
                            AppLogger.w(TAG, "Sequence reset detected: old=$lastAssembledSeq new=$seq")
                            lastAssembledSeq = -1
                            for (fbItem in frameBuffers.values) {
                                recycleFrameBuffer(fbItem)
                            }
                            frameBuffers.clear()
                        } else {
                            val existing = frameBuffers[seq]
                            if (existing == null || existing.isCompleted) {
                                continue
                            }
                        }
                    }

                    var fb = frameBuffers[seq]
                    if (fb == null) {
                        val fecGroupSize = if ((buffer[25].toInt() and 0xFF) > 0) (buffer[25].toInt() and 0xFF) else 10
                        fb = obtainFrameBuffer(seq, totalFragments, frameSize, ts, flags, fecGroupSize)
                        frameBuffers[seq] = fb
                        cleanOldFrames(seq)
                    }
                    fb.lastUpdate = System.currentTimeMillis()
                    
                    if (isFec) {
                        val groupId = fragIndex
                        if (groupId in 0 until fb.maxGroups && !fb.fecReceived[groupId]) {
                            val fecLen = Math.min(1300, length - 28)
                            System.arraycopy(buffer, 28, fb.fecPackets[groupId], 0, fecLen)
                            fb.fecLengths[groupId] = fecLen
                            fb.fecReceived[groupId] = true
                            fb.fecCount++
                        }
                    } else {
                        val offset = fragIndex * 1300
                        val dataLen = length - 28
                        if (fragIndex < fb.totalFragments && !fb.receivedFragments[fragIndex]) {
                            if (offset + dataLen <= fb.frameBytes.size) {
                                System.arraycopy(buffer, 28, fb.frameBytes, offset, dataLen)
                                fb.receivedFragments[fragIndex] = true
                                fb.receivedDataCount++
                            }
                        }
                    }
                    
                    checkAndAssemble(fb)
                    tickStats()
                }
            } catch (e: Exception) {
                if (isListening) {
                    AppLogger.e(TAG, "UDP receiver thread exception", e)
                }
            } finally {
                isListening = false
            }
        }
    }
    
    override fun requestKeyframe() {
        sendKeyframeRequest()
    }

    private fun sendKeyframeRequest() {
        val s = socket ?: return
        val addr = lastSenderAddress ?: return
        val now = System.currentTimeMillis()
        if (now - lastKeyframeRequestTime < 100) return
        lastKeyframeRequestTime = now
        val idrReq = ByteArray(22)
        idrReq[0] = PacketProtocol.MAGIC_0
        idrReq[1] = PacketProtocol.MAGIC_1
        idrReq[2] = PacketProtocol.VERSION
        idrReq[3] = PacketProtocol.FLAG_IDR_REQUEST
        try {
            s.send(DatagramPacket(idrReq, idrReq.size, addr, lastSenderPort))
            AppLogger.i(TAG, "[IDR_REQUEST] Sent instant IDR Keyframe Request (PLI) to sender.")
        } catch (_: Exception) {}
    }

    private fun cleanOldFrames(currentSeq: Int) {
        val iter = frameBuffers.entries.iterator()
        val now = System.currentTimeMillis()
        var hadDrop = false
        val maxSeqAge = if (jitterBufferMs > 100) 150 else 30
        val maxTimeAgeMs = if (jitterBufferMs > 100) 2000L else 1000L
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key < currentSeq - maxSeqAge || now - entry.value.lastUpdate > maxTimeAgeMs) {
                if (!entry.value.isCompleted) {
                    hadDrop = true
                    totalDroppedFrames++
                    windowDroppedFrames++
                    AppLogger.w(TAG, "[FRAME_DROP] Discarding incomplete Frame #${entry.key} (age=${now - entry.value.lastUpdate}ms, received ${entry.value.receivedDataCount}/${entry.value.totalFragments} frags, FEC received: ${entry.value.fecCount}/${entry.value.maxGroups}, recovered: ${entry.value.fecRecoveredCount})")
                }
                recycleFrameBuffer(entry.value)
                iter.remove()
            }
        }
        if (hadDrop) {
            sendKeyframeRequest()
        }
    }
    
    private fun checkAndAssemble(fb: FrameBuffer) {
        if (fb.isCompleted) return
        
        // Try Interleaved FEC recovery (fragment i belongs to group i % maxGroups)
        val m = fb.maxGroups
        if (fb.receivedDataCount < fb.totalFragments && fb.fecCount > 0 && m > 0) {
            val frameBuf = ByteBuffer.wrap(fb.frameBytes).order(ByteOrder.nativeOrder())
            
            for (groupId in 0 until m) {
                if (!fb.fecReceived[groupId]) continue
                
                var missingCount = 0
                var missingIdx = -1
                var i = groupId
                while (i < fb.totalFragments) {
                    if (!fb.receivedFragments[i]) {
                        missingCount++
                        missingIdx = i
                    }
                    i += m
                }
                
                if (missingCount == 1) {
                    // Recover missing fragment using reusable scratch buffer with 64-bit fast Long XOR
                    val fecPayload = fb.fecPackets[groupId]
                    val fecLen = fb.fecLengths[groupId]
                    System.arraycopy(fecPayload, 0, fecScratch, 0, fecLen)
                    
                    var k = groupId
                    while (k < fb.totalFragments) {
                        if (k != missingIdx && fb.receivedFragments[k]) {
                            val fragOffset = k * 1300
                            val fragLen = fb.getExpectedFragLength(k)
                            val longsCount = fragLen ushr 3
                            for (l in 0 until longsCount) {
                                val pos = l shl 3
                                fecScratchBuf.putLong(pos, fecScratchBuf.getLong(pos) xor frameBuf.getLong(fragOffset + pos))
                            }
                            val remStart = longsCount shl 3
                            for (b in remStart until fragLen) {
                                fecScratch[b] = (fecScratch[b].toInt() xor fb.frameBytes[fragOffset + b].toInt()).toByte()
                            }
                        }
                        k += m
                    }
                    
                    val expectedLen = fb.getExpectedFragLength(missingIdx)
                    val copyLen = Math.min(expectedLen, fecLen)
                    val missingOffset = missingIdx * 1300
                    if (missingOffset + copyLen <= fb.frameBytes.size) {
                        System.arraycopy(fecScratch, 0, fb.frameBytes, missingOffset, copyLen)
                        fb.receivedFragments[missingIdx] = true
                        fb.receivedDataCount++
                        fb.fecRecoveredCount++
                        totalFecRecovered++
                        windowFecRecovered++
                        AppLogger.i(TAG, "[FEC] Recovered missing frag $missingIdx/${fb.totalFragments} for Frame #${fb.seq} (Interleaved Group $groupId/$m)")
                    }
                } else if (missingCount > 1) {
                    AppLogger.d(TAG, "[FEC] Group $groupId/$m of Frame #${fb.seq} missing $missingCount frags (unrecoverable by single XOR)")
                }
            }
        }
        
        if (fb.receivedDataCount == fb.totalFragments) {
            fb.isCompleted = true
            val isKeyframe = (fb.flags.toInt() and 1) != 0
            val isCodecConfig = (fb.flags.toInt() and 2) != 0
            val isHevc = (fb.flags.toInt() and 4) != 0
            
            if (fb.seq > lastAssembledSeq) {
                lastAssembledSeq = fb.seq
            }
            
            if (!isCodecConfig) {
                totalReceivedFrames++
                windowReceivedFrames++
            }
            
            val assembledBytes = fb.frameBytes.copyOf(fb.frameSize)
            onFrameAssembled?.invoke(assembledBytes, isKeyframe, isCodecConfig, isHevc, fb.timestampMs, fb.seq)
        }
    }
    
    private fun tickStats() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastStatsResetTime
        if (elapsed >= 1000L) {
            currentFps = (windowReceivedFrames * 1000f) / elapsed
            currentBitrateMbps = (windowBytesReceived * 8f) / (elapsed * 1000f)

            if (windowFecRecovered > 0 || windowDroppedFrames > 0) {
                AppLogger.i(TAG, "[STATS 1s] FPS: %.1f, Bitrate: %.2f Mbps, Frames: $totalReceivedFrames, FEC Recovered: $windowFecRecovered, Drops: $windowDroppedFrames".format(currentFps, currentBitrateMbps))
            }

            val stats = StreamStats(
                isReceiving = true,
                fps = currentFps,
                bitrateMbps = currentBitrateMbps,
                latencyMs = 0L,
                totalFrames = totalReceivedFrames,
                droppedFrames = totalDroppedFrames,
                packetLossPercent = 0f,
                lossTimeoutPercent = 0f,
                lossEvictedPercent = 0f,
                lossNetworkPercent = lastReportedLossPercent,
                rttMs = lastReportedRttMs,
                inFlightBytes = 0L,
                statsTimestampMs = now
            )

            onStatsUpdated?.invoke(stats)

            windowReceivedFrames = 0
            windowBytesReceived = 0
            windowFecRecovered = 0
            windowDroppedFrames = 0
            lastStatsResetTime = now
        }
    }
    
    override fun stop() {
        isListening = false
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        frameBuffers.clear()
    }
}
