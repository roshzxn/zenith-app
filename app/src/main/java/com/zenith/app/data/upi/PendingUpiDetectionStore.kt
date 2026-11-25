package com.zenith.app.data.upi

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Persists recently detected-but-not-yet-logged UPI notifications for up to 48 hours
 * so users can still review and log payments even if they swipe away a shade notification.
 */
object PendingUpiDetectionStore {
    private const val PREFS_NAME = "zenith_pending_upi_detections"
    private const val KEY_ITEMS = "pending_items_json"
    private const val MAX_ITEMS = 10
    private const val TTL_MS = 48L * 60 * 60 * 1000 // 48 hours

    private val lock = Any()

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _pendingDetections = MutableStateFlow<List<ExtractedUpiPayment>>(emptyList())
    val pendingDetections: StateFlow<List<ExtractedUpiPayment>> = _pendingDetections.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(lock) {
                if (prefs == null) {
                    val appContext = context.applicationContext ?: context
                    prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
                loadLocked()
            }
        }
    }

    fun enqueue(context: Context? = null, payment: ExtractedUpiPayment) {
        context?.let { init(it) }
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val current = pruneExpired(loadLocked(), now).toMutableList()
            val dupIndex = current.indexOfFirst { existing ->
                existing.deduplicationKey == payment.deduplicationKey ||
                    (!payment.upiTransactionId.isNullOrBlank() &&
                        existing.upiTransactionId.equals(payment.upiTransactionId, ignoreCase = true)) ||
                    (abs(existing.amount - payment.amount) < 0.01 &&
                        abs(existing.timestamp - payment.timestamp) <= 120_000L &&
                        existing.isDebit == payment.isDebit)
            }
            if (dupIndex >= 0) {
                // Keep richer info (e.g. if second notification includes UTR/VPA)
                val existing = current[dupIndex]
                current[dupIndex] = existing.copy(
                    upiTransactionId = payment.upiTransactionId ?: existing.upiTransactionId,
                    upiId = payment.upiId ?: existing.upiId,
                    payeeName = if (existing.payeeName == "Merchant" && payment.payeeName != "Merchant") {
                        payment.payeeName
                    } else {
                        existing.payeeName
                    }
                )
            } else {
                current.add(0, payment)
            }
            val bounded = current.take(MAX_ITEMS)
            persistLocked(bounded)
        }
    }

    fun markResolved(amount: Double, utr: String?, context: Context? = null) {
        context?.let { init(it) }
        synchronized(lock) {
            val current = _pendingDetections.value
            val normalizedUtr = utr?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            val filtered = current.filterNot { item ->
                val utrMatch = normalizedUtr != null &&
                    item.upiTransactionId?.trim()?.lowercase() == normalizedUtr
                val amountMatch = abs(item.amount - amount) < 0.01
                utrMatch || amountMatch
            }
            persistLocked(filtered)
        }
    }

    fun removeItem(payment: ExtractedUpiPayment, context: Context? = null) {
        context?.let { init(it) }
        synchronized(lock) {
            val filtered = _pendingDetections.value.filterNot {
                it.deduplicationKey == payment.deduplicationKey && it.timestamp == payment.timestamp
            }
            persistLocked(filtered)
        }
    }

    fun clearAll(context: Context? = null) {
        context?.let { init(it) }
        synchronized(lock) {
            persistLocked(emptyList())
        }
    }

    private fun pruneExpired(items: List<ExtractedUpiPayment>, now: Long): List<ExtractedUpiPayment> {
        return items.filter { now - it.timestamp <= TTL_MS }
    }

    private fun loadLocked(): List<ExtractedUpiPayment> {
        val raw = prefs?.getString(KEY_ITEMS, null) ?: run {
            _pendingDetections.value = emptyList()
            return emptyList()
        }
        val list = try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        ExtractedUpiPayment(
                            amount = obj.optDouble("amount", 0.0),
                            payeeName = obj.optString("payeeName", "Merchant"),
                            upiId = obj.optString("upiId", "").takeIf { it.isNotBlank() },
                            upiTransactionId = obj.optString("upiTransactionId", "").takeIf { it.isNotBlank() },
                            sourceApp = obj.optString("sourceApp", "UPI"),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            detectedCategory = obj.optString("detectedCategory", "Other"),
                            rawText = obj.optString("rawText", ""),
                            isDebit = obj.optBoolean("isDebit", true)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
        val valid = pruneExpired(list, System.currentTimeMillis())
        _pendingDetections.value = valid
        return valid
    }

    private fun persistLocked(items: List<ExtractedUpiPayment>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("amount", item.amount)
                    put("payeeName", item.payeeName)
                    put("upiId", item.upiId ?: "")
                    put("upiTransactionId", item.upiTransactionId ?: "")
                    put("sourceApp", item.sourceApp)
                    put("timestamp", item.timestamp)
                    put("detectedCategory", item.detectedCategory)
                    put("rawText", item.rawText.take(240))
                    put("isDebit", item.isDebit)
                }
            )
        }
        prefs?.edit()?.putString(KEY_ITEMS, arr.toString())?.apply()
        _pendingDetections.value = items
    }
}
