package com.wansaw.opensms.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("opensms_prefs")

/** Local (non-secret) app state. The settings cache mirrors what the server holds. */
class Prefs(private val context: Context) {
    private object K {
        val termsVersion = stringPreferencesKey("terms_version")
        val termsAt = stringPreferencesKey("terms_accepted_at")
        val deviceName = stringPreferencesKey("device_name")
        val setupDone = booleanPreferencesKey("setup_done")
        val settings = stringPreferencesKey("settings_json")
        val revision = intPreferencesKey("settings_revision")
        val effectiveLimit = intPreferencesKey("effective_limit")
        val summary = stringPreferencesKey("summary_json")
        val rrNext = intPreferencesKey("rr_next")
        val enabled = booleanPreferencesKey("gateway_enabled")
        val draft = stringPreferencesKey("draft_settings_json")
    }

    private val flow: Flow<Preferences> get() = context.store.data

    val setupDone: Flow<Boolean> = flow.map { it[K.setupDone] ?: false }
    val deviceName: Flow<String> = flow.map { it[K.deviceName] ?: "" }
    val gatewayEnabled: Flow<Boolean> = flow.map { it[K.enabled] ?: true }
    val settings: Flow<SettingsEnvelope> = flow.map { p ->
        val s = p[K.settings]?.let { runCatching { AppJson.decodeFromString<Settings>(it) }.getOrNull() } ?: Settings()
        SettingsEnvelope(p[K.revision] ?: 0, s, p[K.effectiveLimit])
    }
    val summary: Flow<DeviceSummary?> = flow.map { p ->
        p[K.summary]?.let { runCatching { AppJson.decodeFromString<DeviceSummary>(it) }.getOrNull() }
    }

    suspend fun termsAccepted(): Pair<String, String>? {
        val p = context.store.data.first()
        val v = p[K.termsVersion] ?: return null
        val at = p[K.termsAt] ?: return null
        return v to at
    }

    suspend fun acceptTerms(version: String, atIso: String) = context.store.edit {
        it[K.termsVersion] = version
        it[K.termsAt] = atIso
    }

    suspend fun setDeviceName(name: String) = context.store.edit { it[K.deviceName] = name }
    suspend fun setSetupDone(done: Boolean) = context.store.edit { it[K.setupDone] = done }
    suspend fun setGatewayEnabled(on: Boolean) = context.store.edit { it[K.enabled] = on }

    suspend fun saveSettings(env: SettingsEnvelope) = context.store.edit {
        it[K.settings] = AppJson.encodeToString(Settings.serializer(), env.settings)
        it[K.revision] = env.revision
        if (env.effectiveDailyLimit != null) it[K.effectiveLimit] = env.effectiveDailyLimit else it.remove(K.effectiveLimit)
    }

    suspend fun saveSummary(s: DeviceSummary) = context.store.edit {
        it[K.summary] = AppJson.encodeToString(DeviceSummary.serializer(), s)
    }

    suspend fun nextRoundRobin(): Int {
        var v = 0
        context.store.edit {
            v = it[K.rrNext] ?: 0
            it[K.rrNext] = (v + 1) % 2
        }
        return v
    }

    /** Settings chosen in the setup wizard, pushed to the server once the device is approved (if the server has none). */
    suspend fun draft(): Settings? =
        context.store.data.first()[K.draft]?.let { runCatching { AppJson.decodeFromString<Settings>(it) }.getOrNull() }

    suspend fun saveDraft(s: Settings?) = context.store.edit {
        if (s == null) it.remove(K.draft) else it[K.draft] = AppJson.encodeToString(Settings.serializer(), s)
    }

    suspend fun resetAll() = context.store.edit { it.clear() }
}
