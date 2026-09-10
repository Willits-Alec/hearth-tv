package com.alec.hearthtv

import android.content.Context
import android.net.wifi.WifiManager
import com.alec.hearthtv.data.AppSettings
import com.alec.hearthtv.data.SettingsRepository
import com.alec.hearthtv.diagnostics.ErrorLog
import com.alec.hearthtv.diagnostics.SelfTest
import com.alec.hearthtv.net.LanTransport
import com.alec.hearthtv.net.WifiLanTransport
import com.alec.hearthtv.protocol.SsdpDiscovery
import com.alec.hearthtv.protocol.WakeOnLan
import com.alec.hearthtv.protocol.bravia.BraviaClient
import com.alec.hearthtv.protocol.bravia.BraviaCredentials
import com.alec.hearthtv.protocol.roku.RokuClient
import com.alec.hearthtv.protocol.sonos.SonosClient
import com.alec.hearthtv.remote.RemoteController
import com.alec.hearthtv.remote.TvRelocator
import com.alec.hearthtv.update.UpdateChecker
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** The one place objects are wired together. Everything here is constructed from [AppSettings] on demand. */
object HearthGraph {
    lateinit var appContext: Context
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var transport: LanTransport
        private set

    /** The last 20 failures with timestamps, shared by the remote and the Diagnostics screen (SCOPE.md §4.2). */
    val errorLog = ErrorLog()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        settings = SettingsRepository(appContext)
        transport = WifiLanTransport(appContext)
    }

    fun bravia(host: String, s: AppSettings? = null): BraviaClient =
        BraviaClient("http://$host", transport.http(), s?.credentials ?: BraviaCredentials.None)

    fun sonos(host: String): SonosClient = SonosClient("http://$host:1400", transport.http())

    fun roku(host: String): RokuClient = RokuClient("http://$host:8060", transport.http())

    fun controller(s: AppSettings): RemoteController? {
        val host = s.tvHost ?: return null
        return RemoteController(
            tv = bravia(host, s),
            sonos = s.sonosHost?.let { sonos(it) },
            credentials = settings,
            tvMac = s.tvMac,
            wakeOnLan = { mac -> WakeOnLan.send(mac, socketProvider = { transport.udpSocket() }) },
            clientId = s.clientId,
            errors = errorLog,
            roku = s.rokuHost?.let { roku(it) },
            volumeViaTv = s.volumeViaTv,
        )
    }

    fun selfTest(s: AppSettings): SelfTest? {
        val host = s.tvHost ?: return null
        return SelfTest(
            tv = bravia(host, s),
            sonos = s.sonosHost?.let { sonos(it) },
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            discover = { findSonyTvs() },
            tvHost = host,
            update = { updateChecker().check() },
            roku = s.rokuHost?.let { roku(it) },
        )
    }

    fun discovery(): SsdpDiscovery = SsdpDiscovery(socketProvider = { transport.udpSocket() })

    /** Hosts of every Sony TV that answers the SSDP search, in the order they answered. */
    suspend fun findSonyTvs(): List<String> =
        withMulticast { discovery().search(SsdpDiscovery.ST_SONY_SCALAR, timeoutMs = 3000) }.map { it.host }.distinct()

    /** Hosts of every Roku that answers the SSDP search. */
    suspend fun findRokus(): List<String> =
        withMulticast { discovery().search(SsdpDiscovery.ST_ROKU, timeoutMs = 3000) }.map { it.host }.distinct()

    /** Finds the TV again by MAC when DHCP has moved it (SCOPE.md §4.2). */
    fun relocator(): TvRelocator = TvRelocator(discover = { findSonyTvs() }, macOf = { host -> bravia(host).wolMac() })

    /** The update check goes to the internet, not the LAN: plain client, longer timeout. */
    fun updateChecker(): UpdateChecker = UpdateChecker(
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build(),
        BuildConfig.UPDATE_URL,
        BuildConfig.VERSION_CODE,
    )

    /** SSDP needs the multicast lock while it runs. */
    suspend fun <T> withMulticast(block: suspend () -> T): T {
        val wifi = appContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("hearth-tv-ssdp")?.apply { setReferenceCounted(false); acquire() }
        try {
            return block()
        } finally {
            runCatching { lock?.release() }
        }
    }
}
