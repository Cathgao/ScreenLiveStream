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
    // Fired when the receiver side decodes a sender-originated probe's
    // echo that somehow came back addressed to us (only meaningful when
    // the receiver itself is also running a probe, e.g. for round-trip
    // monitoring on the receive device). Optional; current sender uses
    // its own ping/echo loop, so this is left unused.
    var onProbeReply: ((probeSeq: Int, originalSendTimeNanos: Long) -> Unit)? = null

    private val videoFrameBuffers = ConcurrentHashMap<Int, FrameAssembly>()
    private val audioFrameBuffers = ConcurrentHashMap<Int, FrameAssembly>()

    // ---- Per-window stats (reset every STATS_WINDOW_MS) ----
    private var windowReceivedFrames = 0L
    // Receiver-side loss counters. NOTE: we deliberately removed the
    // earlier "networkDrops" estimate that was derived from frameSeq
    // gaps — it suffered from a dispatcher / listener race that produced
    // 95 %+ phantom loss on every test run. True network-layer loss
    // now comes exclusively from UdpRttProbe on the sender side (and
    // mirrored onto the receiver HUD via the sender's onStatsUpdate).
    // What stays here are *receiver* events that are unambiguously
    // observable inside one thread:
    //   "windowTimeoutDrops": assemblies swept by STALE_FRAME_TIMEOUT_MS.
    //   "windowEvictedDrops": assemblies we proactively dropped to keep
    //                          the in-flight byte budget.
    private var windowTimeoutDrops = 0L
    private var windowEvictedDrops = 0L
    private var windowBytesReceived = 0L
    private var lastStatsResetTime = System.currentTimeMillis()
    private var currentFps = 0f
    private var currentBitrateMbps = 0f
    // No longer used to publish link latency. Real RTT comes from
    // UdpRttProbe on the sender side. Kept so legacy callers can still
    // inspect the most recent sample if they ever want to.
    private var currentWallClockDeltaMs = 0L

    // Sender-side probe stats forwarded to the receiver by the service.
    // Updated every time the probe publishes (≈ 1 s). The receiver's
    // StreamStats is the union of receiver-side events and these two
    // network-ground-truth values.
    @Volatile
    private var lastReportedNetworkLossPercent: Float = 0f
    @Volatile
    private var lastReportedRttMs: Int = 0

    /**
     * Receives the latest RTT probe sample from the sender (mirrored
     * over the LAN via [setRttStats] on the receiver service). The
     * probe is the only credible network-loss / latency source on this
     * build; everything else here is receiver-side bookkeeping.
     */
    fun setRttStats(rttMs: Int, lossPercent: Float) {
        lastReportedRttMs = rttMs
        lastReportedNetworkLossPercent = lossPercent
    }

    // ---- Cumulative stats since the receiver started ----
    private var totalReceivedFrames = 0L
    private var totalTimeoutDrops = 0L
    private var totalEvictedDrops = 0L
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
    var jitterBufferMs: Int = 30 // 0 means direct low-latency mode, >0 enables reordering buffer
    // 30 ms is enough to absorb typical LAN/Wi-Fi jitter (5–20 ms) without adding perceptible
    // end-to-end latency. If packet loss dominated, a longer buffer would not save us — the
    // stream relies on the next IDR frame after a loss, so the buffer mainly smooths reorder.

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
        // Per-packet MTU is 1300 B, but it's an upper bound (last packet
        // is typically shorter). Using totalPackets * MAX_PAYLOAD_SIZE
        // over-estimates, so any in-flight byte count we derive is an
        // upper bound and triggers evict *later* — which is safer than
        // triggering it too eagerly. We track this so the periodic
        // sweeper can drive a real byte budget instead of an arbitrary
        // 20-slot cap.
        val expectedBytes: Int = totalPackets * PacketProtocol.MAX_PAYLOAD_SIZE

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
                    // Sender-broadcast stats beacon. These are FLAG_PING_STATS
                    // packets carrying rolling RTT / network-loss-percent
                    // numbers from the sender's UdpRttProbe. We pick them up
                    // here so the HUD can show real link RTT instead of the
                    // bogus wall-clock delta that used to clamp at 1000 ms.
                    // Cheap (one int-pair parse) and never touches frame
                    // assembly state.
                    val statsBeacon = PacketProtocol.readPingStatsPayload(packet.data, packet.length)
                    if (statsBeacon != null) {
                        // readPingStatsPayload returns (rttMs, lossBp).
                        // lossBp = lossPercent × 100.
                        setRttStats(
                            rttMs = statsBeacon.first,
                            lossPercent = statsBeacon.second / 100f
                        )
                        continue
                    }
                    // Probe packets share the same magic+version but use
                    // their own flag bits and a payload layout that the
                    // media parser would otherwise see as malformed. We
                    // sniff them out first and short-circuit.
                    val probe = PacketProtocol.readProbeSequence(packet.data, packet.length)
                    if (probe != null) {
                        if (probe.isReply) {
                            // Replies are echoed from us by the sender's
                            // RTT probe; on the *receiver* side they're
                            // meaningful only when we've been configured
                            // as bidirectional. Hook here so future code
                            // can react; today the receiver simply
                            // echoes incoming probes back to the sender
                            // if it ever receives one without a prior
                            // outbound ping.
                            if (onProbeReply != null) {
                                try { onProbeReply!!.invoke(probe.seq, probe.echoedNanos) } catch (_: Exception) {}
                            }
                        } else {
                            // Probe request from sender — bounce back
                            // via the same socket.
                            replyToProbe(packet.address, packet.port, probe)
                        }
                        continue
                    }
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
        // The legacy "currentLatencyMs" derived from a wall-clock delta
        // has been retired — sender and receiver may be on different
        // clocks (no NTP across phones), so this number is meaningless.
        // Real link RTT now comes from UdpRttProbe on the sender side.
        // We keep a tiny trace for debug only.
        if (parsed.timestampMs > 0 && parsed.timestampMs <= now) {
            currentWallClockDeltaMs = now - parsed.timestampMs
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

        // FrameSeq-gap network-loss estimation has been REMOVED: see the
        // note on the receiver-side counters above. True link-layer loss
        // now comes from UdpRttProbe.lossPercent only.

        if (assembly.addPacket(parsed.packetIndex, parsed.payload)) {
            val completeFrame = assembly.assemble()
            frameBuffers.remove(parsed.frameSeq)
            windowReceivedFrames++
            totalReceivedFrames++

            // Memory-pressure eviction is now driven by the periodic worker
            // on a real byte budget (see evictIfOverBudget below); the
            // receive path no longer thrashes the buffer on every complete
            // frame.

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
                        windowTimeoutDrops++
                        totalTimeoutDrops++
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
            // Independent of timeouts, also enforce the byte-budget guard.
            evictIfOverBudget()
        }

        val elapsed = now - lastStatsResetTime
        if (elapsed >= STATS_WINDOW_MS) {
            currentFps = (windowReceivedFrames * 1000f) / elapsed
            currentBitrateMbps = (windowBytesReceived * 8f) / (elapsed * 1000f)

            // Receiver-side loss reporting. Network-layer loss is *not*
            // computed here — the only reliable signal we have is the
            // sender's UdpRttProbe lossPercent which arrives via
            // QuestReceiverService.setRttStats(). We zero lossNetworkPct
            // from this side; the override below means "the last value
            // sent by the probe" if the receiver service has set one.
            val totalConsidered =
                (windowReceivedFrames + windowTimeoutDrops + windowEvictedDrops)
                    .coerceAtLeast(1)
            val lossTimeoutPct = (windowTimeoutDrops * 100f) / totalConsidered
            val lossEvictedPct = (windowEvictedDrops * 100f) / totalConsidered
            val inFlightBytes = videoFrameBuffers.values.sumOf { it.expectedBytes.toLong() } +
                audioFrameBuffers.values.sumOf { it.expectedBytes.toLong() }

            // Drop counts are receiver-side facts; network loss is
            // sender-side. Don't blend them into one number.
            val composite = (lossTimeoutPct + lossEvictedPct * 2f) / 3f

            val stats = StreamStats(
                isReceiving = true,
                fps = currentFps,
                bitrateMbps = currentBitrateMbps,
                latencyMs = currentWallClockDeltaMs,
                totalFrames = totalReceivedFrames,
                droppedFrames = totalTimeoutDrops + totalEvictedDrops,
                packetLossPercent = composite,
                lossTimeoutPercent = lossTimeoutPct,
                lossEvictedPercent = lossEvictedPct,
                // lossNetworkPercent / rttMs filled in by
                // QuestReceiverService from the probe.
                lossNetworkPercent = lastReportedNetworkLossPercent,
                rttMs = lastReportedRttMs,
                inFlightBytes = inFlightBytes,
                statsTimestampMs = now
            )

            onStatsUpdated?.invoke(stats)

            // Reset the sliding window. Cumulative counters keep growing.
            windowReceivedFrames = 0
            windowTimeoutDrops = 0
            windowEvictedDrops = 0
            windowBytesReceived = 0
            lastStatsResetTime = now
        } else if (forceStats) {
            // SoTimeout tick that didn't cross the window boundary yet —
            // still publish so the UI sees a live reading even when
            // no packets are arriving.
            val totalConsidered =
                (windowReceivedFrames + windowTimeoutDrops + windowEvictedDrops)
                    .coerceAtLeast(1)
            val lossTimeoutPct = (windowTimeoutDrops * 100f) / totalConsidered
            val lossEvictedPct = (windowEvictedDrops * 100f) / totalConsidered
            val inFlightBytes = videoFrameBuffers.values.sumOf { it.expectedBytes.toLong() } +
                audioFrameBuffers.values.sumOf { it.expectedBytes.toLong() }
            val composite = (lossTimeoutPct + lossEvictedPct * 2f) / 3f
            onStatsUpdated?.invoke(
                StreamStats(
                    isReceiving = isListening,
                    fps = currentFps,
                    bitrateMbps = currentBitrateMbps,
                    latencyMs = currentWallClockDeltaMs,
                    totalFrames = totalReceivedFrames,
                    droppedFrames = totalTimeoutDrops + totalEvictedDrops,
                    packetLossPercent = composite,
                    lossTimeoutPercent = lossTimeoutPct,
                    lossEvictedPercent = lossEvictedPct,
                    lossNetworkPercent = lastReportedNetworkLossPercent,
                    rttMs = lastReportedRttMs,
                    inFlightBytes = inFlightBytes,
                    statsTimestampMs = now
                )
            )
        }
    }

    /**
     * If the assembly buffers grow past [IN_FLIGHT_BYTE_BUDGET], drop
     * frames (oldest first) until we are at half the budget. Compared to
     * the previous "20-slot" hard cap this is honest about variable frame
     * sizes (a single 200 KB keyframe ~ 200 frames of static content).
     *
     * Drops are tallied into [windowEvictedDrops] / [totalEvictedDrops].
     */
    private fun evictIfOverBudget() {
        val video = videoFrameBuffers
        val audio = audioFrameBuffers
        if (video.isEmpty() && audio.isEmpty()) return

        val videoBytes = video.values.sumOf { it.expectedBytes.toLong() }
        val audioBytes = audio.values.sumOf { it.expectedBytes.toLong() }
        val inFlight = videoBytes + audioBytes
        if (inFlight <= IN_FLIGHT_BYTE_BUDGET) return

        // Drop oldest assemblies until we are back under the budget.
        // We tolerate eviction-driven transient loss because it's strictly
        // less catastrophic than OOM; the alternative is dropping at the
        // kernel level.
        var freed = 0L
        val ordered = (video.entries + audio.entries).sortedBy { it.value.firstSeenMs }
        for (entry in ordered) {
            if (inFlight - freed <= IN_FLIGHT_BYTE_BUDGET / 2) break
            val key = entry.key
            val fa = entry.value
            val target = if (video.containsKey(key)) video else audio
            target.remove(key)
            windowEvictedDrops++
            totalEvictedDrops++
            freed += fa.expectedBytes
            SessionLog.w(
                TAG,
                "evicted assembly seq=${fa.frameSeq} got=${fa.receivedCount}/${fa.totalPackets} " +
                    "size=${fa.expectedBytes}B budget=$IN_FLIGHT_BYTE_BUDGET freed=$freed"
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
        // Reset cumulative counters so a re-start of the receiver shows
        // fresh numbers instead of forever-increasing totals.
        totalReceivedFrames = 0
        totalTimeoutDrops = 0
        totalEvictedDrops = 0
        totalBytesReceived = 0
        totalMalformedPackets = 0
        windowReceivedFrames = 0
        windowTimeoutDrops = 0
        windowEvictedDrops = 0
        windowBytesReceived = 0
    }

    /**
     * Reply to a sender-initiated RTT probe by echoing the same payload
     * bytes back through the local socket. This is invoked from the
     * listener thread when we identify a packet carrying FLAG_PING.
     * The reply uses FLAG_PING_REPLY so the sender can correlate the
     * packet back to the in-flight probe.
     */
    private fun replyToProbe(source: java.net.InetAddress, sourcePort: Int, probe: PacketProtocol.ProbeType) {
        if (probe.isReply) return // never bounce a reply
        val socket = socket ?: return
        try {
            val bytes = PacketProtocol.buildPingReplyPacket(probe.seq, probe.echoedNanos)
            socket.send(DatagramPacket(bytes, bytes.size, source, sourcePort))
        } catch (e: Exception) {
            SessionLog.w(TAG, "replyToProbe failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "UdpReceiver"
        private const val STALE_FRAME_TIMEOUT_MS = 500L
        private const val STATS_WINDOW_MS = 1000L
        private const val STATS_TICK_MS = 250L
        // Hard cap on the total bytes we hold in unfinished assemblies.
        // 8 MB is roughly two full HD-keyframes at 16 Mbps / 250 ms — a
        // very generous buffer that still caps the worst case of a
        // stalled network keeping large keyframes hanging around.
        private const val IN_FLIGHT_BYTE_BUDGET = 8 * 1024 * 1024L
    }
}