package com.wansaw.opensms.sms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevSmsTest {
    @Test fun prefixMatchesAtStartIgnoringCaseAndLeadingSpace() {
        assertTrue(RevSms.matches("ABC# 482913", "ABC#", "prefix"))
        assertTrue(RevSms.matches("abc#482913", "ABC#", "prefix"))
        assertTrue(RevSms.matches("  \n ABC# hi", "ABC#", "prefix"))
        assertTrue(RevSms.matches("ABC#", "ABC#", "prefix"))
    }

    @Test fun prefixRejectsOtherPositions() {
        assertFalse(RevSms.matches("hello ABC# 1", "ABC#", "prefix"))
        assertFalse(RevSms.matches("AB C#", "ABC#", "prefix"))
        assertFalse(RevSms.matches("", "ABC#", "prefix"))
    }

    @Test fun containsMatchesAnywhere() {
        assertTrue(RevSms.matches("my code is abc# 1234", "ABC#", "contains"))
        assertFalse(RevSms.matches("my code is 1234", "ABC#", "contains"))
    }

    @Test fun unknownModeFallsBackToPrefix() {
        assertFalse(RevSms.matches("x ABC#", "ABC#", "whatever"))
        assertTrue(RevSms.matches("ABC# x", "ABC#", "whatever"))
    }

    @Test fun disabledOrBlankHintNeverMatches() {
        assertFalse(RevSms.matches("ABC# 1", "ABC#", "prefix", enabled = false))
        assertFalse(RevSms.matches("anything", "", "prefix"))
        assertFalse(RevSms.matches("anything", "   ", "contains"))
    }

    @Test fun hintIsTrimmed() {
        assertTrue(RevSms.matches("ABC# 1", "  ABC#  ", "prefix"))
    }
}
