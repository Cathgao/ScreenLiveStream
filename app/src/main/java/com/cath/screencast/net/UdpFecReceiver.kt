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
    private var receiverThread: Thread? = null
    override var onFrameAssembled: ((ByteArray, Int, Boolean, Boolean, Boolean, Long, Int) -> Unit)? = null
    override var onAudioFrame: ((ByteArray, Int, Boolean, Long) -> Unit)? = null
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
    private var lastKeyframeReceivedTime = 0L
    
    private class FrameBuffer {
        var seq: Int = 0
        var totalFragments: Int = 0
        var frameSize: Int = 0
        var timestampMs: Long = 0
        var flags: Byte = 0
        var fecGroupSize: Int = 8
        var frameBytes: ByteArray = ByteArray(0)
        var frameNativeBuf: ByteBuffer = ByteBuffer.wrap(frameBytes).order(ByteOrder.nativeOrder())
        var receivedFragments: BooleanArray = BooleanArray(0)
        var receivedDataCount = 0
        var maxGroups = 0
        var fecPackets = Array(32) { ByteArray(1300) }
        var fecLengths = IntArray(32)
        var fecReceived = BooleanArray(32)
        var groupReceivedCount = IntArray(32)
        var fecCount = 0
        var fecRecoveredCount = 0
        var lastUpdate = 0L
        var isCompleted = false

        fun init(
            seq: Int,
            totalFragments: Int,
            frameSize: Int,
            timestampMs: Long,
            flags: Byte,
            fecGroupSize: Int,
            explicitGroups: Int
        ) {
            this.seq = seq
            this.totalFragments = totalFragments
            this.frameSize = frameSize
            this.timestampMs = timestampMs
            this.flags = flags
            val gSize = if (fecGroupSize > 0) fecGroupSize else 8
            this.fecGroupSize = gSize
            if (frameBytes.size < frameSize) {
                frameBytes = ByteArray(Math.max(frameSize, 256 * 1024))
                frameNativeBuf = ByteBuffer.wrap(frameBytes).order(ByteOrder.nativeOrder())
            }
            if (receivedFragments.size < totalFragments) {
                receivedFragments = BooleanArray(Math.max(totalFragments, 128))
            } else {
                java.util.Arrays.fill(receivedFragments, 0, totalFragments, false)
            }
            this.receivedDataCount = 0
            this.maxGroups = if (totalFragments > 1) {
                if (explicitGroups > 0) explicitGroups else ((totalFragments + gSize - 1) / gSize).coerceAtLeast(1)
            } else {
                0
            }
            val neededGroups = Math.max(this.maxGroups, 32)
            if (fecPackets.size < neededGroups) {
                fecPackets = Array(neededGroups) { ByteArray(1300) }
                fecLengths = IntArray(neededGroups)
                fecReceived = BooleanArray(neededGroups)
                groupReceivedCount = IntArray(neededGroups)
            } else {
                java.util.Arrays.fill(fecLengths, 0, neededGroups, 0)
                java.util.Arrays.fill(fecReceived, 0, neededGroups, false)
                java.util.Arrays.fill(groupReceivedCount, 0, neededGroups, 0)
            }
            this.fecCount = 0
            this.fecRecoveredCount = 0
            this.lastUpdate = System.currentTimeMillis()
            this.isCompleted = false
        }

        fun getExpectedFragLength(index: Int): Int {
            return if (index < totalFragments - 1) 1300 else frameSize - (totalFragments - 1) * 1300
        }

        fun getGroupExpectedCount(groupId: Int): Int {
            if (maxGroups <= 0 || groupId < 0 || groupId >= totalFragments) return 0
            return (totalFragments - groupId + maxGroups - 1) / maxGroups
        }
    }

    private class SequencerSlot {
        var isOccupied: Boolean = false
        var seq: Int = -1
        var readyTimeMs: Long = 0L
        var fb: FrameBuffer? = null

        fun attach(fb: FrameBuffer, now: Long) {
            this.isOccupied = true
            this.seq = fb.seq
            this.readyTimeMs = now
            this.fb = fb
        }

        fun clear() {
            this.isOccupied = false
            this.seq = -1
            this.fb = null
        }
    }

    private val SEQUENCER_SIZE = 128
    private val SEQUENCER_MASK = SEQUENCER_SIZE - 1
    private val sequencerSlots = Array(SEQUENCER_SIZE) { SequencerSlot() }
    private var nextExpectedSeq = -1
    private var highestSeenSeq = -1
    private var lastCleanupTimeMs = 0L
    private var sequencerCount = 0

    private val frameBuffers = HashMap<Int, FrameBuffer>()
    private val frameBufferPool = ConcurrentLinkedQueue<FrameBuffer>()
    private var lastAssembledSeq = -1
    private val fecScratch = ByteArray(MAX_PAYLOAD)
    private val fecScratchBuf = ByteBuffer.wrap(fecScratch).order(ByteOrder.nativeOrder())
    private var reusableAudioBuf = ByteArray(4096)

    private fun obtainFrameBuffer(
        seq: Int,
        totalFragments: Int,
        frameSize: Int,
        timestampMs: Long,
        flags: Byte,
        fecGroupSize: Int,
        explicitGroups: Int
    ): FrameBuffer {
        val fb = frameBufferPool.poll() ?: FrameBuffer()
        fb.init(seq, totalFragments, frameSize, timestampMs, flags, fecGroupSize, explicitGroups)
        return fb
    }

    private fun recycleFrameBuffer(fb: FrameBuffer) {
        frameBufferPool.offer(fb)
    }

    private fun clearAllPendingState() {
        lastAssembledSeq = -1
        nextExpectedSeq = -1
        highestSeenSeq = -1
        sequencerCount = 0
        for (slot in sequencerSlots) {
            slot.fb?.let { recycleFrameBuffer(it) }
            slot.clear()
        }
        for (fbItem in frameBuffers.values) {
            recycleFrameBuffer(fbItem)
        }
        frameBuffers.clear()
    }

    override fun start(port: Int) {
        if (isListening) return
        isListening = true

        receiverThread = kotlin.concurrent.thread(name = "UdpReceiverThread") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.soTimeout = 25
                s.bind(java.net.InetSocketAddress(listenPort))
                socket = s
                s.receiveBufferSize = 16 * 1024 * 1024
                AppLogger.i(TAG, "UDP Receiver listening on port $listenPort (rcvBuf=${s.receiveBufferSize}, Interleaved FEC Enabled)")

                val buffer = ByteArray(2000)
                val dp = DatagramPacket(buffer, buffer.size)

                while (isListening) {
                    try {
                        dp.length = buffer.size
                        s.receive(dp)
                    } catch (_: java.net.SocketTimeoutException) {
                        val now = System.currentTimeMillis()
                        if (highestSeenSeq >= 0) {
                            cleanOldFrames(highestSeenSeq, now)
                        } else {
                            flushSequencer(now)
                        }
                        continue
                    }
                    val length = dp.length

                    if (length >= PacketProtocol.HEADER_SIZE) {
                        if (PacketProtocol.isStreamStopPacket(buffer, length)) {
                            AppLogger.i(TAG, "Received STREAM_STOP packet via UDP")
                            onStreamStop?.invoke()
                            clearAllPendingState()
                            continue
                        }
                        val probe = PacketProtocol.readProbeSequence(buffer, length)
                        if (probe != null) {
                            if (!probe.isReply && !probe.isIdrRequest) {
                                val reply = PacketProtocol.buildPingReplyPacket(probe.seq, probe.echoedNanos)
                                s.send(DatagramPacket(reply, reply.size, dp.address, dp.port))
                            }
                            continue
                        }
                    }

                    if (length < 28) continue

                    val magic = ((buffer[0].toInt() and 0xFF) shl 24) or
                                ((buffer[1].toInt() and 0xFF) shl 16) or
                                ((buffer[2].toInt() and 0xFF) shl 8) or
                                (buffer[3].toInt() and 0xFF)
                    if (magic != 0x55445056) continue

                    lastSenderAddress = dp.address
                    lastSenderPort = dp.port

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

                    val isCodecConfig = (flags.toInt() and 2) != 0
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
                            onAudioFrame?.invoke(reusableAudioBuf, payloadSize, isAudioConfig, ts)
                            tickStats()
                        }
                        continue
                    }

                    // Bounds check for safety against corrupt UDP packets
                    if (totalFragments <= 0 || totalFragments > 2000) continue
                    if (frameSize <= 0 || frameSize > 16 * 1024 * 1024) continue

                    if (seq <= lastAssembledSeq) {
                        val isRestart = (lastAssembledSeq - seq > 50) || (isCodecConfig && seq <= 5 && lastAssembledSeq > 5)
                        if (isRestart) {
                            AppLogger.w(TAG, "Sequence reset detected: old=$lastAssembledSeq new=$seq")
                            clearAllPendingState()
                        } else {
                            val existing = frameBuffers[seq]
                            if (existing == null || existing.isCompleted) {
                                continue
                            }
                        }
                    }

                    // Skip duplicate packets for frames already completed & waiting in sequencerSlots
                    val pendingSlot = sequencerSlots[seq and SEQUENCER_MASK]
                    if (pendingSlot.isOccupied && pendingSlot.seq == seq) {
                        continue
                    }

                    var fb = frameBuffers[seq]
                    val now = System.currentTimeMillis()
                    if (fb == null) {
                        val fecGroupSize = if ((buffer[25].toInt() and 0xFF) > 0) (buffer[25].toInt() and 0xFF) else 8
                        val explicitGroups = buffer[26].toInt() and 0xFF
                        fb = obtainFrameBuffer(seq, totalFragments, frameSize, ts, flags, fecGroupSize, explicitGroups)
                        frameBuffers[seq] = fb
                        if (seq > highestSeenSeq) highestSeenSeq = seq
                        cleanOldFrames(highestSeenSeq, now)
                    } else if (now - lastCleanupTimeMs >= 20L) {
                        cleanOldFrames(highestSeenSeq, now)
                    }
                    fb.lastUpdate = now

                    var touchedGroupId = -1
                    if (isFec) {
                        val groupId = fragIndex
                        if (groupId in 0 until fb.maxGroups && !fb.fecReceived[groupId]) {
                            val fecLen = Math.min(1300, length - 28)
                            System.arraycopy(buffer, 28, fb.fecPackets[groupId], 0, fecLen)
                            fb.fecLengths[groupId] = fecLen
                            fb.fecReceived[groupId] = true
                            fb.fecCount++
                            touchedGroupId = groupId
                        }
                    } else {
                        if (fragIndex < 0 || fragIndex >= fb.totalFragments) continue
                        val offset = fragIndex * 1300
                        val dataLen = length - 28
                        if (!fb.receivedFragments[fragIndex]) {
                            if (offset + dataLen <= fb.frameBytes.size) {
                                System.arraycopy(buffer, 28, fb.frameBytes, offset, dataLen)
                                fb.receivedFragments[fragIndex] = true
                                fb.receivedDataCount++
                                if (fb.maxGroups > 0) {
                                    val g = fragIndex % fb.maxGroups
                                    fb.groupReceivedCount[g]++
                                    touchedGroupId = g
                                }
                            }
                        }
                    }

                    checkAndAssemble(fb, touchedGroupId)
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
        if (now - lastKeyframeRequestTime < 600L || now - lastKeyframeReceivedTime < 350L) return
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

    private fun cleanOldFrames(currentSeq: Int, now: Long = System.currentTimeMillis()) {
        lastCleanupTimeMs = now
        val iter = frameBuffers.entries.iterator()
        var hadDrop = false
        val maxSeqAge = if (jitterBufferMs > 100) 150 else 45
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
        flushSequencer(now)
        if (hadDrop) {
            sendKeyframeRequest()
        }
    }

    private fun checkAndAssemble(fb: FrameBuffer, touchedGroupId: Int) {
        if (fb.isCompleted) return

        // O(1) Interleaved FEC recovery: only inspect the specific group touched by this packet
        val m = fb.maxGroups
        if (fb.receivedDataCount < fb.totalFragments && m > 0 && touchedGroupId in 0 until m) {
            if (fb.fecReceived[touchedGroupId]) {
                val expectedInGroup = fb.getGroupExpectedCount(touchedGroupId)
                if (fb.groupReceivedCount[touchedGroupId] == expectedInGroup - 1) {
                    var missingIdx = -1
                    var k = touchedGroupId
                    while (k < fb.totalFragments) {
                        if (!fb.receivedFragments[k]) {
                            missingIdx = k
                            break
                        }
                        k += m
                    }
                    if (missingIdx >= 0) {
                        val frameBuf = fb.frameNativeBuf
                        val fecPayload = fb.fecPackets[touchedGroupId]
                        val fecLen = fb.fecLengths[touchedGroupId]
                        System.arraycopy(fecPayload, 0, fecScratch, 0, fecLen)

                        var j = touchedGroupId
                        while (j < fb.totalFragments) {
                            if (j != missingIdx && fb.receivedFragments[j]) {
                                val fragOffset = j * 1300
                                val fragLen = fb.getExpectedFragLength(j)
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
                            j += m
                        }

                        val expectedLen = fb.getExpectedFragLength(missingIdx)
                        val copyLen = Math.min(expectedLen, fecLen)
                        val missingOffset = missingIdx * 1300
                        if (missingOffset + copyLen <= fb.frameBytes.size) {
                            System.arraycopy(fecScratch, 0, fb.frameBytes, missingOffset, copyLen)
                            fb.receivedFragments[missingIdx] = true
                            fb.receivedDataCount++
                            fb.groupReceivedCount[touchedGroupId]++
                            fb.fecRecoveredCount++
                            totalFecRecovered++
                            windowFecRecovered++
                            AppLogger.i(TAG, "[FEC] Interleaved recovered missing frag $missingIdx/${fb.totalFragments} for Frame #${fb.seq} (Group $touchedGroupId/$m)")
                        }
                    }
                }
            }
        }

        if (fb.receivedDataCount == fb.totalFragments) {
            onFrameCompleted(fb)
        }
    }

    private fun onFrameCompleted(fb: FrameBuffer) {
        fb.isCompleted = true
        frameBuffers.remove(fb.seq)
        val now = System.currentTimeMillis()

        if (nextExpectedSeq == -1 || Math.abs(fb.seq - nextExpectedSeq) > 100) {
            nextExpectedSeq = fb.seq
        }

        val slotIdx = fb.seq and SEQUENCER_MASK
        val slot = sequencerSlots[slotIdx]
        if (slot.isOccupied) {
            if (slot.seq == fb.seq) {
                recycleFrameBuffer(fb)
                return
            }
            // Flush or recycle old unconsumed slot before reusing ring position
            if (slot.seq - nextExpectedSeq >= 0) {
                nextExpectedSeq = slot.seq
                flushSequencer(now)
            }
            if (slot.isOccupied) {
                slot.fb?.let { recycleFrameBuffer(it) }
                slot.clear()
                sequencerCount--
            }
        }
        slot.attach(fb, now)
        sequencerCount++

        flushSequencer(now)
    }

    private fun flushSequencer(now: Long) {
        if (sequencerCount == 0 || nextExpectedSeq == -1) return

        // 1. Dispatch contiguous ascending sequence without wait
        while (true) {
            val slotIdx = nextExpectedSeq and SEQUENCER_MASK
            val slot = sequencerSlots[slotIdx]
            if (slot.isOccupied && slot.seq == nextExpectedSeq) {
                dispatchSlot(slot)
                sequencerCount--
                nextExpectedSeq++
            } else {
                break
            }
        }

        if (sequencerCount == 0) return

        // 2. Clean stale slots and find the lowest seq >= nextExpectedSeq in ascending seq order
        val maxWaitMs = if (jitterBufferMs > 100) 80L else 30L
        var minSlot: SequencerSlot? = null
        for (i in 0 until SEQUENCER_SIZE) {
            val slot = sequencerSlots[i]
            if (!slot.isOccupied) continue
            val delta = slot.seq - nextExpectedSeq
            if (delta < 0) {
                slot.fb?.let { recycleFrameBuffer(it) }
                slot.clear()
                sequencerCount--
                continue
            }
            if (minSlot == null || slot.seq < minSlot.seq) {
                minSlot = slot
            }
        }

        if (minSlot != null) {
            val waitTime = now - minSlot.readyTimeMs
            val isPriorityWaiting = minSlot.fb?.let { (it.flags.toInt() and 3) != 0 } == true
            if (waitTime > maxWaitMs || (minSlot.seq - nextExpectedSeq > 32) || (isPriorityWaiting && waitTime > 12L)) {
                nextExpectedSeq = minSlot.seq
                while (true) {
                    val curIdx = nextExpectedSeq and SEQUENCER_MASK
                    val curSlot = sequencerSlots[curIdx]
                    if (curSlot.isOccupied && curSlot.seq == nextExpectedSeq) {
                        dispatchSlot(curSlot)
                        sequencerCount--
                        nextExpectedSeq++
                    } else {
                        break
                    }
                }
            }
        }
    }

    private fun dispatchSlot(slot: SequencerSlot) {
        val fb = slot.fb
        if (fb != null) {
            val isKeyframe = (fb.flags.toInt() and 1) != 0
            val isCodecConfig = (fb.flags.toInt() and 2) != 0
            val isHevc = (fb.flags.toInt() and 4) != 0
            if (!isCodecConfig) {
                totalReceivedFrames++
                windowReceivedFrames++
            }
            if (isKeyframe) {
                lastKeyframeReceivedTime = System.currentTimeMillis()
            }
            lastAssembledSeq = fb.seq
            onFrameAssembled?.invoke(fb.frameBytes, fb.frameSize, isKeyframe, isCodecConfig, isHevc, fb.timestampMs, fb.seq)
            recycleFrameBuffer(fb)
        }
        slot.clear()
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
        val threadRef = receiverThread
        threadRef?.interrupt()
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        try {
            threadRef?.join(500L)
        } catch (_: Exception) {}
        receiverThread = null
        clearAllPendingState()
    }
}
