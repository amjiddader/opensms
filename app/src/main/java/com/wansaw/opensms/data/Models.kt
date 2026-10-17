package com.wansaw.opensms.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

@Serializable
data class SimCfg(
    val enabled: Boolean = true,
    @SerialName("daily_limit") val dailyLimit: Int? = null,
)

@Serializable
data class SimsCfg(
    @SerialName("sim_1") val sim1: SimCfg = SimCfg(),
    @SerialName("sim_2") val sim2: SimCfg = SimCfg(),
) {
    fun forSlot(slot: Int) = if (slot == 1) sim1 else sim2
}

@Serializable
data class Destination(
    val mode: String = "single", // single | multiple | all
    val countries: List<String> = emptyList(),
)

@Serializable
data class Settings(
    @SerialName("home_country") val homeCountry: String? = null,
    val destination: Destination = Destination(),
    val sims: SimsCfg = SimsCfg(),
    @SerialName("sim_strategy") val simStrategy: String = "round_robin", // preferred | round_robin | least_used
    @SerialName("preferred_sim") val preferredSim: String? = null,
    @SerialName("retry_alternative_sim") val retryAlternativeSim: Boolean = true,
    @SerialName("daily_limit") val dailyLimit: Int? = null,
    @SerialName("pacing_interval_ms") val pacingIntervalMs: Int = 2000,
    @SerialName("delivery_reports") val deliveryReports: Boolean = false,
    @SerialName("history_retention_days") val historyRetentionDays: Int = 30,
) {
    val isConfigured: Boolean
        get() = !homeCountry.isNullOrBlank() && (destination.mode == "all" || destination.countries.isNotEmpty())
}

@Serializable
data class SettingsEnvelope(
    val revision: Int = 0,
    val settings: Settings = Settings(),
    @SerialName("effective_daily_limit") val effectiveDailyLimit: Int? = null,
)

@Serializable
data class SimReport(
    val slot: Int,
    val present: Boolean,
    @SerialName("carrier_name") val carrierName: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("country_iso") val countryIso: String? = null,
)

@Serializable
data class CallbackConfig(
    val enabled: Boolean = false,
    val url: String? = null,
    val secret: String? = null,
)

@Serializable
data class UsageToday(
    @SerialName("segments_admitted") val segmentsAdmitted: Int = 0,
    @SerialName("messages_sent") val messagesSent: Int = 0,
    @SerialName("messages_failed") val messagesFailed: Int = 0,
    @SerialName("daily_limit") val dailyLimit: Int? = null,
    val remaining: Int? = null,
)

@Serializable
data class DeviceSummary(
    val id: String = "",
    val name: String = "",
    val status: String = "",
    @SerialName("ban_reason") val banReason: String? = null,
    @SerialName("websocket_url") val websocketUrl: String? = null,
    val settings: SettingsEnvelope = SettingsEnvelope(),
    val sims: List<SimReport> = emptyList(),
    val callback: CallbackConfig = CallbackConfig(),
    @SerialName("usage_today") val usageToday: UsageToday = UsageToday(),
)

// ---- REST bodies -----------------------------------------------------------------------------

@Serializable
data class DeviceMeta(
    val model: String,
    val manufacturer: String,
    @SerialName("os_version") val osVersion: String,
    @SerialName("app_version") val appVersion: String,
    val timezone: String,
)

@Serializable
data class TermsAcceptance(val version: String, @SerialName("accepted_at") val acceptedAt: String)

@Serializable
data class LoginRequest(
    @SerialName("static_id") val staticId: String,
    val name: String,
    val device: DeviceMeta,
    val terms: TermsAcceptance,
)

@Serializable
data class LoginResponse(
    val status: String = "",
    val device: DeviceSummary? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("claim_token") val claimToken: String? = null,
    @SerialName("poll_after_seconds") val pollAfterSeconds: Int = 15,
    val kind: String? = null,
)

@Serializable
data class ClaimRequest(@SerialName("request_id") val requestId: String, @SerialName("claim_token") val claimToken: String)

@Serializable
data class ClaimResponse(
    val status: String = "",
    @SerialName("device_secret") val deviceSecret: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    val device: DeviceSummary? = null,
    @SerialName("poll_after_seconds") val pollAfterSeconds: Int = 15,
)

@Serializable
data class SettingsPut(val revision: Int, val settings: Settings)

@Serializable
data class SimsPut(val sims: List<SimReport>)

@Serializable
data class CallbackPut(val enabled: Boolean, val url: String?)

@Serializable
data class ApiKey(
    val id: String,
    val name: String,
    val key: String,
    val prefix: String = "",
    val scopes: List<String> = emptyList(),
    @SerialName("allowed_ips") val allowedIps: List<String>? = null,
    @SerialName("daily_request_cap") val dailyRequestCap: Int? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val status: String = "active",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("last_used_at") val lastUsedAt: String? = null,
)

@Serializable
data class KeyList(val data: List<ApiKey> = emptyList())

@Serializable
data class KeyCreate(
    val name: String,
    val scopes: List<String> = listOf("sms:send", "sms:read"),
    @SerialName("allowed_ips") val allowedIps: List<String>? = null,
    @SerialName("daily_request_cap") val dailyRequestCap: Int? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class CallbackDelivery(
    val id: String = "",
    @SerialName("job_id") val jobId: String? = null,
    val event: String = "",
    val status: String = "",
    val attempts: Int = 0,
    @SerialName("last_status_code") val lastStatusCode: Int? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class DeliveryList(val data: List<CallbackDelivery> = emptyList())

@Serializable
data class ApiErrorBody(val error: ApiErrorDetail? = null)

@Serializable
data class ApiErrorDetail(val code: String = "", val message: String = "", val details: JsonElement? = null)

// ---- WebSocket frames --------------------------------------------------------------------------

@Serializable
data class InFlight(@SerialName("job_id") val jobId: String, @SerialName("attempt_id") val attemptId: String?, val state: String)

@Serializable
data class Reconcile(val redispatch: List<String> = emptyList(), val lost: List<String> = emptyList())

@Serializable
data class DeviceReady(
    @SerialName("heartbeat_interval_seconds") val heartbeatIntervalSeconds: Int = 60,
    val settings: SettingsEnvelope? = null,
    val reconcile: Reconcile = Reconcile(),
)

@Serializable
data class SmsCommand(
    @SerialName("job_id") val jobId: String,
    val to: String,
    val text: String,
    val sim: String = "auto",
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("segments_estimated") val segmentsEstimated: Int = 1,
    val encoding: String = "gsm7",
)
