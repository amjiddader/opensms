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

object SmsError {
    /** Platform result code -> (error_code, retryable on another SIM). */
    fun fromResult(code: Int): Pair<String, Boolean> = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic_failure" to true
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio_off" to true
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no_service" to true
        SmsManager.RESULT_ERROR_NULL_PDU -> "null_pdu" to false
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "limit_exceeded" to false
        SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "fdn_check_failure" to false
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "short_code_not_allowed" to false
        SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "short_code_never_allowed" to false
        else -> "send_error_$code" to false
    }
}
