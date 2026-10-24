package com.wansaw.opensms.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.wansaw.opensms.OpenSmsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reverse SMS: receives every incoming SMS (either SIM) and hands it to the gateway, which ignores it unless it
 * matches the user's hint. The message is never deleted or hidden (Android does not allow that for a normal app).
 */
class SmsReceivedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull()
        if (parts.isNullOrEmpty()) return
        val from = (parts[0].displayOriginatingAddress ?: parts[0].originatingAddress).orEmpty()
        val body = parts.joinToString("") { it.displayMessageBody.orEmpty() } // multipart messages arrive as several parts
        val receivedAt = parts[0].timestampMillis
        val subId = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1))
        val slotHint = intent.getIntExtra("android.telephony.extra.SLOT_INDEX", intent.getIntExtra("slot", -1))
        val container = (context.applicationContext as OpenSmsApp).container
        val slot = container.sims.slotForSubscription(subId, slotHint)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                container.gateway.onSmsReceived(from, body, slot, receivedAt)
            } finally {
                pending.finish()
            }
        }
    }
}
