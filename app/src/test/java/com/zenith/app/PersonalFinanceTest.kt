package com.zenith.app

import android.app.Notification
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zenith.app.data.database.CashFlowDatabase
import com.zenith.app.data.models.*
import com.zenith.app.data.network.LocalAuthService
import com.zenith.app.data.network.SyncEngine
import com.zenith.app.data.repository.CashFlowRepository
import com.zenith.app.data.upi.NotificationExpenseActionReceiver
import com.zenith.app.data.upi.UpiNotificationListenerService
import com.zenith.app.data.upi.UpiNotificationParser
import com.zenith.app.data.upi.UpiPaymentBus
import com.zenith.app.ui.components.categoryVectorFor
import com.zenith.app.ui.screens.calculateBudgetSpent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PersonalFinanceTest {
    private lateinit var db: CashFlowDatabase
    private lateinit var repo: CashFlowRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CashFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = CashFlowRepository(
            transactionDao = db.transactionDao(),
            categoryDao = db.categoryDao(),
            budgetDao = db.budgetDao(),
            savingsGoalDao = db.savingsGoalDao(),
            scannedItemDao = db.scannedItemDao(),
            receiptDao = db.receiptDao(),
            autoPayReminderDao = db.autoPayReminderDao()
        )
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testPersonalTransaction() = runBlocking {
        val tx = TransactionEntity(
            title = "Transport",
            amount = 500.0,
            type = TransactionType.EXPENSE,
            category = "Transport",
            financeScope = FinanceScope.PERSONAL
        )
        repo.addTransaction(tx)

        val personal = repo.getTransactionsByScope(FinanceScope.PERSONAL).first()
        assertEquals(1, personal.size)
        assertEquals(FinanceScope.PERSONAL, personal[0].financeScope)
        assertNull(personal[0].familyId)
    }

    @Test
    fun testReceiptWithItemizedDetails() = runBlocking {
        val tx = TransactionEntity(
            title = "Whole Foods",
            amount = 14.98,
            type = TransactionType.EXPENSE,
            category = "Food & Dining",
            financeScope = FinanceScope.PERSONAL
        )
        val txId = repo.addTransaction(tx)

        val receipt = ReceiptEntity(
            transactionId = txId,
            merchantName = "Whole Foods Market",
            receiptNumber = "INV-9948",
            receiptDate = "2026-08-15",
            receiptTime = "14:30",
            subtotal = 13.98,
            discount = 0.0,
            tax = 1.00,
            total = 14.98,
            currency = "₹",
            paymentMethod = "UPI"
        )

        val items = listOf(
            ReceiptItemEntity(
                transactionId = txId,
                name = "Organic Whole Milk 1Gal",
                quantity = 1.0,
                unitPrice = 5.49,
                totalPrice = 5.49
            ),
            ReceiptItemEntity(
                transactionId = txId,
                name = "Artisan Sourdough Bread",
                quantity = 2.0,
                unitPrice = 4.245,
                totalPrice = 8.49
            )
        )

        val savedReceiptId = repo.saveReceiptWithItems(receipt, items)
        assertTrue(savedReceiptId > 0)

        val fetchedReceipt = repo.getReceiptByTransactionIdDirect(txId)
        assertNotNull(fetchedReceipt)
        assertEquals("Whole Foods Market", fetchedReceipt?.merchantName)
        assertEquals("INV-9948", fetchedReceipt?.receiptNumber)
        assertEquals(14.98, fetchedReceipt?.total ?: 0.0, 0.01)

        val fetchedItems = repo.getItemsForTransactionDirect(txId)
        assertEquals(2, fetchedItems.size)
        assertEquals("Organic Whole Milk 1Gal", fetchedItems[0].name)

        repo.deleteReceiptByTransactionId(txId)
        assertNull(repo.getReceiptByTransactionIdDirect(txId))
        assertTrue(repo.getItemsForTransactionDirect(txId).isEmpty())
    }

    @Test
    fun testBudgetCreationAndQuery() = runBlocking {
        val budget = BudgetEntity(
            categoryName = "Food & Dining",
            monthlyLimit = 15000.0,
            monthYear = "2026-08",
            periodType = "MONTHLY"
        )
        val budgetId = repo.saveBudget(budget)
        assertTrue(budgetId > 0)

        val allBudgets = repo.getBudgetsForMonth("2026-08").first()
        assertEquals(1, allBudgets.size)
        assertEquals("Food & Dining", allBudgets[0].categoryName)
        assertEquals(15000.0, allBudgets[0].monthlyLimit, 0.01)
    }

    @Test
    fun testCalculateBudgetSpentWeeklyWindowAndMonthlyIsolation() {
        val nowCal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 25, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMillis = nowCal.timeInMillis
        val twoDaysAgoSameWeek = nowMillis - 2L * 86_400_000L
        val fourteenDaysAgo = nowMillis - 14L * 86_400_000L
        val monthYear = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(nowMillis))

        val txs = listOf(
            TransactionEntity(title = "Lunch", amount = 400.0, type = TransactionType.EXPENSE, category = "Food & Dining", dateMillis = twoDaysAgoSameWeek),
            TransactionEntity(title = "Old Dinner", amount = 1200.0, type = TransactionType.EXPENSE, category = "Food & Dining", dateMillis = fourteenDaysAgo),
            TransactionEntity(title = "Refund", amount = 150.0, type = TransactionType.INCOME, category = "Food & Dining", dateMillis = nowMillis)
        )

        val weeklyBudget = BudgetEntity(categoryName = "Food & Dining", monthlyLimit = 2000.0, monthYear = monthYear, periodType = "WEEKLY")
        val monthlyBudget = BudgetEntity(categoryName = "Food & Dining", monthlyLimit = 5000.0, monthYear = monthYear, periodType = "MONTHLY")

        assertEquals(400.0, calculateBudgetSpent(weeklyBudget, txs, nowMillis), 0.01)
        assertEquals(1600.0, calculateBudgetSpent(monthlyBudget, txs, nowMillis), 0.01)
    }

    @Test
    fun testSavingsGoalAndDeposit() = runBlocking {
        val goal = SavingsGoalEntity(
            title = "Emergency Fund",
            targetAmount = 100000.0,
            currentAmount = 25000.0,
            colorHex = "#10B981"
        )
        val goalId = repo.saveSavingsGoal(goal)
        assertTrue(goalId > 0)

        val updatedGoal = goal.copy(id = goalId, currentAmount = 35000.0)
        repo.saveSavingsGoal(updatedGoal)

        val goals = repo.allSavingsGoals.first()
        assertEquals(1, goals.size)
        assertEquals("Emergency Fund", goals[0].title)
        assertEquals(35000.0, goals[0].currentAmount, 0.01)
        assertEquals(100000.0, goals[0].targetAmount, 0.01)
    }

    @Test
    fun testSoftDeleteForUndoAndRestorePreservesSyncStatus() = runBlocking {
        val localId = repo.addTransaction(
            TransactionEntity(
                title = "Local Snack",
                amount = 80.0,
                type = TransactionType.EXPENSE,
                category = "Food & Dining",
                serverId = null,
                syncStatus = "PENDING_CREATE"
            )
        )
        val syncedId = repo.addTransaction(
            TransactionEntity(
                title = "Cloud Flight",
                amount = 4500.0,
                type = TransactionType.EXPENSE,
                category = "Transportation",
                serverId = "srv_flight_1",
                syncStatus = "SYNCED"
            )
        )
        repo.saveReceiptWithItems(
            ReceiptEntity(transactionId = localId, merchantName = "Cafe", total = 80.0),
            emptyList()
        )

        val activeBefore = repo.allTransactions.first()
        assertEquals(2, activeBefore.size)

        // Soft delete both for undo
        repo.softDeleteForUndo(activeBefore.first { it.id == localId })
        repo.softDeleteForUndo(activeBefore.first { it.id == syncedId })
        assertEquals(0, repo.allTransactions.first().size)
        assertNotNull("Receipt must survive softDeleteForUndo during undo window", repo.getReceiptByTransactionIdDirect(localId))

        // Restore both and inspect syncStatus
        repo.restoreTransaction(activeBefore.first { it.id == localId })
        repo.restoreTransaction(activeBefore.first { it.id == syncedId })
        val restored = repo.allTransactions.first()
        assertEquals(2, restored.size)
        assertEquals("PENDING_CREATE", restored.first { it.id == localId }.syncStatus)
        assertEquals("PENDING_UPDATE", restored.first { it.id == syncedId }.syncStatus)
    }

    @Test
    fun testSyncEngineSafeOfflineExecution() = runBlocking {
        val syncEngine = SyncEngine(
            transactionDao = db.transactionDao(),
            authService = LocalAuthService(),
            budgetDao = db.budgetDao(),
            savingsGoalDao = db.savingsGoalDao(),
            autoPayReminderDao = db.autoPayReminderDao()
        )
        val result = syncEngine.syncAll()
        assertTrue("SyncEngine should execute safely without crashing in offline mode", result)
    }

    @Test
    fun testTransactionSoftDeleteFiltering() = runBlocking {
        val tx = TransactionEntity(
            id = 100L,
            title = "Movie Tickets",
            amount = 450.0,
            type = TransactionType.EXPENSE,
            category = "Entertainment",
            financeScope = FinanceScope.PERSONAL,
            serverId = "srv_tx_100"
        )
        repo.addTransaction(tx)

        var activeTxs = repo.allTransactions.first()
        assertEquals(1, activeTxs.size)

        repo.deleteTransaction(activeTxs[0])

        activeTxs = repo.allTransactions.first()
        assertEquals(0, activeTxs.size)

        val pendingDeletes = db.transactionDao().getPendingDeletes()
        assertEquals(1, pendingDeletes.size)
        assertEquals("srv_tx_100", pendingDeletes[0].serverId)
        assertTrue(pendingDeletes[0].isDeleted)
    }

    @Test
    fun testLocalOnlyTransactionHardDelete() = runBlocking {
        val localTx = TransactionEntity(
            id = 200L,
            title = "Coffee",
            amount = 120.0,
            type = TransactionType.EXPENSE,
            category = "Food & Dining",
            financeScope = FinanceScope.PERSONAL,
            serverId = null
        )
        repo.addTransaction(localTx)

        var activeTxs = repo.allTransactions.first()
        assertEquals(1, activeTxs.size)

        repo.deleteTransaction(activeTxs[0])

        activeTxs = repo.allTransactions.first()
        assertEquals(0, activeTxs.size)
        assertEquals(0, db.transactionDao().getPendingDeletes().size)
    }

    @Test
    fun testDatabaseSumAggregations() = runBlocking {
        repo.addTransaction(TransactionEntity(title = "Salary", amount = 50000.0, type = TransactionType.INCOME, category = "Salary"))
        repo.addTransaction(TransactionEntity(title = "Dividend", amount = 2500.0, type = TransactionType.INCOME, category = "Investments"))
        repo.addTransaction(TransactionEntity(title = "Rent", amount = 15000.0, type = TransactionType.EXPENSE, category = "Housing"))

        val totalIncome = db.transactionDao().observeTotalIncome(FinanceScope.PERSONAL).first()
        val totalExpense = db.transactionDao().observeTotalExpense(FinanceScope.PERSONAL).first()

        assertEquals(52500.0, totalIncome, 0.001)
        assertEquals(15000.0, totalExpense, 0.001)
    }

    @Test
    fun testUpiPaymentBusCrossPathDedup() {
        UpiPaymentBus.markRecorded(amount = 499.50, utr = "UTR99887766")

        assertTrue(UpiPaymentBus.wasRecentlyRecorded(amount = 499.50, utr = null))
        assertTrue(UpiPaymentBus.wasRecentlyRecorded(amount = 1.0, utr = "utr99887766"))
        assertFalse(UpiPaymentBus.wasRecentlyRecorded(amount = 500.0, utr = "UTR00000001"))
    }

    @Test
    fun testCategoryVectorForSupportsPascalSnakeAndCategoryFallback() {
        assertEquals(Icons.Default.DirectionsCar, categoryVectorFor("DirectionsCar"))
        assertEquals(Icons.Default.DirectionsCar, categoryVectorFor("directions_car"))
        assertEquals(Icons.Default.ShoppingBag, categoryVectorFor("ShoppingBag"))
        assertEquals(Icons.Default.ShoppingBag, categoryVectorFor("shopping_bag"))
        assertEquals(Icons.Default.MedicalServices, categoryVectorFor("MedicalServices"))
        assertEquals(Icons.Default.MedicalServices, categoryVectorFor("medical_services"))
        assertEquals(Icons.Default.Bolt, categoryVectorFor("Receipt", "Bills & Utilities"))
        assertEquals(Icons.Default.DirectionsCar, categoryVectorFor("", "Transportation"))
    }

    @Test
    fun testNotificationListenerExtractsInboxLinesAndSkipsGroupSummary() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notif = NotificationCompat.Builder(context, "test_upi_channel")
            .setContentTitle("HDFC Bank")
            .setSubText("UPI Alert")
            .setStyle(
                NotificationCompat.InboxStyle()
                    .addLine("Rs.320.00 debited from a/c **1234 to Zomato")
                    .addLine("UPI Ref No. 667788990011")
            )
            .build()

        val (title, body) = UpiNotificationListenerService.extractNotificationPayload(notif)
        assertEquals("HDFC Bank", title)
        assertNotNull(body)

        val parsed = UpiNotificationParser.parse("com.snapwork.hdfc", title, body)
        assertNotNull(parsed)
        assertEquals(320.0, parsed!!.amount, 0.01)
        assertEquals("Zomato", parsed.payeeName)
        assertEquals("667788990011", parsed.upiTransactionId)

        assertTrue(
            UpiNotificationListenerService.shouldIgnoreNotification(
                sourcePackage = "com.phonepe.app",
                ownPackage = context.packageName,
                flags = Notification.FLAG_GROUP_SUMMARY
            )
        )
        assertTrue(
            UpiNotificationListenerService.shouldIgnoreNotification(
                sourcePackage = context.packageName,
                ownPackage = context.packageName,
                flags = 0
            )
        )
        assertFalse(
            UpiNotificationListenerService.shouldIgnoreNotification(
                sourcePackage = "com.phonepe.app",
                ownPackage = context.packageName,
                flags = 0
            )
        )
    }

    @Test
    fun testListenerPermissionComponentNameMatching() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            "com.zenith.app.fake/com.zenith.app.fake.Listener"
        )
        assertFalse(UpiNotificationListenerService.isPermissionGranted(context))
        assertFalse(UpiNotificationListenerService.ensureListenerBound(context))

        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            "${context.packageName}/.data.upi.UpiNotificationListenerService"
        )
        assertTrue(UpiNotificationListenerService.isPermissionGranted(context))

        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            "${context.packageName}/com.zenith.app.data.upi.UpiNotificationListenerService"
        )
        assertTrue(UpiNotificationListenerService.isPermissionGranted(context))
        assertTrue(UpiNotificationListenerService.ensureListenerBound(context))
    }

    @Test
    fun testNotificationActionPersistsExpenseCreditAndDedupsByUtr() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = System.currentTimeMillis()

        val expenseSaved = NotificationExpenseActionReceiver.persistNotificationTransaction(
            context = context,
            db = db,
            title = "Swiggy",
            amount = 420.0,
            category = "Food & Dining",
            paymentMethod = "Google Pay",
            upiId = "swiggy@okaxis",
            utr = "991122334455",
            dateMillis = now,
            isDebit = true,
            syncWithCloud = false
        )
        assertTrue(expenseSaved)

        val duplicateSkipped = NotificationExpenseActionReceiver.persistNotificationTransaction(
            context = context,
            db = db,
            title = "Swiggy",
            amount = 420.0,
            category = "Food & Dining",
            paymentMethod = "HDFC Bank",
            upiId = null,
            utr = "991122334455",
            dateMillis = now + 2_000L,
            isDebit = true,
            syncWithCloud = false
        )
        assertFalse(duplicateSkipped)

        val creditSaved = NotificationExpenseActionReceiver.persistNotificationTransaction(
            context = context,
            db = db,
            title = "Ramesh",
            amount = 850.0,
            category = "Other",
            paymentMethod = "PhonePe",
            upiId = "ramesh@ybl",
            utr = "881122334466",
            dateMillis = now,
            isDebit = false,
            syncWithCloud = false
        )
        assertTrue(creditSaved)

        val all = db.transactionDao().getTransactionsByScope(FinanceScope.PERSONAL).first()
        assertEquals(2, all.size)
        val credit = all.first { it.upiTransactionId == "881122334466" }
        val expense = all.first { it.upiTransactionId == "991122334455" }
        assertEquals(TransactionType.INCOME, credit.type)
        assertEquals(TransactionType.EXPENSE, expense.type)
    }
}
