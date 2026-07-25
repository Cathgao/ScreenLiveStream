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
    const val FLAG_NACK: Byte = 0x80.toByte()
    const val FLAG_PLI: Byte = 0x81.toByte()

    fun buildNackPacket(frameSeq: Int, packetIndex: Int): ByteArray {
        val packet = ByteArray(HEADER_SIZE)
        val bb = ByteBuffer.wrap(packet)
        bb.put(MAGIC_0)
        bb.put(MAGIC_1)
        bb.put(VERSION)
        bb.put(FLAG_NACK)
        bb.putInt(frameSeq)
        bb.putLong(0L)
        bb.putShort(packetIndex.toShort())
        bb.putShort(0)
        bb.putShort(0)
        return packet
    }

    fun buildPliPacket(): ByteArray {
        val packet = ByteArray(HEADER_SIZE)
        val bb = ByteBuffer.wrap(packet)
        bb.put(MAGIC_0)
        bb.put(MAGIC_1)
        bb.put(VERSION)
        bb.put(FLAG_PLI)
        bb.putInt(0)
        bb.putLong(0L)
        bb.putShort(0)
        bb.putShort(0)
        bb.putShort(0)
        return packet
    }

    fun parseNackPacket(data: ByteArray, length: Int): Pair<Int, Int>? {
        if (length < HEADER_SIZE) return null
        if (data[0] != MAGIC_0 || data[1] != MAGIC_1) return null
        if (data[3] != FLAG_NACK) return null
        val bb = ByteBuffer.wrap(data, 4, length - 4)
        val frameSeq = bb.int
        bb.long // timestamp
        val packetIndex = bb.short.toInt() and 0xFFFF
        return Pair(frameSeq, packetIndex)
    }

    fun isPliPacket(data: ByteArray, length: Int): Boolean {
        if (length < HEADER_SIZE) return false
        if (data[0] != MAGIC_0 || data[1] != MAGIC_1) return false
        return data[3] == FLAG_PLI
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
                val bb = ByteBuffer.wrap(data, 4, length - 4)
                ProbeType(isReply = false, seq = bb.int, echoedNanos = 0L)
            }
            (flags and FLAG_PING_REPLY.toInt()) != 0 -> {
                val bb = ByteBuffer.wrap(data, 4, length - 4)
                ProbeType(
                    isReply = true,
                    seq = bb.int,
                    // skip the 8B wall-clock field after frameSeq (int=4B + long=8B = total 12B from offset 4)
                    echoedNanos = bb.getLong(12)
                )
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
        val bb = ByteBuffer.wrap(data, HEADER_SIZE, 8)
        val rttMs = bb.int and 0x7FFFFFFF
        val lossBps = bb.int and 0x7FFFFFFF
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
        val payload: ByteArray
    )

    fun parsePacket(data: ByteArray, length: Int): ParsedPacket? {
        if (length < HEADER_SIZE) return null
        if (data[0] != MAGIC_0 || data[1] != MAGIC_1) return null

        val bb = ByteBuffer.wrap(data, 0, length)
        bb.position(3) // skip magic + version
        val flags = bb.get().toInt()

        val isKeyframe = (flags and FLAG_KEYFRAME.toInt()) != 0
        val isCodecConfig = (flags and FLAG_CODEC_CONFIG.toInt()) != 0
        val isHevc = (flags and FLAG_CODEC_HEVC.toInt()) != 0
        val isAudio = (flags and FLAG_AUDIO.toInt()) != 0

        val frameSeq = bb.int
        val timestampMs = bb.long
        val packetIndex = bb.short.toInt() and 0xFFFF
        val totalPackets = bb.short.toInt() and 0xFFFF
        val payloadSize = bb.short.toInt() and 0xFFFF

        if (length < HEADER_SIZE + payloadSize) return null

        val payload = ByteArray(payloadSize)
        System.arraycopy(data, HEADER_SIZE, payload, 0, payloadSize)

        return ParsedPacket(
            frameSeq = frameSeq,
            timestampMs = timestampMs,
            packetIndex = packetIndex,
            totalPackets = totalPackets,
            isKeyframe = isKeyframe,
            isCodecConfig = isCodecConfig,
            isHevc = isHevc,
            isAudio = isAudio,
            payload = payload
        )
    }
}
