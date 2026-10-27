package com.wansaw.opensms.net

import com.wansaw.opensms.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An error answer from the backend (`{"error":{"code","message"}}`). */
class ApiException(val status: Int, val code: String, override val message: String, val body: String = "") : Exception(message)

/** No usable answer (offline, DNS, timeout, TLS ...). Always retryable. */
class NetworkException(cause: Throwable) : Exception(cause.message ?: "Network error", cause)

class TestResult(val ok: Boolean, val statusCode: Int?, val error: String?)

class ApiClient(
    baseUrl: String,
    private val secret: () -> String?,
    val http: OkHttpClient = defaultHttp(),
) {
    private val base = baseUrl.trimEnd('/')
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    val baseUrl: String get() = base
    val webSocketUrl: String get() = base.replaceFirst("http", "ws") + "/v1/connect"

    class Raw(val status: Int, val body: String)

    private suspend fun call(method: String, path: String, body: String? = null, auth: Boolean = true): Raw = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(base + path).header("Accept", "application/json")
        if (auth) secret()?.let { b.header("Authorization", "Bearer $it") }
        b.method(method, if (body != null || method in setOf("POST", "PUT", "PATCH")) (body ?: "{}").toRequestBody(jsonType) else null)
        try {
            http.newCall(b.build()).execute().use { r -> Raw(r.code, r.body.string()) }
        } catch (e: IOException) {
            throw NetworkException(e)
        }
    }

    private fun fail(r: Raw): Nothing {
        val err = runCatching { AppJson.decodeFromString<ApiErrorBody>(r.body).error }.getOrNull()
        throw ApiException(r.status, err?.code ?: "http_${r.status}", err?.message ?: "HTTP ${r.status}", r.body)
    }

    private suspend inline fun <reified T> ok(method: String, path: String, body: String? = null): T {
        val r = call(method, path, body)
        if (r.status !in 200..299) fail(r)
        return AppJson.decodeFromString(r.body)
    }

    // ---- install flow ---------------------------------------------------------------------------

    /** 200 = logged in, 202 = install request created. */
    suspend fun login(req: LoginRequest, useSecret: Boolean): Pair<Int, LoginResponse> {
        val r = call("POST", "/v1/devices/login", AppJson.encodeToString(LoginRequest.serializer(), req), auth = useSecret)
        if (r.status != 200 && r.status != 202) fail(r)
        return r.status to AppJson.decodeFromString(r.body)
    }

    /** 200 = approved (secret inside), 202 = still pending. */
    suspend fun claim(req: ClaimRequest): Pair<Int, ClaimResponse> {
        val r = call("POST", "/v1/devices/claim", AppJson.encodeToString(ClaimRequest.serializer(), req), auth = false)
        if (r.status != 200 && r.status != 202) fail(r)
        return r.status to AppJson.decodeFromString(r.body)
    }

    // ---- device ---------------------------------------------------------------------------------

    suspend fun me(): DeviceSummary = ok("GET", "/v1/devices/me")
    suspend fun putSettings(revision: Int, s: Settings): SettingsEnvelope =
        ok("PUT", "/v1/devices/me/settings", AppJson.encodeToString(SettingsPut.serializer(), SettingsPut(revision, s)))

    suspend fun putSims(sims: List<SimReport>) {
        ok<JsonObject>("PUT", "/v1/devices/me/sims", AppJson.encodeToString(SimsPut.serializer(), SimsPut(sims)))
    }

    // ---- callback -------------------------------------------------------------------------------

    suspend fun callback(): CallbackConfig = ok("GET", "/v1/devices/me/callback")
    suspend fun putCallback(enabled: Boolean, url: String?): CallbackConfig =
        ok("PUT", "/v1/devices/me/callback", AppJson.encodeToString(CallbackPut.serializer(), CallbackPut(enabled, url)))

    suspend fun rotateCallbackSecret(): CallbackConfig = ok("POST", "/v1/devices/me/callback/secret/rotate")
    suspend fun deliveries(): List<CallbackDelivery> = ok<DeliveryList>("GET", "/v1/devices/me/callback/deliveries").data
    suspend fun testCallback(): TestResult {
        val o = ok<JsonObject>("POST", "/v1/devices/me/callback/test")
        return TestResult(
            (o["ok"] as? JsonPrimitive)?.booleanOrNull == true,
            (o["status_code"] as? JsonPrimitive)?.intOrNull,
            (o["error"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    // ---- WhatsApp OTP ---------------------------------------------------------------------------

    suspend fun waba(): WabaState = ok("GET", "/v1/devices/me/waba")
    suspend fun putWaba(identifier: String?, enabled: Boolean?): WabaState =
        ok("PUT", "/v1/devices/me/waba", AppJson.encodeToString(WabaPut.serializer(), WabaPut(identifier, enabled)))

    suspend fun wabaAvailability(identifier: String): WabaAvailability =
        ok("GET", "/v1/devices/me/waba/availability?identifier=" + java.net.URLEncoder.encode(identifier, "UTF-8"))

    suspend fun putWabaCallback(url: String?): WabaState =
        ok("PUT", "/v1/devices/me/waba/callback", AppJson.encodeToString(WabaUrlPut.serializer(), WabaUrlPut(url)))

    suspend fun rotateWabaSecret(): WabaState = ok("POST", "/v1/devices/me/waba/callback/secret/rotate")
    suspend fun wabaLog(): List<WabaLogEntry> = ok<WabaLogList>("GET", "/v1/devices/me/waba/log?limit=20").data
    suspend fun testWabaCallback(): TestResult {
        val o = ok<JsonObject>("POST", "/v1/devices/me/waba/callback/test")
        return TestResult(
            (o["ok"] as? JsonPrimitive)?.booleanOrNull == true,
            (o["status_code"] as? JsonPrimitive)?.intOrNull,
            (o["error"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    // ---- API keys -------------------------------------------------------------------------------

    suspend fun keys(): List<ApiKey> = ok<KeyList>("GET", "/v1/keys").data
    suspend fun createKey(req: KeyCreate): ApiKey = ok("POST", "/v1/keys", AppJson.encodeToString(KeyCreate.serializer(), req))
    suspend fun revokeKey(id: String): ApiKey = ok("DELETE", "/v1/keys/$id")

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }
}
