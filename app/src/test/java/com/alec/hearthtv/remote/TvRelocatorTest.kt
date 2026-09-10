package com.alec.hearthtv.remote

import com.alec.hearthtv.fakes.FakeBraviaServer
import com.alec.hearthtv.protocol.bravia.BraviaClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/** DHCP moved the TV: SSDP lists candidates, the WOL MAC (readable without pairing) says which one is ours. */
class TvRelocatorTest {
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private suspend fun macAt(host: String): String? = BraviaClient("http://$host", http).wolMac()

    @Test fun `finds the TV at its new address by MAC, case-insensitively`() = runTest {
        FakeBraviaServer().use { tv ->
            val newHost = tv.baseUrl.removePrefix("http://")
            val r = TvRelocator(discover = { listOf("127.0.0.1:1", newHost) }, macOf = ::macAt)
            assertEquals(newHost, r.find("02:00:00:00:00:85", currentHost = "10.0.0.85"))
            assertEquals(newHost, r.find("02:00:00:00:00:85".uppercase(), currentHost = null))
        }
    }

    @Test fun `ignores another TV, the current address, an empty network and a failed search`() = runTest {
        FakeBraviaServer().use { tv ->
            val host = tv.baseUrl.removePrefix("http://")
            val r = TvRelocator(discover = { listOf(host) }, macOf = ::macAt)
            assertNull(r.find("aa:bb:cc:dd:ee:ff", currentHost = null))
            assertNull(r.find("02:00:00:00:00:85", currentHost = host))
        }
        assertNull(TvRelocator(discover = { emptyList() }, macOf = { null }).find("02:00:00:00:00:85", null))
        assertNull(TvRelocator(discover = { throw IllegalStateException("multicast blocked") }, macOf = { null }).find("02:00:00:00:00:85", null))
    }
}
