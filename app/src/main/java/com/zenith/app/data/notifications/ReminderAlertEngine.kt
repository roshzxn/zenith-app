package com.zenith.app.data.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.zenith.app.R
import com.zenith.app.data.database.CashFlowDatabase
import com.zenith.app.data.models.AutoPayReminderEntity
import com.zenith.app.data.models.BudgetEntity
import com.zenith.app.data.models.FinanceScope
import com.zenith.app.data.models.TransactionEntity
import com.zenith.app.data.models.TransactionType
import com.zenith.app.data.upi.UpiNotificationListenerService
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.Locale

data class BillAlertCandidate(
    val reminder: AutoPayReminderEntity,
    val daysUntilDue: Int
)

data class BudgetAlertCandidate(
    val budget: BudgetEntity,
    val spent: Double,
    val percentUsed: Int
)

/**
 * Evaluates due Auto-Pay reminders and Budget thresholds on-device, posting
 * non-spammy Android notifications on a daily cadence or on bill/budget changes.
 */
object ReminderAlertEngine {
    const val CHANNEL_ID = "zenith_bill_reminders"
    private const val PREFS_NAME = "zenith_alert_cooldowns"
    private const val COOLDOWN_MS = 20L * 60 * 60 * 1000 // ~20 hours per item
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val TAG = "ReminderAlertEngine"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bills & Budget Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Due bill reminders and monthly spending threshold alerts"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun evaluateDueBills(
        reminders: List<AutoPayReminderEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<BillAlertCandidate> {
        val startOfToday = startOfDayMillis(nowMillis)
        return reminders.mapNotNull { reminder ->
            if (reminder.isDeleted || reminder.isPaused) return@mapNotNull null
            val startOfDueDay = startOfDayMillis(reminder.nextDueDateMillis)
            val daysDiff = ((startOfDueDay - startOfToday) / DAY_MS).toInt()
            val windowDays = reminder.reminderDaysBefore.coerceIn(0, 14)
            if (daysDiff in -3..windowDays) {
                BillAlertCandidate(reminder = reminder, daysUntilDue = daysDiff)
            } else {
                null
            }
        }.sortedBy { it.daysUntilDue }
    }

    fun evaluateBudgetThresholds(
        budgets: List<BudgetEntity>,
        expenses: List<TransactionEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<BudgetAlertCandidate> {
        val expenseTxs = expenses.filter { !it.isDeleted && it.type == TransactionType.EXPENSE }
        return budgets.mapNotNull { budget ->
            if (budget.isDeleted || budget.monthlyLimit <= 0.0) return@mapNotNull null
            val spent = calculateSpentInPeriod(expenseTxs, budget, nowMillis)
            val pct = ((spent / budget.monthlyLimit) * 100.0).toInt()
            if (pct >= 85) {
                BudgetAlertCandidate(budget = budget, spent = spent, percentUsed = pct)
            } else {
                null
            }
        }.sortedByDescending { it.percentUsed }
    }

    fun calculateSpentInPeriod(
        expenses: List<TransactionEntity>,
        budget: BudgetEntity,
        nowMillis: Long = System.currentTimeMillis()
    ): Double {
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        return expenses
            .filter { it.category.equals(budget.categoryName, ignoreCase = true) }
            .filter { tx ->
                val txCal = Calendar.getInstance().apply { timeInMillis = tx.dateMillis }
                when (budget.periodType.uppercase()) {
                    "MONTHLY" -> txCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                        txCal.get(Calendar.MONTH) == nowCal.get(Calendar.MONTH)
                    "YEARLY" -> txCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
                    "WEEKLY" -> txCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                        txCal.get(Calendar.WEEK_OF_YEAR) == nowCal.get(Calendar.WEEK_OF_YEAR)
                    else -> true
                }
            }
            .sumOf { it.amount }
    }

    suspend fun checkAndNotify(context: Context, respectCooldown: Boolean = true) {
        val appPrefs = context.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)
        if (!appPrefs.getBoolean("is_notifications_enabled", true)) return
        if (!UpiNotificationListenerService.hasPostNotificationPermission(context)) return

        ensureChannel(context)
        val cooldownPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val db = CashFlowDatabase.getDatabase(context)

        try {
            val reminders = db.autoPayReminderDao().getRemindersByScope(FinanceScope.PERSONAL).first()
            val budgets = db.budgetDao().getBudgetsByScope(FinanceScope.PERSONAL).first()
            val txs = db.transactionDao().getTransactionsByScope(FinanceScope.PERSONAL).first()

            val dueBills = evaluateDueBills(reminders, now)
            for (candidate in dueBills) {
                val cooldownKey = "bill_${candidate.reminder.id}"
                val lastNotified = cooldownPrefs.getLong(cooldownKey, 0L)
                if (respectCooldown && (now - lastNotified) < COOLDOWN_MS) continue

                postBillReminderNotification(context, candidate)
                cooldownPrefs.edit().putLong(cooldownKey, now).apply()
            }

            val alerts = evaluateBudgetThresholds(budgets, txs, now)
            for (alert in alerts) {
                val tier = if (alert.percentUsed >= 100) "over" else "near"
                val cooldownKey = "budget_${alert.budget.id}_$tier"
                val lastNotified = cooldownPrefs.getLong(cooldownKey, 0L)
                if (respectCooldown && (now - lastNotified) < COOLDOWN_MS) continue

                postBudgetAlertNotification(context, alert)
                cooldownPrefs.edit().putLong(cooldownKey, now).apply()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission denied during reminder check: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed evaluating bill/budget alerts: ${e.message}")
        }
    }

    fun scheduleDailyCheck(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ReminderAlarmReceiver.ACTION_DAILY_REMINDER_CHECK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                4041,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Start next check in ~30 minutes, repeating roughly every 12 hours (inexact/battery-friendly)
            val firstTrigger = System.currentTimeMillis() + (30L * 60 * 1000)
            alarmManager.setInexactRepeating(
                AlarmManager.RTC,
                firstTrigger,
                AlarmManager.INTERVAL_HALF_DAY,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unable to schedule reminder alarm: ${e.message}")
        }
    }

    private fun postBillReminderNotification(context: Context, candidate: BillAlertCandidate) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val reminder = candidate.reminder
        val notifId = (20_000 + reminder.id.toInt()) and 0x7FFFFFFF

        val dueLabel = when {
            candidate.daysUntilDue < 0 -> "Overdue by ${-candidate.daysUntilDue} day(s)"
            candidate.daysUntilDue == 0 -> "Due today"
            candidate.daysUntilDue == 1 -> "Due tomorrow"
            else -> "Due in ${candidate.daysUntilDue} days"
        }
        val formattedAmt = String.format(Locale.US, "%,.0f", reminder.amount)

        val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("extra_open_tab", 2)
        }
        val openPending = if (openAppIntent != null) {
            PendingIntent.getActivity(
                context,
                notifId,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val markPaidIntent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_MARK_BILL_PAID
            putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_ID, reminder.id)
            putExtra(ReminderAlarmReceiver.EXTRA_NOTIFICATION_ID, notifId)
        }
        val markPaidPending = PendingIntent.getBroadcast(
            context,
            notifId + 1,
            markPaidIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeText = if (reminder.isAutoDebit) "Auto-debit via ${reminder.paymentMethod}" else "Manual pay via ${reminder.paymentMethod}"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_zenith)
            .setContentTitle("📅 ${reminder.title} • ₹$formattedAmt ($dueLabel)")
            .setContentText(modeText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$dueLabel • $modeText"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .addAction(android.R.drawable.checkbox_on_background, "Mark Paid", markPaidPending)

        if (openPending != null) builder.setContentIntent(openPending)
        nm.notify(notifId, builder.build())
    }

    private fun postBudgetAlertNotification(context: Context, alert: BudgetAlertCandidate) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = (30_000 + alert.budget.id.toInt()) and 0x7FFFFFFF
        val limitFormatted = String.format(Locale.US, "%,.0f", alert.budget.monthlyLimit)
        val spentFormatted = String.format(Locale.US, "%,.0f", alert.spent)

        val title = if (alert.percentUsed >= 100) {
            "⚠️ ${alert.budget.categoryName} Budget Exceeded (${alert.percentUsed}%)"
        } else {
            "📊 ${alert.budget.categoryName} Budget at ${alert.percentUsed}%"
        }
        val body = "Spent ₹$spentFormatted of ₹$limitFormatted ${alert.budget.periodType.lowercase()} limit."

        val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("extra_open_tab", 2)
        }
        val openPending = if (openAppIntent != null) {
            PendingIntent.getActivity(
                context,
                notifId,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_zenith)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        if (openPending != null) builder.setContentIntent(openPending)
        nm.notify(notifId, builder.build())
    }

    private fun startOfDayMillis(millis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
