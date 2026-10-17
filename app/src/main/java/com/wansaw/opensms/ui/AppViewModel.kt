package com.wansaw.opensms.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wansaw.opensms.OpenSmsApp
import com.wansaw.opensms.data.*
import com.wansaw.opensms.gateway.GatewayService
import com.wansaw.opensms.gateway.Phase
import com.wansaw.opensms.net.ApiException
import com.wansaw.opensms.net.NetworkException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class Async<T>(val loading: Boolean = false, val data: T? = null, val error: String? = null)

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    private val c = (app as OpenSmsApp).container

    val gateway = c.gateway.state
    val setupDone: StateFlow<Boolean?> = c.prefs.setupDone.map<Boolean, Boolean?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val deviceName: StateFlow<String> = c.prefs.deviceName.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val gatewayEnabled: StateFlow<Boolean> = c.prefs.gatewayEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val settingsEnv: StateFlow<SettingsEnvelope> = c.prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsEnvelope())
    val summary: StateFlow<DeviceSummary?> = c.prefs.summary.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val startOfDay get() = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val history: StateFlow<List<JobEntity>> = c.db.jobs().history().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sentToday: StateFlow<Int> = c.db.jobs().sentSince(startOfDay).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val failedToday: StateFlow<Int> = c.db.jobs().failedSince(startOfDay).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val pending: StateFlow<Int> = c.db.jobs().pending().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _sims = MutableStateFlow(c.sims.report())
    val sims: StateFlow<List<SimReport>> = _sims
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    fun toast(t: String) { _messages.tryEmit(t) }

    init {
        // Service is (re)started whenever the app is opened and setup is complete.
        viewModelScope.launch {
            setupDone.filterNotNull().first { true }
            ensureRunning()
        }
    }

    fun refreshSims() { _sims.value = c.sims.report() }
    fun homeCountryGuess(): String? = c.sims.homeCountryGuess()
    fun hasSimPermission() = c.sims.hasPhonePermission()
    val staticIdHint: String get() = android.provider.Settings.Secure.getString(app.contentResolver, android.provider.Settings.Secure.ANDROID_ID).orEmpty()

    fun ensureRunning() {
        viewModelScope.launch {
            if (c.prefs.setupDone.first() && c.prefs.gatewayEnabled.first()) {
                runCatching { GatewayService.start(app) }
            }
        }
    }

    // ---- setup ------------------------------------------------------------------------------

    fun acceptTerms() = viewModelScope.launch { c.prefs.acceptTerms(com.wansaw.opensms.BuildConfig.TERMS_VERSION, java.time.Instant.now().toString()) }

    fun completeSetup(name: String, draft: Settings) = viewModelScope.launch {
        c.prefs.setDeviceName(name.trim())
        c.prefs.saveDraft(draft)
        c.prefs.saveSettings(SettingsEnvelope(0, draft))
        c.prefs.setGatewayEnabled(true)
        c.prefs.setSetupDone(true)
        GatewayService.start(app)
    }

    // ---- gateway ------------------------------------------------------------------------------

    fun setGatewayOn(on: Boolean) = viewModelScope.launch {
        c.prefs.setGatewayEnabled(on)
        if (on) GatewayService.start(app) else GatewayService.stop(app)
    }

    fun retryNow() = c.gateway.reconnectNow()

    /** Button "Refresh status": ask the server immediately whether the administrator approved this phone. */
    fun refreshStatus() {
        ensureRunning()
        c.gateway.reconnectNow()
    }

    /** Called whenever the app comes to the foreground. */
    fun onForeground() {
        refreshSims()
        ensureRunning()
        if (c.gateway.state.value.phase in setOf(Phase.WAITING_APPROVAL, Phase.OFFLINE)) c.gateway.reconnectNow()
    }
    fun requestAgain() = c.gateway.retryAfterRejection()

    val canPause get() = gateway.value.phase != Phase.PAUSED

    fun updateSettings(okMessage: String? = null, change: (Settings) -> Settings) = viewModelScope.launch {
        val err = c.gateway.updateSettings(change)
        if (err != null) toast(err) else if (okMessage != null) toast(okMessage)
    }

    fun refreshAll() = viewModelScope.launch {
        c.gateway.refresh()?.let { toast(it) }
        refreshSims()
    }

    // ---- API keys -----------------------------------------------------------------------------

    private val _keys = MutableStateFlow(Async<List<ApiKey>>())
    val keys: StateFlow<Async<List<ApiKey>>> = _keys

    private suspend fun <T> guarded(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: ApiException) {
        Result.failure(Exception(e.message))
    } catch (e: NetworkException) {
        Result.failure(Exception("No connection to the server"))
    }

    fun loadKeys() = viewModelScope.launch {
        _keys.value = _keys.value.copy(loading = true, error = null)
        guarded { c.api.keys() }.onSuccess { _keys.value = Async(false, it) }.onFailure { _keys.value = Async(false, _keys.value.data, it.message) }
    }

    fun createKey(name: String, onCreated: (ApiKey) -> Unit) = viewModelScope.launch {
        guarded { c.api.createKey(KeyCreate(name = name.trim())) }.onSuccess { onCreated(it); loadKeys() }.onFailure { toast(it.message ?: "Failed") }
    }

    fun revokeKey(id: String) = viewModelScope.launch {
        guarded { c.api.revokeKey(id) }.onSuccess { toast("Key revoked"); loadKeys() }.onFailure { toast(it.message ?: "Failed") }
    }

    // ---- callback -----------------------------------------------------------------------------

    private val _callback = MutableStateFlow(Async<CallbackConfig>())
    val callback: StateFlow<Async<CallbackConfig>> = _callback
    private val _deliveries = MutableStateFlow<List<CallbackDelivery>>(emptyList())
    val deliveries: StateFlow<List<CallbackDelivery>> = _deliveries
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing

    fun loadCallback() = viewModelScope.launch {
        _callback.value = _callback.value.copy(loading = true, error = null)
        guarded { c.api.callback() }.onSuccess { _callback.value = Async(false, it) }.onFailure { _callback.value = Async(false, _callback.value.data, it.message) }
        guarded { c.api.deliveries() }.onSuccess { _deliveries.value = it }
    }

    fun saveCallback(enabled: Boolean, url: String) = viewModelScope.launch {
        guarded { c.api.putCallback(enabled, url.trim().ifBlank { null }) }
            .onSuccess { _callback.value = Async(false, it); toast(if (enabled) "Callbacks enabled" else "Callbacks disabled") }
            .onFailure { toast(it.message ?: "Failed") }
    }

    fun testCallback() = viewModelScope.launch {
        _testing.value = true
        guarded { c.api.testCallback() }.onSuccess {
            toast(if (it.ok) "Test delivered (HTTP ${it.statusCode ?: 200})" else "Test failed: ${it.error ?: "HTTP ${it.statusCode}"}")
        }.onFailure { toast(it.message ?: "Failed") }
        _testing.value = false
        guarded { c.api.deliveries() }.onSuccess { _deliveries.value = it }
    }

    fun rotateSecret() = viewModelScope.launch {
        guarded { c.api.rotateCallbackSecret() }.onSuccess { _callback.value = Async(false, it); toast("Signing secret rotated") }.onFailure { toast(it.message ?: "Failed") }
    }

    // ---- reset --------------------------------------------------------------------------------

    fun resetApp(done: () -> Unit) = viewModelScope.launch {
        GatewayService.stop(app)
        c.gateway.resetLocal()
        done()
    }
}
