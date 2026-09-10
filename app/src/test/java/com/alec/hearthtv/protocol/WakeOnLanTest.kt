package com.alec.hearthtv.protocol

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class WakeOnLanTest {
    @Test fun `magic packet is six 0xFF then the MAC sixteen times`() {
        val pkt = WakeOnLan.magicPacket("02:00:00:00:00:85")
        assertEquals(102, pkt.size)
        assertArrayEquals(ByteArray(6) { 0xFF.toByte() }, pkt.copyOfRange(0, 6))
        val mac = byteArrayOf(0x02, 0, 0, 0, 0, 0x85.toByte())
        for (i in 0 until 16) assertArrayEquals(mac, pkt.copyOfRange(6 + i * 6, 12 + i * 6))
    }

    @Test fun `MAC parsing accepts colons, dashes and lowercase`() {
        val a = WakeOnLan.magicPacket("ac:80:0a:e0:29:3b")
        val b = WakeOnLan.magicPacket("AC-80-0A-E0-29-3B")
        assertArrayEquals(a, b)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a malformed MAC is rejected before anything is sent`() {
        WakeOnLan.magicPacket("not-a-mac")
    }

    @Test fun `send delivers the packet to the target port`() = runTest {
        val listener = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val received = ByteArray(200)
        val latch = CountDownLatch(1)
        var length = 0
        thread {
            val p = DatagramPacket(received, received.size)
            listener.soTimeout = 3000
            runCatching { listener.receive(p); length = p.length }
            latch.countDown()
        }
        WakeOnLan.send(
            mac = "02:00:00:00:00:85",
            address = InetAddress.getLoopbackAddress().hostAddress,
            port = listener.localPort,
        )
        latch.await(4, TimeUnit.SECONDS)
        listener.close()
        assertEquals(102, length)
        assertArrayEquals(WakeOnLan.magicPacket("02:00:00:00:00:85"), received.copyOf(102))
    }
}
