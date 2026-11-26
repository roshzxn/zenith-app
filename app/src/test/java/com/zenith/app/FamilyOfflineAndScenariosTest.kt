package com.zenith.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zenith.app.data.database.CashFlowDatabase
import com.zenith.app.data.models.FamilyMessageEntity
import com.zenith.app.data.models.FamilyTransactionEntity
import com.zenith.app.data.models.FinanceScope
import com.zenith.app.data.models.TransactionEntity
import com.zenith.app.data.models.TransactionType
import com.zenith.app.data.network.ActiveFamilyBundle
import com.zenith.app.data.network.FamilyMessageDto
import com.zenith.app.data.network.FamilyTransactionDto
import com.zenith.app.data.repository.FamilyRepository
import com.zenith.app.domain.FamilyInfo
import com.zenith.app.domain.FamilyMemberInfo
import com.zenith.app.domain.FamilyRole
import com.zenith.app.domain.FinancialMode
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Phase 9 verification suite covering offline retry idempotency (client_tx_id / client_msg_id),
 * sync status transitions (PENDING_CREATE / PENDING_UPDATE / PENDING_DELETE / SYNCED),
 * cross-member reconciliation (Scenario E), role permissions (Scenario D), and
 * multi-account cache isolation (Scenario I).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FamilyOfflineAndScenariosTest {

    private lateinit var app: Application
    private lateinit var db: CashFlowDatabase
    private lateinit var familyRepo: FamilyRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(app, CashFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        familyRepo = FamilyRepository(db.familyDao())
    }

    @After
    fun tearDown() {
        runBlocking {
            withContext(Dispatchers.IO) {
                CashFlowDatabase.getDatabase(app).clearAllTables()
            }
        }
        db.close()
        app.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE).edit().clear().commit()
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
    fun testOfflineIdempotencyAndSyncStatusTransitionsPreventDuplicates() = runBlocking {
        val famId = UUID.randomUUID().toString()
        val motherId = UUID.randomUUID().toString()
        val clientTxId = UUID.randomUUID().toString()
        val firstId = UUID.randomUUID().toString()

        // 1. Offline insert with clientTxId starts as PENDING_CREATE
        familyRepo.addOrUpdateTransaction(
            FamilyTransactionEntity(
                id = firstId,
                familyId = famId,
                createdBy = motherId,
                paidBy = motherId,
                createdByName = "Mother",
                paidByName = "Mother",
                transactionType = TransactionType.EXPENSE,
                amount = 540.0,
                categoryName = "Groceries",
                title = "Organic Vegetables",
                customLabel = "Weekly Box",
                clientTxId = clientTxId
            )
        )

        // 2. Duplicate tap / retry with same clientTxId must update in-place, never create a duplicate row
        val retryId = UUID.randomUUID().toString()
        familyRepo.addOrUpdateTransaction(
            FamilyTransactionEntity(
                id = retryId,
                familyId = famId,
                createdBy = motherId,
                paidBy = motherId,
                createdByName = "Mother",
                paidByName = "Mother",
                transactionType = TransactionType.EXPENSE,
                amount = 620.0,
                categoryName = "Groceries",
                title = "Organic Vegetables Updated",
                customLabel = "Weekly Box",
                clientTxId = clientTxId
            )
        )

        val rowsAfterRetry = familyRepo.observeFamilyTransactions(famId).first()
        assertEquals("Repeated clientTxId must never duplicate rows", 1, rowsAfterRetry.size)
        assertEquals(firstId, rowsAfterRetry.first().id)
        assertEquals(620.0, rowsAfterRetry.first().amount, 0.01)
        assertEquals("PENDING_CREATE", rowsAfterRetry.first().syncStatus)

        // 3. Simulate synced state -> subsequent offline edit transitions to PENDING_UPDATE
        db.familyDao().upsertFamilyTransaction(rowsAfterRetry.first().copy(syncStatus = "SYNCED"))
        familyRepo.addOrUpdateTransaction(rowsAfterRetry.first().copy(amount = 710.0))
        val afterSyncedEdit = familyRepo.getTransactionById(firstId)
        assertEquals("PENDING_UPDATE", afterSyncedEdit?.syncStatus)
        assertEquals(710.0, afterSyncedEdit?.amount ?: 0.0, 0.01)

        // 4. Soft delete hides row from active list and marks PENDING_DELETE; restore flips back to PENDING_UPDATE
        familyRepo.softDeleteTransaction(firstId)
        assertTrue(familyRepo.observeFamilyTransactions(famId).first().isEmpty())
        assertEquals("PENDING_DELETE", familyRepo.getTransactionById(firstId)?.syncStatus)

        familyRepo.restoreTransaction(firstId)
        val restored = familyRepo.getTransactionById(firstId)
        assertFalse(restored?.isDeleted ?: true)
        assertEquals("PENDING_UPDATE", restored?.syncStatus)

        // 5. Chat message clientMsgId uniqueness prevents duplicate offline chat messages
        val clientMsgId = UUID.randomUUID().toString()
        familyRepo.enqueueMessage(
            FamilyMessageEntity(
                id = UUID.randomUUID().toString(),
                familyId = famId,
                senderUserId = motherId,
                senderName = "Mother",
                body = "Bought vegetables",
                clientMsgId = clientMsgId
            )
        )
        familyRepo.enqueueMessage(
            FamilyMessageEntity(
                id = UUID.randomUUID().toString(),
                familyId = famId,
                senderUserId = motherId,
                senderName = "Mother",
                body = "Bought vegetables (retry)",
                clientMsgId = clientMsgId
            )
        )
        val msgs = familyRepo.observeFamilyMessages(famId).first()
        assertEquals("Unique clientMsgId must replace rather than duplicate chat messages", 1, msgs.size)
        assertEquals("PENDING_CREATE", msgs.first().syncStatus)
    }

    @Test
    fun testCrossMemberReconciliationAndPermissionEnforcement() = runBlocking {
        val singletonDb = CashFlowDatabase.getDatabase(app)
        withContext(Dispatchers.IO) { singletonDb.clearAllTables() }

        val famId = UUID.randomUUID().toString()
        val motherId = UUID.randomUUID().toString()
        val fatherId = UUID.randomUUID().toString()
        val family = FamilyInfo(
            id = famId,
            name = "Sharma Home",
            avatarEmoji = "🏡",
            familyLabel = "Household",
            ownerId = motherId,
            monthlyBudgetLimit = 30000.0
        )
        val mother = FamilyMemberInfo(
            id = UUID.randomUUID().toString(),
            familyId = famId,
            userId = motherId,
            displayName = "Mother",
            avatarEmoji = "👩",
            memberLabel = "Mom",
            role = FamilyRole.OWNER
        )
        val father = FamilyMemberInfo(
            id = UUID.randomUUID().toString(),
            familyId = famId,
            userId = fatherId,
            displayName = "Father",
            avatarEmoji = "👨",
            memberLabel = "Dad",
            role = FamilyRole.MEMBER
        )

        val famRepoSingleton = FamilyRepository(singletonDb.familyDao())
        famRepoSingleton.cacheActiveBundle(
            ActiveFamilyBundle(
                family = family,
                membership = father,
                members = listOf(mother, father)
            )
        )

        // Father logs in as a regular MEMBER
        val fatherVm = CashFlowViewModel(app)
        fatherVm.setAuthenticatedUser("father@example.com", "Father", userId = fatherId, isGuest = false)
        fatherVm.applyLocalCachedFamilyContextForTest(family, listOf(mother, father), FamilyRole.MEMBER, enterFamilyMode = true)
        waitUntil { fatherVm.uiState.value.activeMode == FinancialMode.FAMILY }

        // Scenario E: Mother adds an expense + chat share remotely -> Father reconciles from cloud
        val remoteTxId = UUID.randomUUID().toString()
        famRepoSingleton.reconcilePulledTransactions(
            familyId = famId,
            remoteTxs = listOf(
                FamilyTransactionDto(
                    id = remoteTxId,
                    familyId = famId,
                    createdBy = motherId,
                    paidBy = motherId,
                    createdByName = "Mother",
                    paidByName = "Mother",
                    transactionType = "EXPENSE",
                    amount = 2400.0,
                    categoryName = "Groceries",
                    title = "Supermarket Run",
                    customLabel = "Kitchen",
                    clientTxId = UUID.randomUUID().toString()
                )
            ),
            memberNames = mapOf(motherId to "Mother", fatherId to "Father")
        )
        famRepoSingleton.reconcilePulledMessages(
            familyId = famId,
            remoteMsgs = listOf(
                FamilyMessageDto(
                    id = UUID.randomUUID().toString(),
                    familyId = famId,
                    senderUserId = motherId,
                    senderName = "Mother",
                    senderAvatar = "👩",
                    messageType = "EXPENSE_SHARE",
                    body = "Paid for weekly groceries",
                    relatedTransactionId = remoteTxId,
                    clientMsgId = UUID.randomUUID().toString()
                )
            )
        )

        waitUntil {
            fatherVm.uiState.value.allTransactions.any { it.title == "Supermarket Run" } &&
                fatherVm.uiState.value.familyMessages.isNotEmpty()
        }

        val visibleTx = fatherVm.uiState.value.allTransactions.first { it.title == "Supermarket Run" }
        val totalExpense = fatherVm.uiState.value.allTransactions
            .filter { it.type == TransactionType.EXPENSE }
            .sumOf { it.amount }
        assertEquals(2400.0, totalExpense, 0.01)
        assertEquals("Kitchen", visibleTx.customLabel)
        assertFalse(
            "Regular MEMBER must not edit/delete another member's transaction",
            fatherVm.canEditOrDeleteFamilyTransaction(visibleTx)
        )

        val ownTx = TransactionEntity(
            id = 999L,
            serverId = UUID.randomUUID().toString(),
            title = "Father Cab Ride",
            amount = 350.0,
            type = TransactionType.EXPENSE,
            category = "Transport",
            financeScope = FinanceScope.FAMILY,
            familyId = famId,
            createdByUserId = fatherId
        )
        assertTrue(
            "MEMBER can manage their own family transaction",
            fatherVm.canEditOrDeleteFamilyTransaction(ownTx)
        )

        // Promote Father to ADMIN -> now allowed to manage Mother's transaction too
        fatherVm.applyLocalCachedFamilyContextForTest(family, listOf(mother, father), FamilyRole.ADMIN, enterFamilyMode = true)
        waitUntil { fatherVm.uiState.value.activeFamilyRole == FamilyRole.ADMIN }
        assertTrue(
            "ADMIN must be allowed to manage other members' family transactions",
            fatherVm.canEditOrDeleteFamilyTransaction(visibleTx)
        )
    }

    @Test
    fun testMultiAccountSignOutSwitchPreventsCrossFamilyCacheLeak() = runBlocking {
        val singletonDb = CashFlowDatabase.getDatabase(app)
        withContext(Dispatchers.IO) { singletonDb.clearAllTables() }

        val famA = UUID.randomUUID().toString()
        val userA = UUID.randomUUID().toString()
        val famRepoSingleton = FamilyRepository(singletonDb.familyDao())
        val famAInfo = FamilyInfo(id = famA, name = "Family Alpha", avatarEmoji = "🦁", ownerId = userA)
        val memberA = FamilyMemberInfo(id = UUID.randomUUID().toString(), familyId = famA, userId = userA, displayName = "Alice", role = FamilyRole.OWNER)
        famRepoSingleton.cacheActiveBundle(ActiveFamilyBundle(famAInfo, memberA, listOf(memberA)))

        val vmA = CashFlowViewModel(app)
        vmA.setAuthenticatedUser("alice@example.com", "Alice", userId = userA, isGuest = false)
        vmA.applyLocalCachedFamilyContextForTest(famAInfo, listOf(memberA), FamilyRole.OWNER, enterFamilyMode = true)
        vmA.addTransaction("Alpha Rent", 18000.0, TransactionType.EXPENSE, "Housing", "UPI", "Rent", paidByUserId = userA)
        waitUntil { vmA.uiState.value.allTransactions.any { it.title == "Alpha Rent" } }

        // User A signs out -> local cache cleared
        vmA.clearLocalUserCache()
        waitUntil {
            withContext(Dispatchers.IO) { singletonDb.familyDao().getFamilyTransactionsOnce(famA).isEmpty() }
        }

        // User B signs into another account without belonging to Family Alpha
        val famB = UUID.randomUUID().toString()
        val userB = UUID.randomUUID().toString()
        val famBInfo = FamilyInfo(id = famB, name = "Family Beta", avatarEmoji = "🦊", ownerId = userB)
        val memberB = FamilyMemberInfo(id = UUID.randomUUID().toString(), familyId = famB, userId = userB, displayName = "Bob", role = FamilyRole.OWNER)
        famRepoSingleton.cacheActiveBundle(ActiveFamilyBundle(famBInfo, memberB, listOf(memberB)))

        val vmB = CashFlowViewModel(app)
        vmB.setAuthenticatedUser("bob@example.com", "Bob", userId = userB, isGuest = false)
        vmB.applyLocalCachedFamilyContextForTest(famBInfo, listOf(memberB), FamilyRole.OWNER, enterFamilyMode = true)
        waitUntil { vmB.uiState.value.activeMode == FinancialMode.FAMILY }

        assertFalse(
            "Family Alpha transactions must never appear for Family Beta account",
            vmB.uiState.value.allTransactions.any { it.title == "Alpha Rent" }
        )
    }

    @Test
    fun testFamilyReceiptUpiAndOverallBudgetOfflineRouting() = runBlocking {
        val singletonDb = CashFlowDatabase.getDatabase(app)
        withContext(Dispatchers.IO) { singletonDb.clearAllTables() }

        val famId = UUID.randomUUID().toString()
        val uid = UUID.randomUUID().toString()
        val famRepoSingleton = FamilyRepository(singletonDb.familyDao())
        val famInfo = FamilyInfo(id = famId, name = "Patel Home", avatarEmoji = "🏡", ownerId = uid, monthlyBudgetLimit = 10000.0)
        val owner = FamilyMemberInfo(id = UUID.randomUUID().toString(), familyId = famId, userId = uid, displayName = "Arun", role = FamilyRole.OWNER)
        famRepoSingleton.cacheActiveBundle(ActiveFamilyBundle(famInfo, owner, listOf(owner)))

        val vm = CashFlowViewModel(app)
        vm.setAuthenticatedUser("arun@example.com", "Arun", userId = uid, isGuest = false)
        vm.applyLocalCachedFamilyContextForTest(famInfo, listOf(owner), FamilyRole.OWNER, enterFamilyMode = true)
        waitUntil { vm.uiState.value.activeMode == FinancialMode.FAMILY }

        // 1. Receipt scan while in Family Mode saves to Family ledger
        vm.saveReceiptExpense(
            merchant = "Family Mart",
            amount = 980.0,
            category = "Groceries",
            paymentMethod = "Card",
            dateStr = "2026-11-26",
            timeStr = "10:00 AM",
            receiptNumber = "INV-1",
            subtotal = 980.0,
            discount = 0.0,
            tax = 0.0,
            items = emptyList(),
            imageUri = null,
            rawText = "Receipt"
        )

        // 2. Explicit UPI Pay while in Family Mode (scopeToFamily = true) saves to Family ledger
        vm.addUpiTransaction(
            title = "Milk Booth UPI",
            amount = 140.0,
            category = "Groceries",
            upiId = "milk@upi",
            upiTransactionId = "UTR-FAM-1",
            scopeToFamily = true
        )

        // 3. Default passive UPI (scopeToFamily = false) stays isolated in Personal ledger
        vm.addUpiTransaction(
            title = "Personal Coffee UPI",
            amount = 90.0,
            category = "Food & Dining",
            upiId = "cafe@upi",
            upiTransactionId = "UTR-PER-1",
            scopeToFamily = false
        )

        // 4. Overall budget update persists to both FamilyBudgetEntity & FamilyEntity offline
        vm.saveBudget(categoryName = "Overall", limit = 25000.0)

        waitUntil {
            vm.uiState.value.allTransactions.any { it.title == "Family Mart" } &&
                vm.uiState.value.allTransactions.any { it.title == "Milk Booth UPI" } &&
                vm.uiState.value.activeFamily?.monthlyBudgetLimit == 25000.0
        }

        assertTrue(vm.uiState.value.allTransactions.any { it.title == "Family Mart" })
        assertTrue(vm.uiState.value.allTransactions.any { it.title == "Milk Booth UPI" })
        assertFalse(vm.uiState.value.allTransactions.any { it.title == "Personal Coffee UPI" })
        assertEquals(25000.0, vm.uiState.value.activeFamily?.monthlyBudgetLimit ?: 0.0, 0.01)

        // Verify Personal Coffee UPI actually landed cleanly in Personal Mode
        assertTrue(vm.selectFinancialMode(FinancialMode.PERSONAL))
        waitUntil {
            vm.uiState.value.activeMode == FinancialMode.PERSONAL &&
                vm.uiState.value.allTransactions.any { it.title == "Personal Coffee UPI" }
        }
        assertTrue(vm.uiState.value.allTransactions.any { it.title == "Personal Coffee UPI" })
        assertFalse(vm.uiState.value.allTransactions.any { it.title == "Family Mart" })
        assertFalse(vm.uiState.value.allTransactions.any { it.title == "Milk Booth UPI" })
    }

    @Test
    fun testOfflineCleanAndUndoTombstonesPreventSyncRegain() = runBlocking {
        val personalRepo = com.zenith.app.data.repository.CashFlowRepository(
            transactionDao = db.transactionDao(),
            categoryDao = db.categoryDao(),
            budgetDao = db.budgetDao(),
            savingsGoalDao = db.savingsGoalDao(),
            scannedItemDao = db.scannedItemDao(),
            receiptDao = db.receiptDao(),
            autoPayReminderDao = db.autoPayReminderDao()
        )
        val cloudTxServerId = UUID.randomUUID().toString()
        val undoTxServerId = UUID.randomUUID().toString()

        val cloudRowId = personalRepo.addTransaction(
            TransactionEntity(
                title = "Synced Rent",
                amount = 15000.0,
                type = TransactionType.EXPENSE,
                category = "Housing & Rent",
                financeScope = FinanceScope.PERSONAL,
                serverId = cloudTxServerId,
                syncStatus = "SYNCED"
            )
        )
        val undoRowId = personalRepo.addTransaction(
            TransactionEntity(
                title = "Undo Coffee",
                amount = 180.0,
                type = TransactionType.EXPENSE,
                category = "Food & Dining",
                financeScope = FinanceScope.PERSONAL,
                serverId = undoTxServerId,
                syncStatus = "SYNCED"
            )
        )

        // 1. Undo soft-delete holds PENDING_UNDO_DELETE out of PendingDeletes until committed
        val undoTx = personalRepo.getTransactionByServerId(undoTxServerId)!!
        personalRepo.softDeleteForUndo(undoTx)
        assertTrue(personalRepo.getTransactionsByScope(FinanceScope.PERSONAL).first().none { it.id == undoRowId })
        assertFalse(
            "PENDING_UNDO_DELETE must wait for snackbar dismissal before pushing cloud delete",
            db.transactionDao().getPendingDeletes().any { it.id == undoRowId }
        )

        personalRepo.commitPendingUndoDeletes()
        assertTrue(
            "Committed undo delete must queue PENDING_DELETE for cloud removal",
            db.transactionDao().getPendingDeletes().any { it.id == undoRowId && it.syncStatus == "PENDING_DELETE" }
        )

        // 2. Cleaning Personal ledger offline preserves serverId tombstones (no cloud regain!)
        personalRepo.markAllPersonalCloudDataPendingDelete()
        assertTrue(personalRepo.getTransactionsByScope(FinanceScope.PERSONAL).first().isEmpty())
        val deletedIds = db.transactionDao().getDeletedServerIds()
        assertTrue("Tombstone must remain until cloud confirms delete", deletedIds.contains(cloudTxServerId))
        assertTrue("Pending deletes must queue cloud delete on reconnect", db.transactionDao().getPendingDeletes().any { it.id == cloudRowId && it.syncStatus == "PENDING_DELETE" })

        // 3. Family Member clear removes only member's own entries; Admin clear queues all
        val famId = UUID.randomUUID().toString()
        val memberA = UUID.randomUUID().toString()
        val memberB = UUID.randomUUID().toString()
        db.familyDao().upsertFamilyTransaction(
            FamilyTransactionEntity(
                id = "tx-a",
                familyId = famId,
                createdBy = memberA,
                paidBy = memberA,
                amount = 500.0,
                title = "Member A Lunch",
                clientTxId = UUID.randomUUID().toString(),
                syncStatus = "SYNCED"
            )
        )
        db.familyDao().upsertFamilyTransaction(
            FamilyTransactionEntity(
                id = "tx-b",
                familyId = famId,
                createdBy = memberB,
                paidBy = memberB,
                amount = 900.0,
                title = "Member B Grocery",
                clientTxId = UUID.randomUUID().toString(),
                syncStatus = "SYNCED"
            )
        )

        familyRepo.clearFamilyTransactionsEverywhere(famId, memberA, isAdmin = false, alsoClearCloud = true)
        val afterMemberClear = familyRepo.observeFamilyTransactions(famId).first()
        assertEquals(1, afterMemberClear.size)
        assertEquals("tx-b", afterMemberClear.first().id)
        assertEquals("PENDING_DELETE", db.familyDao().getFamilyTransactionById("tx-a")?.syncStatus)

        familyRepo.clearFamilyTransactionsEverywhere(famId, memberB, isAdmin = true, alsoClearCloud = true)
        assertTrue(familyRepo.observeFamilyTransactions(famId).first().isEmpty())
        assertEquals("PENDING_DELETE", db.familyDao().getFamilyTransactionById("tx-b")?.syncStatus)
    }
}
