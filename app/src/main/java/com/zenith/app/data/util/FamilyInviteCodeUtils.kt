package com.zenith.app.data.util

object FamilyInviteCodeUtils {
    private val INVITE_CODE_REGEX = Regex("""\bFAM-[A-Z0-9]{4,10}\b""", RegexOption.IGNORE_CASE)
    private val SHORT_CODE_REGEX = Regex("""\b[A-Z0-9]{6}\b""", RegexOption.IGNORE_CASE)
    private val UUID_REGEX = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")

    /**
     * Extracts and normalizes a family invite code or family ID from raw input,
     * including full shared messages, deep link URLs, QR string content, or plain user input.
     */
    fun extractInviteCode(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return ""

        // 1. Check for standard FAM-XXXXXX in text (handles WhatsApp/SMS shared text)
        val famMatch = INVITE_CODE_REGEX.find(trimmed)
        if (famMatch != null) {
            return famMatch.value.uppercase()
        }

        // 2. Check for standard UUID
        val uuidMatch = UUID_REGEX.find(trimmed)
        if (uuidMatch != null) {
            return uuidMatch.value.lowercase()
        }

        // 3. Check for 6-character alphanumeric code
        val shortMatch = SHORT_CODE_REGEX.find(trimmed)
        if (shortMatch != null && !trimmed.contains("http", ignoreCase = true)) {
            return "FAM-${shortMatch.value.uppercase()}"
        }

        return trimmed.uppercase()
    }
}
