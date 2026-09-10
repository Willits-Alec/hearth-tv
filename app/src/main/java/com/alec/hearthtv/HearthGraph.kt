package com.alec.hearthtv

import android.content.Context
import android.net.wifi.WifiManager
import com.alec.hearthtv.data.AppSettings
import com.alec.hearthtv.data.SettingsRepository
import com.alec.hearthtv.diagnostics.SelfTest
import com.alec.hearthtv.net.LanTransport
import com.alec.hearthtv.net.WifiLanTransport
import com.alec.hearthtv.protocol.SsdpDiscovery
import com.alec.hearthtv.protocol.WakeOnLan
import com.alec.hearthtv.protocol.bravia.BraviaClient
import com.alec.hearthtv.protocol.sonos.SonosClient
import com.alec.hearthtv.remote.RemoteController
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

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        settings = SettingsRepository(appContext)
        transport = WifiLanTransport(appContext)
    }

    fun bravia(host: String, s: AppSettings? = null): BraviaClient =
        BraviaClient("http://$host", transport.http(), s?.credentials ?: com.alec.hearthtv.protocol.bravia.BraviaCredentials.None)

    fun sonos(host: String): SonosClient = SonosClient("http://$host:1400", transport.http())

    fun controller(s: AppSettings): RemoteController? {
        val host = s.tvHost ?: return null
        return RemoteController(
            tv = bravia(host, s),
            sonos = s.sonosHost?.let { sonos(it) },
            credentials = settings,
            tvMac = s.tvMac,
            wakeOnLan = { mac -> WakeOnLan.send(mac, socketProvider = { transport.udpSocket() }) },
            clientId = s.clientId,
        )
    }

    fun selfTest(s: AppSettings): SelfTest? {
        val host = s.tvHost ?: return null
        return SelfTest(bravia(host, s), s.sonosHost?.let { sonos(it) }, appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }

    fun discovery(): SsdpDiscovery = SsdpDiscovery(socketProvider = { transport.udpSocket() })

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
