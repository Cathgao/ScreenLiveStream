package com.example.net

import com.example.log.AppLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer

import com.example.model.StreamStats

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
        val fragments = arrayOfNulls<ByteArray>(totalFragments)
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
    
    override fun start(port: Int) {
        if (isListening) return
        isListening = true
        
        kotlin.concurrent.thread(name = "UdpReceiverThread") {
            try {
                socket = DatagramSocket(listenPort)
                socket?.receiveBufferSize = 8 * 1024 * 1024
                AppLogger.i(TAG, "UDP Receiver listening on port $listenPort")
                
                val buffer = ByteArray(2000)
                val dp = DatagramPacket(buffer, buffer.size)
                
                while (isListening) {
                    dp.length = buffer.size
                    socket?.receive(dp)
                    val length = dp.length
                    if (length < 28) continue
                    
                    val bb = ByteBuffer.wrap(buffer, 0, length)
                    val magic = bb.getInt()
                    if (magic != 0x55445056) continue
                    
                    val seq = bb.getInt()
                    val ts = bb.getLong()
                    val flags = bb.get()
                    val fragIndex = bb.getShort().toInt()
                    val totalFragments = bb.getShort().toInt()
                    val frameSize = bb.getInt()
                    
                    val isAudio = (flags.toInt() and 16) != 0
                    val isBeacon = (flags.toInt() and 64) != 0
                    val isCodecConfig = (flags.toInt() and 32) != 0
                    
                    if (isBeacon) {
                        bb.position(28)
                        lastReportedRttMs = bb.getInt()
                        lastReportedLossPercent = bb.getInt() / 100f
                        tickStats()
                        continue
                    }
                    
                    totalBytesReceived += length
                    windowBytesReceived += length
                    
                    if (isAudio) {
                        val payloadSize = length - 28
                        val payload = ByteArray(payloadSize)
                        System.arraycopy(buffer, 28, payload, 0, payloadSize)
                        onAudioFrame?.invoke(payload, isCodecConfig, ts)
                        tickStats()
                        continue
                    }
                    
                    if (seq <= lastAssembledSeq) continue
                    
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
                        if (fb.fragments[fragIndex] == null) {
                            val payload = ByteArray(length - 28)
                            System.arraycopy(buffer, 28, payload, 0, length - 28)
                            fb.fragments[fragIndex] = payload
                            fb.receivedDataCount++
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
                    if (fb.fragments[i] == null) {
                        missingCount++
                        missingIdx = i
                    }
                }
                
                if (missingCount == 1) {
                    // Recover missing fragment
                    val recovered = ByteArray(MAX_PAYLOAD)
                    System.arraycopy(fecPayload, 0, recovered, 0, fecPayload.size)
                    
                    for (i in startIdx until endIdx) {
                        if (i != missingIdx && fb.fragments[i] != null) {
                            val frag = fb.fragments[i]!!
                            for (k in frag.indices) {
                                recovered[k] = (recovered[k].toInt() xor frag[k].toInt()).toByte()
                            }
                        }
                    }
                    
                    val expectedLen = fb.getExpectedFragLength(missingIdx)
                    val finalRecovered = ByteArray(expectedLen)
                    System.arraycopy(recovered, 0, finalRecovered, 0, Math.min(expectedLen, recovered.size))
                    
                    fb.fragments[missingIdx] = finalRecovered
                    fb.receivedDataCount++
                }
            }
        }
        
        if (fb.receivedDataCount == fb.totalFragments) {
            fb.isCompleted = true
            
            // Reassemble frame
            val frameBytes = ByteArray(fb.frameSize)
            var offset = 0
            for (i in 0 until fb.totalFragments) {
                val frag = fb.fragments[i]!!
                System.arraycopy(frag, 0, frameBytes, offset, frag.size)
                offset += frag.size
            }
            
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
            
            onFrameAssembled?.invoke(frameBytes, isKeyframe, isCodecConfig, isHevc, fb.timestampMs, fb.seq)
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
