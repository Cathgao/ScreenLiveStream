package com.cath.screencast.net

import com.cath.screencast.log.AppLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer

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
    private val FEC_GROUP_SIZE = 12
    
    // Stats
    private var totalReceivedFrames = 0L
    private var totalBytesReceived = 0L
    private var windowReceivedFrames = 0L
    private var windowBytesReceived = 0L
    private var lastStatsResetTime = System.currentTimeMillis()
    private var currentFps = 0f
    private var currentBitrateMbps = 0f
    
    private var lastReportedRttMs = 0
    private var lastReportedLossPercent = 0f
    
    private class FrameBuffer(val seq: Int, val totalFragments: Int, val frameSize: Int, val timestampMs: Long, val flags: Byte) {
        val frameBytes = ByteArray(frameSize)
        val receivedFragments = BooleanArray(totalFragments)
        var receivedDataCount = 0
        val fecPackets = HashMap<Int, ByteArray>() // groupId -> payload
        var lastUpdate = System.currentTimeMillis()
        var isCompleted = false
        
        fun getExpectedFragLength(index: Int): Int {
            return if (index < totalFragments - 1) 1300 else frameSize - (totalFragments - 1) * 1300
        }
    }
    
    private val frameBuffers = HashMap<Int, FrameBuffer>()
    private var lastAssembledSeq = -1
    private val fecScratch = ByteArray(MAX_PAYLOAD)
    
    override fun start(port: Int) {
        if (isListening) return
        isListening = true
        
        kotlin.concurrent.thread(name = "UdpReceiverThread") {
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(java.net.InetSocketAddress(listenPort))
                socket = s
                socket?.receiveBufferSize = 8 * 1024 * 1024
                AppLogger.i(TAG, "UDP Receiver listening on port $listenPort")
                
                val buffer = ByteArray(2000)
                val dp = DatagramPacket(buffer, buffer.size)
                
                while (isListening) {
                    dp.length = buffer.size
                    socket?.receive(dp)
                    val length = dp.length
                    
                    if (length >= PacketProtocol.HEADER_SIZE) {
                        if (PacketProtocol.isStreamStopPacket(buffer, length)) {
                            AppLogger.i(TAG, "Received STREAM_STOP packet via UDP")
                            onStreamStop?.invoke()
                            // Clear state
                            lastAssembledSeq = -1
                            frameBuffers.clear()
                            continue
                        }
                    }

                    if (length >= PacketProtocol.HEADER_SIZE + 8) {
                        val probe = PacketProtocol.readProbeSequence(buffer, length)
                        if (probe != null && !probe.isReply) {
                            val reply = PacketProtocol.buildPingReplyPacket(probe.seq, probe.echoedNanos)
                            socket?.send(DatagramPacket(reply, reply.size, dp.address, dp.port))
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
                    val isAudio = (flags.toInt() and 16) != 0
                    val isBeacon = (flags.toInt() and 64) != 0
                    val isCodecConfig = (flags.toInt() and 32) != 0
                    
                    if (isBeacon) {
                        if (length >= 28 + 8) {
                            lastReportedRttMs = ((buffer[28].toInt() and 0xFF) shl 24) or
                                                ((buffer[29].toInt() and 0xFF) shl 16) or
                                                ((buffer[30].toInt() and 0xFF) shl 8) or
                                                (buffer[31].toInt() and 0xFF)
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
                            val payload = ByteArray(payloadSize)
                            System.arraycopy(buffer, 28, payload, 0, payloadSize)
                            onAudioFrame?.invoke(payload, isCodecConfig, ts)
                            tickStats()
                        }
                        continue
                    }

                    // Bounds check for safety against corrupt UDP packets
                    if (totalFragments <= 0 || totalFragments > 1000) continue
                    if (fragIndex < 0 || fragIndex >= totalFragments) continue
                    if (frameSize <= 0 || frameSize > 8 * 1024 * 1024) continue
                    
                    if (seq <= lastAssembledSeq) {
                        val isRestart = isKeyframe || (lastAssembledSeq - seq > 50)
                        if (isRestart) {
                            AppLogger.w(TAG, "Sequence reset detected: old=$lastAssembledSeq new=$seq isKeyframe=$isKeyframe")
                            lastAssembledSeq = -1
                            frameBuffers.clear()
                            // Fall through to process this packet
                        } else {
                            continue
                        }
                    }

                    var fb = frameBuffers[seq]
                    if (fb == null) {
                        fb = FrameBuffer(seq, totalFragments, frameSize, ts, flags)
                        frameBuffers[seq] = fb
                        cleanOldFrames(seq)
                    }
                    fb.lastUpdate = System.currentTimeMillis()
                    
                    val isFec = (flags.toInt() and 8) != 0
                    if (isFec) {
                        val groupId = fragIndex
                        if (!fb.fecPackets.containsKey(groupId)) {
                            val payload = ByteArray(length - 28)
                            System.arraycopy(buffer, 28, payload, 0, length - 28)
                            fb.fecPackets[groupId] = payload
                        }
                    } else {
                        if (!fb.receivedFragments[fragIndex]) {
                            val fragOffset = fragIndex * 1300
                            val fragLength = length - 28
                            if (fragOffset + fragLength <= fb.frameBytes.size) {
                                System.arraycopy(buffer, 28, fb.frameBytes, fragOffset, fragLength)
                                fb.receivedFragments[fragIndex] = true
                                fb.receivedDataCount++
                            }
                        }
                    }
                    
                    checkAndAssemble(fb)
                }
            } catch (e: Exception) {
                if (isListening) AppLogger.e(TAG, "UDP Receiver error", e)
            } finally {
                isListening = false
            }
        }
    }
    
    private fun cleanOldFrames(currentSeq: Int) {
        val iter = frameBuffers.entries.iterator()
        val now = System.currentTimeMillis()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key < currentSeq - 30 || now - entry.value.lastUpdate > 1000) {
                iter.remove()
            }
        }
    }
    
    private fun checkAndAssemble(fb: FrameBuffer) {
        if (fb.isCompleted) return
        
        // Try FEC recovery
        if (fb.receivedDataCount < fb.totalFragments && fb.fecPackets.isNotEmpty()) {
            for ((groupId, fecPayload) in fb.fecPackets) {
                val startIdx = groupId * FEC_GROUP_SIZE
                val endIdx = Math.min(startIdx + FEC_GROUP_SIZE, fb.totalFragments)
                
                var missingCount = 0
                var missingIdx = -1
                for (i in startIdx until endIdx) {
                    if (!fb.receivedFragments[i]) {
                        missingCount++
                        missingIdx = i
                    }
                }
                
                if (missingCount == 1) {
                    // Recover missing fragment using reusable scratch buffer
                    System.arraycopy(fecPayload, 0, fecScratch, 0, fecPayload.size)
                    for (i in startIdx until endIdx) {
                        if (i != missingIdx && fb.receivedFragments[i]) {
                            val fragOffset = i * 1300
                            val fragLen = fb.getExpectedFragLength(i)
                            for (k in 0 until fragLen) {
                                fecScratch[k] = (fecScratch[k].toInt() xor fb.frameBytes[fragOffset + k].toInt()).toByte()
                            }
                        }
                    }
                    val expectedLen = fb.getExpectedFragLength(missingIdx)
                    val copyLen = Math.min(expectedLen, fecScratch.size)
                    val missingOffset = missingIdx * 1300
                    if (missingOffset + copyLen <= fb.frameBytes.size) {
                        System.arraycopy(fecScratch, 0, fb.frameBytes, missingOffset, copyLen)
                        fb.receivedFragments[missingIdx] = true
                        fb.receivedDataCount++
                    }
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
            
            onFrameAssembled?.invoke(fb.frameBytes, isKeyframe, isCodecConfig, isHevc, fb.timestampMs, fb.seq)
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
                packetLossPercent = 0f, // No direct packet loss tracking here yet
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
