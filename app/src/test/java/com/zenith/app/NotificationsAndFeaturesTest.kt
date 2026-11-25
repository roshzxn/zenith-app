package com.zenith.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.zenith.app.data.models.AutoPayReminderEntity
import com.zenith.app.data.models.BudgetEntity
import com.zenith.app.data.models.TransactionEntity
import com.zenith.app.data.models.TransactionType
import com.zenith.app.data.notifications.ReminderAlertEngine
import com.zenith.app.data.upi.ExtractedUpiPayment
import com.zenith.app.data.upi.MerchantCategoryRules
import com.zenith.app.data.upi.PendingUpiDetectionStore
import com.zenith.app.data.upi.RecurringPatternDetector
import com.zenith.app.data.upi.UpiNotificationListenerService
import com.zenith.app.data.upi.UpiNotificationParser
import com.zenith.app.ui.components.UpiPaymentDetectedModal
import com.zenith.app.ui.theme.CashFlowTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationsAndFeaturesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        MerchantCategoryRules.init(context)
        MerchantCategoryRules.clear()
        PendingUpiDetectionStore.init(context)
        PendingUpiDetectionStore.clearAll(context)
    }

    @After
    fun tearDown() {
        MerchantCategoryRules.clear()
        PendingUpiDetectionStore.clearAll(context)
    }

    @Test
    fun testMerchantCategoryRulesLearnResolveAndOverrideParser() {
        MerchantCategoryRules.learn("Rohan", "rohan@okhdfcbank", "Housing")
        assertTrue(MerchantCategoryRules.ruleCount() >= 2)

        val parsed = UpiNotificationParser.parse(
            packageName = "com.google.android.apps.nbu.paisa.user",
            title = "Paid ₹8,000.00",
            text = "Paid ₹8,000.00 to rohan@okhdfcbank. UPI Ref: 123456789012"
        )
        assertNotNull(parsed)
        assertEquals("Housing", parsed!!.detectedCategory)

        MerchantCategoryRules.clear()
        assertEquals(0, MerchantCategoryRules.ruleCount())
    }

    @Test
    fun testPendingUpiDetectionStoreQueueDedupAndResolution() {
        val now = System.currentTimeMillis()
        val first = ExtractedUpiPayment(
            amount = 240.0,
            payeeName = "Swiggy",
            upiTransactionId = "554433221100",
            sourceApp = "Google Pay",
            timestamp = now,
            detectedCategory = "Food & Dining"
        )
        val enrichedDuplicate = first.copy(upiId = "swiggy@okaxis", timestamp = now + 5_000L)
        val second = ExtractedUpiPayment(
            amount = 99.0,
            payeeName = "Metro Card",
            upiTransactionId = "998877665544",
            sourceApp = "PhonePe",
            timestamp = now + 10_000L,
            detectedCategory = "Transportation"
        )

        PendingUpiDetectionStore.enqueue(context, first)
        PendingUpiDetectionStore.enqueue(context, enrichedDuplicate)
        PendingUpiDetectionStore.enqueue(context, second)

        val queued = PendingUpiDetectionStore.pendingDetections.value
        assertEquals(2, queued.size)
        assertEquals("swiggy@okaxis", queued.first { it.upiTransactionId == "554433221100" }.upiId)

        PendingUpiDetectionStore.markResolved(240.0, "554433221100", context)
        val remaining = PendingUpiDetectionStore.pendingDetections.value
        assertEquals(1, remaining.size)
        assertEquals("Metro Card", remaining.first().payeeName)

        PendingUpiDetectionStore.clearAll(context)
        assertTrue(PendingUpiDetectionStore.pendingDetections.value.isEmpty())
    }

    @Test
    fun testRecurringPatternDetectorDetectsMonthlyWeeklyAndSkipsTracked() {
        val dayMs = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()

        val transactions = listOf(
            // Monthly Netflix (3 occurrences ~30 days apart)
            TransactionEntity(title = "Netflix", amount = 649.0, type = TransactionType.EXPENSE, category = "Entertainment", paymentMethod = "UPI", dateMillis = now - 62 * dayMs),
            TransactionEntity(title = "Netflix", amount = 649.0, type = TransactionType.EXPENSE, category = "Entertainment", paymentMethod = "UPI", dateMillis = now - 31 * dayMs),
            TransactionEntity(title = "Netflix", amount = 649.0, type = TransactionType.EXPENSE, category = "Entertainment", paymentMethod = "UPI", dateMillis = now - 1 * dayMs),
            // Weekly Groceries (3 occurrences ~7 days apart)
            TransactionEntity(title = "Milk Basket", amount = 320.0, type = TransactionType.EXPENSE, category = "Shopping", paymentMethod = "UPI", dateMillis = now - 15 * dayMs),
            TransactionEntity(title = "Milk Basket", amount = 330.0, type = TransactionType.EXPENSE, category = "Shopping", paymentMethod = "UPI", dateMillis = now - 8 * dayMs),
            TransactionEntity(title = "Milk Basket", amount = 315.0, type = TransactionType.EXPENSE, category = "Shopping", paymentMethod = "UPI", dateMillis = now - 1 * dayMs),
            // Already tracked Jio Fiber
            TransactionEntity(title = "Jio Fiber", amount = 999.0, type = TransactionType.EXPENSE, category = "Bills & Utilities", paymentMethod = "UPI", dateMillis = now - 31 * dayMs),
            TransactionEntity(title = "Jio Fiber", amount = 999.0, type = TransactionType.EXPENSE, category = "Bills & Utilities", paymentMethod = "UPI", dateMillis = now - 1 * dayMs),
            // Erratic irregular shopping
            TransactionEntity(title = "Amazon", amount = 450.0, type = TransactionType.EXPENSE, category = "Shopping", paymentMethod = "UPI", dateMillis = now - 5 * dayMs),
            TransactionEntity(title = "Amazon", amount = 2900.0, type = TransactionType.EXPENSE, category = "Shopping", paymentMethod = "UPI", dateMillis = now - 2 * dayMs)
        )

        val existing = listOf(
            AutoPayReminderEntity(title = "jio fiber", amount = 999.0, nextDueDateMillis = now + 29 * dayMs)
        )

        val suggestions = RecurringPatternDetector.detectSuggestions(
            transactions = transactions,
            existingReminders = existing,
            dismissedKeys = emptySet(),
            nowMillis = now
        )

        assertEquals(2, suggestions.size)
        val netflix = suggestions.first { it.title == "Netflix" }
        assertEquals("MONTHLY", netflix.frequency)
        assertEquals(3, netflix.occurrenceCount)

        val milk = suggestions.first { it.title == "Milk Basket" }
        assertEquals("WEEKLY", milk.frequency)
    }

    @Test
    fun testReminderAlertEngineEvaluatesDueBillsAndBudgetsAccurately() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 25, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis
        val dayMs = 24L * 60 * 60 * 1000

        val reminders = listOf(
            AutoPayReminderEntity(id = 1, title = "ACT Fibernet", amount = 1099.0, reminderDaysBefore = 2, nextDueDateMillis = now + dayMs),
            AutoPayReminderEntity(id = 2, title = "Paused Gym", amount = 1500.0, isPaused = true, reminderDaysBefore = 5, nextDueDateMillis = now),
            AutoPayReminderEntity(id = 3, title = "Distant Rent", amount = 18000.0, reminderDaysBefore = 2, nextDueDateMillis = now + 10 * dayMs),
            AutoPayReminderEntity(id = 4, title = "Overdue Mobile", amount = 399.0, reminderDaysBefore = 1, nextDueDateMillis = now - dayMs)
        )

        val dueBills = ReminderAlertEngine.evaluateDueBills(reminders, now)
        assertEquals(2, dueBills.size)
        assertEquals("Overdue Mobile", dueBills[0].reminder.title)
        assertEquals(-1, dueBills[0].daysUntilDue)
        assertEquals("ACT Fibernet", dueBills[1].reminder.title)
        assertEquals(1, dueBills[1].daysUntilDue)

        val budgets = listOf(
            BudgetEntity(id = 10, categoryName = "Food & Dining", monthlyLimit = 1000.0, periodType = "MONTHLY"),
            BudgetEntity(id = 11, categoryName = "Shopping", monthlyLimit = 2000.0, periodType = "WEEKLY"),
            BudgetEntity(id = 12, categoryName = "Travel", monthlyLimit = 5000.0, periodType = "MONTHLY")
        )

        val lastMonthCal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 20, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val expenses = listOf(
            // Old month ignored for Food budget
            TransactionEntity(title = "Old Food", amount = 950.0, type = TransactionType.EXPENSE, category = "Food & Dining", dateMillis = lastMonthCal.timeInMillis),
            // Current month Food = 900/1000 -> 90%
            TransactionEntity(title = "Dinner", amount = 900.0, type = TransactionType.EXPENSE, category = "Food & Dining", dateMillis = now),
            // Current week Shopping = 2200/2000 -> 110%
            TransactionEntity(title = "Jacket", amount = 2200.0, type = TransactionType.EXPENSE, category = "Shopping", dateMillis = now),
            // Old week ignored for Weekly Shopping
            TransactionEntity(title = "Old Shoes", amount = 5000.0, type = TransactionType.EXPENSE, category = "Shopping", dateMillis = now - 14 * dayMs),
            // Low spend Travel = 20% -> not flagged
            TransactionEntity(title = "Metro", amount = 1000.0, type = TransactionType.EXPENSE, category = "Travel", dateMillis = now)
        )

        val budgetAlerts = ReminderAlertEngine.evaluateBudgetThresholds(budgets, expenses, now)
        assertEquals(2, budgetAlerts.size)
        assertEquals("Shopping", budgetAlerts[0].budget.categoryName)
        assertEquals(110, budgetAlerts[0].percentUsed)
        assertEquals("Food & Dining", budgetAlerts[1].budget.categoryName)
        assertEquals(90, budgetAlerts[1].percentUsed)
    }

    @Test
    fun testInstantAutoSaveToggleAndChannelCreation() {
        assertFalse(UpiNotificationListenerService.isInstantAutoSaveEnabled(context))
        UpiNotificationListenerService.setInstantAutoSaveEnabled(context, true)
        assertTrue(UpiNotificationListenerService.isInstantAutoSaveEnabled(context))
        UpiNotificationListenerService.setInstantAutoSaveEnabled(context, false)
        assertFalse(UpiNotificationListenerService.isInstantAutoSaveEnabled(context))

        UpiNotificationListenerService.ensureChannel(context)
        ReminderAlertEngine.ensureChannel(context)
    }

    @Test
    fun testUpiPaymentDetectedModalRefreshesStateAcrossConsecutivePayments() {
        val debit = ExtractedUpiPayment(
            amount = 350.0,
            payeeName = "Swiggy",
            detectedCategory = "Food & Dining",
            sourceApp = "Google Pay",
            isDebit = true
        )
        val credit = ExtractedUpiPayment(
            amount = 1200.0,
            payeeName = "Ananya",
            detectedCategory = "Other",
            sourceApp = "PhonePe",
            isDebit = false
        )
        val paymentState = mutableStateOf(debit)

        composeRule.setContent {
            CashFlowTheme(isDarkMode = true) {
                UpiPaymentDetectedModal(
                    payment = paymentState.value,
                    currencySymbol = "₹",
                    onDismiss = {},
                    onConfirmSave = { _, _, _, _, _ -> }
                )
            }
        }

        composeRule.onNodeWithText("Swiggy").assertIsDisplayed()
        composeRule.onNodeWithText("Record Expense").assertIsDisplayed()

        composeRule.runOnIdle {
            paymentState.value = credit
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Ananya").assertIsDisplayed()
        composeRule.onNodeWithText("Record Income").assertIsDisplayed()
    }
}
