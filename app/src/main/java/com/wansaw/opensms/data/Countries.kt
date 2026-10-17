package com.wansaw.opensms.data

import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

data class Country(val iso: String, val name: String, val dial: Int) {
    val flag: String
        get() = iso.map { String(Character.toChars(0x1F1E6 + (it.uppercaseChar() - 'A'))) }.joinToString("")
    val label: String get() = "$flag  $name  +$dial"
}

object Countries {
    private val util: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    val all: List<Country> by lazy {
        util.supportedRegions
            .filter { it.length == 2 && it.all(Char::isLetter) }
            .map { Country(it, Locale("", it).getDisplayCountry(Locale.ENGLISH).ifBlank { it }, util.getCountryCodeForRegion(it)) }
            .filter { it.dial > 0 }
            .sortedBy { it.name }
    }

    fun byIso(iso: String?): Country? = iso?.uppercase()?.let { c -> all.firstOrNull { it.iso == c } }

    fun search(q: String): List<Country> {
        val t = q.trim().removePrefix("+").lowercase()
        if (t.isEmpty()) return all
        return all.filter { it.name.lowercase().contains(t) || it.iso.lowercase() == t || it.dial.toString().startsWith(t) }
    }

    fun callingCodes(isos: Collection<String>): Set<Int> = isos.mapNotNull { byIso(it)?.dial }.toSet()
}
