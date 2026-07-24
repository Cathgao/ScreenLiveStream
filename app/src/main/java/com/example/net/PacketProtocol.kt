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
        isHevc: Boolean
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
        bb.put(flags)

        bb.putInt(frameSeq)
        bb.putLong(timestampMs)
        bb.putShort(packetIndex)
        bb.putShort(totalPackets)
        bb.putShort(payloadSize.toShort())

        System.arraycopy(buffer, offset, packet, HEADER_SIZE, payloadSize)
        return packet
    }

    class ParsedPacket(
        val frameSeq: Int,
        val timestampMs: Long,
        val packetIndex: Int,
        val totalPackets: Int,
        val isKeyframe: Boolean,
        val isCodecConfig: Boolean,
        val isHevc: Boolean,
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
            payload = payload
        )
    }
}
