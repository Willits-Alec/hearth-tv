package com.alec.hearthtv.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

sealed class UpdateStatus {
    data object UpToDate : UpdateStatus()
    data class Available(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String?) : UpdateStatus()
    data class Unknown(val reason: String) : UpdateStatus()
}

/**
 * Reads `version.json` from the public download page (written by publish.ps1) and compares versionCode.
 * Never throws: an offline phone or a broken page is [UpdateStatus.Unknown].
 */
class UpdateChecker(
    private val http: OkHttpClient,
    private val url: String,
    private val currentVersionCode: Int,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): UpdateStatus = withContext(Dispatchers.IO) {
        try {
            val text = http.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").get().build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext UpdateStatus.Unknown("HTTP ${resp.code}")
                    resp.body?.string() ?: ""
                }
            val obj = json.parseToJsonElement(text).jsonObject
            val code = obj["versionCode"]?.jsonPrimitive?.intOrNull ?: return@withContext UpdateStatus.Unknown("no versionCode")
            if (code <= currentVersionCode) return@withContext UpdateStatus.UpToDate
            UpdateStatus.Available(
                versionCode = code,
                versionName = obj["versionName"]?.jsonPrimitive?.contentOrNull ?: code.toString(),
                apkUrl = obj["apkUrl"]?.jsonPrimitive?.contentOrNull ?: return@withContext UpdateStatus.Unknown("no apkUrl"),
                notes = obj["notes"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
            )
        } catch (e: Exception) {
            UpdateStatus.Unknown(e.message ?: e::class.java.simpleName)
        }
    }
}
