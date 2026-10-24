package com.wansaw.opensms.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.wansaw.opensms.data.SimReport

/** Logical SIMs: sim_1 = physical slot index 0, sim_2 = slot index 1. */
class SimInfo(private val context: Context) {
    fun hasPhonePermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun info(slot: Int) = try {
        if (!hasPhonePermission()) null
        else context.getSystemService(SubscriptionManager::class.java)?.getActiveSubscriptionInfoForSimSlotIndex(slot - 1)
    } catch (_: SecurityException) {
        null
    }

    fun subscriptionId(slot: Int): Int? = info(slot)?.subscriptionId

    fun report(): List<SimReport> = listOf(1, 2).map { slot ->
        val i = info(slot)
        if (i == null) SimReport(slot, false)
        else SimReport(slot, true, i.carrierName?.toString(), i.displayName?.toString(), i.countryIso?.uppercase())
    }

    /** Maps the subscription of an incoming SMS to sim_1 / sim_2. [slotIndexHint] is the 0-based slot from the broadcast, or -1. */
    fun slotForSubscription(subId: Int, slotIndexHint: Int = -1): Int? {
        if (subId >= 0) listOf(1, 2).firstOrNull { subscriptionId(it) == subId }?.let { return it }
        return (slotIndexHint + 1).takeIf { it in 1..2 }
    }

    fun isPresent(slot: Int) = info(slot) != null

    /** ISO country of the first inserted SIM, used to suggest the home country. */
    fun homeCountryGuess(): String? {
        report().firstOrNull { it.present && !it.countryIso.isNullOrBlank() }?.let { return it.countryIso }
        return try {
            context.getSystemService(TelephonyManager::class.java)?.simCountryIso?.takeIf { it.length == 2 }?.uppercase()
        } catch (_: SecurityException) {
            null
        }
    }
}
