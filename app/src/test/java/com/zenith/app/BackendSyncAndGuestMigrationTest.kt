package com.zenith.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zenith.app.data.database.CashFlowDatabase
import com.zenith.app.data.models.*
import com.zenith.app.data.network.LocalAuthService
import com.zenith.app.data.network.SharedPreferencesSessionManager
import com.zenith.app.data.network.SyncEngine
import com.zenith.app.data.repository.CashFlowRepository
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class BackendSyncAndGuestMigrationTest {

    private lateinit var context: Context
    private lateinit var db: CashFlowDatabase
    private lateinit var repo: CashFlowRepository
    private lateinit var syncEngine: SyncEngine

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
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
        syncEngine = SyncEngine(
            transactionDao = db.transactionDao(),
            authService = LocalAuthService(),
            budgetDao = db.budgetDao(),
            savingsGoalDao = db.savingsGoalDao(),
            autoPayReminderDao = db.autoPayReminderDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testSyncEngineUuidValidation() {
        val validUuid = UUID.randomUUID().toString()
        assertTrue("Expected valid UUID string to pass validation", syncEngine.isValidUuid(validUuid))

        assertFalse("local_user_1 must not be treated as a valid UUID", syncEngine.isValidUuid("local_user_1"))
        assertFalse("Email must not be treated as a valid UUID", syncEngine.isValidUuid("user@example.com"))
        assertFalse("Blank string must not be treated as a valid UUID", syncEngine.isValidUuid(""))
        assertFalse("Null string must not be treated as a valid UUID", syncEngine.isValidUuid(null))
        assertFalse("Arbitrary text must not be treated as a valid UUID", syncEngine.isValidUuid("not-a-uuid"))
    }

    @Test
    fun testGuestToAccountDataMigration() = runBlocking {
        val guestTx1 = TransactionEntity(
            title = "Morning Coffee",
            amount = 120.0,
            type = TransactionType.EXPENSE,
            category = "Food & Dining",
            financeScope = FinanceScope.PERSONAL,
            createdByUserId = "local_user_1",
            syncStatus = "LOCAL_ONLY"
        )
        val guestTx2 = TransactionEntity(
            title = "Metro Card Recharge",
            amount = 500.0,
            type = TransactionType.EXPENSE,
            category = "Transport",
            financeScope = FinanceScope.PERSONAL,
            createdByUserId = null,
            syncStatus = "LOCAL_ONLY"
        )
        repo.addTransaction(guestTx1)
        repo.addTransaction(guestTx2)

        val authenticatedUserId = UUID.randomUUID().toString()
        val count = repo.reassignPersonalTransactionsToUser(authenticatedUserId)
        assertEquals(2, count)

        val transactions = repo.getTransactionsByScope(FinanceScope.PERSONAL).first()
        assertEquals(2, transactions.size)
        for (tx in transactions) {
            assertEquals(authenticatedUserId, tx.createdByUserId)
            assertEquals("PENDING_CREATE", tx.syncStatus)
        }
    }

    @Test
    fun testAutoPayDefaultsAndCategoryUniqueSeedingAndWipe() = runBlocking {
        val reminder = AutoPayReminderEntity(
            title = "Fiber Internet",
            amount = 999.0,
            category = "Bills & Utilities",
            paymentMethod = "UPI",
            nextDueDateMillis = System.currentTimeMillis() + 86_400_000L
        )
        assertEquals("PENDING_CREATE", reminder.syncStatus)
        repo.saveAutoPayReminder(reminder)
        assertEquals(1, db.autoPayReminderDao().getPendingCreates().size)

        // Repeated reseeds should not create duplicate category names
        CashFlowDatabase.seedDefaultData(db)
        CashFlowDatabase.seedDefaultData(db)
        val categories = db.categoryDao().getAllCategories().first()
        assertEquals(categories.map { it.name }.distinct().size, categories.size)
        assertEquals(10, categories.size)

        repo.addTransaction(
            TransactionEntity(
                title = "Sample",
                amount = 100.0,
                type = TransactionType.EXPENSE,
                category = "Shopping"
            )
        )
        repo.wipeLocalDatabase()

        assertTrue(repo.allTransactions.first().isEmpty())
        assertTrue(repo.allAutoPayReminders.first().isEmpty())
        assertTrue(db.categoryDao().getAllCategories().first().isEmpty())
    }

    @Test
    fun testSharedPreferencesSessionManagerPersistence() = runBlocking {
        val sessionManager = SharedPreferencesSessionManager(context)

        val dummySession = UserSession(
            accessToken = "test_access_jwt_token_12345",
            refreshToken = "test_refresh_jwt_token_67890",
            expiresIn = 3600L,
            tokenType = "bearer",
            user = null,
            type = "bearer"
        )

        sessionManager.saveSession(dummySession)
        val restoredSession = sessionManager.loadSession()
        assertNotNull("Session must be restored from SharedPreferences", restoredSession)
        assertEquals(dummySession.accessToken, restoredSession?.accessToken)
        assertEquals(dummySession.refreshToken, restoredSession?.refreshToken)

        sessionManager.deleteSession()
        val clearedSession = sessionManager.loadSession()
        assertNull("Session must be null after deleteSession", clearedSession)
    }
}
