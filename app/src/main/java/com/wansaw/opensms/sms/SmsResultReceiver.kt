package com.wansaw.opensms.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import com.wansaw.opensms.OpenSmsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Receives the per-part "sent" and "delivered" results of the platform and forwards them to the gateway. */
class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(EXTRA_JOB) ?: return
        val attemptId = intent.getStringExtra(EXTRA_ATTEMPT) ?: return
        val gateway = (context.applicationContext as OpenSmsApp).container.gateway
        val result = resultCode
        val pdu = intent.getByteArrayExtra("pdu")
        val format = intent.getStringExtra("format")
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_SENT -> gateway.onPartSent(jobId, attemptId, result)
                    ACTION_DELIVERED -> {
                        // status: 0 = delivered, 32..63 = carrier still trying (ignore), 64+ = permanent failure
                        val status = pdu?.let { runCatching { SmsMessage.createFromPdu(it, format).status }.getOrNull() }
                        when {
                            status != null && status in 32..63 -> Unit
                            status != null -> gateway.onPartDelivered(jobId, attemptId, status < 32)
                            else -> gateway.onPartDelivered(jobId, attemptId, result == Activity.RESULT_OK)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SENT = "com.wansaw.opensms.SMS_SENT"
        const val ACTION_DELIVERED = "com.wansaw.opensms.SMS_DELIVERED"
        const val EXTRA_JOB = "job"
        const val EXTRA_ATTEMPT = "attempt"
        const val EXTRA_INDEX = "index"
        const val EXTRA_TOTAL = "total"
    }
}
