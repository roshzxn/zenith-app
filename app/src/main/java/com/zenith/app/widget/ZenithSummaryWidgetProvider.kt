package com.zenith.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.zenith.app.MainActivity
import com.zenith.app.R
import com.zenith.app.data.database.CashFlowDatabase
import com.zenith.app.data.models.FinanceScope
import com.zenith.app.data.models.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Home-screen summary widget displaying current month spend, net balance,
 * upcoming bill deadline, and 1-tap quick actions into Zenith.
 */
class ZenithSummaryWidgetProvider : AppWidgetProvider() {

    companion object {
        fun requestUpdate(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, ZenithSummaryWidgetProvider::class.java)
                )
                if (ids.isNotEmpty()) {
                    val intent = Intent(context, ZenithSummaryWidgetProvider::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                    context.sendBroadcast(intent)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)
                val currency = prefs.getString("currency_symbol", "₹") ?: "₹"
                val hideBalance = prefs.getBoolean("is_balance_hidden", false)

                val db = CashFlowDatabase.getDatabase(context)
                val txs = db.transactionDao().getTransactionsByScope(FinanceScope.PERSONAL).first()
                val reminders = db.autoPayReminderDao().getRemindersByScope(FinanceScope.PERSONAL).first()

                val nowCal = Calendar.getInstance()
                val monthSpent = txs.filter { tx ->
                    !tx.isDeleted && tx.type == TransactionType.EXPENSE && run {
                        val c = Calendar.getInstance().apply { timeInMillis = tx.dateMillis }
                        c.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                            c.get(Calendar.MONTH) == nowCal.get(Calendar.MONTH)
                    }
                }.sumOf { it.amount }

                val totalIncome = txs.filter { !it.isDeleted && it.type == TransactionType.INCOME }.sumOf { it.amount }
                val totalExpense = txs.filter { !it.isDeleted && it.type == TransactionType.EXPENSE }.sumOf { it.amount }
                val netBalance = totalIncome - totalExpense

                val nextBill = reminders.filter { !it.isDeleted && !it.isPaused }
                    .minByOrNull { it.nextDueDateMillis }

                val periodText = SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date())
                val spentText = if (hideBalance) "$currency••••" else "$currency${String.format(Locale.US, "%,.0f", monthSpent)}"
                val balanceText = if (hideBalance) "$currency••••" else "$currency${String.format(Locale.US, "%,.0f", netBalance)}"
                val billText = if (nextBill != null) {
                    val dateStr = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(nextBill.nextDueDateMillis))
                    val billAmt = if (hideBalance) "$currency•••" else "$currency${String.format(Locale.US, "%,.0f", nextBill.amount)}"
                    "Next: ${nextBill.title} • $billAmt ($dateStr)"
                } else {
                    context.getString(R.string.widget_no_due_bills)
                }

                for (appWidgetId in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_zenith_summary)
                    views.setTextViewText(R.id.tv_widget_period, periodText)
                    views.setTextViewText(R.id.tv_widget_spent, spentText)
                    views.setTextViewText(R.id.tv_widget_balance, balanceText)
                    views.setTextViewText(R.id.tv_widget_next_bill, billText)

                    views.setOnClickPendingIntent(
                        R.id.widget_root,
                        buildLaunchPendingIntent(context, 100, null)
                    )
                    views.setOnClickPendingIntent(
                        R.id.btn_widget_add,
                        buildLaunchPendingIntent(context, 101, "add")
                    )
                    views.setOnClickPendingIntent(
                        R.id.btn_widget_upi,
                        buildLaunchPendingIntent(context, 102, "upi_pay")
                    )

                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun buildLaunchPendingIntent(
        context: Context,
        requestCode: Int,
        quickAction: String?
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (quickAction != null) {
                putExtra("extra_quick_action", quickAction)
            }
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
