package com.zenith.app.data.upi

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
import com.zenith.app.data.network.SupabaseAuthService
import com.zenith.app.data.network.SyncEngine
import com.zenith.app.data.util.CategoryIconHelper
import com.zenith.app.widget.ZenithSummaryWidgetProvider
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
        const val ACTION_LOG_EXPENSE = "com.zenith.app.ACTION_LOG_EXPENSE"
        const val ACTION_DISMISS_EXPENSE = "com.zenith.app.ACTION_DISMISS_EXPENSE"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_AMOUNT = "extra_amount"
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_PAYMENT_METHOD = "extra_payment_method"
        const val EXTRA_UPI_ID = "extra_upi_id"
        const val EXTRA_UTR = "extra_utr"
        const val EXTRA_DATE_MILLIS = "extra_date_millis"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_IS_DEBIT = "extra_is_debit"

        internal suspend fun persistNotificationTransaction(
            context: Context,
            db: CashFlowDatabase,
            title: String,
            amount: Double,
            category: String,
            paymentMethod: String,
            upiId: String?,
            utr: String?,
            dateMillis: Long,
            isDebit: Boolean,
            syncWithCloud: Boolean = true
        ): Boolean {
            if (amount <= 0.0) return false
            val txnType = if (isDebit) TransactionType.EXPENSE else TransactionType.INCOME
            val transactionDao = db.transactionDao()

            // Prevent duplicate insertion if already logged by UTR or in-app UPI flow within 5 minutes
            val existingByUtr = utr?.takeIf { it.isNotBlank() }?.let { transactionDao.getTransactionByUtr(it) }
            val existingDup = transactionDao.findPotentialDuplicates(
                amount = amount,
                type = txnType,
                scope = FinanceScope.PERSONAL,
                startMillis = dateMillis - 300_000L,
                endMillis = dateMillis + 300_000L
            ).firstOrNull()

            if (existingByUtr != null || existingDup != null || !UpiPaymentBus.tryClaimRecording(amount, utr)) {
                UpiPaymentBus.markRecorded(amount, utr)
                PendingUpiDetectionStore.markResolved(amount, utr, context)
                return false
            }

            val generatedServerId = UUID.randomUUID().toString()
            val prefs = context.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)
            val currentUserId = prefs.getString("user_supabase_id", null) ?: "local_user_1"

            val entity = TransactionEntity(
                title = title,
                amount = amount,
                type = txnType,
                category = category,
                categoryIconName = CategoryIconHelper.iconNameForCategory(category),
                note = if (isDebit) "Logged via notification tracker" else "Credited via notification tracker",
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
            UpiPaymentBus.markRecorded(amount, utr)
            PendingUpiDetectionStore.markResolved(amount, utr, context)
            MerchantCategoryRules.learn(title, upiId, category)
            ZenithSummaryWidgetProvider.requestUpdate(context)
            Log.i("NotifExpenseReceiver", "Recorded UPI ${txnType.name} via notification action (hasUtr=${!utr.isNullOrBlank()})")

            if (syncWithCloud) {
                try {
                    val authService = SupabaseAuthService()
                    val syncEngine = SyncEngine(
                        transactionDao = transactionDao,
                        authService = authService,
                        budgetDao = db.budgetDao(),
                        savingsGoalDao = db.savingsGoalDao(),
                        autoPayReminderDao = db.autoPayReminderDao()
                    )
                    syncEngine.syncAll(currentUserId)
                } catch (e: Exception) {
                    Log.w("NotifExpenseReceiver", "Post-notification sync trigger: ${e.message}")
                }
            }
            return true
        }
    }

    private val tag = "NotifExpenseReceiver"

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 1001)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notifId)

        if (action == ACTION_DISMISS_EXPENSE) {
            val amount = intent.getDoubleExtra(EXTRA_AMOUNT, 0.0)
            val utr = intent.getStringExtra(EXTRA_UTR)
            if (amount > 0.0 || !utr.isNullOrBlank()) {
                PendingUpiDetectionStore.markResolved(amount, utr, context)
            }
            UpiPaymentBus.clearPending()
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
            val isDebit = intent.getBooleanExtra(EXTRA_IS_DEBIT, true)

            if (amount <= 0.0) return

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val inserted = persistNotificationTransaction(
                        context = context,
                        db = CashFlowDatabase.getDatabase(context),
                        title = title,
                        amount = amount,
                        category = category,
                        paymentMethod = paymentMethod,
                        upiId = upiId,
                        utr = utr,
                        dateMillis = dateMillis,
                        isDebit = isDebit
                    )
                    val prep = if (isDebit) "at" else "from"
                    val msg = if (inserted) "Recorded ₹$amount $prep $title" else "Already recorded ₹$amount $prep $title"
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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
