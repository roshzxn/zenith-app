package com.zenith.app.data.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.zenith.app.data.database.CashFlowDatabase
import com.zenith.app.data.models.FinanceScope
import com.zenith.app.data.models.TransactionEntity
import com.zenith.app.data.models.TransactionType
import com.zenith.app.data.upi.UpiNotificationListenerService
import com.zenith.app.data.util.CategoryIconHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

/**
 * Handles background bill/budget alert alarm ticks, device boot recovery
 * (re-binding the UPI notification listener), and 1-tap "Mark Paid" bill actions.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DAILY_REMINDER_CHECK = "com.zenith.app.ACTION_DAILY_REMINDER_CHECK"
        const val ACTION_MARK_BILL_PAID = "com.zenith.app.ACTION_MARK_BILL_PAID"

        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                UpiNotificationListenerService.ensureListenerBound(context, forceRebind = true)
                ReminderAlertEngine.scheduleDailyCheck(context)
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ReminderAlertEngine.checkAndNotify(context)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_DAILY_REMINDER_CHECK -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ReminderAlertEngine.checkAndNotify(context)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_MARK_BILL_PAID -> {
                val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, 0L)
                val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
                if (notifId != 0) {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(notifId)
                }
                if (reminderId <= 0L) return

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val db = CashFlowDatabase.getDatabase(context)
                        val dao = db.autoPayReminderDao()
                        val reminder = dao.getReminderById(reminderId) ?: return@launch
                        val now = System.currentTimeMillis()
                        val prefs = context.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)
                        val currentUserId = prefs.getString("user_supabase_id", null) ?: "local_user_1"

                        db.transactionDao().insertTransaction(
                            TransactionEntity(
                                title = reminder.title,
                                amount = reminder.amount,
                                type = TransactionType.EXPENSE,
                                category = reminder.category,
                                categoryIconName = CategoryIconHelper.iconNameForCategory(reminder.category),
                                paymentMethod = reminder.paymentMethod,
                                dateMillis = now,
                                note = if (reminder.notes.isNotBlank()) "Auto-Pay: ${reminder.notes}" else "Auto-Pay: ${reminder.title}",
                                financeScope = FinanceScope.PERSONAL,
                                familyId = null,
                                createdByUserId = currentUserId,
                                serverId = UUID.randomUUID().toString(),
                                syncStatus = "PENDING_CREATE",
                                updatedAt = now
                            )
                        )

                        val cal = Calendar.getInstance().apply { timeInMillis = reminder.nextDueDateMillis }
                        when (reminder.frequency.uppercase()) {
                            "WEEKLY" -> cal.add(Calendar.WEEK_OF_YEAR, 1)
                            "QUARTERLY" -> {
                                cal.add(Calendar.MONTH, 3)
                                val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                                cal.set(Calendar.DAY_OF_MONTH, reminder.dueDayOfMonth.coerceIn(1, maxDay))
                            }
                            "YEARLY" -> {
                                cal.add(Calendar.YEAR, 1)
                                val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                                cal.set(Calendar.DAY_OF_MONTH, reminder.dueDayOfMonth.coerceIn(1, maxDay))
                            }
                            "ONE_TIME" -> dao.setPaused(reminder.id, true, now)
                            else -> {
                                cal.add(Calendar.MONTH, 1)
                                val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                                cal.set(Calendar.DAY_OF_MONTH, reminder.dueDayOfMonth.coerceIn(1, maxDay))
                            }
                        }
                        dao.markAsPaid(reminder.id, cal.timeInMillis, now, now)

                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "Marked ${reminder.title} as paid", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.w("ReminderAlarmReceiver", "Failed marking bill paid: ${e.message}")
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
