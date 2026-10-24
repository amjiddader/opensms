package com.wansaw.opensms.gateway

import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings.Secure
import android.util.Log
import com.wansaw.opensms.BuildConfig
import com.wansaw.opensms.data.*
import com.wansaw.opensms.net.ApiClient
import com.wansaw.opensms.net.ApiException
import com.wansaw.opensms.net.NetworkException
import com.wansaw.opensms.sms.MAX_MESSAGE_CHARS
import com.wansaw.opensms.sms.Recipient
import com.wansaw.opensms.sms.RevSms
import com.wansaw.opensms.sms.RecipientResult
import com.wansaw.opensms.sms.SimInfo
import com.wansaw.opensms.sms.SmsError
import com.wansaw.opensms.sms.SmsSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class Phase { SETUP_REQUIRED, STARTING, LOGGING_IN, WAITING_APPROVAL, REJECTED, CONNECTING, ONLINE, OFFLINE, BLOCKED, UPDATE_REQUIRED, PAUSED }

data class GatewayState(
    val phase: Phase = Phase.STARTING,
    val message: String? = null,
    val requestId: String? = null,
    val retryInSeconds: Int? = null,
    /** When the app last asked the server whether the installation was approved (epoch ms). */
    val lastCheckedAt: Long? = null,
) {
    val isOnline get() = phase == Phase.ONLINE
}

/**
 * The brain of the app: login / approval / claim, the WebSocket session, the persistent job queue and the
 * SMS dispatcher. It runs inside [GatewayService] but all durable state lives in Room, so a process death
 * never loses an accepted job or an unacknowledged event.
 */
class Gateway(
    private val context: Context,
    private val prefs: Prefs,
    private val secure: SecureStore,
    private val db: AppDatabase,
    private val api: ApiClient,
    private val sims: SimInfo,
    private val sender: SmsSender,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(GatewayState(Phase.STARTING))
    val state: StateFlow<GatewayState> = _state.asStateFlow()

    private val jobs get() = db.jobs()
    private val outbox get() = db.outbox()

    private var loopJob: Job? = null
    private var dispatcherJob: Job? = null
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val retryAfterReject = Channel<Unit>(Channel.CONFLATED)
    private val eventClock = AtomicLong(0)
    private val flushLock = Mutex()
    private val sessionRef = java.util.concurrent.atomic.AtomicReference<Session?>(null)

    private class Session(val ws: WebSocket) {
        @Volatile var accepting = false
        val sentIds = java.util.Collections.synchronizedSet(HashSet<String>())
    }

    // ------------------------------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------------------------------

    @Synchronized
    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { runLoop() }
        dispatcherJob = scope.launch { dispatcherLoop() }
    }

    @Synchronized
    fun stop() {
        loopJob?.cancel()
        dispatcherJob?.cancel()
        loopJob = null
        dispatcherJob = null
        sessionRef.getAndSet(null)?.ws?.cancel()
        _state.value = GatewayState(Phase.PAUSED)
    }

    val isRunning get() = loopJob?.isActive == true

    /** The user pressed "Refresh status" / "Retry now": skip any waiting and ask the server right away. */
    fun reconnectNow() {
        kick.trySend(Unit)
    }

    /** A network became available: only useful when we are not connected (a live socket notices a dead network itself). */
    fun onNetworkAvailable() {
        when (_state.value.phase) {
            Phase.OFFLINE, Phase.WAITING_APPROVAL, Phase.LOGGING_IN -> kick.trySend(Unit)
            else -> Unit
        }
    }

    /** User pressed "request again" after a rejection. */
    fun retryAfterRejection() {
        retryAfterReject.trySend(Unit)
    }

    // ------------------------------------------------------------------------------------------
    // login / approval / claim
    // ------------------------------------------------------------------------------------------

    private class Blocked(val reason: String?) : Exception()
    private class UpdateRequired : Exception()

    private fun staticId(): String =
        Secure.getString(context.contentResolver, Secure.ANDROID_ID)?.takeIf { it.length >= 8 } ?: error("ANDROID_ID unavailable")

    private fun loginRequest(name: String, terms: Pair<String, String>) = LoginRequest(
        staticId = staticId(),
        name = name.ifBlank { Build.MODEL },
        device = deviceMeta(),
        terms = TermsAcceptance(terms.first, terms.second),
    )

    private fun deviceMeta() = DeviceMeta(
        model = Build.MODEL.take(80),
        manufacturer = Build.MANUFACTURER.take(80),
        osVersion = Build.VERSION.RELEASE.take(40),
        appVersion = BuildConfig.VERSION_NAME.take(40),
        timezone = TimeZone.getDefault().id,
    )

    @Volatile private var lastCheckedAt: Long? = null

    private fun set(phase: Phase, message: String? = null, requestId: String? = null, retryIn: Int? = null) {
        _state.value = GatewayState(phase, message, requestId, retryIn, lastCheckedAt)
    }

    private suspend fun waitUntilSetupDone() {
        if (prefs.setupDone.first()) return
        set(Phase.SETUP_REQUIRED)
        while (!prefs.setupDone.first()) delay(1000)
    }

    /** Makes sure we hold a valid device secret; returns the summary when the login itself succeeded. */
    private suspend fun authenticate() {
        while (scope.isActive) {
            try {
                if (secure.requestId != null && secure.claimToken != null) {
                    if (claimLoop()) return
                    continue
                }
                set(Phase.LOGGING_IN)
                val terms = prefs.termsAccepted() ?: ("" to "")
                val req = loginRequest(prefs.deviceName.first(), if (terms.first.isEmpty()) BuildConfig.TERMS_VERSION to Instant.now().toString() else terms)
                val hasSecret = secure.deviceSecret != null
                val (status, res) = api.login(req, useSecret = hasSecret)
                if (status == 200 && res.device != null) {
                    onSummary(res.device)
                    return
                }
                if (status == 202 && res.requestId != null && res.claimToken != null) {
                    secure.requestId = res.requestId
                    secure.claimToken = res.claimToken
                    continue
                }
                throw ApiException(status, "unexpected_login_response", "Unexpected login response")
            } catch (e: ApiException) {
                handleAuthError(e)
            } catch (e: NetworkException) {
                waitRetry("No connection to the server", 15)
            }
        }
    }

    /** Polls the claim endpoint. Returns true once the secret was received. */
    private suspend fun claimLoop(): Boolean {
        var wait = 5
        while (scope.isActive) {
            val rid = secure.requestId ?: return false
            val tok = secure.claimToken ?: return false
            set(Phase.WAITING_APPROVAL, requestId = rid)
            try {
                val (status, res) = api.claim(ClaimRequest(rid, tok))
                if (status == 200 && res.deviceSecret != null) {
                    secure.deviceSecret = res.deviceSecret
                    secure.clearInstallRequest()
                    res.device?.let { onSummary(it) }
                    return true
                }
                lastCheckedAt = System.currentTimeMillis()
                set(Phase.WAITING_APPROVAL, requestId = rid)
                wait = res.pollAfterSeconds.coerceIn(5, 10)
            } catch (e: ApiException) {
                when (e.code) {
                    "install_rejected" -> {
                        secure.clearInstallRequest()
                        set(Phase.REJECTED, "The administrator rejected this installation")
                        retryAfterReject.receive()
                        return false
                    }
                    "request_expired" -> {
                        secure.clearInstallRequest()
                        return false
                    }
                    else -> if (e.status == 429) wait = 6 else handleAuthError(e) // server allows one poll per 5 s
                }
            } catch (_: NetworkException) {
                wait = 15
            }
            // sleep, but wake up early on a kick
            withTimeoutOrNull(wait * 1000L) { kick.receive() }
        }
        return false
    }

    private suspend fun handleAuthError(e: ApiException) {
        when {
            e.code == "device_banned" -> throw Blocked(e.message)
            e.code == "registration_closed" -> {
                set(Phase.REJECTED, "New device registration is closed on the server")
                retryAfterReject.receive()
            }
            e.status == 429 -> waitRetry("Too many attempts, waiting", 120)
            e.status >= 500 -> waitRetry("Server error (${e.status})", 30)
            else -> waitRetry(e.message, 60)
        }
    }

    private suspend fun waitRetry(message: String, seconds: Int) {
        var left = seconds
        while (left > 0 && scope.isActive) {
            set(Phase.OFFLINE, message, retryIn = left)
            if (withTimeoutOrNull(1000) { kick.receive() } != null) return
            left--
        }
    }

    /** Applies the server's view after login/claim: restore settings, push the setup draft, report SIMs. */
    private suspend fun onSummary(sum: DeviceSummary) {
        prefs.saveSummary(sum)
        val draft = prefs.draft()
        if (!sum.settings.settings.isConfigured && draft != null && draft.isConfigured) {
            try {
                val saved = api.putSettings(sum.settings.revision, draft)
                prefs.saveSettings(saved)
                prefs.saveDraft(null)
            } catch (e: ApiException) {
                Log.w(TAG, "could not push the draft settings: ${e.code}")
                prefs.saveSettings(sum.settings)
            }
        } else {
            prefs.saveSettings(sum.settings) // server wins: restores everything after a reinstall
            if (sum.settings.settings.isConfigured) prefs.saveDraft(null)
        }
        runCatching { api.putSims(sims.report()) }
    }

    // ------------------------------------------------------------------------------------------
    // main loop
    // ------------------------------------------------------------------------------------------

    private suspend fun runLoop() {
        var backoff = 2
        var needLogin = true
        while (scope.isActive) {
            try {
                waitUntilSetupDone()
                if (needLogin || secure.deviceSecret == null) {
                    authenticate()
                    needLogin = false
                }
                val outcome = serve()
                when (outcome) {
                    is Outcome.Closed -> {
                        when (outcome.code) {
                            4002, 401 -> {
                                secure.deviceSecret = null
                                needLogin = true
                            }
                            4003, 403 -> throw Blocked(outcome.reason)
                            4004 -> throw UpdateRequired()
                            4001 -> {
                                waitRetry("Another connection replaced this one", 60)
                                continue
                            }
                        }
                        if (outcome.wasReady) backoff = 2
                    }
                    is Outcome.Failed -> if (outcome.wasReady) backoff = 2
                }
                waitRetry("Disconnected, reconnecting", backoff)
                backoff = (backoff * 2).coerceAtMost(120)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Blocked) {
                set(Phase.BLOCKED, e.reason ?: "This device has been blocked")
                return
            } catch (e: UpdateRequired) {
                set(Phase.UPDATE_REQUIRED, "Please update the app")
                return
            } catch (e: ApiException) {
                handleAuthError(e)
            } catch (e: Exception) {
                Log.e(TAG, "loop error", e)
                waitRetry(e.message ?: "Unexpected error", backoff)
                backoff = (backoff * 2).coerceAtMost(120)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // WebSocket session
    // ------------------------------------------------------------------------------------------

    private sealed interface Ws {
        data object Open : Ws
        data class Text(val s: String) : Ws
        data class Closed(val code: Int, val reason: String) : Ws
        data class Failed(val t: Throwable, val httpCode: Int?) : Ws
    }

    private sealed interface Outcome {
        data class Closed(val code: Int, val reason: String?, val wasReady: Boolean) : Outcome
        data class Failed(val message: String?, val wasReady: Boolean) : Outcome
    }

    private suspend fun serve(): Outcome {
        val secret = secure.deviceSecret ?: return Outcome.Closed(401, "no secret", false)
        set(Phase.CONNECTING)
        val ch = Channel<Ws>(Channel.UNLIMITED)
        val request = Request.Builder().url(api.webSocketUrl).header("Authorization", "Bearer $secret").build()
        val ws = api.http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { ch.trySend(Ws.Open) }
            override fun onMessage(webSocket: WebSocket, text: String) { ch.trySend(Ws.Text(text)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                ch.trySend(Ws.Closed(code, reason))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ch.trySend(Ws.Closed(code, reason)) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ch.trySend(Ws.Failed(t, response?.code)) }
        })
        val session = Session(ws)
        sessionRef.set(session)
        kick.tryReceive() // drop a stale "retry now" from before this connection
        var ready = false
        var heartbeat: Job? = null
        val readyTimeout = scope.launch {
            delay(25_000)
            if (!ready) ws.cancel()
        }
        try {
            // A kick while connected means "network changed": drop the socket and reconnect.
            val kicker = scope.launch { kick.receive(); ws.cancel() }
            try {
                for (ev in ch) {
                    when (ev) {
                        Ws.Open -> send(session, buildHello())
                        is Ws.Text -> {
                            val wasReady = ready
                            if (handleText(session, ev.s) && !wasReady) {
                                ready = true
                                heartbeat = scope.launch { heartbeatLoop(session) }
                            }
                        }
                        is Ws.Closed -> return Outcome.Closed(ev.code, ev.reason, ready)
                        is Ws.Failed -> {
                            return when (ev.httpCode) {
                                401 -> Outcome.Closed(4002, "unauthorized", ready)
                                403 -> Outcome.Closed(4003, "forbidden", ready)
                                else -> Outcome.Failed(ev.t.message, ready)
                            }
                        }
                    }
                }
            } finally {
                kicker.cancel()
            }
            return Outcome.Failed("closed", ready)
        } finally {
            readyTimeout.cancel()
            heartbeat?.cancel()
            session.accepting = false
            sessionRef.compareAndSet(session, null)
            ws.cancel()
        }
    }

    private fun send(session: Session, o: JsonObject): Boolean = session.ws.send(o.toString())

    private suspend fun heartbeatLoop(session: Session) {
        while (scope.isActive) {
            delay(heartbeatSeconds * 1000L)
            val pending = jobs.active().size
            send(session, buildJsonObject {
                put("type", "device.heartbeat")
                put("sims", simsJson())
                put("queue_depth", pending)
            })
        }
    }

    @Volatile private var heartbeatSeconds = 60

    private fun simsJson(): JsonArray = JsonArray(sims.report().map { AppJson.encodeToJsonElement(SimReport.serializer(), it) })

    private suspend fun buildHello(): JsonObject {
        val env = prefs.settings.first()
        val inflight = LinkedHashMap<String, JobEntity?>()
        jobs.active().forEach { inflight[it.jobId] = it }
        outbox.pendingJobIds().forEach { id -> if (id !in inflight) inflight[id] = jobs.get(id) }
        return buildJsonObject {
            put("type", "device.hello")
            put("protocol_version", BuildConfig.PROTOCOL_VERSION)
            put("app_version", BuildConfig.VERSION_NAME)
            putJsonObject("device") {
                put("model", Build.MODEL.take(80))
                put("manufacturer", Build.MANUFACTURER.take(80))
                put("os_version", Build.VERSION.RELEASE.take(40))
            }
            put("timezone", ZoneId.systemDefault().id)
            put("sims", simsJson())
            put("settings_revision", env.revision)
            putJsonArray("in_flight") {
                inflight.forEach { (id, job) ->
                    add(buildJsonObject {
                        put("job_id", id)
                        job?.attemptId?.let { put("attempt_id", it) }
                        put("state", job?.state ?: JobState.UNKNOWN)
                    })
                }
            }
        }
    }

    /** Returns true when this message was `device.ready` (handshake finished). */
    private suspend fun handleText(session: Session, text: String): Boolean {
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "device.ready" -> {
                val ready = AppJson.decodeFromJsonElement(DeviceReady.serializer(), obj)
                heartbeatSeconds = ready.heartbeatIntervalSeconds.coerceIn(10, 300)
                ready.settings?.let { applyServerSettings(it, ack = false) }
                uploadOutbox(session, force = true)
                session.accepting = true
                send(session, buildJsonObject { put("type", "device.accepting") })
                set(Phase.ONLINE)
                wake.trySend(Unit)
                return true
            }
            "events.ack" -> {
                val ids = (obj["event_ids"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                if (ids.isNotEmpty()) {
                    outbox.delete(ids)
                    session.sentIds.removeAll(ids.toSet())
                }
            }
            "sms.command" -> onCommand(AppJson.decodeFromJsonElement(SmsCommand.serializer(), obj))
            "device.settings" -> {
                val env = AppJson.decodeFromJsonElement(SettingsEnvelope.serializer(), obj)
                applyServerSettings(env, ack = true)
            }
            "error" -> Log.w(TAG, "server error: $text")
        }
        return false
    }

    private suspend fun applyServerSettings(env: SettingsEnvelope, ack: Boolean) {
        val local = prefs.settings.first()
        if (env.revision >= local.revision) prefs.saveSettings(env)
        if (ack) sessionRef.get()?.let { s -> send(s, buildJsonObject { put("type", "device.settings.ack"); put("revision", env.revision) }) }
    }

    // ------------------------------------------------------------------------------------------
    // events (outbox)
    // ------------------------------------------------------------------------------------------

    private fun nextTs(): Long {
        val now = System.currentTimeMillis()
        return eventClock.updateAndGet { if (now > it) now else it + 1 }
    }

    private fun newEventId(ts: Long): String = "e" + ts.toString(36).padStart(10, '0') + UUID.randomUUID().toString().replace("-", "").take(12)

    private suspend fun emit(
        type: String,
        job: JobEntity,
        attemptId: String = job.attemptId,
        sim: Int? = job.simUsed,
        segmentsTotal: Int? = null,
        segmentsSent: Int? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
        stage: String? = null,
    ) {
        val ts = nextTs()
        val id = newEventId(ts)
        val json = buildJsonObject {
            put("type", type)
            put("event_id", id)
            put("job_id", job.jobId)
            put("attempt_id", attemptId)
            put("ts", ts)
            sim?.let { put("sim", it) }
            segmentsTotal?.let { put("segments_total", it.coerceIn(0, 255)) }
            segmentsSent?.let { put("segments_sent", it.coerceIn(0, 255)) }
            errorCode?.let { put("error_code", it.take(64)) }
            errorMessage?.let { put("error_message", it.take(200)) }
            stage?.let { put("stage", it) }
        }
        outbox.insert(EventEntity(id, job.jobId, json.toString(), ts))
        sessionRef.get()?.takeIf { it.accepting }?.let { uploadOutbox(it, force = false) }
    }

    /**
     * Reverse SMS (ReverseOTP). Called by [com.wansaw.opensms.sms.SmsReceivedReceiver] for every incoming SMS.
     * Messages that do not match the global hint are ignored (nothing is stored or sent). A match is written to
     * the outbox first and uploaded as `sms.received`; it stays there until the server acknowledges it.
     */
    suspend fun onSmsReceived(from: String, body: String, slot: Int?, receivedAt: Long) {
        if (!prefs.setupDone.first() || !prefs.gatewayEnabled.first()) return
        val cfg = prefs.settings.first().settings
        if (!RevSms.matches(body, cfg.revsmsHint, cfg.revsmsMatch, cfg.revsmsEnabled) || from.isBlank()) return
        val ts = nextTs()
        val id = newEventId(ts)
        val json = buildJsonObject {
            put("type", "sms.received")
            put("event_id", id)
            put("ts", ts)
            put("received_at", if (receivedAt > 0) receivedAt else ts)
            slot?.let { put("sim", it) }
            put("from", from.take(64))
            put("body", body.take(RevSms.MAX_BODY))
            put("hint", cfg.revsmsHint.trim().take(32))
        }
        outbox.pruneInbound(System.currentTimeMillis() - INBOUND_KEEP_MS)
        outbox.insert(EventEntity(id, null, json.toString(), ts))
        sessionRef.get()?.takeIf { it.accepting }?.let { uploadOutbox(it, force = false) }
    }

    /** Sends everything not yet acknowledged (in order, <=100 per frame). */
    private suspend fun uploadOutbox(session: Session, force: Boolean) {
        flushLock.withLock {
            if (force) session.sentIds.clear()
            while (true) {
                val batch = outbox.oldest(100).filter { it.eventId !in session.sentIds }
                if (batch.isEmpty()) return
                val frame = buildJsonObject {
                    put("type", "events.batch")
                    put("events", JsonArray(batch.map { Json.parseToJsonElement(it.json) }))
                }
                if (!send(session, frame)) return
                session.sentIds.addAll(batch.map { it.eventId })
                if (batch.size < 100) return
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // incoming commands + dispatcher
    // ------------------------------------------------------------------------------------------

    private fun newAttemptId() = "att-" + UUID.randomUUID().toString().replace("-", "").take(20)

    private suspend fun onCommand(cmd: SmsCommand) {
        if (jobs.get(cmd.jobId) != null) return // duplicate delivery after a reconnect
        val now = System.currentTimeMillis()
        val attempt = newAttemptId()
        val base = JobEntity(
            jobId = cmd.jobId, attemptId = attempt, recipient = cmd.to, text = "", simRequested = cmd.sim,
            expiresAt = cmd.expiresAt, segmentsEstimated = cmd.segmentsEstimated, state = JobState.ACCEPTED, createdAt = now,
        )
        if (now >= cmd.expiresAt) {
            finishRejected(base, JobState.EXPIRED, "expired", "The job expired before it reached the phone")
            return
        }
        if (cmd.text.codePointCount(0, cmd.text.length) > MAX_MESSAGE_CHARS) {
            finishRejected(base, JobState.FAILED, "message_too_long", "A message can have at most $MAX_MESSAGE_CHARS characters")
            return
        }
        val settings = prefs.settings.first().settings
        when (val r = Recipient.validate(cmd.to, settings)) {
            is RecipientResult.Rejected -> finishRejected(base, JobState.FAILED, r.code, r.message)
            is RecipientResult.Ok -> {
                // The number is kept and dialled exactly as the API caller wrote it.
                jobs.insert(base.copy(recipient = r.dial, text = cmd.text))
                emit("sms.accepted", base.copy(recipient = r.dial), sim = null)
                wake.trySend(Unit)
            }
        }
    }

    private suspend fun finishRejected(job: JobEntity, state: String, code: String, message: String) {
        val j = job.copy(state = state, errorCode = code, errorMessage = message, finishedAt = System.currentTimeMillis())
        jobs.insert(j)
        emit("sms.failed", j, sim = null, errorCode = code, errorMessage = message, stage = "send")
    }

    private fun startOfDay(): Long =
        java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private suspend fun dispatcherLoop() {
        recoverStale()
        var lastMaintenance = 0L
        while (scope.isActive) {
            try {
                val job = jobs.nextAccepted(System.currentTimeMillis())
                if (job == null) {
                    withTimeoutOrNull(30_000) { wake.receive() }
                    val now = System.currentTimeMillis()
                    if (now - lastMaintenance > 30_000) {
                        lastMaintenance = now
                        recoverStale()
                        val days = prefs.settings.first().settings.historyRetentionDays
                        jobs.prune(now - days * 86_400_000L)
                    }
                    continue
                }
                process(job)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "dispatcher error", e)
                delay(2000)
            }
        }
    }

    private suspend fun fail(job: JobEntity, code: String, message: String, sim: Int? = job.simUsed) {
        val j = job.copy(state = JobState.FAILED, errorCode = code, errorMessage = message, text = "", finishedAt = System.currentTimeMillis())
        jobs.update(j)
        emit("sms.failed", j, sim = sim, segmentsTotal = job.partsTotal.takeIf { it > 0 }, segmentsSent = 0, errorCode = code, errorMessage = message, stage = "send")
    }

    private suspend fun process(job: JobEntity) {
        val now = System.currentTimeMillis()
        if (now >= job.expiresAt) {
            val j = job.copy(state = JobState.EXPIRED, errorCode = "expired", errorMessage = "Expired before sending", text = "", finishedAt = now)
            jobs.update(j)
            emit("sms.failed", j, sim = null, errorCode = "expired", errorMessage = "Expired before sending", stage = "send")
            return
        }
        val env = prefs.settings.first()
        val settings = env.settings
        val rec = Recipient.validate(job.recipient, settings)
        if (rec is RecipientResult.Rejected) return fail(job, rec.code, rec.message, sim = null)

        val day = startOfDay()
        val limit = env.effectiveDailyLimit
        if (limit != null && jobs.messagesSince(day, 0) > limit) {
            return fail(job, "quota_exceeded", "Daily message limit reached", sim = null)
        }

        val wanted = when (job.simRequested) {
            "sim_1" -> listOf(1)
            "sim_2" -> listOf(2)
            else -> listOf(1, 2)
        }
        val usable = wanted.filter { slot ->
            val cfg = settings.sims.forSlot(slot)
            cfg.enabled && sims.isPresent(slot) && (cfg.dailyLimit == null || jobs.messagesSince(day, slot) < cfg.dailyLimit)
        }
        // A retry prefers the SIM that has not been tried yet (if allowed); otherwise it tries the same SIM again.
        val untried = usable.filter { job.triedSims and (1 shl (it - 1)) == 0 }
        val candidates = when {
            job.attemptNo <= 1 -> usable
            settings.retryAlternativeSim && untried.isNotEmpty() -> untried
            else -> usable.filter { it == job.simUsed }.ifEmpty { usable }
        }
        if (candidates.isEmpty()) {
            return fail(
                job,
                job.errorCode ?: "sim_not_available",
                job.errorMessage ?: "No enabled SIM with remaining quota is available",
                sim = job.simUsed,
            )
        }
        val slot = pickSim(settings, candidates, day)
        val parts = sender.divide(job.text, slot) ?: return fail(job, "sim_not_available", "SIM $slot is not available")
        if (parts.isEmpty() || parts.size > 255) return fail(job, "message_too_long", "Message cannot be split into SMS parts")

        val attempt = if (job.attemptNo <= 1) job.attemptId else newAttemptId()
        val submitting = job.copy(
            state = JobState.SUBMITTING, attemptId = attempt, simUsed = slot, triedSims = job.triedSims or (1 shl (slot - 1)),
            partsTotal = parts.size, partsOk = 0, partsFailed = 0, partsDelivered = 0, deliveryFailed = false,
            errorCode = null, errorMessage = null, submittedAt = now,
        )
        jobs.update(submitting)
        emit("sms.submitting", submitting, segmentsTotal = parts.size)
        try {
            sender.send(job.jobId, attempt, job.recipient, parts, slot, settings.deliveryReports)
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
            val code = if (e is SecurityException) "permission_denied" else "send_exception"
            // Nothing was handed to the radio, so the whole attempt failed.
            jobs.update(jobs.get(job.jobId)!!.copy(partsFailed = parts.size, errorCode = code, errorMessage = e.message?.take(180)))
            finalizeAttempt(jobs.get(job.jobId)!!)
        }
        // Stay far below the platform's ~30 messages/minute limit for non-default SMS apps.
        delay(maxOf(settings.pacingIntervalMs, MIN_PACING_MS).toLong() * parts.size)
    }

    private suspend fun pickSim(s: Settings, candidates: List<Int>, day: Long): Int {
        if (candidates.size == 1) return candidates[0]
        return when (s.simStrategy) {
            "preferred" -> s.preferredSim?.removePrefix("sim_")?.toIntOrNull()?.takeIf { it in candidates } ?: candidates[0]
            "least_used" -> candidates.minBy { jobs.messagesSince(day, it) }
            else -> (prefs.nextRoundRobin() + 1).takeIf { it in candidates } ?: candidates[0]
        }
    }

    // ------------------------------------------------------------------------------------------
    // results from the platform
    // ------------------------------------------------------------------------------------------

    suspend fun onPartSent(jobId: String, attemptId: String, resultCode: Int) {
        val ok = resultCode == Activity.RESULT_OK
        val failure = if (ok) null else SmsError.fromResult(resultCode)
        val job = jobs.recordPart(jobId, attemptId, ok, failure?.code, failure?.message) ?: return
        if (job.partsOk + job.partsFailed >= job.partsTotal && job.state == JobState.SUBMITTING) finalizeAttempt(job)
    }

    private suspend fun finalizeAttempt(job: JobEntity) {
        val now = System.currentTimeMillis()
        val settings = prefs.settings.first().settings
        when {
            job.partsOk >= job.partsTotal && job.partsTotal > 0 -> {
                val sent = job.copy(state = JobState.SENT, text = "", finishedAt = if (settings.deliveryReports) null else now)
                jobs.update(sent)
                emit("sms.sent", sent, segmentsTotal = job.partsTotal, segmentsSent = job.partsOk)
            }
            job.partsOk == 0 -> {
                // Confirmed failure: nothing was sent, so a retry can never double-send.
                val retryable = job.errorCode in RETRYABLE_CODES
                if (retryable && job.attemptNo <= settings.retryCount) {
                    val delayMs = settings.retryDelayMs.coerceIn(0, 60_000).toLong()
                    jobs.update(job.copy(state = JobState.ACCEPTED, attemptNo = job.attemptNo + 1, notBefore = now + delayMs))
                    scope.launch { delay(delayMs + 50); wake.trySend(Unit) }
                } else {
                    val tries = if (job.attemptNo > 1) " (after ${job.attemptNo} attempts)" else ""
                    fail(job, job.errorCode ?: "send_failed", ((job.errorMessage ?: "The message could not be sent") + tries))
                }
            }
            else -> {
                val u = job.copy(state = JobState.UNKNOWN, errorCode = "partial_send", errorMessage = "${job.partsOk} of ${job.partsTotal} parts were sent", text = "", finishedAt = now)
                jobs.update(u)
                emit("sms.unknown", u, segmentsTotal = job.partsTotal, segmentsSent = job.partsOk, errorCode = "partial_send", errorMessage = u.errorMessage)
            }
        }
    }

    suspend fun onPartDelivered(jobId: String, attemptId: String, ok: Boolean) {
        val job = jobs.recordDelivery(jobId, attemptId, ok) ?: return
        if (job.state != JobState.SENT) return
        val now = System.currentTimeMillis()
        if (job.deliveryFailed) {
            val u = job.copy(state = JobState.UNDELIVERED, errorCode = "delivery_failed", errorMessage = "The carrier reported non-delivery", finishedAt = now)
            jobs.update(u)
            emit("sms.failed", u, errorCode = "delivery_failed", errorMessage = u.errorMessage, stage = "delivery")
        } else if (job.partsDelivered >= job.partsTotal) {
            val d = job.copy(state = JobState.DELIVERED, finishedAt = now)
            jobs.update(d)
            emit("sms.delivered", d, segmentsTotal = job.partsTotal, segmentsSent = job.partsOk)
        }
    }

    /** Jobs stuck in `submitting` (no radio result ever arrived, e.g. process was killed) become `unknown`. Never re-sent. */
    private suspend fun recoverStale() {
        val limit = System.currentTimeMillis() - STALE_SUBMIT_MS
        jobs.active().filter { it.state == JobState.SUBMITTING && (it.submittedAt ?: 0) < limit }.forEach { j ->
            if (j.partsOk + j.partsFailed >= j.partsTotal && j.partsTotal > 0) {
                finalizeAttempt(j)
            } else {
                val u = j.copy(state = JobState.UNKNOWN, errorCode = "no_result", errorMessage = "No radio result was received", text = "", finishedAt = System.currentTimeMillis())
                jobs.update(u)
                emit("sms.unknown", u, segmentsTotal = j.partsTotal, segmentsSent = j.partsOk, errorCode = "no_result", errorMessage = u.errorMessage)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // settings updates from the UI
    // ------------------------------------------------------------------------------------------

    /** Applies [change] to the server settings with optimistic concurrency; returns an error text or null. */
    suspend fun updateSettings(change: (Settings) -> Settings): String? {
        repeat(2) {
            val cur = prefs.settings.first()
            try {
                val saved = api.putSettings(cur.revision, change(cur.settings))
                prefs.saveSettings(saved)
                sessionRef.get()?.let { s -> send(s, buildJsonObject { put("type", "device.settings.ack"); put("revision", saved.revision) }) }
                return null
            } catch (e: ApiException) {
                if (e.code == "revision_conflict") {
                    runCatching { api.me() }.getOrNull()?.let { prefs.saveSettings(it.settings) }
                } else {
                    return e.message
                }
            } catch (e: NetworkException) {
                return "No connection to the server"
            }
        }
        return "Settings changed elsewhere, please try again"
    }

    /** Re-reads the whole device summary (settings, callback, usage) from the server. */
    suspend fun refresh(): String? = try {
        val sum = api.me()
        prefs.saveSummary(sum)
        prefs.saveSettings(sum.settings)
        null
    } catch (e: ApiException) {
        e.message
    } catch (e: NetworkException) {
        "No connection to the server"
    }

    /** Forget this phone's credentials (used by "reset app"). Pending local jobs are discarded as well. */
    suspend fun resetLocal() {
        stop()
        secure.deviceSecret = null
        secure.clearInstallRequest()
        jobs.clear()
        outbox.clear()
        prefs.resetAll()
    }

    companion object {
        private const val TAG = "Gateway"
        private const val MIN_PACING_MS = 2100
        private const val STALE_SUBMIT_MS = 120_000L
        /** Unacknowledged Reverse-SMS events are kept this long while the phone is offline. */
        private const val INBOUND_KEEP_MS = 7 * 24 * 3600 * 1000L
        /** error_codes (see SmsError) that justify an automatic retry. */
        private val RETRYABLE_CODES = setOf(
            "generic_failure", "radio_off", "no_service", "radio_not_available", "network_reject", "invalid_state", "no_memory",
            "system_error", "modem_error", "network_error", "internal_error", "no_resources", "blocked_during_emergency",
            "send_retry_failed", "remote_exception", "rate_limited", "network_not_ready", "blocked_due_to_call", "access_barred", "ril_error",
        )
    }
}
