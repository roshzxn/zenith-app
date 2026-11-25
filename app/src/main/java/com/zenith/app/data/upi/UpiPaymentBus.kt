package com.zenith.app.data.upi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Event bus that broadcasts automatically detected UPI payments to
 * CashFlowViewModel, UI dialogs, and the sync engine, while coordinating
 * deduplication across in-app UPI Pay and Notification Shade actions.
 */
object UpiPaymentBus {
    private const val RECENTLY_RECORDED_WINDOW_MS = 300_000L // 5 minutes
    private val recentlyRecordedKeys = ConcurrentHashMap<String, Long>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val _detectedPayments = MutableSharedFlow<ExtractedUpiPayment>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val detectedPayments: SharedFlow<ExtractedUpiPayment> = _detectedPayments.asSharedFlow()

    fun post(payment: ExtractedUpiPayment) {
        _detectedPayments.tryEmit(payment)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearPending() {
        _detectedPayments.resetReplayCache()
    }

    private fun buildAmountKey(amount: Double): String {
        return String.format(Locale.US, "amt_%.2f", amount)
    }

    fun markRecorded(amount: Double, utr: String? = null) {
        val now = System.currentTimeMillis()
        cleanup(now)
        recentlyRecordedKeys[buildAmountKey(amount)] = now
        if (!utr.isNullOrBlank()) {
            recentlyRecordedKeys["utr_${utr.trim().lowercase()}"] = now
        }
        clearPending()
    }

    fun wasRecentlyRecorded(amount: Double, utr: String? = null): Boolean {
        val now = System.currentTimeMillis()
        cleanup(now)
        if (!utr.isNullOrBlank()) {
            val utrTime = recentlyRecordedKeys["utr_${utr.trim().lowercase()}"]
            if (utrTime != null && (now - utrTime) < RECENTLY_RECORDED_WINDOW_MS) {
                return true
            }
        }
        val amtTime = recentlyRecordedKeys[buildAmountKey(amount)]
        return amtTime != null && (now - amtTime) < RECENTLY_RECORDED_WINDOW_MS
    }

    /**
     * Atomically checks and claims a payment recording slot so concurrent clicks or
     * notification listener callbacks cannot insert duplicate ledger rows.
     * Returns true if claimed (not recently recorded); false if already recorded.
     */
    @Synchronized
    fun tryClaimRecording(amount: Double, utr: String? = null): Boolean {
        if (wasRecentlyRecorded(amount, utr)) {
            return false
        }
        markRecorded(amount, utr)
        return true
    }

    private fun cleanup(now: Long) {
        val it = recentlyRecordedKeys.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value > RECENTLY_RECORDED_WINDOW_MS) {
                it.remove()
            }
        }
    }
}
