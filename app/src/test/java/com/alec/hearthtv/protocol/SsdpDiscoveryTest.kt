package com.alec.hearthtv.protocol

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/**
 * SSDP over loopback: a fake responder answers the M-SEARCH the way the Bravia, the Arc and the Roku do.
 * On the phone the same code multicasts to 239.255.255.250:1900; here the target is the responder's port.
 */
class SsdpDiscoveryTest {
    private fun responder(reply: (String) -> String?): DatagramSocket {
        val sock = DatagramSocket(0, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            val buf = ByteArray(2048)
            while (!sock.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { sock.receive(p) } catch (_: Exception) { break }
                val text = String(p.data, 0, p.length)
                val answer = reply(text) ?: continue
                sock.send(DatagramPacket(answer.toByteArray(), answer.length, p.socketAddress))
            }
        }
        return sock
    }

    private val braviaReply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n" +
        "LOCATION: http://192.0.2.85:52323/dmr.xml\r\nSERVER: Linux/3.10 UPnP/1.0 Sony-BRAVIA/1.0\r\n" +
        "ST: urn:schemas-sony-com:service:ScalarWebAPI:1\r\n" +
        "USN: uuid:00000000-0000-1010-8000-020000000085::urn:schemas-sony-com:service:ScalarWebAPI:1\r\n" +
        "X-AV-Physical-Unit-Info: pa=\"BRAVIA\";\r\n\r\n"

    @Test fun `M-SEARCH is well-formed and the Sony reply is parsed`() = runTest {
        var seen = ""
        val sock = responder { req -> seen = req; if (req.contains("ScalarWebAPI")) braviaReply else null }
        val ssdp = SsdpDiscovery(target = InetSocketAddress(InetAddress.getLoopbackAddress(), sock.localPort))
        val found = ssdp.search(SsdpDiscovery.ST_SONY_SCALAR, timeoutMs = 800)
        sock.close()
        assertTrue(seen.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(seen.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(seen.contains("ST: urn:schemas-sony-com:service:ScalarWebAPI:1\r\n"))
        assertEquals(1, found.size)
        val d = found.single()
        assertEquals("http://192.0.2.85:52323/dmr.xml", d.location)
        assertEquals("192.0.2.85", d.host)
        assertEquals("urn:schemas-sony-com:service:ScalarWebAPI:1", d.st)
        assertTrue(d.server!!.contains("Sony-BRAVIA"))
    }

    @Test fun `duplicate replies collapse and silence returns an empty list`() = runTest {
        val sock = responder { braviaReply + braviaReply.replace("max-age=1800", "max-age=1801") }
        val ssdp = SsdpDiscovery(target = InetSocketAddress(InetAddress.getLoopbackAddress(), sock.localPort))
        val found = ssdp.search(SsdpDiscovery.ST_SONY_SCALAR, timeoutMs = 800)
        sock.close()
        assertEquals(1, found.size)

        val quiet = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val none = SsdpDiscovery(target = InetSocketAddress(InetAddress.getLoopbackAddress(), quiet.localPort))
            .search(SsdpDiscovery.ST_ROKU, timeoutMs = 300)
        quiet.close()
        assertTrue(none.isEmpty())
    }

    @Test fun `Sonos and Roku search targets are the documented ones`() {
        assertEquals("urn:schemas-upnp-org:device:ZonePlayer:1", SsdpDiscovery.ST_SONOS)
        assertEquals("roku:ecp", SsdpDiscovery.ST_ROKU)
    }
}
