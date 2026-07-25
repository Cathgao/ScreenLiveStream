package com.example.net

import android.util.Log
import com.example.log.AppLogger as SessionLog
import com.example.model.StreamStats
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class UdpReceiver {
    private var socket: DatagramSocket? = null
    @Volatile
    private var isListening = false
    private var listenerThread: Thread? = null

    // Callback for decoded frames: (frameBytes, isKeyframe, isCodecConfig, isHevc, timestampMs, seq)
    var onFrameAssembled: ((ByteArray, Boolean, Boolean, Boolean, Long, Int) -> Unit)? = null
    // Callback for completed audio frames: (aacBytes, isCodecConfig, timestampMs)
    var onAudioFrame: ((ByteArray, Boolean, Long) -> Unit)? = null
    var onStatsUpdated: ((StreamStats) -> Unit)? = null

    private val videoFrameBuffers = ConcurrentHashMap<Int, FrameAssembly>()
    private val audioFrameBuffers = ConcurrentHashMap<Int, FrameAssembly>()

    // ---- Per-window stats (reset every STATS_WINDOW_MS) ----
    private var windowReceivedFrames = 0L
    private var windowDroppedFrames = 0L
    private var windowBytesReceived = 0L
    private var lastStatsResetTime = System.currentTimeMillis()
    private var currentFps = 0f
    private var currentBitrateMbps = 0f
    private var currentLatencyMs = 0L
    private var currentPacketLossPercent = 0f

    // ---- Cumulative stats since the receiver started ----
    private var totalReceivedFrames = 0L
    private var totalDroppedFrames = 0L
    private var totalBytesReceived = 0L
    private var totalMalformedPackets = 0L
    private val sessionStartTime = System.currentTimeMillis()

    // Out-of-order and sender-reset tracking
    private var lastVideoFrameSeq = -1
    private var lastAudioFrameSeq = -1
    private var lastVideoFrameTime = 0L
    private var lastAudioFrameTime = 0L
    private var lastEvictionTime = 0L

    // ---- Jitter Buffer Configurations ----
    @Volatile
    var jitterBufferMs: Int = 120 // 0 means direct low-latency mode, >0 enables reordering buffer

    private val videoJitterQueue = java.util.TreeMap<Int, JitterFrame>()
    private val audioJitterQueue = java.util.TreeMap<Int, JitterFrame>()
    private val jitterLock = Object()
    private var dispatcherThread: Thread? = null

    private class JitterFrame(
        val seq: Int,
        val timestampMs: Long,
        val isAudio: Boolean,
        val data: ByteArray,
        val isKeyframe: Boolean,
        val isCodecConfig: Boolean,
        val isHevc: Boolean,
        val assembledTimeMs: Long
    )

    private fun startDispatcher() {
        synchronized(jitterLock) {
            videoJitterQueue.clear()
            audioJitterQueue.clear()
        }
        dispatcherThread = thread(start = true, name = "UdpReceiverDispatcher") {
            try {
                while (isListening) {
                    var nextVideoFrame: JitterFrame? = null
                    var nextAudioFrame: JitterFrame? = null
                    val nowMs = System.currentTimeMillis()

                    synchronized(jitterLock) {
                        // 1. Process Video Queue
                        if (videoJitterQueue.isNotEmpty()) {
                            val firstKey = videoJitterQueue.firstKey()
                            val frame = videoJitterQueue[firstKey]!!
                            // Codec config frames are dispatched immediately
                            if (frame.isCodecConfig || jitterBufferMs <= 0 || (nowMs - frame.assembledTimeMs) >= jitterBufferMs) {
                                videoJitterQueue.remove(firstKey)
                                nextVideoFrame = frame
                            }
                        }

                        // 2. Process Audio Queue
                        if (audioJitterQueue.isNotEmpty()) {
                            val firstKey = audioJitterQueue.firstKey()
                            val frame = audioJitterQueue[firstKey]!!
                            if (frame.isCodecConfig || jitterBufferMs <= 0 || (nowMs - frame.assembledTimeMs) >= jitterBufferMs) {
                                audioJitterQueue.remove(firstKey)
                                nextAudioFrame = frame
                            }
                        }

                        if (nextVideoFrame == null && nextAudioFrame == null) {
                            val waitTime = 5L
                            jitterLock.wait(waitTime)
                        }
                    }

                    // Dispatch outside of lock to keep loop fast
                    nextVideoFrame?.let { frame ->
                        val isRestart = frame.isCodecConfig || 
                                        frame.seq < lastVideoFrameSeq - 100 || 
                                        (System.currentTimeMillis() - lastVideoFrameTime > 2000)
                        if (isRestart || frame.seq > lastVideoFrameSeq) {
                            lastVideoFrameSeq = frame.seq
                            lastVideoFrameTime = System.currentTimeMillis()
                            onFrameAssembled?.invoke(
                                frame.data,
                                frame.isKeyframe,
                                frame.isCodecConfig,
                                frame.isHevc,
                                frame.timestampMs,
                                frame.seq
                            )
                        } else {
                            SessionLog.w(TAG, "Dropped stale video frame in dispatcher: seq=${frame.seq}, last=$lastVideoFrameSeq")
                        }
                    }

                    nextAudioFrame?.let { frame ->
                        val isRestart = frame.isCodecConfig || 
                                        frame.seq < lastAudioFrameSeq - 100 || 
                                        (System.currentTimeMillis() - lastAudioFrameTime > 2000)
                        if (isRestart || frame.seq > lastAudioFrameSeq) {
                            lastAudioFrameSeq = frame.seq
                            lastAudioFrameTime = System.currentTimeMillis()
                            onAudioFrame?.invoke(
                                frame.data,
                                frame.isCodecConfig,
                                frame.timestampMs
                            )
                        } else {
                            SessionLog.w(TAG, "Dropped stale audio frame in dispatcher: seq=${frame.seq}, last=$lastAudioFrameSeq")
                        }
                    }
                }
            } catch (e: InterruptedException) {
                // Thread interrupted, exit loop
            } catch (e: Exception) {
                SessionLog.e(TAG, "Error in UdpReceiver dispatcher thread", e)
            }
        }
    }

    private class FrameAssembly(
        val frameSeq: Int,
        val totalPackets: Int,
        val isKeyframe: Boolean,
        val isCodecConfig: Boolean,
        val isHevc: Boolean,
        val isAudio: Boolean,
        val timestampMs: Long,
        val firstSeenMs: Long
    ) {
        val packets = Array<ByteArray?>(totalPackets) { null }
        var receivedCount = 0
        var lastUpdateMs: Long = firstSeenMs

        fun addPacket(idx: Int, payload: ByteArray): Boolean {
            if (idx in 0 until totalPackets && packets[idx] == null) {
                packets[idx] = payload
                receivedCount++
            }
            lastUpdateMs = System.currentTimeMillis()
            return receivedCount == totalPackets
        }

        fun assemble(): ByteArray {
            val baos = ByteArrayOutputStream()
            for (p in packets) {
                if (p != null) {
                    baos.write(p)
                }
            }
            return baos.toByteArray()
        }
    }

    fun start(port: Int) {
        stop()
        isListening = true
        startDispatcher()
        listenerThread = thread(start = true, name = "UdpReceiverThread") {
            try {
                val requestedReceiveBuffer = 4 * 1024 * 1024 // 4MB receive socket buffer for high-throughput
                val ds = DatagramSocket(null).apply {
                    reuseAddress = true
                    receiveBufferSize = requestedReceiveBuffer
                    bind(java.net.InetSocketAddress(port))
                }
                // The kernel may clamp this to its SO_RCVBUF limit; log the actual
                // value so we can spot a too-small buffer as a cause of drops.
                val actualReceiveBuffer = ds.receiveBufferSize
                // SoTimeout lets us wake up periodically to publish stats
                // and to evict stale assemblies even when no packets arrive
                // — without spinning the loop.
                ds.soTimeout = STATS_TICK_MS.toInt()
                socket = ds
                SessionLog.i(
                    TAG,
                    "UdpReceiver listening on port $port (requestedRcvBuf=${requestedReceiveBuffer}B " +
                        "actualRcvBuf=${actualReceiveBuffer}B soTimeout=${STATS_TICK_MS}ms)"
                )
                Log.d(TAG, "UdpReceiver listening on port $port (rcvBuf=$actualReceiveBuffer)")

                val receiveBuffer = ByteArray(65535)
                val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)

                var pktCounter = 0L
                var malformedCounter = 0L
                var lastDiagLog = 0L
                while (isListening && !ds.isClosed) {
                    try {
                        ds.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        // Periodic tick — use it to publish stats and to
                        // evict stale assemblies even when the sender is
                        // silent, then loop back to receive().
                        tickPeriodicWork(forceStats = true)
                        continue
                    }
                    pktCounter++
                    val parsed = PacketProtocol.parsePacket(packet.data, packet.length)
                    if (parsed != null) {
                        // Group/packet coherence check: a packet whose declared
                        // totalPackets is zero, whose index is out of range, or
                        // whose payload size disagrees with the header is
                        // structurally invalid even though the framing passed.
                        if (!isPacketCoherent(parsed)) {
                            malformedCounter++
                            totalMalformedPackets++
                            if (malformedCounter < 5 || malformedCounter % 100 == 0L) {
                                SessionLog.w(
                                    TAG,
                                    "incoherent packet #$pktCounter seq=${parsed.frameSeq} " +
                                        "idx=${parsed.packetIndex}/${parsed.totalPackets} " +
                                        "payload=${parsed.payload.size}B addr=${packet.address.hostAddress}"
                                )
                            }
                        } else {
                            totalBytesReceived += packet.length
                            windowBytesReceived += packet.length
                            try {
                                processParsedPacket(parsed)
                            } catch (e: Exception) {
                                // Never let a callback exception kill the listener
                                // thread — the rest of the stream would silently
                                // stall.
                                SessionLog.e(TAG, "processParsedPacket threw (kept listener alive)", e)
                            }
                        }
                    } else {
                        malformedCounter++
                        totalMalformedPackets++
                        if (malformedCounter < 5 || malformedCounter % 100 == 0L) {
                            SessionLog.w(TAG, "malformed packet #$pktCounter (len=${packet.length}) addr=${packet.address.hostAddress}")
                        }
                    }
                    // Diagnostic: every 5s dump raw receive count and how many
                    // frames have actually been assembled. This helps tell the
                    // difference between "sender is silent", "packets arrive but
                    // are malformed", and "frames never complete".
                    val diagNow = System.currentTimeMillis()
                    if (diagNow - lastDiagLog > 5000) {
                        lastDiagLog = diagNow
                        SessionLog.i(
                            TAG,
                            "rx diag: pkts=$pktCounter malformed=$malformedCounter " +
                                "completedFrames(total)=$totalReceivedFrames pendingAssemblies=${videoFrameBuffers.size + audioFrameBuffers.size}"
                        )
                    }
                    tickPeriodicWork(forceStats = false)
                }
            } catch (e: Exception) {
                if (isListening) {
                    SessionLog.e(TAG, "Error in UdpReceiver listener", e)
                }
            }
        }
    }

    /**
     * Coherence check on top of the framing-level parser: catches packets
     * whose header parsed but whose group layout is obviously broken
     * (out-of-range index, zero total, oversized payload, etc.).
     */
    private fun isPacketCoherent(parsed: PacketProtocol.ParsedPacket): Boolean {
        if (parsed.totalPackets <= 0) return false
        if (parsed.packetIndex < 0 || parsed.packetIndex >= parsed.totalPackets) return false
        if (parsed.payload.size > PacketProtocol.MAX_PAYLOAD_SIZE) return false
        return true
    }

    private fun processParsedPacket(parsed: PacketProtocol.ParsedPacket) {
        val now = System.currentTimeMillis()
        if (parsed.timestampMs > 0 && parsed.timestampMs <= now) {
            currentLatencyMs = (now - parsed.timestampMs).coerceIn(1, 1000)
        }

        // Stale frame eviction has been moved to tickPeriodicWork to keep the hot path lightweight.

        val frameBuffers = if (parsed.isAudio) audioFrameBuffers else videoFrameBuffers
        val assembly = frameBuffers.getOrPut(parsed.frameSeq) {
            FrameAssembly(
                frameSeq = parsed.frameSeq,
                totalPackets = parsed.totalPackets,
                isKeyframe = parsed.isKeyframe,
                isCodecConfig = parsed.isCodecConfig,
                isHevc = parsed.isHevc,
                isAudio = parsed.isAudio,
                timestampMs = parsed.timestampMs,
                firstSeenMs = now
            )
        }

        // If the sender ever reports a different totalPackets for the same
        // seq (retransmit, mtd mismatch, bug), prefer the larger of the two
        // so we don't shrink an in-flight assembly and silently drop already
        // received packets.
        if (parsed.totalPackets != assembly.totalPackets && parsed.totalPackets > assembly.totalPackets) {
            SessionLog.w(
                TAG,
                "assembly totalPackets grew: seq=${assembly.frameSeq} ${assembly.totalPackets}->${parsed.totalPackets}"
            )
        }

        if (assembly.addPacket(parsed.packetIndex, parsed.payload)) {
            val completeFrame = assembly.assemble()
            frameBuffers.remove(parsed.frameSeq)
            windowReceivedFrames++
            totalReceivedFrames++

            // Clean up old incomplete frames to prevent memory leaks
            if (frameBuffers.size > 20) {
                val oldestKeys = frameBuffers.keys().toList().sorted().take(10)
                for (k in oldestKeys) {
                    frameBuffers.remove(k)
                    windowDroppedFrames++
                    totalDroppedFrames++
                }
            }

            // First few frames: log a digest so we can correlate with
            // decoder behavior. Subsequent frames: only log periodic samples
            // to avoid spamming.
            if (totalReceivedFrames <= 5 || totalReceivedFrames % 300 == 0L) {
                SessionLog.d(
                    TAG,
                    "frame #$totalReceivedFrames: seq=${parsed.frameSeq} " +
                        "key=${assembly.isKeyframe} cfg=${assembly.isCodecConfig} " +
                        "hevc=${assembly.isHevc} size=${completeFrame.size}B"
                )
            }

            if (jitterBufferMs <= 0) {
                if (assembly.isAudio) {
                    val nowMs = System.currentTimeMillis()
                    // Check if sender reset (restarted): seq number wrapped or massive pause
                    val isRestart = assembly.isCodecConfig || 
                                    parsed.frameSeq < lastAudioFrameSeq - 100 || 
                                    (nowMs - lastAudioFrameTime > 2000)
                    if (isRestart || parsed.frameSeq > lastAudioFrameSeq) {
                        lastAudioFrameSeq = parsed.frameSeq
                        lastAudioFrameTime = nowMs
                        // Audio frames skip the video decoder; route directly to AudioTrack.
                        onAudioFrame?.invoke(completeFrame, assembly.isCodecConfig, assembly.timestampMs)
                    } else {
                        SessionLog.w(TAG, "Dropped out-of-order audio frame: seq=${parsed.frameSeq}, last=$lastAudioFrameSeq")
                    }
                } else {
                    val nowMs = System.currentTimeMillis()
                    // Check if sender reset (restarted): seq number wrapped or massive pause
                    val isRestart = assembly.isCodecConfig || 
                                    parsed.frameSeq < lastVideoFrameSeq - 100 || 
                                    (nowMs - lastVideoFrameTime > 2000)
                    if (isRestart || parsed.frameSeq > lastVideoFrameSeq) {
                        lastVideoFrameSeq = parsed.frameSeq
                        lastVideoFrameTime = nowMs
                        onFrameAssembled?.invoke(
                            completeFrame,
                            assembly.isKeyframe,
                            assembly.isCodecConfig,
                            assembly.isHevc,
                            assembly.timestampMs,
                            parsed.frameSeq
                        )
                    } else {
                        SessionLog.w(TAG, "Dropped out-of-order video frame: seq=${parsed.frameSeq}, last=$lastVideoFrameSeq")
                    }
                }
            } else {
                val jitterFrame = JitterFrame(
                    seq = parsed.frameSeq,
                    timestampMs = assembly.timestampMs,
                    isAudio = assembly.isAudio,
                    data = completeFrame,
                    isKeyframe = assembly.isKeyframe,
                    isCodecConfig = assembly.isCodecConfig,
                    isHevc = assembly.isHevc,
                    assembledTimeMs = System.currentTimeMillis()
                )
                synchronized(jitterLock) {
                    if (assembly.isAudio) {
                        audioJitterQueue[parsed.frameSeq] = jitterFrame
                        if (audioJitterQueue.size > 50) {
                            val oldestKey = audioJitterQueue.firstKey()
                            audioJitterQueue.remove(oldestKey)
                        }
                    } else {
                        videoJitterQueue[parsed.frameSeq] = jitterFrame
                        if (videoJitterQueue.size > 50) {
                            val oldestKey = videoJitterQueue.firstKey()
                            videoJitterQueue.remove(oldestKey)
                        }
                    }
                    jitterLock.notifyAll()
                }
            }
        }
    }

    /**
     * Periodic work driven by the receive loop and by SoTimeout ticks.
     * Emits [StreamStats] at most once per [STATS_WINDOW_MS] and otherwise
     * does nothing — the receiver is intentionally event-driven.
     */
    private fun tickPeriodicWork(forceStats: Boolean) {
        val now = System.currentTimeMillis()

        // Periodically evict stale frame assemblies (every 250ms instead of on every packet)
        if (now - lastEvictionTime >= STATS_TICK_MS) {
            lastEvictionTime = now
            val listsToEvict = listOf(videoFrameBuffers, audioFrameBuffers)
            for (fb in listsToEvict) {
                if (fb.isNotEmpty()) {
                    val staleEntries = fb.entries.filter { now - it.value.lastUpdateMs > STALE_FRAME_TIMEOUT_MS }
                    for (entry in staleEntries) {
                        fb.remove(entry.key)
                        windowDroppedFrames++
                        totalDroppedFrames++
                        val ageMs = now - entry.value.firstSeenMs
                        SessionLog.w(
                            TAG,
                            "expired assembly seq=${entry.value.frameSeq} ageMs=$ageMs " +
                                "got=${entry.value.receivedCount}/${entry.value.totalPackets} " +
                                "key=${entry.value.isKeyframe} cfg=${entry.value.isCodecConfig} " +
                                "hevc=${entry.value.isHevc} audio=${entry.value.isAudio}"
                        )
                    }
                }
            }
        }

        val elapsed = now - lastStatsResetTime
        if (elapsed >= STATS_WINDOW_MS) {
            currentFps = (windowReceivedFrames * 1000f) / elapsed
            currentBitrateMbps = (windowBytesReceived * 8f) / (elapsed * 1000f)

            val totalExpected = (windowReceivedFrames + windowDroppedFrames).coerceAtLeast(1)
            currentPacketLossPercent = (windowDroppedFrames * 100f) / totalExpected

            val stats = StreamStats(
                isReceiving = true,
                fps = currentFps,
                bitrateMbps = currentBitrateMbps,
                latencyMs = currentLatencyMs,
                totalFrames = totalReceivedFrames,
                droppedFrames = totalDroppedFrames,
                packetLossPercent = currentPacketLossPercent
            )

            onStatsUpdated?.invoke(stats)

            // Reset the sliding window. Cumulative counters keep growing.
            windowReceivedFrames = 0
            windowDroppedFrames = 0
            windowBytesReceived = 0
            lastStatsResetTime = now
        } else if (forceStats) {
            // SoTimeout tick that didn't cross the window boundary yet —
            // still publish so the UI sees a live latency reading even when
            // no packets are arriving.
            val totalExpected = (windowReceivedFrames + windowDroppedFrames).coerceAtLeast(1)
            currentPacketLossPercent = (windowDroppedFrames * 100f) / totalExpected
            onStatsUpdated?.invoke(
                StreamStats(
                    isReceiving = isListening,
                    fps = currentFps,
                    bitrateMbps = currentBitrateMbps,
                    latencyMs = currentLatencyMs,
                    totalFrames = totalReceivedFrames,
                    droppedFrames = totalDroppedFrames,
                    packetLossPercent = currentPacketLossPercent
                )
            )
        }
    }

    fun stop() {
        isListening = false
        synchronized(jitterLock) {
            jitterLock.notifyAll()
        }
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        socket = null
        videoFrameBuffers.clear()
        audioFrameBuffers.clear()
        synchronized(jitterLock) {
            videoJitterQueue.clear()
            audioJitterQueue.clear()
        }
    }

    companion object {
        private const val TAG = "UdpReceiver"
        private const val STALE_FRAME_TIMEOUT_MS = 500L
        private const val STATS_WINDOW_MS = 1000L
        private const val STATS_TICK_MS = 250L
    }
}