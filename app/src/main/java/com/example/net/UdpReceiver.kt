package com.example.net

import android.util.Log
import com.example.log.SessionLog
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

    // Callback for decoded frames: (frameBytes, isKeyframe, isCodecConfig, isHevc, timestampMs)
    var onFrameAssembled: ((ByteArray, Boolean, Boolean, Boolean, Long) -> Unit)? = null
    // Callback for completed audio frames: (aacBytes, isCodecConfig, timestampMs)
    var onAudioFrame: ((ByteArray, Boolean, Long) -> Unit)? = null
    var onStatsUpdated: ((StreamStats) -> Unit)? = null

    private val frameBuffers = ConcurrentHashMap<Int, FrameAssembly>()

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
        listenerThread = thread(start = true, name = "UdpReceiverThread") {
            try {
                val ds = DatagramSocket(port)
                val requestedReceiveBuffer = 2 * 1024 * 1024 // 2MB receive socket buffer for high throughput
                ds.receiveBufferSize = requestedReceiveBuffer
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
                                "completedFrames(total)=$totalReceivedFrames pendingAssemblies=${frameBuffers.size}"
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

        // Evict stale partial assemblies. Without this, a frame whose last
        // packets were lost in transit would sit in the map until 20 newer
        // seq numbers accumulated and forced it out — wasting memory and
        // skewing stats. 500ms is well under the sender's 1.5s keyframe
        // cadence so we never throw away a frame that might still complete.
        if (frameBuffers.isNotEmpty()) {
            val staleEntries = frameBuffers.entries.filter { now - it.value.lastUpdateMs > STALE_FRAME_TIMEOUT_MS }
            for (entry in staleEntries) {
                frameBuffers.remove(entry.key)
                windowDroppedFrames++
                totalDroppedFrames++
                // Log expired assemblies: we want to know whether we're
                // systematically losing the same kind of frame (keyframe vs
                // P-frame, audio vs video) — that's a hint that something
                // upstream is wrong rather than random UDP loss.
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

            if (assembly.isAudio) {
                // Audio frames skip the video decoder; route directly to AudioTrack.
                onAudioFrame?.invoke(completeFrame, assembly.isCodecConfig, assembly.timestampMs)
            } else {
                onFrameAssembled?.invoke(
                    completeFrame,
                    assembly.isKeyframe,
                    assembly.isCodecConfig,
                    assembly.isHevc,
                    assembly.timestampMs
                )
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
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        socket = null
        frameBuffers.clear()
    }

    companion object {
        private const val TAG = "UdpReceiver"
        private const val STALE_FRAME_TIMEOUT_MS = 500L
        private const val STATS_WINDOW_MS = 1000L
        private const val STATS_TICK_MS = 250L
    }
}