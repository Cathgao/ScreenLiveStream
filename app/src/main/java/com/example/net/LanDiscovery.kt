package com.example.net

import android.os.Build
import android.util.Log
import com.example.model.DiscoveredDevice
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class LanDiscovery {
    @Volatile
    private var isScanning = false
    @Volatile
    private var isAnnouncing = false

    private var scanSocket: DatagramSocket? = null
    private var announceSocket: DatagramSocket? = null

    val discoveredDevices = ConcurrentHashMap<String, DiscoveredDevice>()
    var onDevicesUpdated: ((List<DiscoveredDevice>) -> Unit)? = null

    fun startScanning() {
        if (isScanning) return
        isScanning = true
        discoveredDevices.clear()

        thread(start = true, name = "LanDiscoveryScanner") {
            try {
                val ds = DatagramSocket()
                ds.broadcast = true
                scanSocket = ds

                var lastPingTime = 0L

                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isScanning && !ds.isClosed) {
                    val now = System.currentTimeMillis()
                    if (now - lastPingTime >= 2000) {
                        lastPingTime = now
                        sendPingBroadcast(ds)
                    }

                    ds.soTimeout = 1000
                    try {
                        ds.receive(packet)
                        val message = String(packet.data, 0, packet.length).trim()
                        val senderIp = packet.address.hostAddress ?: continue

                        if (message.startsWith(PacketProtocol.DISCOVERY_ACK_PREFIX) ||
                            message.startsWith(PacketProtocol.DISCOVERY_BEACON_PREFIX)
                        ) {
                            val parts = message.split(":")
                            val devName = if (parts.size >= 2) parts[1] else "Android Receiver"
                            val port = if (parts.size >= 3) parts[2].toIntOrNull() ?: 8888 else 8888

                            val rtt = if (lastPingTime > 0) {
                                (System.currentTimeMillis() - lastPingTime).coerceIn(1, 999).toInt()
                            } else {
                                5
                            }
                            val device = DiscoveredDevice(
                                deviceName = devName,
                                ipAddress = senderIp,
                                port = port,
                                lastSeenMs = System.currentTimeMillis(),
                                pingMs = rtt
                            )
                            discoveredDevices[senderIp] = device
                            onDevicesUpdated?.invoke(discoveredDevices.values.toList())
                        }
                    } catch (e: Exception) {
                        // Socket timeout or receive error
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Scanner error", e)
            }
        }
    }

    private fun sendPingBroadcast(socket: DatagramSocket) {
        try {
            val pingMsg = PacketProtocol.DISCOVERY_PING.toByteArray()
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val packet = DatagramPacket(
                pingMsg,
                pingMsg.size,
                broadcastAddr,
                PacketProtocol.DISCOVERY_PORT
            )
            socket.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast ping failed", e)
        }
    }

    fun startAnnouncing(listenPort: Int) {
        if (isAnnouncing) return
        isAnnouncing = true

        val manufacturerName = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val deviceName = "$manufacturerName ${Build.MODEL}"

        thread(start = true, name = "LanDiscoveryAnnouncer") {
            try {
                val ds = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(PacketProtocol.DISCOVERY_PORT))
                    broadcast = true
                }
                announceSocket = ds

                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                Log.d(TAG, "LanDiscovery Announcer listening on port ${PacketProtocol.DISCOVERY_PORT}")

                while (isAnnouncing && !ds.isClosed) {
                    try {
                        ds.soTimeout = 2000
                        ds.receive(packet)
                        val message = String(packet.data, 0, packet.length).trim()

                        if (message == PacketProtocol.DISCOVERY_PING) {
                            val replyMsg = "${PacketProtocol.DISCOVERY_ACK_PREFIX}$deviceName:$listenPort"
                            val replyBytes = replyMsg.toByteArray()
                            val replyPacket = DatagramPacket(
                                replyBytes,
                                replyBytes.size,
                                packet.address,
                                packet.port
                            )
                            ds.send(replyPacket)
                        }
                    } catch (e: Exception) {
                        // Timeout or non-fatal receive exception
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Announcer error", e)
            }
        }
    }

    fun stopScanning() {
        isScanning = false
        try {
            scanSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        scanSocket = null
    }

    fun stopAnnouncing() {
        isAnnouncing = false
        try {
            announceSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        announceSocket = null
    }

    companion object {
        private const val TAG = "LanDiscovery"
    }
}
