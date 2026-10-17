package com.wansaw.opensms.sms

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.wansaw.opensms.data.Countries
import com.wansaw.opensms.data.Settings

/** Mirrors the backend recipient rules (docs/protocol/v1.md section 4). */
sealed interface RecipientResult {
    /** [dial] is the number exactly as the API caller wrote it (separators removed): this is what gets dialled. */
    data class Ok(val dial: String, val e164: String, val callingCode: Int) : RecipientResult
    data class Rejected(val code: String, val message: String) : RecipientResult
}

/** Maximum characters (Unicode code points) per message. Same limit as the backend. */
const val MAX_MESSAGE_CHARS = 100

object Recipient {
    private val util = PhoneNumberUtil.getInstance()
    private val shape = Regex("^\\+?\\d+$")

    fun validate(raw: String, settings: Settings): RecipientResult {
        val cleaned = raw.trim().replace(Regex("[\\s\\-.()]"), "")
        if (!shape.matches(cleaned)) return RecipientResult.Rejected("invalid_recipient", "Only digits and an optional leading + are allowed")
        val home = settings.homeCountry
        if (!cleaned.startsWith("+") && home.isNullOrBlank()) {
            return RecipientResult.Rejected("device_not_configured", "Home country is not set")
        }
        val number = try {
            util.parse(cleaned, if (cleaned.startsWith("+")) null else home!!.uppercase())
        } catch (_: NumberParseException) {
            return RecipientResult.Rejected("invalid_recipient", "Not a valid phone number")
        }
        if (!util.isPossibleNumber(number)) return RecipientResult.Rejected("invalid_recipient", "Not a possible phone number")
        val e164 = util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
        val digits = e164.removePrefix("+")
        if (digits.length !in 10..15) return RecipientResult.Rejected("invalid_recipient", "Number must have 10-15 digits")
        if (number.countryCode == 91 && number.nationalNumber.toString().length != 10) {
            return RecipientResult.Rejected("invalid_recipient", "Indian numbers need exactly 10 digits")
        }
        val d = settings.destination
        if (d.mode != "all" && number.countryCode !in Countries.callingCodes(d.countries)) {
            return RecipientResult.Rejected("destination_not_supported", "Destination country is not allowed on this device")
        }
        return RecipientResult.Ok(cleaned, e164, number.countryCode)
    }
}
