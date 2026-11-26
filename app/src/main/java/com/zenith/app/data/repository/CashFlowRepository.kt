package com.zenith.app.data.repository

import com.zenith.app.data.dao.*
import com.zenith.app.data.models.*
import kotlinx.coroutines.flow.Flow

class CashFlowRepository(
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val budgetDao: BudgetDao,
    private val savingsGoalDao: SavingsGoalDao,
    private val scannedItemDao: ScannedItemDao,
    private val receiptDao: ReceiptDao,
    private val autoPayReminderDao: AutoPayReminderDao? = null
) {
    val allTransactions: Flow<List<TransactionEntity>> = transactionDao.getAllTransactions()
    val allCategories: Flow<List<CategoryEntity>> = categoryDao.getAllCategories()
    val allSavingsGoals: Flow<List<SavingsGoalEntity>> = savingsGoalDao.getAllGoals()
    val allBudgets: Flow<List<BudgetEntity>> = budgetDao.getAllBudgets()
    val allScannedItems: Flow<List<ScannedItemEntity>> = scannedItemDao.getAllScannedItems()
    val allReceipts: Flow<List<ReceiptEntity>> = receiptDao.getAllReceipts()
    val allReceiptItems: Flow<List<ReceiptItemEntity>> = receiptDao.getAllReceiptItems()
    val allAutoPayReminders: Flow<List<AutoPayReminderEntity>> = autoPayReminderDao?.getAllReminders() ?: kotlinx.coroutines.flow.flowOf(emptyList())

    // Auto-Pay Reminders operations
    fun getAutoPayRemindersByScope(scope: FinanceScope): Flow<List<AutoPayReminderEntity>> =
        autoPayReminderDao?.getRemindersByScope(scope) ?: kotlinx.coroutines.flow.flowOf(emptyList())

    fun getUpcomingAutoPayReminders(untilMillis: Long): Flow<List<AutoPayReminderEntity>> =
        autoPayReminderDao?.getUpcomingReminders(untilMillis) ?: kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun getAutoPayReminderById(id: Long): AutoPayReminderEntity? =
        autoPayReminderDao?.getReminderById(id)

    suspend fun saveAutoPayReminder(reminder: AutoPayReminderEntity): Long {
        return autoPayReminderDao?.insertReminder(reminder) ?: 0L
    }

    suspend fun updateAutoPayReminder(reminder: AutoPayReminderEntity) {
        autoPayReminderDao?.updateReminder(reminder)
    }

    suspend fun deleteAutoPayReminder(reminder: AutoPayReminderEntity) {
        autoPayReminderDao?.softDeleteReminder(reminder.id)
    }

    suspend fun togglePauseAutoPayReminder(id: Long, isPaused: Boolean) {
        autoPayReminderDao?.setPaused(id, isPaused)
    }

    suspend fun markAutoPayPaid(id: Long, nextDueDateMillis: Long, lastPaidMillis: Long) {
        autoPayReminderDao?.markAsPaid(id, nextDueDateMillis, lastPaidMillis)
    }

    // Receipt operations
    fun getReceiptForTransaction(transactionId: Long): Flow<ReceiptEntity?> = receiptDao.getReceiptForTransaction(transactionId)
    suspend fun getReceiptByTransactionIdDirect(transactionId: Long): ReceiptEntity? = receiptDao.getReceiptByTransactionIdDirect(transactionId)
    fun getItemsForTransaction(transactionId: Long): Flow<List<ReceiptItemEntity>> = receiptDao.getItemsForTransaction(transactionId)
    suspend fun getItemsForTransactionDirect(transactionId: Long): List<ReceiptItemEntity> = receiptDao.getItemsForTransactionDirect(transactionId)

    suspend fun saveReceiptWithItems(receipt: ReceiptEntity, items: List<ReceiptItemEntity>): Long {
        return receiptDao.insertReceiptWithItems(receipt, items)
    }

    suspend fun updateReceiptWithItems(receipt: ReceiptEntity, items: List<ReceiptItemEntity>) {
        receiptDao.replaceReceiptWithItems(receipt, items)
    }

    suspend fun deleteReceiptByTransactionId(transactionId: Long) {
        receiptDao.deleteReceiptAndItemsByTransactionId(transactionId)
    }

    // Transaction scoping
    fun getTransactionsByScope(scope: FinanceScope): Flow<List<TransactionEntity>> = transactionDao.getTransactionsByScope(scope)
    fun getTransactionsByScopeAndType(scope: FinanceScope, type: TransactionType): Flow<List<TransactionEntity>> = transactionDao.getTransactionsByScopeAndType(scope, type)

    // Budget queries
    fun getBudgetsByScope(scope: FinanceScope): Flow<List<BudgetEntity>> = budgetDao.getBudgetsByScope(scope)
    suspend fun getBudgetById(id: Long): BudgetEntity? = budgetDao.getBudgetById(id)

    // Savings Goals queries
    fun getGoalsByScope(scope: FinanceScope): Flow<List<SavingsGoalEntity>> = savingsGoalDao.getGoalsByScope(scope)
    suspend fun getGoalById(id: Long): SavingsGoalEntity? = savingsGoalDao.getGoalById(id)

    fun getBudgetsForMonth(monthYear: String): Flow<List<BudgetEntity>> {
        return budgetDao.getBudgetsForMonth(monthYear)
    }

    suspend fun addTransaction(transaction: TransactionEntity): Long {
        return transactionDao.insertTransaction(transaction)
    }

    suspend fun insertTransaction(transaction: TransactionEntity): Long {
        return transactionDao.insertTransaction(transaction)
    }

    suspend fun getTransactionByServerId(serverId: String): TransactionEntity? {
        return transactionDao.getTransactionByServerId(serverId)
    }

    suspend fun getTransactionByUtr(utr: String): TransactionEntity? {
        return transactionDao.getTransactionByUtr(utr)
    }

    suspend fun findPotentialDuplicates(
        amount: Double,
        type: TransactionType,
        scope: FinanceScope,
        startMillis: Long,
        endMillis: Long
    ): List<TransactionEntity> {
        return transactionDao.findPotentialDuplicates(amount, type, scope, startMillis, endMillis)
    }

    suspend fun deduplicateTransactions() {
        try {
            val all = transactionDao.getAllTransactionsOnce()
            val grouped = all.groupBy { "${it.title.trim().lowercase()}_${it.amount}_${it.type}_${it.financeScope}" }
            for ((_, group) in grouped) {
                if (group.size <= 1) continue
                val sorted = group.sortedWith(
                    compareBy<TransactionEntity> { it.serverId.isNullOrBlank() }
                        .thenBy { it.syncStatus != "SYNCED" }
                        .thenBy { it.id }
                )
                val keep = sorted.first()
                val duplicates = sorted.drop(1).filter { dup ->
                    val sameWindow = kotlin.math.abs(dup.dateMillis - keep.dateMillis) <= 15_000L
                    val utrConflict = !dup.upiTransactionId.isNullOrBlank() &&
                        !keep.upiTransactionId.isNullOrBlank() &&
                        !dup.upiTransactionId.equals(keep.upiTransactionId, ignoreCase = true)
                    val sameServerOrUnsynced = dup.syncStatus != "SYNCED" || (!dup.serverId.isNullOrBlank() && dup.serverId == keep.serverId)
                    sameWindow && !utrConflict && sameServerOrUnsynced
                }
                for (dup in duplicates) {
                    deleteReceiptByTransactionId(dup.id)
                    transactionDao.deleteTransactionById(dup.id)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("CashFlowRepo", "deduplicateTransactions error: ${e.message}")
        }
    }

    suspend fun updateTransaction(transaction: TransactionEntity) {
        transactionDao.updateTransaction(transaction.copy(syncStatus = "PENDING_UPDATE", updatedAt = System.currentTimeMillis()))
    }

    /**
     * Soft-deletes any transaction so Snackbar Undo can cleanly restore both the transaction row
     * and any linked receipt/items regardless of whether it was already synced or purely local.
     */
    suspend fun softDeleteForUndo(transaction: TransactionEntity) {
        transactionDao.markTransactionPendingUndoDelete(transaction.id)
    }

    suspend fun commitPendingUndoDeletes() {
        transactionDao.commitAllPendingUndoDeletes()
    }

    suspend fun commitPendingUndoDelete(id: Long) {
        transactionDao.commitPendingUndoDelete(id)
    }

    suspend fun deleteTransaction(transaction: TransactionEntity) {
        if (!transaction.serverId.isNullOrBlank()) {
            transactionDao.markTransactionDeleted(transaction.id)
        } else {
            deleteReceiptByTransactionId(transaction.id)
            transactionDao.deleteTransactionById(transaction.id)
        }
    }

    suspend fun restoreTransaction(transaction: TransactionEntity) {
        val sid = transaction.serverId
        val existsById = transaction.id > 0 && transactionDao.getTransactionById(transaction.id) != null
        val existsByServerId = !sid.isNullOrBlank() && transactionDao.getTransactionByServerId(sid) != null
        if (existsById) {
            transactionDao.restoreTransaction(transaction.id)
        } else if (existsByServerId && !sid.isNullOrBlank()) {
            transactionDao.restoreTransactionByServerId(sid)
        } else {
            // Re-insert cleanly if background sync already removed local tombstone during brief race
            transactionDao.insertTransaction(
                transaction.copy(
                    id = 0,
                    isDeleted = false,
                    syncStatus = if (sid.isNullOrBlank()) "PENDING_CREATE" else "PENDING_UPDATE",
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun markAllPersonalCloudDataPendingDelete() {
        val now = System.currentTimeMillis()
        transactionDao.deleteLocalOnlyPersonalTransactions()
        transactionDao.markAllPersonalTransactionsPendingDelete(now)
        budgetDao.deleteLocalOnlyPersonalBudgets()
        budgetDao.markAllPersonalBudgetsPendingDelete(now)
        savingsGoalDao.deleteLocalOnlyPersonalGoals()
        savingsGoalDao.markAllPersonalGoalsPendingDelete(now)
        autoPayReminderDao?.deleteLocalOnlyPersonalReminders()
        autoPayReminderDao?.markAllPersonalRemindersPendingDelete(now)
        scannedItemDao.deleteAllScannedItems()
        receiptDao.deleteAllReceiptItems()
        receiptDao.deleteAllReceipts()
    }

    suspend fun reassignPersonalTransactionsToUser(newUserId: String): Int {
        return transactionDao.reassignPersonalTransactionsToUser(newUserId)
    }

    suspend fun deleteTransactionById(id: Long) {
        deleteReceiptByTransactionId(id)
        transactionDao.deleteTransactionById(id)
    }

    suspend fun addCategory(category: CategoryEntity): Long {
        return categoryDao.insertCategory(category)
    }

    suspend fun saveBudget(budget: BudgetEntity): Long {
        return budgetDao.insertOrUpdateBudget(budget)
    }

    suspend fun deleteBudget(budget: BudgetEntity) {
        if (!budget.serverId.isNullOrBlank()) {
            budgetDao.markBudgetDeleted(budget.id)
        } else {
            budgetDao.deleteBudget(budget)
        }
    }

    suspend fun saveSavingsGoal(goal: SavingsGoalEntity): Long {
        return savingsGoalDao.insertOrUpdateGoal(goal)
    }

    suspend fun deleteSavingsGoal(goal: SavingsGoalEntity) {
        if (!goal.serverId.isNullOrBlank()) {
            savingsGoalDao.markGoalDeleted(goal.id)
        } else {
            savingsGoalDao.deleteGoal(goal)
        }
    }

    suspend fun addScannedItem(item: ScannedItemEntity): Long {
        return scannedItemDao.insertScannedItem(item)
    }

    suspend fun deleteScannedItem(item: ScannedItemEntity) {
        scannedItemDao.deleteScannedItem(item)
    }

    suspend fun wipeLocalDatabase() {
        transactionDao.deleteAllTransactions()
        budgetDao.deleteAllBudgets()
        savingsGoalDao.deleteAllGoals()
        scannedItemDao.deleteAllScannedItems()
        receiptDao.deleteAllReceiptItems()
        receiptDao.deleteAllReceipts()
        autoPayReminderDao?.deleteAllReminders()
        categoryDao.deleteAllCategories()
    }

    suspend fun reseedDefaultCategories() {
        val categories = listOf(
            CategoryEntity(name = "Food & Dining", iconName = "Restaurant", colorHex = "#EF4444", type = TransactionType.EXPENSE, isDefault = true),
            CategoryEntity(name = "Shopping", iconName = "ShoppingBag", colorHex = "#EC4899", type = TransactionType.EXPENSE, isDefault = true),
            CategoryEntity(name = "Housing & Rent", iconName = "Home", colorHex = "#8B5CF6", type = TransactionType.EXPENSE, isDefault = true),
            CategoryEntity(name = "Transportation", iconName = "DirectionsCar", colorHex = "#3B82F6", type = TransactionType.EXPENSE, isDefault = true),
            CategoryEntity(name = "Bills & Utilities", iconName = "Receipt", colorHex = "#06B6D4", type = TransactionType.EXPENSE, isDefault = true),
            CategoryEntity(name = "Entertainment", iconName = "Movie", colorHex = "#F59E0B", type = TransactionType.EXPENSE, isDefault = true),
            CategoryEntity(name = "Healthcare", iconName = "MedicalServices", colorHex = "#10B981", type = TransactionType.EXPENSE, isDefault = true),
            CategoryEntity(name = "Salary & Income", iconName = "Payments", colorHex = "#059669", type = TransactionType.INCOME, isDefault = true),
            CategoryEntity(name = "Freelance / Business", iconName = "Work", colorHex = "#D97706", type = TransactionType.INCOME, isDefault = true),
            CategoryEntity(name = "Investments", iconName = "TrendingUp", colorHex = "#6366F1", type = TransactionType.INCOME, isDefault = true)
        )
        categoryDao.insertCategories(categories)
    }
}
