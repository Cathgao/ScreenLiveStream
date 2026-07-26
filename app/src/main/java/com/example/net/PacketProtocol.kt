package com.example.net

import java.nio.ByteBuffer

object PacketProtocol {
    const val MAGIC_0: Byte = 0x51 // 'Q'
    const val MAGIC_1: Byte = 0x43 // 'C'
    const val VERSION: Byte = 0x01

    const val HEADER_SIZE = 22
    const val MAX_PAYLOAD_SIZE = 1300

    const val FLAG_KEYFRAME: Byte = 0x01
    const val FLAG_CODEC_CONFIG: Byte = 0x02
    const val FLAG_CODEC_HEVC: Byte = 0x04
    const val FLAG_AUDIO: Byte = 0x08
    // Bit 0x10: this packet is a sender → receiver RTT probe.
    // Bit 0x20: this packet is a receiver → sender echo of a probe.
    // Bit 0x40: this packet is a sender → receiver "ping stats beacon"
    //           carrying the latest rolling RTT / lossPercent values.
    //           The 8-byte payload encodes two 32-bit ints:
    //             bytes  0-3 : rolling RTT in milliseconds (uint32)
    //             bytes  4-7 : network loss percent in basis-points
    //                          (0..10000 → 0.00% .. 100.00%) to fit a
    //                          float-precision number into an int.
    // All three flags share the same 22-byte header; their payload
    // occupies the same position where media payloads normally live.
    const val FLAG_PING: Byte = 0x10
    const val FLAG_PING_REPLY: Byte = 0x20
    const val FLAG_PING_STATS: Byte = 0x40
    const val FLAG_STREAM_STOP: Byte = 0x82.toByte()

    fun buildStreamStopPacket(): ByteArray {
        val packet = ByteArray(HEADER_SIZE)
        val bb = ByteBuffer.wrap(packet)
        bb.put(MAGIC_0)
        bb.put(MAGIC_1)
        bb.put(VERSION)
        bb.put(FLAG_STREAM_STOP)
        // Rest can be zeros
        return packet
    }

    fun isStreamStopPacket(data: ByteArray, length: Int): Boolean {
        if (length < HEADER_SIZE) return false
        if (data[0] != MAGIC_0 || data[1] != MAGIC_1) return false
        return data[3] == FLAG_STREAM_STOP
    }

    // Discovery UDP Constants
    const val DISCOVERY_PORT = 9998
    const val DISCOVERY_PING = "QUEST_CAST_DISCOVER_PING"
    const val DISCOVERY_ACK_PREFIX = "QUEST_CAST_RECEIVER_ACK:"
    const val DISCOVERY_BEACON_PREFIX = "QUEST_CAST_BEACON:"

    fun buildPacket(
        buffer: ByteArray,
        offset: Int,
        payloadSize: Int,
        frameSeq: Int,
        timestampMs: Long,
        packetIndex: Short,
        totalPackets: Short,
        isKeyframe: Boolean,
        isCodecConfig: Boolean,
        isHevc: Boolean,
        isAudio: Boolean = false
    ): ByteArray {
        val totalLength = HEADER_SIZE + payloadSize
        val packet = ByteArray(totalLength)
        val bb = ByteBuffer.wrap(packet)

        bb.put(MAGIC_0)
        bb.put(MAGIC_1)
        bb.put(VERSION)

        var flags: Byte = 0
        if (isKeyframe) flags = (flags.toInt() or FLAG_KEYFRAME.toInt()).toByte()
        if (isCodecConfig) flags = (flags.toInt() or FLAG_CODEC_CONFIG.toInt()).toByte()
        if (isHevc) flags = (flags.toInt() or FLAG_CODEC_HEVC.toInt()).toByte()
        if (isAudio) flags = (flags.toInt() or FLAG_AUDIO.toInt()).toByte()
        bb.put(flags)

        bb.putInt(frameSeq)
        bb.putLong(timestampMs)
        bb.putShort(packetIndex)
        bb.putShort(totalPackets)
        bb.putShort(payloadSize.toShort())

        System.arraycopy(buffer, offset, packet, HEADER_SIZE, payloadSize)
        return packet
    }

    /**
     * Build a sender-initiated RTT probe. The 8 byte payload carries the
     * monotonic send timestamp (nanos since some arbitrary origin) so the
     * receiver can echo it back unchanged. Frame-level fields (idx/total)
     * are zero; only the int4 frameSeq is used as a probe id.
     */
    fun buildPingPacket(probeSeq: Int, sendTimeNanos: Long): ByteArray {
        val packet = ByteArray(HEADER_SIZE + 8)
        val bb = ByteBuffer.wrap(packet)
        bb.put(MAGIC_0)
        bb.put(MAGIC_1)
        bb.put(VERSION)
        bb.put(FLAG_PING)
        bb.putInt(probeSeq)
        bb.putLong(0L) // wall-clock field unused for probes
        bb.putShort(0) // packetIndex
        bb.putShort(1) // totalPackets (1 of 1)
        bb.putShort(8) // payload size
        bb.putLong(sendTimeNanos)
        return packet
    }

    /** Build the echo reply for a probe. */
    fun buildPingReplyPacket(probeSeq: Int, originalSendTimeNanos: Long): ByteArray {
        val packet = ByteArray(HEADER_SIZE + 8)
        val bb = ByteBuffer.wrap(packet)
        bb.put(MAGIC_0)
        bb.put(MAGIC_1)
        bb.put(VERSION)
        bb.put(FLAG_PING_REPLY)
        bb.putInt(probeSeq)
        bb.putLong(0L)
        bb.putShort(0)
        bb.putShort(1)
        bb.putShort(8)
        bb.putLong(originalSendTimeNanos)
        return packet
    }

    /**
     * Lightweight check: identify a RTT probe packet so the receiver knows
     * to skip frame-assembly bookkeeping and reply with [buildPingReplyPacket].
     * Returns null if the bytes are not a recognizable ping / echo.
     *
     * Note: FLAG_PING_STATS beacons are *not* ping packets — they are a
     * one-way broadcast of rolling RTT / loss-percent statistics. Read them
     * via [readPingStatsPayload] instead.
     */
    fun readProbeSequence(data: ByteArray, length: Int): ProbeType? {
        if (length < HEADER_SIZE + 8) return null
        if (data[0] != MAGIC_0 || data[1] != MAGIC_1) return null
        val flags = data[3].toInt() and 0xFF
        return when {
            (flags and FLAG_PING.toInt()) != 0 -> {
                val seq = ((data[4].toInt() and 0xFF) shl 24) or
                          ((data[5].toInt() and 0xFF) shl 16) or
                          ((data[6].toInt() and 0xFF) shl 8) or
                          (data[7].toInt() and 0xFF)
                ProbeType(isReply = false, seq = seq, echoedNanos = 0L)
            }
            (flags and FLAG_PING_REPLY.toInt()) != 0 -> {
                val seq = ((data[4].toInt() and 0xFF) shl 24) or
                          ((data[5].toInt() and 0xFF) shl 16) or
                          ((data[6].toInt() and 0xFF) shl 8) or
                          (data[7].toInt() and 0xFF)
                // skip 8B wall-clock field after frameSeq, echoedNanos starts at byte index 16
                val echoedNanos = ((data[16].toLong() and 0xFFL) shl 56) or
                                  ((data[17].toLong() and 0xFFL) shl 48) or
                                  ((data[18].toLong() and 0xFFL) shl 40) or
                                  ((data[19].toLong() and 0xFFL) shl 32) or
                                  ((data[20].toLong() and 0xFFL) shl 24) or
                                  ((data[21].toLong() and 0xFFL) shl 16) or
                                  ((data[22].toLong() and 0xFFL) shl 8) or
                                  (data[23].toLong() and 0xFFL)
                ProbeType(isReply = true, seq = seq, echoedNanos = echoedNanos)
            }
            else -> null
        }
    }

    /**
     * Read the payload of a FLAG_PING_STATS beacon. Returns
     * (rttMs, lossPercent×100) where lossPercent×100 is an integer
     * in [0..10000] → divide by 100f to get the percentage.
     */
    fun readPingStatsPayload(data: ByteArray, length: Int): Pair<Int, Int>? {
        if (length < HEADER_SIZE + 8) return null
        if (data[0] != MAGIC_0 || data[1] != MAGIC_1) return null
        val flags = data[3].toInt() and 0xFF
        if ((flags and FLAG_PING_STATS.toInt()) == 0) return null
        val rttMs = (((data[HEADER_SIZE].toInt() and 0xFF) shl 24) or
                    ((data[HEADER_SIZE + 1].toInt() and 0xFF) shl 16) or
                    ((data[HEADER_SIZE + 2].toInt() and 0xFF) shl 8) or
                    (data[HEADER_SIZE + 3].toInt() and 0xFF)) and 0x7FFFFFFF
        val lossBps = (((data[HEADER_SIZE + 4].toInt() and 0xFF) shl 24) or
                      ((data[HEADER_SIZE + 5].toInt() and 0xFF) shl 16) or
                      ((data[HEADER_SIZE + 6].toInt() and 0xFF) shl 8) or
                      (data[HEADER_SIZE + 7].toInt() and 0xFF)) and 0x7FFFFFFF
        return Pair(rttMs, lossBps)
    }

    /** Build a sender-side beacon carrying the latest rolling RTT and
     *  network-loss estimate. Receiver reads this via [readPingStatsPayload].
     */
    fun buildPingStatsPacket(rttMs: Int, lossPercentX100: Int): ByteArray {
        val packet = ByteArray(HEADER_SIZE + 8)
        val bb = ByteBuffer.wrap(packet)
        bb.put(MAGIC_0)
        bb.put(MAGIC_1)
        bb.put(VERSION)
        bb.put(FLAG_PING_STATS)
        bb.putInt(0) // frameSeq unused for stats beacons
        bb.putLong(0L)
        bb.putShort(0)
        bb.putShort(1)
        bb.putShort(8)
        bb.putInt(rttMs.coerceAtLeast(0))
        bb.putInt(lossPercentX100.coerceIn(0, 10000))
        return packet
    }

    data class ProbeType(val isReply: Boolean, val seq: Int, val echoedNanos: Long)

    class ParsedPacket(
        val frameSeq: Int,
        val timestampMs: Long,
        val packetIndex: Int,
        val totalPackets: Int,
        val isKeyframe: Boolean,
        val isCodecConfig: Boolean,
        val isHevc: Boolean,
        val isAudio: Boolean,
        val payloadSize: Int,
        val payloadOffset: Int
    )

    fun parsePacket(data: ByteArray, length: Int): ParsedPacket? {
        if (length < HEADER_SIZE) return null
        if (data[0] != MAGIC_0 || data[1] != MAGIC_1) return null

        val flags = data[3].toInt() and 0xFF

        val isKeyframe = (flags and FLAG_KEYFRAME.toInt()) != 0
        val isCodecConfig = (flags and FLAG_CODEC_CONFIG.toInt()) != 0
        val isHevc = (flags and FLAG_CODEC_HEVC.toInt()) != 0
        val isAudio = (flags and FLAG_AUDIO.toInt()) != 0

        val frameSeq = ((data[4].toInt() and 0xFF) shl 24) or
                       ((data[5].toInt() and 0xFF) shl 16) or
                       ((data[6].toInt() and 0xFF) shl 8) or
                       (data[7].toInt() and 0xFF)

        val timestampMs = ((data[8].toLong() and 0xFFL) shl 56) or
                          ((data[9].toLong() and 0xFFL) shl 48) or
                          ((data[10].toLong() and 0xFFL) shl 40) or
                          ((data[11].toLong() and 0xFFL) shl 32) or
                          ((data[12].toLong() and 0xFFL) shl 24) or
                          ((data[13].toLong() and 0xFFL) shl 16) or
                          ((data[14].toLong() and 0xFFL) shl 8) or
                          (data[15].toLong() and 0xFFL)

        val packetIndex = ((data[16].toInt() and 0xFF) shl 8) or (data[17].toInt() and 0xFF)
        val totalPackets = ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
        val payloadSize = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)

        if (length < HEADER_SIZE + payloadSize) return null

        return ParsedPacket(
            frameSeq = frameSeq,
            timestampMs = timestampMs,
            packetIndex = packetIndex,
            totalPackets = totalPackets,
            isKeyframe = isKeyframe,
            isCodecConfig = isCodecConfig,
            isHevc = isHevc,
            isAudio = isAudio,
            payloadSize = payloadSize,
            payloadOffset = HEADER_SIZE
        )
    }
}
