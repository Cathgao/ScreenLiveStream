package com.cath.screencast.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.cath.screencast.log.AppLogger
import com.cath.screencast.model.DiscoveredDevice
import com.cath.screencast.model.TransportProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class LanDiscovery(private val context: Context? = null) {
    @Volatile
    private var isScanning = false
    @Volatile
    private var isAnnouncing = false

    @Volatile
    private var scanSocket: DatagramSocket? = null
    @Volatile
    private var announceSocket: DatagramSocket? = null

    private var scanThread: Thread? = null
    private var announceThread: Thread? = null

    private var multicastLock: WifiManager.MulticastLock? = null

    val discoveredDevices = ConcurrentHashMap<String, DiscoveredDevice>()
    var onDevicesUpdated: ((List<DiscoveredDevice>) -> Unit)? = null
    var deviceNameProvider: (() -> String)? = null

    @Synchronized
    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null && context != null) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifiManager?.createMulticastLock("QuestCastLanDiscovery")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to acquire MulticastLock", e)
        }
    }

    @Synchronized
    private fun releaseMulticastLock() {
        try {
            if (!isScanning && !isAnnouncing) {
                multicastLock?.let {
                    if (it.isHeld) it.release()
                }
                multicastLock = null
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to release MulticastLock", e)
        }
    }

    private var cachedBroadcastAddrs: List<InetAddress> = emptyList()
    private var lastBroadcastAddrRefresh = 0L

    private fun getBroadcastAddresses(): List<InetAddress> {
        val now = System.currentTimeMillis()
        if (cachedBroadcastAddrs.isNotEmpty() && now - lastBroadcastAddrRefresh < 30_000L) {
            return cachedBroadcastAddrs
        }
        val list = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                for (ia in iface.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast != null) {
                        list.add(bcast)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error enumerating broadcast addresses", e)
        }
        try {
            list.add(InetAddress.getByName("255.255.255.255"))
        } catch (e: Exception) {}
        val distinct = list.distinct()
        cachedBroadcastAddrs = distinct
        lastBroadcastAddrRefresh = now
        return distinct
    }

    fun startScanning() {
        if (isScanning) return
        isScanning = true
        acquireMulticastLock()
        discoveredDevices.clear()

        scanThread = thread(start = true, name = "LanDiscoveryScanner") {
            var ds: DatagramSocket? = null
            try {
                ds = DatagramSocket()
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
                        packet.length = buffer.size
                        ds.receive(packet)
                        val message = String(packet.data, 0, packet.length).trim()
                        val senderIp = packet.address.hostAddress ?: continue

                        if (message.startsWith(PacketProtocol.DISCOVERY_ACK_PREFIX) ||
                            message.startsWith(PacketProtocol.DISCOVERY_BEACON_PREFIX)
                        ) {
                            val parts = message.split(":")
                            val devName = if (parts.size >= 2) parts[1] else "Android Receiver"
                            val port = if (parts.size >= 3) parts[2].toIntOrNull() ?: 8888 else 8888
                            val protocol = if (parts.size >= 4) {
                                try {
                                    TransportProtocol.valueOf(parts[3].uppercase())
                                } catch (e: Exception) {
                                    TransportProtocol.UDP
                                }
                            } else {
                                TransportProtocol.UDP
                            }

                            val rtt = if (lastPingTime > 0) {
                                (System.currentTimeMillis() - lastPingTime).coerceIn(1, 999).toInt()
                            } else {
                                5
                            }
                            val device = DiscoveredDevice(
                                deviceName = devName,
                                ipAddress = senderIp,
                                port = port,
                                protocol = protocol,
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
                AppLogger.e(TAG, "Scanner error", e)
            } finally {
                try {
                    ds?.close()
                } catch (e: Exception) {}
                scanSocket = null
                releaseMulticastLock()
            }
        }
    }

    private fun sendPingBroadcast(socket: DatagramSocket) {
        try {
            val pingMsg = PacketProtocol.DISCOVERY_PING.toByteArray()
            val bcastAddrs = getBroadcastAddresses()
            for (addr in bcastAddrs) {
                try {
                    val packet = DatagramPacket(
                        pingMsg,
                        pingMsg.size,
                        addr,
                        PacketProtocol.DISCOVERY_PORT
                    )
                    socket.send(packet)
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Broadcast ping failed", e)
        }
    }

    fun startAnnouncing(listenPort: Int, protocol: TransportProtocol = TransportProtocol.UDP) {
        stopAnnouncing()
        isAnnouncing = true
        acquireMulticastLock()

        announceThread = thread(start = true, name = "LanDiscoveryAnnouncer") {
            var ds: DatagramSocket? = null
            try {
                ds = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(PacketProtocol.DISCOVERY_PORT))
                    broadcast = true
                }
                announceSocket = ds

                fun resolveDeviceName(): String {
                    val manufacturerName = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    val fallbackName = "$manufacturerName ${Build.MODEL}"
                    val customName = deviceNameProvider?.invoke()
                    return if (!customName.isNullOrBlank()) customName else fallbackName
                }

                fun sendBeaconBroadcast() {
                    try {
                        val deviceName = resolveDeviceName()
                        val beaconMsg = "${PacketProtocol.DISCOVERY_BEACON_PREFIX}$deviceName:$listenPort:${protocol.name}"
                        val beaconBytes = beaconMsg.toByteArray()
                        val bcastAddrs = getBroadcastAddresses()
                        for (addr in bcastAddrs) {
                            try {
                                val beaconPacket = DatagramPacket(
                                    beaconBytes,
                                    beaconBytes.size,
                                    addr,
                                    PacketProtocol.DISCOVERY_PORT
                                )
                                ds.send(beaconPacket)
                            } catch (e: Exception) {}
                        }
                        AppLogger.d(TAG, "Discovery beacon broadcast sent (${protocol.name}, port $listenPort): $beaconMsg")
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Beacon broadcast failed", e)
                    }
                }

                // Send initial beacon broadcast
                sendBeaconBroadcast()

                var lastBeaconTime = System.currentTimeMillis()
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                AppLogger.d(TAG, "LanDiscovery Announcer listening on port ${PacketProtocol.DISCOVERY_PORT}, protocol=${protocol.name}")

                while (isAnnouncing && !ds.isClosed) {
                    val now = System.currentTimeMillis()
                    if (now - lastBeaconTime >= 2000) {
                        lastBeaconTime = now
                        sendBeaconBroadcast()
                    }

                    try {
                        packet.length = buffer.size
                        ds.soTimeout = 1000
                        ds.receive(packet)
                        val message = String(packet.data, 0, packet.length).trim()

                        if (message == PacketProtocol.DISCOVERY_PING) {
                            val deviceName = resolveDeviceName()
                            val replyMsg = "${PacketProtocol.DISCOVERY_ACK_PREFIX}$deviceName:$listenPort:${protocol.name}"
                            val replyBytes = replyMsg.toByteArray()
                            val replyPacket = DatagramPacket(
                                replyBytes,
                                replyBytes.size,
                                packet.address,
                                packet.port
                            )
                            ds.send(replyPacket)
                            AppLogger.d(TAG, "Replied to DISCOVERY_PING from ${packet.address.hostAddress}:${packet.port} with $replyMsg")
                        }
                    } catch (e: Exception) {
                        // Timeout or non-fatal receive exception
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Announcer error", e)
            } finally {
                try {
                    ds?.close()
                } catch (e: Exception) {}
                announceSocket = null
                releaseMulticastLock()
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
        try {
            scanThread?.join(500)
        } catch (e: Exception) {}
        scanThread = null
        releaseMulticastLock()
    }

    fun stopAnnouncing() {
        isAnnouncing = false
        try {
            announceSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        announceSocket = null
        try {
            announceThread?.join(500)
        } catch (e: Exception) {}
        announceThread = null
        releaseMulticastLock()
    }

    companion object {
        private const val TAG = "LanDiscovery"
    }
}
