package com.cath.screencast.net

import android.util.Log
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
 * Sender-side RTT probe.
 *
 * Pattern is the classic ICMP ping/echo: every [pingIntervalMs] we
 * fire a FLAG_PING packet through the same UDP socket the media uses,
 * record the (seq, sendTimeNanos) pair, and wait for the receiver to
 * bounce it back with FLAG_PING_REPLY. When the reply lands we compute
 * the round-trip in nanoseconds and feed it into a sliding window.
 *
 * RTT computed this way is *independent of clock skew* between the
 * two devices because we only ever read time on the sender side. It
 * is also our only true measurement of network-layer loss: if a probe
 * never comes back, that one probe is counted as a lost packet.
 *
 * The class is fire-and-forget from the sender service's point of
 * view: start with target IP+port, expose [onStats] for the
 * pipeline aggregator, then stop().
 */
class UdpRttProbe {
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

    // Cumulative counters since [start]. Plain Longs guarded by the
    // sender thread for sent and by the listener thread for the other
    // primitives; only the read paths in publishStats race with the
    // writers and that race is benign (a slightly stale read is fine
    // for a 1-second-statistics emitter).
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
            // We always use the *own* socket for probes so we don't
            // contend with media traffic for the same receive buffer.
            // (Using the media socket's send queue is fine — the
            // listener thread can still receive replies — but reading
            // them gets tangled with the UdpReceiver callback chain.
            // A small dedicated socket makes the lifecycle trivial.)
            this.socket = socket ?: DatagramSocket().also {
                it.soTimeout = 200
            }
            // Reset window
            synchronized(rttWindowLock) { rttWindowNanos.clear() }
            inFlight.clear()
            totalSent = 0
            totalLost = 0

            isRunning = true
            listenerThread = thread(start = true, name = "UdpRttProbeReply") {
                val buf = ByteArray(64)
                val pkt = DatagramPacket(buf, buf.size)
                while (isRunning) {
                    try {
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
                Thread(r, "UdpRttProbeSender").also { it.isDaemon = true }
            }
            scheduler!!.scheduleAtFixedRate({
                sendOneProbe()
            }, 0L, pingIntervalMs, TimeUnit.MILLISECONDS)

            // Periodic stats publication — separate from sends so it
            // runs even if probes are short-circuited.
            scheduler!!.scheduleAtFixedRate({ publishStats() }, 1L, 1L, TimeUnit.SECONDS)

            SessionLog.i(TAG, "UdpRttProbe started → $targetIp:$port pingInterval=${pingIntervalMs}ms")
        } catch (e: Exception) {
            SessionLog.e(TAG, "UdpRttProbe start failed", e)
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
        val now = System.nanoTime()
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
        // Drop the very first reply from the window. That sample is the
        // cold-start one: it pays for socket warm-up, JIT, executor
        // thread boot, and the first System.nanoTime() read. On the
        // user's test it kept coming back as exactly 95 ms for ~6
        // samples until the JVM warmed up, even though the steady-state
        // RTT was 22 ms. We only drop *one* sample so the window still
        // represents reality on the very first stats tick.
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
        private const val TAG = "UdpRttProbe"
        /** 250 ms — fast enough to spot a hiccup, slow enough not to add noticeable overhead. */
        private const val pingIntervalMs = 250L
        /** Probe considered lost after this much wall time without a reply. */
        private const val PROBE_TIMEOUT_MS = 1500L
        /** Sliding window size for RTT averaging. */
        private const val WINDOW_SIZE = 30
    }
}
