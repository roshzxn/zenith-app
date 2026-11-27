package com.zenith.app.data.util

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

enum class NumberFormatStyle(val label: String, val example: String) {
    INDIAN("Indian (1,00,000)", "1,00,000"),
    INTERNATIONAL("International (100,000)", "100,000");

    companion object {
        fun fromName(raw: String?): NumberFormatStyle {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: INTERNATIONAL
        }
    }
}

/**
 * Formats monetary amounts cleanly in either Indian Lakh/Crore grouping (`1,00,000`)
 * or standard International grouping (`100,000`), and maps Zenith currency symbols to ISO codes.
 */
object AmountFormatter {

    fun formatNumber(
        amount: Double,
        decimalPlaces: Int = 0,
        style: NumberFormatStyle = NumberFormatStyle.INTERNATIONAL
    ): String {
        val safeVal = if (amount.isNaN() || amount.isInfinite()) 0.0 else abs(amount)
        val clampedDecimals = decimalPlaces.coerceIn(0, 2)
        val rawFixed = safeVal.toBigDecimal()
            .setScale(clampedDecimals, RoundingMode.HALF_UP)
            .toPlainString()

        val dotIdx = rawFixed.indexOf('.')
        val intPart = if (dotIdx >= 0) rawFixed.substring(0, dotIdx) else rawFixed
        val fracPart = if (dotIdx >= 0) rawFixed.substring(dotIdx) else ""

        val groupedInt = when (style) {
            NumberFormatStyle.INTERNATIONAL -> groupInternational(intPart)
            NumberFormatStyle.INDIAN -> groupIndian(intPart)
        }
        return if (amount < 0.0) "-$groupedInt$fracPart" else "$groupedInt$fracPart"
    }

    fun formatCurrency(
        amount: Double,
        currencySymbol: String,
        decimalPlaces: Int = 0,
        style: NumberFormatStyle = NumberFormatStyle.INTERNATIONAL,
        prefixPositive: String = ""
    ): String {
        val formatted = formatNumber(abs(amount), decimalPlaces, style)
        val sign = when {
            amount < 0 -> "-"
            prefixPositive.isNotEmpty() -> prefixPositive
            else -> ""
        }
        return "$sign$currencySymbol$formatted"
    }

    fun currencyCodeForSymbol(symbol: String): String {
        return when (symbol.trim()) {
            "₹" -> "INR"
            "€" -> "EUR"
            "£" -> "GBP"
            "¥" -> "JPY"
            else -> "USD"
        }
    }

    private fun groupInternational(intDigits: String): String {
        if (intDigits.length <= 3) return intDigits
        val sb = StringBuilder(intDigits.length + intDigits.length / 3)
        var count = 0
        for (i in intDigits.length - 1 downTo 0) {
            if (count > 0 && count % 3 == 0) {
                sb.append(',')
            }
            sb.append(intDigits[i])
            count++
        }
        return sb.reverse().toString()
    }

    private fun groupIndian(intDigits: String): String {
        if (intDigits.length <= 3) return intDigits
        val lastThree = intDigits.takeLast(3)
        val prefix = intDigits.dropLast(3)
        val sb = StringBuilder(prefix.length + prefix.length / 2 + 4)
        sb.append(lastThree.reversed()).append(',')
        var count = 0
        for (i in prefix.length - 1 downTo 0) {
            if (count > 0 && count % 2 == 0) {
                sb.append(',')
            }
            sb.append(prefix[i])
            count++
        }
        return sb.reverse().toString()
    }
}
