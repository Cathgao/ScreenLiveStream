package com.cath.screencast.net

import com.cath.screencast.log.AppLogger as SessionLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Sender-side RTT probe over UDP ping.
 */
class RttProbe {
    @Volatile
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 0

    private val seqCounter = AtomicInteger(0)
    /** Outstanding probes: probeSeq -> System.nanoTime() at send. */
    private val inFlight = ConcurrentHashMap<Int, Long>()

    private var scheduler: ScheduledExecutorService? = null
    private var listenerThread: Thread? = null
    @Volatile
    private var isRunning = false

    /** Sliding window of the most recent RTTs (nanos). */
    private val rttWindowNanos = ArrayDeque<Long>()
    private val rttWindowLock = Object()

    private var totalSent: Long = 0
    private var totalLost: Long = 0

    /** Stats callback fired whenever the window rolls (≈ every second). */
    var onStats: ((ProbeStats) -> Unit)? = null

    data class ProbeStats(
        val lastRttMs: Int,           // newest sample; 0 if no reply yet
        val avgRttMs: Int,            // mean of window (excluding the cold-start sample)
        val p95RttMs: Int,            // 95th percentile over window (excluding cold-start)
        val jitterMs: Int,            // sample stdev in ms
        val sentCount: Long,          // cumulative probes sent since start()
        val lostCount: Long,          // cumulative probes that never came back
        val lossPercent: Float,       // (lost / sent) * 100; network-level loss estimate
        val windowSize: Int           // current number of samples in the window
    )

    fun start(targetIp: String, port: Int, socket: DatagramSocket? = null) {
        stop()
        try {
            val target = InetAddress.getByName(targetIp)
            this.targetAddress = target
            this.targetPort = port

            this.socket = socket ?: DatagramSocket().also {
                it.soTimeout = 200
            }
            // Reset window
            synchronized(rttWindowLock) { rttWindowNanos.clear() }
            inFlight.clear()
            totalSent = 0
            totalLost = 0

            isRunning = true
            listenerThread = thread(start = true, name = "RttProbeReplyThread") {
                val buf = ByteArray(64)
                val pkt = DatagramPacket(buf, buf.size)
                while (isRunning) {
                    try {
                        pkt.length = buf.size
                        (this.socket ?: break).receive(pkt)
                        val probe = PacketProtocol.readProbeSequence(pkt.data, pkt.length) ?: continue
                        if (probe.isReply) {
                            handleReply(probe.seq, probe.echoedNanos)
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // heart-beat: check for probe timeouts
                        sweepTimeouts()
                    } catch (e: Exception) {
                        if (isRunning) {
                            SessionLog.w(TAG, "RTT probe listener error: ${e.message}")
                        }
                    }
                }
            }

            scheduler = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "RttProbeSenderThread").also { it.isDaemon = true }
            }
            scheduler!!.scheduleAtFixedRate({
                sendOneProbe()
            }, 0L, pingIntervalMs, TimeUnit.MILLISECONDS)

            scheduler!!.scheduleAtFixedRate({ publishStats() }, 1L, 1L, TimeUnit.SECONDS)

            SessionLog.i(TAG, "RttProbe started → $targetIp:$port pingInterval=${pingIntervalMs}ms")
        } catch (e: Exception) {
            SessionLog.e(TAG, "RttProbe start failed", e)
            stop()
        }
    }

    private fun sendOneProbe() {
        val sock = socket ?: return
        val addr = targetAddress ?: return
        if (!isRunning) return
        try {
            val seq = seqCounter.incrementAndGet()
            val now = System.nanoTime()
            val bytes = PacketProtocol.buildPingPacket(seq, now)
            inFlight[seq] = now
            totalSent++
            sock.send(DatagramPacket(bytes, bytes.size, addr, targetPort))
        } catch (e: Exception) {
            SessionLog.w(TAG, "send probe failed: ${e.message}")
        }
    }

    private fun handleReply(seq: Int, echoedNanos: Long) {
        val sentNanos = inFlight.remove(seq) ?: return // not ours / already swept
        val rttNanos = System.nanoTime() - sentNanos
        if (rttNanos < 0) return // wrap-around safety
        synchronized(rttWindowLock) {
            rttWindowNanos.addLast(rttNanos)
            while (rttWindowNanos.size > WINDOW_SIZE) {
                rttWindowNanos.removeFirst()
            }
        }
    }

    private fun sweepTimeouts() {
        val nowNanos = System.nanoTime()
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(PROBE_TIMEOUT_MS)
        var lost = 0
        for ((seq, sentNanos) in inFlight) {
            if (nowNanos - sentNanos > timeoutNanos) {
                inFlight.remove(seq)
                lost++
            }
        }
        if (lost > 0) {
            totalLost += lost
        }
    }

    private fun publishStats() {
        sweepTimeouts()
        val snapshot = synchronized(rttWindowLock) { rttWindowNanos.toLongArray() }
        if (snapshot.isEmpty()) {
            onStats?.invoke(
                ProbeStats(
                    lastRttMs = 0,
                    avgRttMs = 0,
                    p95RttMs = 0,
                    jitterMs = 0,
                    sentCount = totalSent,
                    lostCount = totalLost,
                    lossPercent = if (totalSent > 0) (totalLost * 100f / totalSent) else 0f,
                    windowSize = 0
                )
            )
            return
        }
        val rawNanos = snapshot.map { TimeUnit.NANOSECONDS.toMillis(it).toInt() }.sorted()
        val nanos = if (rawNanos.size > 1) rawNanos.drop(1) else rawNanos
        if (nanos.isEmpty()) {
            onStats?.invoke(
                ProbeStats(
                    lastRttMs = rawNanos.last(),
                    avgRttMs = rawNanos.last(),
                    p95RttMs = rawNanos.last(),
                    jitterMs = 0,
                    sentCount = totalSent,
                    lostCount = totalLost,
                    lossPercent = if (totalSent > 0) (totalLost * 100f / totalSent) else 0f,
                    windowSize = rawNanos.size
                )
            )
            return
        }
        val avg = nanos.average().toInt()
        val p95Idx = (nanos.size * 0.95f).toInt().coerceAtMost(nanos.size - 1)
        val p95 = nanos[p95Idx]
        val mean = nanos.average()
        val variance = nanos.sumOf { (it - mean).let { d -> d * d } }.toDouble() / nanos.size
        val jitter = Math.sqrt(variance).toInt()
        onStats?.invoke(
            ProbeStats(
                lastRttMs = rawNanos.last(),
                avgRttMs = avg,
                p95RttMs = p95,
                jitterMs = jitter,
                sentCount = totalSent,
                lostCount = totalLost,
                lossPercent = if (totalSent > 0) (totalLost * 100f / totalSent) else 0f,
                windowSize = nanos.size
            )
        )
    }

    fun stop() {
        isRunning = false
        try {
            scheduler?.shutdownNow()
        } catch (_: Exception) {
            // ignore
        }
        scheduler = null
        listenerThread?.interrupt()
        listenerThread = null
        try {
            socket?.close()
        } catch (_: Exception) {
            // ignore
        }
        socket = null
        targetAddress = null
        inFlight.clear()
        synchronized(rttWindowLock) { rttWindowNanos.clear() }
    }

    companion object {
        private const val TAG = "RttProbe"
        private const val pingIntervalMs = 250L
        private const val PROBE_TIMEOUT_MS = 1500L
        private const val WINDOW_SIZE = 30
    }
}
