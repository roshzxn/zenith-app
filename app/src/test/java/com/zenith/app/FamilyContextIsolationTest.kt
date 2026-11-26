package com.zenith.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows
import com.zenith.app.data.database.CashFlowDatabase
import com.zenith.app.data.database.MIGRATION_16_17
import com.zenith.app.data.models.*
import com.zenith.app.data.network.ActiveFamilyBundle
import com.zenith.app.data.network.FamilyTransactionDto
import com.zenith.app.data.repository.CashFlowRepository
import com.zenith.app.data.repository.FamilyRepository
import com.zenith.app.domain.*
import com.zenith.app.ui.viewmodel.CashFlowViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FamilyContextIsolationTest {

    private lateinit var app: Application
    private lateinit var db: CashFlowDatabase
    private lateinit var personalRepo: CashFlowRepository
    private lateinit var familyRepo: FamilyRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(app, CashFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        personalRepo = CashFlowRepository(
            transactionDao = db.transactionDao(),
            categoryDao = db.categoryDao(),
            budgetDao = db.budgetDao(),
            savingsGoalDao = db.savingsGoalDao(),
            scannedItemDao = db.scannedItemDao(),
            receiptDao = db.receiptDao(),
            autoPayReminderDao = db.autoPayReminderDao()
        )
        familyRepo = FamilyRepository(db.familyDao())
    }

    @After
    fun tearDown() {
        runBlocking {
            withContext(Dispatchers.IO) {
                val singletonDb = CashFlowDatabase.getDatabase(app)
                singletonDb.clearAllTables()
            }
        }
        db.close()
        app.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testMigration16To17PreservesPersonalDataAndCreatesIsolatedFamilyTables() {
        val dbName = "migration_16_17_test_${UUID.randomUUID()}.db"
        val callback = object : SupportSQLiteOpenHelper.Callback(16) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `amount` REAL NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `transactions` (`id`, `title`, `amount`) VALUES (1, 'Personal Coffee', 180.0)"
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(app)
            .name(dbName)
            .callback(callback)
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val sqliteDb = helper.writableDatabase

        MIGRATION_16_17.migrate(sqliteDb)

        sqliteDb.query("SELECT title, amount FROM `transactions`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Personal Coffee", c.getString(0))
            assertEquals(180.0, c.getDouble(1), 0.01)
        }

        val expectedTables = listOf(
            "families",
            "family_members",
            "family_categories",
            "family_transactions",
            "family_budgets",
            "family_messages"
        )
        expectedTables.forEach { table ->
            sqliteDb.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table)
            ).use { cursor ->
                assertTrue("Expected migrated table $table", cursor.moveToFirst())
            }
        }
        helper.close()
        app.deleteDatabase(dbName)
    }

    @Test
    fun testOfflinePendingStatusAndReconciliationPreservation() = runBlocking {
        val familyId = UUID.randomUUID().toString()
        val userA = UUID.randomUUID().toString()
        val userB = UUID.randomUUID().toString()
        familyRepo.cacheActiveBundle(
            ActiveFamilyBundle(
                family = FamilyInfo(id = familyId, name = "Home", avatarEmoji = "🏡", ownerId = userA),
                membership = FamilyMemberInfo(
                    id = UUID.randomUUID().toString(),
                    familyId = familyId,
                    userId = userA,
                    displayName = "Mother",
                    role = FamilyRole.OWNER
                ),
                members = listOf(
                    FamilyMemberInfo(
                        id = UUID.randomUUID().toString(),
                        familyId = familyId,
                        userId = userA,
                        displayName = "Mother",
                        role = FamilyRole.OWNER
                    ),
                    FamilyMemberInfo(
                        id = UUID.randomUUID().toString(),
                        familyId = familyId,
                        userId = userB,
                        displayName = "Father",
                        role = FamilyRole.ADMIN
                    )
                )
            )
        )

        // Add offline local transaction -> must be PENDING_CREATE, never SYNCED
        val offlineTxId = UUID.randomUUID().toString()
        familyRepo.addOrUpdateTransaction(
            FamilyTransactionEntity(
                id = offlineTxId,
                familyId = familyId,
                createdBy = userA,
                paidBy = userA,
                createdByName = "Mother",
                paidByName = "Mother",
                transactionType = TransactionType.EXPENSE,
                amount = 650.0,
                categoryName = "Groceries",
                title = "Milk & Fruits",
                clientTxId = UUID.randomUUID().toString(),
                syncStatus = "PENDING_CREATE"
            )
        )

        // Existing SYNCED row that was deleted remotely by Father
        val removedRemoteId = UUID.randomUUID().toString()
        db.familyDao().upsertFamilyTransaction(
            FamilyTransactionEntity(
                id = removedRemoteId,
                familyId = familyId,
                createdBy = userB,
                paidBy = userB,
                createdByName = "Father",
                paidByName = "Father",
                transactionType = TransactionType.EXPENSE,
                amount = 200.0,
                categoryName = "Other",
                title = "Old Stale Item",
                clientTxId = UUID.randomUUID().toString(),
                syncStatus = "SYNCED"
            )
        )

        // Remote pull contains one new Father expense (and omits removedRemoteId)
        val newRemoteId = UUID.randomUUID().toString()
        familyRepo.reconcilePulledTransactions(
            familyId = familyId,
            remoteTxs = listOf(
                FamilyTransactionDto(
                    id = newRemoteId,
                    familyId = familyId,
                    createdBy = userB,
                    paidBy = userB,
                    createdByName = "Father",
                    paidByName = "Father",
                    transactionType = "EXPENSE",
                    amount = 1200.0,
                    categoryName = "Bills & Utilities",
                    title = "Internet Bill",
                    clientTxId = UUID.randomUUID().toString()
                )
            )
        )

        val current = familyRepo.observeFamilyTransactions(familyId).first()
        assertEquals(2, current.size)
        val offlineRow = current.firstOrNull { it.id == offlineTxId }
        assertNotNull(offlineRow)
        assertEquals("PENDING_CREATE", offlineRow?.syncStatus)

        val remoteRow = current.firstOrNull { it.id == newRemoteId }
        assertNotNull(remoteRow)
        assertEquals("SYNCED", remoteRow?.syncStatus)

        assertNull("Remotely deleted SYNCED item must be reconciled out", current.firstOrNull { it.id == removedRemoteId })
    }

    private suspend fun waitUntil(timeoutMs: Long = 3500L, predicate: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            delay(30)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun testPersonalAndFamilyModesNeverLeakIntoEachOtherAndDisablePreservesFamilyCache() = runBlocking {
        val singletonDb = CashFlowDatabase.getDatabase(app)
        withContext(Dispatchers.IO) {
            singletonDb.clearAllTables()
        }

        val uid = UUID.randomUUID().toString()
        val famId = UUID.randomUUID().toString()
        val vm = CashFlowViewModel(app)
        vm.setAuthenticatedUser("mother@example.com", "Mother", userId = uid, isGuest = false)

        // Add Personal transaction & Goal while in Personal Mode
        vm.addTransaction(
            title = "Personal Gym Membership",
            amount = 2500.0,
            type = TransactionType.EXPENSE,
            category = "Healthcare",
            paymentMethod = "UPI",
            note = "Private"
        )
        vm.saveSavingsGoal(title = "Personal Emergency Fund", targetAmount = 100000.0, currentAmount = 20000.0)
        waitUntil {
            vm.uiState.value.allTransactions.any { it.title == "Personal Gym Membership" } &&
                vm.uiState.value.savingsGoals.any { it.title == "Personal Emergency Fund" }
        }

        assertEquals(FinancialMode.PERSONAL, vm.uiState.value.activeMode)
        assertTrue(vm.uiState.value.allTransactions.any { it.title == "Personal Gym Membership" })
        assertTrue(vm.uiState.value.savingsGoals.any { it.title == "Personal Emergency Fund" })

        // Cache family bundle and switch into Family Mode
        val famRepoSingleton = FamilyRepository(singletonDb.familyDao())
        val famInfo = FamilyInfo(id = famId, name = "Sunshine Home", avatarEmoji = "🏡", ownerId = uid, monthlyBudgetLimit = 40000.0)
        val members = listOf(
            FamilyMemberInfo(
                id = UUID.randomUUID().toString(),
                familyId = famId,
                userId = uid,
                displayName = "Mother",
                role = FamilyRole.OWNER
            )
        )
        famRepoSingleton.cacheActiveBundle(
            ActiveFamilyBundle(family = famInfo, membership = members.first(), members = members)
        )
        vm.applyLocalCachedFamilyContextForTest(famInfo, members, FamilyRole.OWNER, enterFamilyMode = true)
        waitUntil { vm.uiState.value.activeMode == FinancialMode.FAMILY }

        // Personal transactions & goals must NOT leak into Family Mode
        assertEquals(FinancialMode.FAMILY, vm.uiState.value.activeMode)
        assertFalse(vm.uiState.value.allTransactions.any { it.title == "Personal Gym Membership" })
        assertTrue("Personal goals must not leak into Family Mode", vm.uiState.value.savingsGoals.isEmpty())

        // Add Family Expense in Family Mode
        vm.addTransaction(
            title = "Weekly Family Groceries",
            amount = 3400.0,
            type = TransactionType.EXPENSE,
            category = "Groceries",
            paymentMethod = "Card",
            note = "Supermarket",
            paidByUserId = uid
        )
        waitUntil { vm.uiState.value.allTransactions.any { it.title == "Weekly Family Groceries" } }

        val familyTxs = vm.uiState.value.allTransactions
        assertEquals(1, familyTxs.size)
        assertEquals("Weekly Family Groceries", familyTxs.first().title)
        assertEquals(FinanceScope.FAMILY, familyTxs.first().financeScope)
        val attr = vm.uiState.value.txAttributionByServerId[familyTxs.first().serverId]
        assertEquals("Mother", attr?.createdByName)
        assertEquals("Mother", attr?.paidByName)

        // Switch back to Personal Mode -> Family expense must disappear, Personal records intact
        assertTrue(vm.selectFinancialMode(FinancialMode.PERSONAL))
        waitUntil {
            vm.uiState.value.activeMode == FinancialMode.PERSONAL &&
                vm.uiState.value.allTransactions.any { it.title == "Personal Gym Membership" }
        }

        assertEquals(FinancialMode.PERSONAL, vm.uiState.value.activeMode)
        assertFalse(vm.uiState.value.allTransactions.any { it.title == "Weekly Family Groceries" })
        assertTrue(vm.uiState.value.allTransactions.any { it.title == "Personal Gym Membership" })

        // Switch back to Family -> Family data restored
        assertTrue(vm.selectFinancialMode(FinancialMode.FAMILY))
        waitUntil {
            vm.uiState.value.activeMode == FinancialMode.FAMILY &&
                vm.uiState.value.allTransactions.any { it.title == "Weekly Family Groceries" }
        }
        assertTrue(vm.uiState.value.allTransactions.any { it.title == "Weekly Family Groceries" })

        // Disabling Family feature hides Family Mode without deleting stored family records
        vm.setFamilyFeatureEnabled(false)
        waitUntil { vm.uiState.value.activeMode == FinancialMode.PERSONAL }
        assertEquals(FinancialMode.PERSONAL, vm.uiState.value.activeMode)
        val cachedAfterDisable = withContext(Dispatchers.IO) {
            singletonDb.familyDao().getFamilyTransactionsOnce(famId)
        }
        assertEquals(1, cachedAfterDisable.size)

        // Wipe local user cache purges both Personal and Family cached data
        vm.clearLocalUserCache()
        waitUntil {
            withContext(Dispatchers.IO) {
                singletonDb.familyDao().getFamilyTransactionsOnce(famId).isEmpty()
            }
        }
        val cachedAfterWipe = withContext(Dispatchers.IO) {
            singletonDb.familyDao().getFamilyTransactionsOnce(famId)
        }
        assertTrue(cachedAfterWipe.isEmpty())
    }
}
