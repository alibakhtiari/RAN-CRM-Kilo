package com.sharedcrm.core

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat

/**
 * Phone normalization utility.
 *
 * - Normalizes to E.164 (e.g., +989123456789)
 * - Validates numbers using libphonenumber
 * - Provides display formatting per region
 *
 * Fallback region: SupabaseConfig.defaultRegion ("IR") when no SIM/locale is available.
 */
object PhoneNormalizer {

    /**
     * Normalize a phone number to E.164 format using a provided region (ISO 3166-1 alpha-2).
     * Returns E.164 string or null if invalid/unparseable.
     */
    fun normalize(phoneRaw: String, region: String? = null): String? {
        val cleaned = sanitize(phoneRaw)
        val util = PhoneNumberUtil.getInstance()
        return try {
            val parsed = util.parse(cleaned, region ?: SupabaseConfig.defaultRegion)
            if (util.isValidNumber(parsed)) {
                util.format(parsed, PhoneNumberFormat.E164)
            } else {
                null
            }
        } catch (e: NumberParseException) {
            null
        }
    }

    /**
     * Normalize with fallback to SupabaseConfig.defaultRegion (IR).
     * If region is null or parsing fails, tries default region as fallback.
     */
    fun normalizeWithFallback(phoneRaw: String, region: String? = null): String? {
        // First attempt with provided region
        normalize(phoneRaw, region)?.let { return it }
        // Fallback to default region
        return normalize(phoneRaw, SupabaseConfig.defaultRegion)
    }

    /**
     * Validate phone number for a region.
     */
    fun isValid(phoneRaw: String, region: String? = null): Boolean {
        val cleaned = sanitize(phoneRaw)
        val util = PhoneNumberUtil.getInstance()
        return try {
            val parsed = util.parse(cleaned, region ?: SupabaseConfig.defaultRegion)
            util.isValidNumber(parsed)
        } catch (_: NumberParseException) {
            false
        }
    }

    /**
     * Format number for display using NATIONAL or INTERNATIONAL based on validity.
     */
    fun formatForDisplay(phoneRaw: String, region: String? = null, international: Boolean = false): String {
        val cleaned = sanitize(phoneRaw)
        val util = PhoneNumberUtil.getInstance()
        return try {
            val parsed = util.parse(cleaned, region ?: SupabaseConfig.defaultRegion)
            if (!util.isValidNumber(parsed)) return phoneRaw.trim()
            val format = if (international) PhoneNumberFormat.INTERNATIONAL else PhoneNumberFormat.NATIONAL
            util.format(parsed, format)
        } catch (_: NumberParseException) {
            phoneRaw.trim()
        }
    }

    /**
     * Basic sanitization: trim, collapse whitespace, remove non-dialable characters except leading '+'.
     */
    private fun sanitize(input: String): String {
        val trimmed = input.trim()
        // Keep leading + if present, remove spaces, dashes, parentheses and other non-digits
        val builder = StringBuilder()
        trimmed.forEachIndexed { index, c ->
            if (c == '+' && index == 0) {
                builder.append(c)
            } else if (c.isDigit()) {
                builder.append(c)
            }
            // ignore other characters
        }
        return builder.toString()
    }
}