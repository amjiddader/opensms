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
