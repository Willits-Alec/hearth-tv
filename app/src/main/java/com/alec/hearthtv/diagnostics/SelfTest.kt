package com.alec.hearthtv.diagnostics

import com.alec.hearthtv.protocol.bravia.BraviaClient
import com.alec.hearthtv.protocol.bravia.BraviaException
import com.alec.hearthtv.protocol.sonos.SonosClient
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
 * The contract suite compiled into the app: read-only checks against the real devices, one tap, one report.
 * Order matters — later TV checks are skipped when the TV cannot be reached at all.
 */
class SelfTest(
    private val tv: BraviaClient,
    private val sonos: SonosClient?,
    private val appVersion: String,
    private val clock: () -> String = { Instant.now().toString() },
) {
    private val tvChecks = listOf("TV paired", "TV power", "TV inputs", "TV volume", "TV sound output", "TV remote codes")

    suspend fun run(): SelfTestReport {
        val checks = mutableListOf<Check>()

        val iface = runCatching { tv.interfaceInfo() }
        if (iface.isFailure) {
            checks += Check("TV reachable", false, iface.exceptionOrNull()?.message ?: "no answer")
            tvChecks.forEach { checks += Check(it, false, "skipped") }
        } else {
            checks += Check("TV reachable", true, iface.getOrThrow().modelName)
            checks += check("TV paired") {
                try {
                    tv.systemInfo(); "registered with the TV"
                } catch (_: BraviaException.AuthRequired) {
                    throw IllegalStateException("not paired — open Setup to pair with the TV")
                }
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
        return SelfTestReport(appVersion, checks, clock())
    }

    private suspend fun check(name: String, block: suspend () -> String): Check = try {
        Check(name, true, block())
    } catch (e: Exception) {
        Check(name, false, e.message ?: e::class.java.simpleName)
    }
}
