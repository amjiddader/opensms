package com.wansaw.opensms.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager

class SmsSender(private val context: Context, private val sims: SimInfo) {
    private fun manager(subId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
        else @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(subId)

    /** Splits the text into SMS parts the way the radio will; null when the SIM slot is unusable. */
    fun divide(text: String, slot: Int): List<String>? {
        val subId = sims.subscriptionId(slot) ?: return null
        return manager(subId).divideMessage(text)
    }

    /**
     * Hands every part to the platform. Each part gets its own explicit, mutable PendingIntent with a unique
     * request code and URI so results can never be mixed up between parts or attempts.
     */
    fun send(jobId: String, attemptId: String, to: String, parts: List<String>, slot: Int, deliveryReports: Boolean) {
        val subId = sims.subscriptionId(slot) ?: throw IllegalStateException("sim_not_available")
        val sentIntents = ArrayList<PendingIntent>()
        val deliveryIntents = ArrayList<PendingIntent>()
        parts.indices.forEach { i ->
            sentIntents += pending(SmsResultReceiver.ACTION_SENT, jobId, attemptId, i, parts.size)
            if (deliveryReports) deliveryIntents += pending(SmsResultReceiver.ACTION_DELIVERED, jobId, attemptId, i, parts.size)
        }
        manager(subId).sendMultipartTextMessage(to, null, ArrayList(parts), sentIntents, if (deliveryReports) deliveryIntents else null)
    }

    private fun pending(action: String, jobId: String, attemptId: String, index: Int, total: Int): PendingIntent {
        val intent = Intent(context, SmsResultReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("opensms://$action/$attemptId/$index"))
            .putExtra(SmsResultReceiver.EXTRA_JOB, jobId)
            .putExtra(SmsResultReceiver.EXTRA_ATTEMPT, attemptId)
            .putExtra(SmsResultReceiver.EXTRA_INDEX, index)
            .putExtra(SmsResultReceiver.EXTRA_TOTAL, total)
        val requestCode = (action.hashCode() * 31 + attemptId.hashCode()) * 31 + index
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }
}

/** What the platform reported for one SMS part. */
class SmsFailure(val code: String, val message: String, val retryable: Boolean)

object SmsError {
    private const val NETWORK_HELP = "The carrier or network refused the SMS. Common causes: the SIM's SMS balance/pack is used up, no signal, or SMS is blocked on the plan."

    /** Platform result code (android.telephony.SmsManager.RESULT_*) -> readable failure. Retryable = worth trying again. */
    fun fromResult(code: Int): SmsFailure = when (code) {
        1 -> SmsFailure("generic_failure", "The phone could not send the SMS (generic failure).", true)
        2 -> SmsFailure("radio_off", "Radio is off (airplane mode?).", true)
        3 -> SmsFailure("null_pdu", "The SMS could not be encoded.", false)
        4 -> SmsFailure("no_service", "No mobile service on this SIM.", true)
        5 -> SmsFailure("limit_exceeded", "Android's SMS rate limit was hit; sending too fast.", false)
        6 -> SmsFailure("fdn_check_failure", "Blocked by the SIM's fixed dialing list.", false)
        7, 8 -> SmsFailure("short_code_not_allowed", "Sending to this short code is not allowed.", false)
        9, 100 -> SmsFailure("radio_not_available", "The radio is not available or is restarting.", true)
        10, 102 -> SmsFailure("network_reject", "The network rejected the SMS.", true)
        11, 104 -> SmsFailure("invalid_arguments", "Invalid SMS arguments.", false)
        12, 103 -> SmsFailure("invalid_state", "The modem was in the wrong state.", true)
        13, 105 -> SmsFailure("no_memory", "The modem is out of memory.", true)
        14, 107 -> SmsFailure("invalid_sms_format", "Invalid SMS format.", false)
        15, 108 -> SmsFailure("system_error", "System error while sending.", true)
        16, 111 -> SmsFailure("modem_error", "Modem error while sending.", true)
        17, 112 -> SmsFailure("network_error", "Network error ($code). $NETWORK_HELP", true)
        18, 109 -> SmsFailure("encoding_error", "The message could not be encoded.", false)
        19, 110 -> SmsFailure("invalid_smsc_address", "The SIM's SMS centre number is invalid or missing.", false)
        20, 117 -> SmsFailure("operation_not_allowed", "Sending SMS is not allowed on this SIM or plan.", false)
        21, 113 -> SmsFailure("internal_error", "Internal telephony error.", true)
        22, 118 -> SmsFailure("no_resources", "The modem has no resources to send right now.", true)
        23, 119 -> SmsFailure("cancelled", "The send was cancelled.", false)
        24, 114 -> SmsFailure("request_not_supported", "This SIM or network does not support this SMS.", false)
        29 -> SmsFailure("blocked_during_emergency", "SMS is blocked during an emergency call.", true)
        30, 101 -> SmsFailure("send_retry_failed", "The radio tried again and still failed.", true)
        31 -> SmsFailure("remote_exception", "The telephony service did not answer.", true)
        32 -> SmsFailure("no_default_sms_app", "No default SMS app is set on this phone.", false)
        33 -> SmsFailure("user_not_allowed", "This Android user is not allowed to send SMS.", false)
        106 -> SmsFailure("rate_limited", "The modem rate-limited the SMS.", true)
        115, 116 -> SmsFailure("network_not_ready", "The network is not ready yet.", true)
        120 -> SmsFailure("sim_absent", "The SIM is absent.", false)
        121, 123 -> SmsFailure("blocked_due_to_call", "SMS is blocked while a call is active.", true)
        122 -> SmsFailure("access_barred", "Network access is barred for this SIM.", true)
        124, 125 -> SmsFailure("ril_error", "Radio layer error ($code).", true)
        else -> SmsFailure("send_error_$code", "The platform reported SMS error $code.", false)
    }
}
