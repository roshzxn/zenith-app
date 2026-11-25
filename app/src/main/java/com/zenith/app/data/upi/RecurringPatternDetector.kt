package com.zenith.app.data.upi

import com.zenith.app.data.models.AutoPayReminderEntity
import com.zenith.app.data.models.TransactionEntity
import com.zenith.app.data.models.TransactionType
import java.util.Calendar
import kotlin.math.abs

data class RecurringSuggestion(
    val title: String,
    val amount: Double,
    val category: String,
    val paymentMethod: String,
    val frequency: String,
    val occurrenceCount: Int,
    val nextEstimatedDueMillis: Long
)

/**
 * Analyzes transaction history to detect repeating weekly/monthly/quarterly bills
 * or subscriptions not yet registered in Auto-Pay Reminders.
 */
object RecurringPatternDetector {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private val GENERIC_MERCHANTS = setOf("merchant", "upi payment", "expense", "transfer", "other")

    fun detectSuggestions(
        transactions: List<TransactionEntity>,
        existingReminders: List<AutoPayReminderEntity>,
        dismissedKeys: Set<String> = emptySet(),
        nowMillis: Long = System.currentTimeMillis()
    ): List<RecurringSuggestion> {
        val existingTitles = existingReminders
            .filter { !it.isDeleted }
            .map { normalizeKey(it.title) }
            .toSet()

        val expensesByMerchant = transactions
            .filter { !it.isDeleted && it.type == TransactionType.EXPENSE && it.amount > 0.0 }
            .groupBy { normalizeKey(it.title) }

        val results = mutableListOf<RecurringSuggestion>()
        for ((key, txs) in expensesByMerchant) {
            if (key.length < 3 || key in GENERIC_MERCHANTS) continue
            if (key in existingTitles || key in dismissedKeys) continue
            if (txs.size < 2) continue

            val sorted = txs.sortedBy { it.dateMillis }
            val latest = sorted.last()

            // Skip stale patterns whose latest transaction was > 105 days ago
            if (nowMillis - latest.dateMillis > 105L * DAY_MS) continue

            // Deduplicate accidental same-day double entries
            val distinctDates = mutableListOf<TransactionEntity>()
            for (tx in sorted) {
                val prev = distinctDates.lastOrNull()
                if (prev == null || (tx.dateMillis - prev.dateMillis) >= 3L * DAY_MS) {
                    distinctDates.add(tx)
                }
            }
            if (distinctDates.size < 2) continue

            val intervalsDays = distinctDates.zipWithNext { a, b ->
                ((b.dateMillis - a.dateMillis) / DAY_MS).toInt()
            }
            val avgInterval = intervalsDays.average()
            val frequency = when {
                avgInterval in 5.0..9.5 && intervalsDays.all { it in 4..11 } -> "WEEKLY"
                avgInterval in 24.0..36.0 && intervalsDays.all { it in 20..40 } -> "MONTHLY"
                avgInterval in 80.0..102.0 && intervalsDays.all { it in 72..110 } -> "QUARTERLY"
                else -> null
            } ?: continue

            // Amounts should be reasonably consistent (within ±15% of median)
            val amounts = distinctDates.map { it.amount }.sorted()
            val medianAmount = amounts[amounts.size / 2]
            val amountsConsistent = distinctDates.all {
                medianAmount > 0 && abs(it.amount - medianAmount) / medianAmount <= 0.15
            }
            if (!amountsConsistent) continue

            val nextCal = Calendar.getInstance().apply { timeInMillis = distinctDates.last().dateMillis }
            when (frequency) {
                "WEEKLY" -> nextCal.add(Calendar.WEEK_OF_YEAR, 1)
                "QUARTERLY" -> nextCal.add(Calendar.MONTH, 3)
                else -> nextCal.add(Calendar.MONTH, 1)
            }

            results.add(
                RecurringSuggestion(
                    title = distinctDates.last().title.trim(),
                    amount = medianAmount,
                    category = distinctDates.last().category,
                    paymentMethod = distinctDates.last().paymentMethod,
                    frequency = frequency,
                    occurrenceCount = distinctDates.size,
                    nextEstimatedDueMillis = nextCal.timeInMillis
                )
            )
        }
        return results.sortedByDescending { it.occurrenceCount }
    }

    fun normalizeKey(raw: String): String {
        return raw.trim().lowercase().replace(Regex("""\s+"""), " ")
    }
}
