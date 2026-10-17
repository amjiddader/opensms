package com.wansaw.opensms.sms

import com.wansaw.opensms.data.Destination
import com.wansaw.opensms.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientTest {
    private val india = Settings(homeCountry = "IN", destination = Destination("single", listOf("IN")))

    private fun ok(raw: String, s: Settings = india) = (Recipient.validate(raw, s) as RecipientResult.Ok).e164
    private fun code(raw: String, s: Settings = india) = (Recipient.validate(raw, s) as RecipientResult.Rejected).code

    @Test fun bareTenDigitsUseHomeCountry() = assertEquals("+919876543210", ok("9876543210"))
    @Test fun withCountryCodeNoPlus() = assertEquals("+919876543210", ok("919876543210"))
    @Test fun withPlusAndSeparators() = assertEquals("+919876543210", ok("+91 98765-43210"))
    @Test fun letters() = assertEquals("invalid_recipient", code("98765abc10"))
    @Test fun tooShort() = assertEquals("invalid_recipient", code("12345"))
    @Test fun otherCountryRefused() = assertEquals("destination_not_supported", code("+14155552671"))
    @Test fun allModeAcceptsOthers() = assertTrue(Recipient.validate("+14155552671", india.copy(destination = Destination("all"))) is RecipientResult.Ok)
    @Test fun multipleMode() = assertEquals("+14155552671", ok("+14155552671", india.copy(destination = Destination("multiple", listOf("IN", "US")))))
    @Test fun bareNumberNeedsHomeCountry() = assertEquals("device_not_configured", code("9876543210", Settings()))
}
