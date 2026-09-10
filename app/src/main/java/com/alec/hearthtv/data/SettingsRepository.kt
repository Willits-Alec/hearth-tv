package com.alec.hearthtv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.alec.hearthtv.protocol.bravia.BraviaCredentials
import com.alec.hearthtv.remote.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Everything the app remembers. Lives in DataStore; nothing here ever leaves the phone. */
data class AppSettings(
    val clientId: String,
    val tvHost: String? = null,
    val tvMac: String? = null,
    val tvModel: String? = null,
    val sonosHost: String? = null,
    val sonosName: String? = null,
    val rokuHost: String? = null,
    val rokuName: String? = null,
    val cookie: String? = null,
    val cookieExpiresAt: Long? = null,
    val psk: String? = null,
    /** Route volume through the TV so its own on-screen bar appears (SCOPE.md D11). Off by default. */
    val volumeViaTv: Boolean = false,
    /** Swipe pad instead of arrow buttons on the Navigate and Roku cards (SCOPE.md §9.1). */
    val touchpad: Boolean = false,
) {
    val isConfigured: Boolean get() = !tvHost.isNullOrBlank()

    val credentials: BraviaCredentials
        get() = when {
            !psk.isNullOrBlank() -> BraviaCredentials.Psk(psk)
            !cookie.isNullOrBlank() -> BraviaCredentials.Cookie(cookie, cookieExpiresAt)
            else -> BraviaCredentials.None
        }
}

private val Context.hearthStore: DataStore<Preferences> by preferencesDataStore(name = "hearth_tv")

class SettingsRepository(private val context: Context) : CredentialStore {
    private object Keys {
        val clientId = stringPreferencesKey("client_id")
        val tvHost = stringPreferencesKey("tv_host")
        val tvMac = stringPreferencesKey("tv_mac")
        val tvModel = stringPreferencesKey("tv_model")
        val sonosHost = stringPreferencesKey("sonos_host")
        val sonosName = stringPreferencesKey("sonos_name")
        val rokuHost = stringPreferencesKey("roku_host")
        val rokuName = stringPreferencesKey("roku_name")
        val cookie = stringPreferencesKey("tv_cookie")
        val cookieExpiresAt = longPreferencesKey("tv_cookie_expires_at")
        val psk = stringPreferencesKey("tv_psk")
        val volumeViaTv = booleanPreferencesKey("volume_via_tv")
        val touchpad = booleanPreferencesKey("touchpad")
    }

    val settings: Flow<AppSettings> = context.hearthStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings {
        val s = settings.first()
        if (s.clientId.isBlank()) {
            val id = "HearthTV:${UUID.randomUUID()}"
            context.hearthStore.edit { it[Keys.clientId] = id }
            return s.copy(clientId = id)
        }
        return s
    }

    suspend fun update(block: (AppSettings) -> AppSettings) {
        val before = current()
        val after = block(before)
        context.hearthStore.edit { p ->
            fun put(key: Preferences.Key<String>, value: String?) { if (value.isNullOrBlank()) p.remove(key) else p[key] = value }
            p[Keys.clientId] = after.clientId
            put(Keys.tvHost, after.tvHost); put(Keys.tvMac, after.tvMac); put(Keys.tvModel, after.tvModel)
            put(Keys.sonosHost, after.sonosHost); put(Keys.sonosName, after.sonosName); put(Keys.rokuHost, after.rokuHost); put(Keys.rokuName, after.rokuName)
            put(Keys.cookie, after.cookie); put(Keys.psk, after.psk)
            if (after.cookieExpiresAt == null) p.remove(Keys.cookieExpiresAt) else p[Keys.cookieExpiresAt] = after.cookieExpiresAt
            p[Keys.volumeViaTv] = after.volumeViaTv
            p[Keys.touchpad] = after.touchpad
        }
    }

    suspend fun forgetAll() { context.hearthStore.edit { it.clear() } }

    override suspend fun load(): BraviaCredentials = current().credentials

    override suspend fun save(credentials: BraviaCredentials) = update { s ->
        when (credentials) {
            is BraviaCredentials.Cookie -> s.copy(cookie = credentials.value, cookieExpiresAt = credentials.expiresAtEpochMs, psk = null)
            is BraviaCredentials.Psk -> s.copy(psk = credentials.key, cookie = null, cookieExpiresAt = null)
            BraviaCredentials.None -> s.copy(cookie = null, cookieExpiresAt = null, psk = null)
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        clientId = this[Keys.clientId] ?: "",
        tvHost = this[Keys.tvHost], tvMac = this[Keys.tvMac], tvModel = this[Keys.tvModel],
        sonosHost = this[Keys.sonosHost], sonosName = this[Keys.sonosName], rokuHost = this[Keys.rokuHost], rokuName = this[Keys.rokuName],
        cookie = this[Keys.cookie], cookieExpiresAt = this[Keys.cookieExpiresAt], psk = this[Keys.psk],
        volumeViaTv = this[Keys.volumeViaTv] ?: false, touchpad = this[Keys.touchpad] ?: false,
    )
}
