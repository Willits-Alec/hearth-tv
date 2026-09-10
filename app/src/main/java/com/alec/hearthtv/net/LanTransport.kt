package com.alec.hearthtv.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Where LAN traffic goes. The protocol clients only ever see an [OkHttpClient] and a UDP socket factory, so
 * tests use [PlainLanTransport] and the phone uses [WifiLanTransport].
 */
interface LanTransport {
    fun http(): OkHttpClient
    fun udpSocket(): DatagramSocket
    val description: String
}

/** Default network stack — what the JVM tests and a phone without a VPN use. */
class PlainLanTransport : LanTransport {
    override fun http(): OkHttpClient = baseClient().build()
    override fun udpSocket(): DatagramSocket = DatagramSocket()
    override val description: String = "default network"
}

/**
 * Binds every socket to the Wi-Fi [Network] object, bypassing any VPN's routing. A full-tunnel WireGuard on
 * the test phone routed LAN traffic into the tunnel and produced empty replies from the TV until sockets were
 * bound to wlan0; this is that lesson as code. Falls back to the default stack when there is no Wi-Fi network.
 */
class WifiLanTransport(context: Context) : LanTransport {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun wifiNetwork(): Network? = cm.allNetworks.firstOrNull { n ->
        cm.getNetworkCapabilities(n)?.let { caps ->
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
    }

    override fun http(): OkHttpClient {
        val n = wifiNetwork() ?: return baseClient().build()
        return baseClient()
            .socketFactory(n.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = n.getAllByName(hostname).toList()
            })
            .build()
    }

    override fun udpSocket(): DatagramSocket = DatagramSocket().also { s -> wifiNetwork()?.bindSocket(s) }

    /**
     * True when this app's default network is a VPN. A VPN that is not bypassable forces every socket of this app
     * into the tunnel, Wi-Fi binding included — the only way out is to exclude the app inside the VPN's own settings.
     */
    fun vpnActive(): Boolean =
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true

    override val description: String
        get() {
            val wifi = if (wifiNetwork() != null) "Wi-Fi (bound)" else "no Wi-Fi network — using default route"
            return if (vpnActive()) "$wifi · VPN active on this phone" else wifi
        }

    companion object {
        const val VPN_HINT = "A VPN is running on this phone and it may be swallowing home-network traffic. " +
            "Exclude Hearth TV in the VPN app (WireGuard: tunnel → Excluded applications), or switch the VPN off while using the remote."
    }
}

internal fun baseClient(): OkHttpClient.Builder = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .writeTimeout(8, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
