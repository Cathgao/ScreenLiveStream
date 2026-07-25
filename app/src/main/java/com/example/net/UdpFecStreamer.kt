package com.example.net

import com.example.log.AppLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

import com.example.model.VideoCodec

class UdpFecStreamer : IStreamer {
    private val TAG = "UdpFecStreamer"
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 0
    
    @Volatile
    var isStreaming = false
        private set
        
    private val MAX_PAYLOAD = 1300
    private val FEC_GROUP_SIZE = 12 // Adjust based on network
    
    override var onRequestKeyframe: (() -> Unit)? = null
    
    private var sendThread: Thread? = null

    private class FrameTask {
        var data: ByteArray = ByteArray(0)
        var size: Int = 0
        var offset: Int = 0
        var timestampMs: Long = 0
        var isKeyframe: Boolean = false
        var isCodecConfig: Boolean = false
        var isAudio: Boolean = false
        var codec: VideoCodec = VideoCodec.H264
        var isBeacon: Boolean = false
        var rttMs: Int = 0
        var lossPercent: Float = 0f
        var isStreamStop: Boolean = false
    }

    private val taskQueue = ArrayBlockingQueue<FrameTask>(100)
    private val taskPool = ConcurrentLinkedQueue<FrameTask>()

    private fun obtainTask(minSize: Int): FrameTask {
        val task = taskPool.poll() ?: FrameTask()
        if (task.data.size < minSize) {
            task.data = ByteArray(Math.max(minSize, 512 * 1024))
        }
        return task
    }

    private fun recycleTask(task: FrameTask) {
        taskPool.offer(task)
    }

    override fun start(targetIp: String, port: Int) {
        if (isStreaming) return
        isStreaming = true
        this.targetPort = port
        taskQueue.clear()

        thread(name = "UdpStreamerThread") {
            try {
                socket = DatagramSocket()
                socket?.sendBufferSize = 4 * 1024 * 1024
                targetAddress = InetAddress.getByName(targetIp)
                AppLogger.i(TAG, "UDP Streamer started targeting $targetIp:$port")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error starting UDP streamer", e)
                isStreaming = false
            }
        }

        sendThread = thread(start = true, name = "UdpStreamerSendThread") {
            val packetBuf = ByteBuffer.allocate(28 + MAX_PAYLOAD)
            val fecPayload = ByteArray(MAX_PAYLOAD)
            val fragments = ArrayList<ByteArray>()
            for (i in 0 until 1000) fragments.add(ByteArray(28 + MAX_PAYLOAD))

            while (isStreaming) {
                try {
                    val task = taskQueue.take()
                    if (socket != null && targetAddress != null) {
                        try {
                            if (task.isStreamStop) {
                                packetBuf.clear()
                                packetBuf.put(PacketProtocol.buildStreamStopPacket())
                                val dp = DatagramPacket(packetBuf.array(), PacketProtocol.HEADER_SIZE, targetAddress, targetPort)
                                for (i in 0 until 10) {
                                    socket?.send(dp)
                                }
                            } else if (task.isBeacon) {
                                val flags = 64 // 64 = Beacon
                                packetBuf.clear()
                                packetBuf.putInt(0x55445056)
                                packetBuf.putInt(0)
                                packetBuf.putLong(0L)
                                packetBuf.put(flags.toByte())
                                packetBuf.putShort(0.toShort())
                                packetBuf.putShort(1.toShort())
                                packetBuf.putInt(8)
                                packetBuf.put(ByteArray(3))
                                
                                packetBuf.putInt(task.rttMs.coerceAtLeast(0))
                                val lossBp = (task.lossPercent * 100f).toInt().coerceIn(0, 10000)
                                packetBuf.putInt(lossBp)
                                
                                val dp = DatagramPacket(packetBuf.array(), 28 + 8, targetAddress, targetPort)
                                socket?.send(dp)
                            } else if (task.isAudio) {
                                var flags = 16 // 16 = Audio
                                if (task.isCodecConfig) flags = flags or 32
                                
                                val seq = audioSeqCounter++
                                
                                packetBuf.clear()
                                packetBuf.putInt(0x55445056)
                                packetBuf.putInt(seq)
                                packetBuf.putLong(task.timestampMs)
                                packetBuf.put(flags.toByte())
                                packetBuf.putShort(0.toShort()) // fragIndex
                                packetBuf.putShort(1.toShort()) // totalFragments
                                packetBuf.putInt(task.size) // frameSize
                                packetBuf.put(ByteArray(3)) // pad
                                packetBuf.put(task.data, 0, task.size)
                                
                                val dp = DatagramPacket(packetBuf.array(), 28 + task.size, targetAddress, targetPort)
                                socket?.send(dp)
                            } else {
                                val frameSize = task.size
                                val totalFragments = (frameSize + MAX_PAYLOAD - 1) / MAX_PAYLOAD
                                var flags = 0
                                if (task.isKeyframe) flags = flags or 1
                                if (task.isCodecConfig) flags = flags or 2
                                if (task.codec == VideoCodec.H265) flags = flags or 4
                                
                                val seq = videoSeqCounter++
                                
                                for (i in 0 until totalFragments) {
                                    val fragOffset = task.offset + i * MAX_PAYLOAD
                                    val length = Math.min(MAX_PAYLOAD, frameSize - i * MAX_PAYLOAD)
                                    
                                    val fragData = fragments[i]
                                    val buf = ByteBuffer.wrap(fragData)
                                    buf.putInt(0x55445056) // magic
                                    buf.putInt(seq)
                                    buf.putLong(task.timestampMs)
                                    buf.put(flags.toByte())
                                    buf.putShort(i.toShort())
                                    buf.putShort(totalFragments.toShort())
                                    buf.putInt(frameSize)
                                    buf.put(ByteArray(3)) // pad to 28 bytes
                                    buf.put(task.data, fragOffset, length)
                                    
                                    val dp = DatagramPacket(fragData, 28 + length, targetAddress, targetPort)
                                    socket?.send(dp)
                                }
                                
                                // Generate FEC
                                var fecGroupId = 0
                                for (i in 0 until totalFragments step FEC_GROUP_SIZE) {
                                    val end = Math.min(i + FEC_GROUP_SIZE, totalFragments)
                                    
                                    fecPayload.fill(0)
                                    for (j in i until end) {
                                        val fragData = fragments[j]
                                        val fragPayloadLen = if (j == totalFragments - 1) frameSize - j * MAX_PAYLOAD else MAX_PAYLOAD
                                        for (k in 0 until fragPayloadLen) {
                                            fecPayload[k] = (fecPayload[k].toInt() xor fragData[28 + k].toInt()).toByte()
                                        }
                                    }
                                    
                                    packetBuf.clear()
                                    packetBuf.putInt(0x55445056)
                                    packetBuf.putInt(seq)
                                    packetBuf.putLong(task.timestampMs)
                                    val fecFlags = flags or 8 // FEC flag
                                    packetBuf.put(fecFlags.toByte())
                                    packetBuf.putShort(fecGroupId.toShort())
                                    packetBuf.putShort(totalFragments.toShort())
                                    packetBuf.putInt(frameSize)
                                    packetBuf.put(ByteArray(3)) // pad
                                    packetBuf.put(fecPayload)
                                    
                                    val dp = DatagramPacket(packetBuf.array(), 28 + MAX_PAYLOAD, targetAddress, targetPort)
                                    socket?.send(dp)
                                    
                                    fecGroupId++
                                }
                            }
                        } catch (e: Exception) {
                            // AppLogger.e(TAG, "Error sending frame", e)
                        }
                    }
                    recycleTask(task)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }
    
    private var videoSeqCounter = 0
    private var audioSeqCounter = 0
    
    override fun sendFrame(
        frameData: ByteArray,
        offset: Int,
        size: Int,
        timestampMs: Long,
        isKeyframe: Boolean,
        isCodecConfig: Boolean,
        codec: VideoCodec
    ) {
        if (!isStreaming) return
        
        val task = obtainTask(size)
        System.arraycopy(frameData, offset, task.data, 0, size)
        task.offset = 0
        task.size = size
        task.timestampMs = timestampMs
        task.isKeyframe = isKeyframe
        task.isCodecConfig = isCodecConfig
        task.codec = codec
        task.isAudio = false
        task.isBeacon = false
        
        if (!taskQueue.offer(task)) {
            recycleTask(task)
        }
    }
    
    override fun sendAudioFrame(
        frameData: ByteArray,
        size: Int,
        timestampMs: Long,
        isCodecConfig: Boolean
    ) {
        if (!isStreaming) return
        
        val task = obtainTask(size)
        System.arraycopy(frameData, 0, task.data, 0, size)
        task.size = size
        task.timestampMs = timestampMs
        task.isCodecConfig = isCodecConfig
        task.isAudio = true
        task.isBeacon = false
        
        if (!taskQueue.offer(task)) {
            recycleTask(task)
        }
    }
    
    override fun sendStatsBeacon(rttMs: Int, lossPercent: Float) {
        if (!isStreaming) return
        
        val task = obtainTask(0)
        task.isBeacon = true
        task.rttMs = rttMs
        task.lossPercent = lossPercent
        
        if (!taskQueue.offer(task)) {
            recycleTask(task)
        }
    }
    
    override fun sendStreamStopSignal() {
        if (!isStreaming) return
        taskQueue.clear()
        val task = obtainTask(0)
        task.isStreamStop = true
        if (!taskQueue.offer(task)) {
            recycleTask(task)
        }
    }
    
    override fun stop() {
        isStreaming = false
        sendThread?.interrupt()
        sendThread = null
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        taskQueue.clear()
    }
}
