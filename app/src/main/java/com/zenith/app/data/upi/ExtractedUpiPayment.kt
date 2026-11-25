package com.zenith.app.data.upi

import com.zenith.app.data.models.FinanceScope

/**
 * Represents a payment transaction extracted automatically from
 * a UPI payment notification, UPI intent response, or payment screenshot.
 */
data class ExtractedUpiPayment(
    val amount: Double,
    val payeeName: String,
    val upiId: String? = null,
    val upiTransactionId: String? = null, // 12-digit UTR reference number
    val sourceApp: String, // e.g. "Google Pay", "PhonePe", "Paytm", "HDFC Bank"
    val timestamp: Long = System.currentTimeMillis(),
    val detectedCategory: String = "Other",
    val detectedScope: FinanceScope = FinanceScope.PERSONAL,
    val rawText: String = "",
    val isDebit: Boolean = true
) {
    /**
     * Unique fingerprint for deduplicating identical notifications
     * received simultaneously from both the UPI app and the bank.
     */
    val deduplicationKey: String
        get() {
            val normalizedPayee = payeeName.lowercase().replace(Regex("[^a-z0-9]"), "").take(10)
            val timeBucket = timestamp / 300000 // 5-minute bucket
            return if (!upiTransactionId.isNullOrBlank()) {
                "utr_${upiTransactionId}"
            } else {
                "tx_${amount}_${normalizedPayee}_${timeBucket}"
            }
        }

    val contentFingerprint: String
        get() {
            val normalizedPayee = payeeName.lowercase().replace(Regex("[^a-z0-9]"), "").take(10)
            val timeBucket = timestamp / 300000
            return "fingerprint_${amount}_${normalizedPayee}_${timeBucket}"
        }
}
