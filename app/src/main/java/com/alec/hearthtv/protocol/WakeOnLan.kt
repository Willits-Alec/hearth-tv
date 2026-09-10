package com.alec.hearthtv.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Wake-on-LAN: the one thing an HTTP API cannot do for a TV that is fully asleep. */
object WakeOnLan {
    fun parseMac(mac: String): ByteArray {
        val hex = mac.trim().replace(Regex("[:\\-.]"), "")
        require(hex.length == 12 && hex.all { it in "0123456789abcdefABCDEF" }) { "not a MAC address: '$mac'" }
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Six 0xFF bytes followed by the MAC sixteen times: 102 bytes. */
    fun magicPacket(mac: String): ByteArray {
        val m = parseMac(mac)
        val pkt = ByteArray(102)
        for (i in 0 until 6) pkt[i] = 0xFF.toByte()
        for (r in 0 until 16) m.copyInto(pkt, 6 + r * 6)
        return pkt
    }

    /**
     * Sends the magic packet [repeat] times to [address]:[port] (the LAN broadcast address by default).
     * Pass a [socketProvider] that binds to the Wi-Fi network on the phone.
     */
    suspend fun send(
        mac: String,
        address: String = "255.255.255.255",
        port: Int = 9,
        repeat: Int = 3,
        socketProvider: () -> DatagramSocket = { DatagramSocket() },
    ) = withContext(Dispatchers.IO) {
        val pkt = magicPacket(mac)
        val target = InetAddress.getByName(address)
        socketProvider().use { sock ->
            sock.broadcast = true
            repeat(repeat) { sock.send(DatagramPacket(pkt, pkt.size, target, port)) }
        }
    }
}
