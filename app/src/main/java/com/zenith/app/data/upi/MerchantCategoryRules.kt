package com.zenith.app.data.upi

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight, privacy-first on-device learner mapping merchant names or VPAs
 * to user-preferred categories across detected UPI payments and manual edits.
 */
object MerchantCategoryRules {
    private const val PREFS_NAME = "zenith_merchant_rules"
    private const val PAYEE_PREFIX = "payee:"
    private const val VPA_PREFIX = "vpa:"

    private val memoryRules = ConcurrentHashMap<String, String>()

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    val appContext = context.applicationContext ?: context
                    val sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs = sp
                    sp.all.forEach { (key, value) ->
                        if (value is String && value.isNotBlank()) {
                            memoryRules[key] = value
                        }
                    }
                }
            }
        }
    }

    fun learn(payee: String?, vpa: String?, category: String) {
        val cleanCategory = category.trim()
        if (cleanCategory.isBlank() || cleanCategory.equals("Other", ignoreCase = true)) return

        val editor = prefs?.edit()
        val payeeKey = normalizePayeeKey(payee)
        if (payeeKey != null) {
            val fullKey = "$PAYEE_PREFIX$payeeKey"
            memoryRules[fullKey] = cleanCategory
            editor?.putString(fullKey, cleanCategory)
        }

        val vpaKey = normalizeVpaKey(vpa)
        if (vpaKey != null) {
            val fullKey = "$VPA_PREFIX$vpaKey"
            memoryRules[fullKey] = cleanCategory
            editor?.putString(fullKey, cleanCategory)
        }
        editor?.apply()
    }

    fun resolve(payee: String?, vpa: String?): String? {
        val vpaKey = normalizeVpaKey(vpa)
        if (vpaKey != null) {
            memoryRules["$VPA_PREFIX$vpaKey"]?.let { return it }
        }
        val payeeKey = normalizePayeeKey(payee)
        if (payeeKey != null) {
            memoryRules["$PAYEE_PREFIX$payeeKey"]?.let { return it }
        }
        return null
    }

    fun clear() {
        memoryRules.clear()
        prefs?.edit()?.clear()?.apply()
    }

    fun ruleCount(): Int = memoryRules.size

    private fun normalizePayeeKey(payee: String?): String? {
        val clean = payee?.trim()?.lowercase()?.takeIf {
            it.length >= 2 && it !in setOf("merchant", "upi payment", "expense", "upi")
        }
        return clean
    }

    private fun normalizeVpaKey(vpa: String?): String? {
        val clean = vpa?.trim()?.lowercase()?.takeIf { it.contains("@") && it.length >= 4 }
        return clean
    }
}
