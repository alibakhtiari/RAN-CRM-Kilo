package com.sharedcrm

import com.sharedcrm.core.PhoneNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNormalizerTest {

    @Test
    fun testNormalize_IranDomestic() {
        // Domestic IR number without country code
        val raw = "0912 345 6789"
        val normalized = PhoneNormalizer.normalizeWithFallback(raw, region = "IR")
        assertEquals("+989123456789", normalized)
    }

    @Test
    fun testNormalize_E164Already() {
        val raw = "+989123456789"
        val normalized = PhoneNormalizer.normalizeWithFallback(raw, region = "IR")
        assertEquals("+989123456789", normalized)
    }

    @Test
    fun testNormalize_Invalid() {
        val raw = "0000"
        val normalized = PhoneNormalizer.normalizeWithFallback(raw, region = "IR")
        assertNull(normalized)
    }

    @Test
    fun testIsValid() {
        assertFalse(PhoneNormalizer.isValid("abcd", region = "IR"))
        assertEquals(true, PhoneNormalizer.isValid("+989123456789", region = "IR"))
        assertEquals(true, PhoneNormalizer.isValid("09123456789", region = "IR"))
    }

    @Test
    fun testFormatForDisplay() {
        val raw = "09123456789"
        val national = PhoneNormalizer.formatForDisplay(raw, region = "IR", international = false)
        val intl = PhoneNormalizer.formatForDisplay(raw, region = "IR", international = true)
        assertNotNull(national)
        assertNotNull(intl)
        // Should not return empty strings
        assertFalse(national.isBlank())
        assertFalse(intl.isBlank())
    }
}