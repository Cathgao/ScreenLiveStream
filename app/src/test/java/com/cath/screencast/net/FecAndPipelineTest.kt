package com.cath.screencast.net

import com.cath.screencast.log.AppLogger
import com.cath.screencast.model.VideoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FecAndPipelineTest {

    @Test
    fun `udp fec streamer and receiver handle codec config and large keyframe over 512 fragments`() {
        val port = DatagramSocket(0).use { it.localPort }
        val receiver = UdpFecReceiver(port)
        val streamer = UdpFecStreamer(targetBitrateKbps = 40000, targetFps = 90)

        val receivedFrames = CopyOnWriteArrayList<Triple<Int, Boolean, ByteArray>>()
        val latch = CountDownLatch(2)

        receiver.onFrameAssembled = { bytes, size, isKeyframe, isCodecConfig, _, _, seq ->
            receivedFrames.add(Triple(seq, isCodecConfig, bytes.copyOf(size)))
            if (isCodecConfig || isKeyframe) {
                latch.countDown()
            }
        }

        receiver.start(port)
        Thread.sleep(50)
        streamer.start("127.0.0.1", port)

        try {
            val configPayload = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E)
            // 700,000 bytes -> 539 fragments (> 512, which previously overflowed MAX_FEC_GROUPS=64)
            val largeKeyframe = ByteArray(700_000) { idx -> (idx * 31 + 7).toByte() }

            streamer.sendFrame(configPayload, 0, configPayload.size, 0L, isKeyframe = false, isCodecConfig = true, codec = VideoCodec.H264)
            streamer.sendFrame(largeKeyframe, 0, largeKeyframe.size, 11L, isKeyframe = true, isCodecConfig = false, codec = VideoCodec.H264)

            assertTrue("Timed out waiting for CodecConfig and large keyframe", latch.await(5, TimeUnit.SECONDS))
            assertEquals(2, receivedFrames.size)
            assertTrue(receivedFrames[0].second)
            assertArrayEquals(configPayload, receivedFrames[0].third)
            assertArrayEquals(largeKeyframe, receivedFrames[1].third)
        } finally {
            streamer.stop()
            receiver.stop()
        }
    }

    @Test
    fun `udp fec receiver recovers single packet loss in large frame over 512 fragments`() {
        val port = DatagramSocket(0).use { it.localPort }
        val receiver = UdpFecReceiver(port)

        // 550 fragments -> 69 FEC groups (exceeds old MAX_FEC_GROUPS=64)
        val frameSize = 550 * 1300
        val expectedFrame = ByteArray(frameSize) { i -> (i xor (i ushr 8)).toByte() }
        val totalFragments = 550
        val fecGroupSize = 8
        val numFecGroups = (totalFragments + fecGroupSize - 1) / fecGroupSize // 69

        val receivedLatch = CountDownLatch(1)
        var assembledCopy: ByteArray? = null

        receiver.onFrameAssembled = { bytes, size, _, _, _, _, _ ->
            assembledCopy = bytes.copyOf(size)
            receivedLatch.countDown()
        }

        receiver.start(port)
        Thread.sleep(50)

        val senderSock = DatagramSocket()
        try {
            val addr = InetAddress.getByName("127.0.0.1")
            val fecPayloads = Array(numFecGroups) { ByteArray(1300) }

            // Drop fragment #5 in group 5 to test interleaved recovery with 69 groups
            val droppedFragIndex = 5
            for (i in 0 until totalFragments) {
                val offset = i * 1300
                val g = i % numFecGroups
                val fec = fecPayloads[g]
                for (b in 0 until 1300) {
                    fec[b] = (fec[b].toInt() xor expectedFrame[offset + b].toInt()).toByte()
                }
                if (i == droppedFragIndex) continue

                val pktBuf = ByteBuffer.allocate(28 + 1300)
                pktBuf.putInt(0x55445056)
                pktBuf.putInt(0) // seq
                pktBuf.putLong(100L) // ts
                pktBuf.put(1.toByte()) // keyframe
                pktBuf.putShort(i.toShort())
                pktBuf.putShort(totalFragments.toShort())
                pktBuf.putInt(frameSize)
                pktBuf.put(fecGroupSize.toByte())
                pktBuf.put((numFecGroups and 0xFF).toByte())
                pktBuf.put(0.toByte())
                pktBuf.put(expectedFrame, offset, 1300)
                senderSock.send(DatagramPacket(pktBuf.array(), pktBuf.capacity(), addr, port))
            }

            // Send FEC parity for group 5
            val fecBuf = ByteBuffer.allocate(28 + 1300)
            fecBuf.putInt(0x55445056)
            fecBuf.putInt(0)
            fecBuf.putLong(100L)
            fecBuf.put((1 or 8).toByte()) // keyframe + FEC
            fecBuf.putShort(5.toShort()) // groupId = 5
            fecBuf.putShort(totalFragments.toShort())
            fecBuf.putInt(frameSize)
            fecBuf.put(fecGroupSize.toByte())
            fecBuf.put((numFecGroups and 0xFF).toByte())
            fecBuf.put(0.toByte())
            fecBuf.put(fecPayloads[5], 0, 1300)
            senderSock.send(DatagramPacket(fecBuf.array(), fecBuf.capacity(), addr, port))

            assertTrue("Timed out waiting for FEC recovery", receivedLatch.await(3, TimeUnit.SECONDS))
            assertArrayEquals(expectedFrame, assembledCopy)
        } finally {
            senderSock.close()
            receiver.stop()
        }
    }

    @Test
    fun `udp fec receiver dispatches sequencer across ring wrap in ascending order`() {
        val port = DatagramSocket(0).use { it.localPort }
        val receiver = UdpFecReceiver(port)
        receiver.jitterBufferMs = 25

        val receivedSeqs = CopyOnWriteArrayList<Int>()
        val latch = CountDownLatch(3)

        receiver.onFrameAssembled = { _, _, _, _, _, _, seq ->
            receivedSeqs.add(seq)
            latch.countDown()
        }

        receiver.start(port)
        Thread.sleep(50)

        val senderSock = DatagramSocket()
        try {
            val addr = InetAddress.getByName("127.0.0.1")
            fun sendSingleFrag(seq: Int) {
                val pktBuf = ByteBuffer.allocate(28 + 16)
                pktBuf.putInt(0x55445056)
                pktBuf.putInt(seq)
                pktBuf.putLong(seq * 10L)
                pktBuf.put(0.toByte())
                pktBuf.putShort(0.toShort())
                pktBuf.putShort(1.toShort())
                pktBuf.putInt(16)
                pktBuf.put(8.toByte())
                pktBuf.put(0.toByte())
                pktBuf.put(0.toByte())
                pktBuf.put(ByteArray(16) { seq.toByte() })
                senderSock.send(DatagramPacket(pktBuf.array(), pktBuf.capacity(), addr, port))
            }

            // Initialize nextExpectedSeq at 125
            sendSingleFrag(125)
            // Skip 126 (missing), send 128 (slot 0) BEFORE 127 (slot 127) across ring boundary 128
            sendSingleFrag(128)
            sendSingleFrag(127)

            assertTrue("Timed out waiting for sequencer flush", latch.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(125, 127, 128), receivedSeqs.toList())
        } finally {
            senderSock.close()
            receiver.stop()
        }
    }

    @Test
    fun `udp fec receiver echoes rtt probe ping packets`() {
        val port = DatagramSocket(0).use { it.localPort }
        val receiver = UdpFecReceiver(port)
        receiver.start(port)
        Thread.sleep(50)

        val probeSock = DatagramSocket()
        probeSock.soTimeout = 2000
        try {
            val addr = InetAddress.getByName("127.0.0.1")
            val sendNanos = 123_456_789L
            val ping = PacketProtocol.buildPingPacket(42, sendNanos)
            probeSock.send(DatagramPacket(ping, ping.size, addr, port))

            val replyBuf = ByteArray(64)
            val replyPkt = DatagramPacket(replyBuf, replyBuf.size)
            probeSock.receive(replyPkt)

            val parsed = PacketProtocol.readProbeSequence(replyPkt.data, replyPkt.length)
            assertTrue("Expected ping reply packet", parsed != null && parsed.isReply)
            assertEquals(42, parsed!!.seq)
            assertEquals(sendNanos, parsed.echoedNanos)
        } finally {
            probeSock.close()
            receiver.stop()
        }
    }

    @Test
    fun `app logger enforces bounded log capacity`() {
        for (i in 0 until 3500) {
            AppLogger.d("TestTag", "line-$i")
        }
        val logs = AppLogger.getLogs()
        assertEquals(3000, logs.size)
        assertTrue(logs.last().contains("line-3499"))
    }
}
