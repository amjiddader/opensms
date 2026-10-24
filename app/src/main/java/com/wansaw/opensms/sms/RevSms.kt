package com.wansaw.opensms.sms

/** Reverse SMS (ReverseOTP) hint matching. Pure Kotlin so it can be unit-tested on the JVM. */
object RevSms {
    /** Longest body the protocol accepts in `sms.received`. */
    const val MAX_BODY = 10_000

    /**
     * True when Reverse SMS is [enabled] and [body] matches [hint]: case-insensitive, leading whitespace ignored.
     * [mode] "contains" matches anywhere; anything else means "prefix" (the default).
     */
    fun matches(body: String, hint: String, mode: String, enabled: Boolean = true): Boolean {
        if (!enabled) return false
        val h = hint.trim()
        if (h.isEmpty()) return false
        return if (mode == "contains") body.contains(h, ignoreCase = true) else body.trimStart().startsWith(h, ignoreCase = true)
    }
}
