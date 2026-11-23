package com.example.data.upi

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.data.database.CashFlowDatabase
import com.example.data.models.FinanceScope
import com.example.data.models.TransactionEntity
import com.example.data.models.TransactionType
import com.example.data.network.SupabaseAuthService
import com.example.data.network.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Handles 1-tap notification actions for automatically detected UPI expense logging.
 * Allows users to record expenses directly from the Android notification shade.
 */
class NotificationExpenseActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_LOG_EXPENSE = "com.example.zenith.ACTION_LOG_EXPENSE"
        const val ACTION_DISMISS_EXPENSE = "com.example.zenith.ACTION_DISMISS_EXPENSE"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_AMOUNT = "extra_amount"
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_PAYMENT_METHOD = "extra_payment_method"
        const val EXTRA_UPI_ID = "extra_upi_id"
        const val EXTRA_UTR = "extra_utr"
        const val EXTRA_DATE_MILLIS = "extra_date_millis"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    private val tag = "NotifExpenseReceiver"

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 1001)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notifId)

        if (action == ACTION_DISMISS_EXPENSE) {
            Log.d(tag, "Expense notification dismissed by user.")
            return
        }

        if (action == ACTION_LOG_EXPENSE) {
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "Expense"
            val amount = intent.getDoubleExtra(EXTRA_AMOUNT, 0.0)
            val category = intent.getStringExtra(EXTRA_CATEGORY) ?: "Other"
            val paymentMethod = intent.getStringExtra(EXTRA_PAYMENT_METHOD) ?: "UPI"
            val upiId = intent.getStringExtra(EXTRA_UPI_ID)
            val utr = intent.getStringExtra(EXTRA_UTR)
            val dateMillis = intent.getLongExtra(EXTRA_DATE_MILLIS, System.currentTimeMillis())

            if (amount <= 0.0) return

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = CashFlowDatabase.getDatabase(context)
                    val transactionDao = db.transactionDao()
                    val generatedServerId = UUID.randomUUID().toString()

                    val prefs = context.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)
                    val currentUserId = prefs.getString("user_supabase_id", null) ?: "local_user_1"

                    val entity = TransactionEntity(
                        title = title,
                        amount = amount,
                        type = TransactionType.EXPENSE,
                        category = category,
                        categoryIconName = "Receipt",
                        note = "Logged via notification tracker",
                        paymentMethod = paymentMethod,
                        dateMillis = dateMillis,
                        upiId = upiId,
                        upiTransactionId = utr,
                        financeScope = FinanceScope.PERSONAL,
                        createdByUserId = currentUserId,
                        serverId = generatedServerId,
                        syncStatus = "PENDING_CREATE",
                        updatedAt = System.currentTimeMillis(),
                        isDeleted = false
                    )

                    transactionDao.insertTransaction(entity)
                    Log.i(tag, "Successfully recorded ₹$amount at $title via notification action (serverId=$generatedServerId)")

                    // Background sync trigger
                    try {
                        val authService = SupabaseAuthService()
                        val syncEngine = SyncEngine(
                            transactionDao = transactionDao,
                            familyDao = db.familyDao(),
                            authService = authService
                        )
                        syncEngine.syncAll(currentUserId)
                    } catch (e: Exception) {
                        Log.w(tag, "Post-notification sync trigger: ${e.message}")
                    }

                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "✅ Recorded ₹$amount at $title", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error saving expense from notification: ${e.message}", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
