package com.alec.hearthtv.diagnostics

import com.alec.hearthtv.protocol.bravia.BraviaClient
import com.alec.hearthtv.protocol.bravia.BraviaException
import com.alec.hearthtv.protocol.roku.RokuClient
import com.alec.hearthtv.protocol.roku.RokuException
import com.alec.hearthtv.protocol.sonos.SonosClient
import com.alec.hearthtv.update.UpdateStatus
import java.time.Instant

data class Check(val name: String, val passed: Boolean, val detail: String)

data class SelfTestReport(val appVersion: String, val checks: List<Check>, val ranAt: String) {
    val allPassed: Boolean get() = checks.all { it.passed }

    /** Plain text the owner can paste into a message. */
    fun text(): String = buildString {
        appendLine("Hearth TV self-test $appVersion")
        appendLine(ranAt)
        checks.forEach { appendLine("${if (it.passed) "PASS" else "FAIL"}  ${it.name} — ${it.detail}") }
    }
}

/**
 * The contract suite compiled into the app (SCOPE.md §4.2, §5 rule 4): read-only checks against the real devices,
 * one tap, one report. Order matters — later TV checks are skipped when the TV cannot be reached at all.
 * [discover] and [update] are optional so the pure-Kotlin tests can run without Wi-Fi or internet.
 */
class SelfTest(
    private val tv: BraviaClient,
    private val sonos: SonosClient?,
    private val appVersion: String,
    private val clock: () -> String = { Instant.now().toString() },
    /** SSDP search for Sony TVs, returning the hosts that answered. */
    private val discover: (suspend () -> List<String>)? = null,
    private val tvHost: String? = null,
    /** The download-page version check. */
    private val update: (suspend () -> UpdateStatus)? = null,
    private val roku: RokuClient? = null,
) {
    private val tvChecks = listOf("TV paired", "TV wake-on-LAN", "TV power", "TV inputs", "TV volume", "TV sound output", "TV remote codes")

    suspend fun run(): SelfTestReport {
        val checks = mutableListOf<Check>()

        discover?.let { search ->
            checks += check("TV discoverable") {
                val hosts = search()
                when {
                    hosts.isEmpty() -> throw IllegalStateException(
                        "no Sony TV answered the network search — the remote still works by address, but the app could not re-find the TV if its address changed",
                    )
                    tvHost != null && hosts.none { it == tvHost } -> "answered from ${hosts.joinToString()} (saved address $tvHost)"
                    else -> "answered from ${hosts.joinToString()}"
                }
            }
        }

        val iface = runCatching { tv.interfaceInfo() }
        if (iface.isFailure) {
            checks += Check("TV reachable", false, iface.exceptionOrNull()?.message ?: "no answer")
            tvChecks.forEach { checks += Check(it, false, "skipped") }
        } else {
            checks += Check("TV reachable", true, iface.getOrThrow().modelName)
            val paired = check("TV paired") {
                try {
                    tv.systemInfo(); "registered with the TV"
                } catch (_: BraviaException.AuthRequired) {
                    throw IllegalStateException("not paired — open Setup to pair with the TV")
                }
            }
            checks += paired
            checks += if (!paired.passed) Check("TV wake-on-LAN", false, "skipped") else check("TV wake-on-LAN") {
                if (tv.wolMode()) "enabled"
                else throw IllegalStateException("disabled — turn on Remote start in the TV's network settings so the app can power it on")
            }
            checks += check("TV power") { tv.powerStatus().name.lowercase() }
            checks += check("TV inputs") { "${tv.inputs().size} inputs" }
            checks += check("TV volume") {
                try {
                    val v = tv.volume(); "${v.level}${if (v.muted) " (muted)" else ""}"
                } catch (e: BraviaException.RpcError) {
                    if (e.code == 40005) "TV is off" else throw e
                }
            }
            checks += check("TV sound output") { tv.soundOutput().name.lowercase().replace('_', ' ') }
            checks += check("TV remote codes") { "${tv.remoteCodes().size} keys" }
        }

        val player = sonos
        if (player == null) {
            checks += Check("Sonos reachable", true, "not configured")
            checks += Check("Sonos group", true, "not configured")
        } else {
            val desc = runCatching { player.description() }
            if (desc.isFailure) {
                checks += Check("Sonos reachable", false, desc.exceptionOrNull()?.message ?: "no answer")
                checks += Check("Sonos group", false, "skipped")
            } else {
                checks += Check("Sonos reachable", true, desc.getOrThrow().modelName)
                checks += check("Sonos group") {
                    val g = player.homeTheatreGroup() ?: throw IllegalStateException("no home-theatre group found")
                    "${g.name} (${g.satelliteUuids.size} satellites)"
                }
            }
        }

        roku?.let { box ->
            val info = runCatching { box.deviceInfo() }
            if (info.isFailure) {
                checks += Check("Roku reachable", false, info.exceptionOrNull()?.message ?: "no answer")
                checks += Check("Roku control", false, "skipped")
            } else {
                checks += Check("Roku reachable", true, "${info.getOrThrow().modelName} · Roku OS ${info.getOrThrow().softwareVersion}")
                checks += check("Roku control") {
                    try {
                        "${box.apps().size} channels"
                    } catch (_: RokuException.LimitedMode) {
                        throw IllegalStateException(RokuException.LIMITED_HINT)
                    }
                }
            }
        }

        update?.let { probe ->
            checks += check("Update check") {
                when (val u = probe()) {
                    UpdateStatus.UpToDate -> "up to date"
                    is UpdateStatus.Available -> "v${u.versionName} available — Download from the Diagnostics screen"
                    is UpdateStatus.Unknown -> throw IllegalStateException("could not reach the download page (${u.reason}) — is the phone online?")
                }
            }
        }
        return SelfTestReport(appVersion, checks, clock())
    }

    private suspend fun check(name: String, block: suspend () -> String): Check = try {
        Check(name, true, block())
    } catch (e: Exception) {
        Check(name, false, e.message ?: e::class.java.simpleName)
    }
}
