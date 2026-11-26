package com.zenith.app.data.network

import android.util.Log
import com.zenith.app.data.dao.*
import com.zenith.app.data.models.*
import com.zenith.app.data.util.IsoDateUtils
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Bidirectional Offline-First Sync Engine for Personal Finance.
 *
 * Guarantees:
 * 1. Offline-first: Instant UI mutations with Room SQLite local caching.
 * 2. User-isolated cloud sync across personal transactions, budgets, savings goals, and auto-pay reminders.
 * 3. Multi-device recovery upon sign-in.
 */
class SyncEngine(
    private val transactionDao: TransactionDao,
    private val authService: AuthService,
    private val budgetDao: BudgetDao? = null,
    private val savingsGoalDao: SavingsGoalDao? = null,
    private val autoPayReminderDao: AutoPayReminderDao? = null
) {
    private val tag = "SyncEngine"
    private val syncMutex = kotlinx.coroutines.sync.Mutex()

    fun isValidUuid(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        return try {
            UUID.fromString(id)
            true
        } catch (e: Exception) {
            false
        }
    }

    val isConfigured: Boolean
        get() = SupabaseClientConfig.isConfigured

    suspend fun syncAll(explicitUserId: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            Log.d(tag, "Operating in Local-First mode (Supabase unconfigured or offline).")
            return@withContext true
        }

        val userId = explicitUserId
            ?: authService.currentUser.value?.id
            ?: try { SupabaseClientConfig.supabase.auth.currentUserOrNull()?.id } catch (e: Exception) { null }

        if (userId.isNullOrBlank() || userId == "local_user_1") {
            Log.d(tag, "No active cloud user session — operating in private local mode.")
            return@withContext true
        }

        if (!isValidUuid(userId)) {
            Log.w(tag, "User ID '$userId' is not a valid UUID. Skipping Supabase cloud sync.")
            return@withContext false
        }

        syncMutex.withLock {
            try {
                val txSuccess = syncPersonalTransactions(userId)
                val budgetSuccess = syncPersonalBudgets(userId)
                val goalsSuccess = syncPersonalSavingsGoals(userId)
                val autoPaySuccess = syncPersonalAutoPayReminders(userId)
                txSuccess && budgetSuccess && goalsSuccess && autoPaySuccess
            } catch (e: Exception) {
                Log.e(tag, "syncAll error: ${e.message}", e)
                false
            }
        }
    }

    suspend fun syncTransactions(explicitUserId: String? = null) {
        syncAll(explicitUserId)
    }

    // =============================================================================
    // PERSONAL TRANSACTIONS SYNC
    // =============================================================================
    private suspend fun syncPersonalTransactions(userId: String): Boolean {
        var success = true
        // 1. Push Pending Creates
        val pendingCreates = transactionDao.getPendingCreates()
        for (localTx in pendingCreates) {
            val serverId = localTx.serverId?.takeIf { it.isNotBlank() && isValidUuid(it) } ?: UUID.randomUUID().toString()
            val dto = TransactionDto(
                id = serverId,
                userId = userId,
                familyId = null,
                financeScope = "PERSONAL",
                amount = localTx.amount,
                title = localTx.title,
                transactionType = localTx.type.name,
                type = localTx.type.name,
                categoryId = localTx.category.takeIf { isValidUuid(it) } ?: "Other",
                category = localTx.category.ifBlank { "Other" },
                description = localTx.note.ifBlank { localTx.title.ifBlank { "Transaction" } },
                paymentMethod = localTx.paymentMethod,
                upiId = localTx.upiId,
                upiTransactionId = localTx.upiTransactionId,
                transactionDate = IsoDateUtils.toIsoString(localTx.dateMillis),
                createdAt = IsoDateUtils.toIsoString(localTx.updatedAt),
                updatedAt = IsoDateUtils.toIsoString(localTx.updatedAt),
                isDeleted = false
            )
            try {
                SupabaseClientConfig.supabase.postgrest["transactions"].upsert(dto)
                transactionDao.updateTransaction(
                    localTx.copy(
                        serverId = serverId,
                        syncStatus = "SYNCED",
                        createdByUserId = userId,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                Log.e(tag, "Failed to push personal transaction create: ${e.message}")
                success = false
            }
        }

        // 2. Push Pending Updates
        val pendingUpdates = transactionDao.getPendingUpdates()
        for (localTx in pendingUpdates) {
            val serverId = localTx.serverId?.takeIf { it.isNotBlank() && isValidUuid(it) } ?: UUID.randomUUID().toString()
            val dto = TransactionDto(
                id = serverId,
                userId = userId,
                familyId = null,
                financeScope = "PERSONAL",
                amount = localTx.amount,
                title = localTx.title,
                transactionType = localTx.type.name,
                type = localTx.type.name,
                categoryId = localTx.category.takeIf { isValidUuid(it) } ?: "Other",
                category = localTx.category.ifBlank { "Other" },
                description = localTx.note.ifBlank { localTx.title.ifBlank { "Transaction" } },
                paymentMethod = localTx.paymentMethod,
                upiId = localTx.upiId,
                upiTransactionId = localTx.upiTransactionId,
                transactionDate = IsoDateUtils.toIsoString(localTx.dateMillis),
                updatedAt = IsoDateUtils.toIsoString(localTx.updatedAt),
                isDeleted = false
            )
            try {
                SupabaseClientConfig.supabase.postgrest["transactions"].upsert(dto)
                transactionDao.updateTransaction(
                    localTx.copy(serverId = serverId, syncStatus = "SYNCED", updatedAt = System.currentTimeMillis())
                )
            } catch (e: Exception) {
                Log.e(tag, "Failed to push personal transaction update: ${e.message}")
                success = false
            }
        }

        // 3. Push Pending Deletes
        val pendingDeletes = transactionDao.getPendingDeletes()
        for (localTx in pendingDeletes) {
            if (localTx.syncStatus == "PENDING_UNDO_DELETE") continue
            val serverId = localTx.serverId
            if (serverId != null && isValidUuid(serverId)) {
                var cloudDeleted = false
                try {
                    SupabaseClientConfig.supabase.postgrest["transactions"].delete {
                        filter {
                            eq("id", serverId)
                            eq("user_id", userId)
                        }
                    }
                    cloudDeleted = true
                } catch (_: Exception) {
                    try {
                        SupabaseClientConfig.supabase.postgrest["transactions"].update({
                            set("is_deleted", true)
                            set("updated_at", IsoDateUtils.toIsoString(System.currentTimeMillis()))
                        }) {
                            filter {
                                eq("id", serverId)
                                eq("user_id", userId)
                            }
                        }
                        cloudDeleted = true
                    } catch (_: Exception) {
                        try {
                            SupabaseClientConfig.supabase.postgrest.rpc(
                                function = "delete_my_personal_transaction",
                                parameters = buildJsonObject { put("p_tx_id", serverId) }
                            )
                            cloudDeleted = true
                        } catch (e3: Exception) {
                            Log.e(tag, "Failed to delete personal transaction on cloud: ${e3.message}")
                            success = false
                        }
                    }
                }
                if (cloudDeleted) {
                    transactionDao.deleteReceiptItemsForLocalCleanup(localTx.id)
                    transactionDao.deleteReceiptForTransactionCleanup(localTx.id)
                    transactionDao.deleteTransactionById(localTx.id)
                }
            } else {
                // Purely local record already committed for deletion: clean up local row and receipts
                transactionDao.deleteReceiptItemsForLocalCleanup(localTx.id)
                transactionDao.deleteReceiptForTransactionCleanup(localTx.id)
                transactionDao.deleteTransactionById(localTx.id)
            }
        }

        // 4. Pull Remote Personal Transactions (include is_deleted = true so cross-device deletions sync cleanly)
        try {
            val deletedServerIds = transactionDao.getDeletedServerIds().toSet()
            val remoteTxs = SupabaseClientConfig.supabase.postgrest["transactions"]
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }
                .decodeList<TransactionDto>()

            for (dto in remoteTxs) {
                if (deletedServerIds.contains(dto.id)) continue

                var existing = transactionDao.getTransactionByServerId(dto.id)
                if (existing != null && existing.isDeleted) continue
                val txDateMillis = parseIsoTimestamp(dto.transactionDate)
                val txUpdatedMillis = parseIsoTimestamp(dto.updatedAt ?: dto.createdAt)
                val txTitle = dto.title?.takeIf { it.isNotBlank() }
                    ?: dto.description?.takeIf { it.isNotBlank() }
                    ?: "Transaction"
                val txCategory = dto.category ?: dto.categoryId ?: "General"
                val txType = try {
                    val typeStr = (dto.transactionType ?: dto.type ?: "EXPENSE").uppercase()
                    TransactionType.valueOf(typeStr)
                } catch (e: Exception) {
                    TransactionType.EXPENSE
                }

                // Deduplication guard: check local transactions with matching amount, type and date within 60s
                if (existing == null && !dto.isDeleted) {
                    val dupes = transactionDao.findPotentialDuplicates(
                        amount = dto.amount,
                        type = txType,
                        scope = FinanceScope.PERSONAL,
                        startMillis = txDateMillis - 60_000L,
                        endMillis = txDateMillis + 60_000L
                    )
                    val match = dupes.firstOrNull { it.serverId.isNullOrBlank() || it.serverId == dto.id }
                    if (match != null) {
                        existing = match
                        transactionDao.updateTransaction(
                            existing.copy(
                                serverId = dto.id,
                                syncStatus = "SYNCED",
                                updatedAt = maxOf(existing.updatedAt, txUpdatedMillis)
                            )
                        )
                    }
                }

                if (dto.isDeleted) {
                    if (existing != null) {
                        transactionDao.deleteReceiptItemsForLocalCleanup(existing.id)
                        transactionDao.deleteReceiptForTransactionCleanup(existing.id)
                        transactionDao.deleteTransaction(existing)
                    }
                } else {
                    if (existing == null) {
                        val newEntity = TransactionEntity(
                            title = txTitle,
                            amount = dto.amount,
                            type = txType,
                            category = txCategory,
                            note = dto.description ?: "",
                            paymentMethod = dto.paymentMethod ?: "UPI",
                            dateMillis = txDateMillis,
                            upiId = dto.upiId,
                            upiTransactionId = dto.upiTransactionId,
                            financeScope = FinanceScope.PERSONAL,
                            familyId = null,
                            createdByUserId = userId,
                            serverId = dto.id,
                            syncStatus = "SYNCED",
                            updatedAt = txUpdatedMillis,
                            isDeleted = false
                        )
                        transactionDao.insertTransaction(newEntity)
                    } else if (existing.syncStatus == "SYNCED" && txUpdatedMillis > existing.updatedAt) {
                        val updatedEntity = existing.copy(
                            title = txTitle,
                            amount = dto.amount,
                            type = txType,
                            category = txCategory,
                            note = dto.description ?: "",
                            paymentMethod = dto.paymentMethod ?: existing.paymentMethod,
                            dateMillis = txDateMillis,
                            upiId = dto.upiId,
                            upiTransactionId = dto.upiTransactionId,
                            syncStatus = "SYNCED",
                            updatedAt = txUpdatedMillis,
                            isDeleted = false
                        )
                        transactionDao.updateTransaction(updatedEntity)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to pull remote personal transactions: ${e.message}")
            success = false
        }
        return success
    }

    // =============================================================================
    // PERSONAL BUDGETS SYNC
    // =============================================================================
    private suspend fun syncPersonalBudgets(userId: String): Boolean {
        val bDao = budgetDao ?: return true
        var success = true

        val pendingCreates = bDao.getPendingCreates()
        for (localBudget in pendingCreates) {
            val serverId = localBudget.serverId?.takeIf { it.isNotBlank() && isValidUuid(it) } ?: UUID.randomUUID().toString()
            val dto = BudgetDto(
                id = serverId,
                userId = userId,
                familyId = null,
                financeScope = "PERSONAL",
                name = localBudget.categoryName.ifBlank { "Budget" },
                categoryId = localBudget.categoryName.takeIf { isValidUuid(it) } ?: "Other",
                categoryName = localBudget.categoryName.ifBlank { "Other" },
                amount = localBudget.monthlyLimit,
                monthlyLimit = localBudget.monthlyLimit,
                monthYear = localBudget.monthYear,
                periodType = localBudget.periodType,
                updatedAt = IsoDateUtils.toIsoString(localBudget.updatedAt),
                isDeleted = false
            )
            try {
                SupabaseClientConfig.supabase.postgrest["budgets"].upsert(dto)
                bDao.updateBudget(
                    localBudget.copy(serverId = serverId, syncStatus = "SYNCED", updatedAt = System.currentTimeMillis())
                )
            } catch (e: Exception) {
                Log.e(tag, "Failed to push budget create: ${e.message}")
                success = false
            }
        }

        val pendingUpdates = bDao.getPendingUpdates()
        for (localBudget in pendingUpdates) {
            val serverId = localBudget.serverId?.takeIf { it.isNotBlank() && isValidUuid(it) } ?: UUID.randomUUID().toString()
            val dto = BudgetDto(
                id = serverId,
                userId = userId,
                familyId = null,
                financeScope = "PERSONAL",
                name = localBudget.categoryName.ifBlank { "Budget" },
                categoryId = localBudget.categoryName.takeIf { isValidUuid(it) } ?: "Other",
                categoryName = localBudget.categoryName.ifBlank { "Other" },
                amount = localBudget.monthlyLimit,
                monthlyLimit = localBudget.monthlyLimit,
                monthYear = localBudget.monthYear,
                periodType = localBudget.periodType,
                updatedAt = IsoDateUtils.toIsoString(localBudget.updatedAt),
                isDeleted = false
            )
            try {
                SupabaseClientConfig.supabase.postgrest["budgets"].upsert(dto)
                bDao.updateBudget(
                    localBudget.copy(serverId = serverId, syncStatus = "SYNCED", updatedAt = System.currentTimeMillis())
                )
            } catch (e: Exception) {
                Log.e(tag, "Failed to push budget update: ${e.message}")
                success = false
            }
        }

        val pendingDeletes = bDao.getPendingDeletes()
        for (localBudget in pendingDeletes) {
            val serverId = localBudget.serverId
            if (serverId != null && isValidUuid(serverId)) {
                var cloudDeleted = false
                try {
                    SupabaseClientConfig.supabase.postgrest["budgets"].delete {
                        filter {
                            eq("id", serverId)
                            eq("user_id", userId)
                        }
                    }
                    cloudDeleted = true
                } catch (_: Exception) {
                    try {
                        SupabaseClientConfig.supabase.postgrest["budgets"].update({
                            set("is_deleted", true)
                            set("updated_at", IsoDateUtils.toIsoString(System.currentTimeMillis()))
                        }) {
                            filter {
                                eq("id", serverId)
                                eq("user_id", userId)
                            }
                        }
                        cloudDeleted = true
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to delete budget on cloud: ${e.message}")
                        success = false
                    }
                }
                if (cloudDeleted) {
                    bDao.deleteBudget(localBudget)
                }
            } else {
                bDao.deleteBudget(localBudget)
            }
        }

        try {
            val remoteBudgets = SupabaseClientConfig.supabase.postgrest["budgets"]
                .select {
                    filter { eq("user_id", userId) }
                }
                .decodeList<BudgetDto>()

            for (dto in remoteBudgets) {
                val existing = bDao.getBudgetByServerId(dto.id)
                if (dto.isDeleted) {
                    if (existing != null) bDao.deleteBudget(existing)
                } else {
                    val limit = if (dto.amount > 0) dto.amount else (dto.monthlyLimit ?: 0.0)
                    val catName = dto.categoryName ?: dto.categoryId ?: dto.name ?: "Other"
                    val mYear = dto.monthYear ?: "2026-07"
                    val updatedMillis = parseIsoTimestamp(dto.updatedAt ?: dto.createdAt)

                    if (existing == null) {
                        val newEntity = BudgetEntity(
                            categoryName = catName,
                            monthlyLimit = limit,
                            monthYear = mYear,
                            periodType = dto.periodType ?: "MONTHLY",
                            financeScope = FinanceScope.PERSONAL,
                            familyId = null,
                            serverId = dto.id,
                            syncStatus = "SYNCED",
                            updatedAt = updatedMillis,
                            isDeleted = false
                        )
                        bDao.insertOrUpdateBudget(newEntity)
                    } else if (existing.syncStatus == "SYNCED" && updatedMillis > existing.updatedAt) {
                        val updated = existing.copy(
                            categoryName = catName,
                            monthlyLimit = limit,
                            monthYear = mYear,
                            periodType = dto.periodType ?: existing.periodType,
                            syncStatus = "SYNCED",
                            updatedAt = updatedMillis
                        )
                        bDao.updateBudget(updated)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to pull remote budgets: ${e.message}")
            success = false
        }
        return success
    }

    // =============================================================================
    // PERSONAL SAVINGS GOALS SYNC
    // =============================================================================
    private suspend fun syncPersonalSavingsGoals(userId: String): Boolean {
        val sDao = savingsGoalDao ?: return true
        var success = true

        val pendingCreates = sDao.getPendingCreates()
        for (localGoal in pendingCreates) {
            val serverId = localGoal.serverId?.takeIf { it.isNotBlank() && isValidUuid(it) } ?: UUID.randomUUID().toString()
            val dto = SavingsGoalDto(
                id = serverId,
                userId = userId,
                familyId = null,
                financeScope = "PERSONAL",
                name = localGoal.title,
                title = localGoal.title,
                targetAmount = localGoal.targetAmount,
                currentAmount = localGoal.currentAmount,
                targetDate = IsoDateUtils.toIsoString(localGoal.targetDateMillis),
                iconName = localGoal.iconName,
                colorHex = localGoal.colorHex,
                updatedAt = IsoDateUtils.toIsoString(localGoal.updatedAt),
                isDeleted = false
            )
            try {
                SupabaseClientConfig.supabase.postgrest["savings_goals"].upsert(dto)
                sDao.updateGoal(
                    localGoal.copy(serverId = serverId, syncStatus = "SYNCED", updatedAt = System.currentTimeMillis())
                )
            } catch (e: Exception) {
                Log.e(tag, "Failed to push savings goal create: ${e.message}")
                success = false
            }
        }

        val pendingUpdates = sDao.getPendingUpdates()
        for (localGoal in pendingUpdates) {
            val serverId = localGoal.serverId?.takeIf { it.isNotBlank() && isValidUuid(it) } ?: UUID.randomUUID().toString()
            val dto = SavingsGoalDto(
                id = serverId,
                userId = userId,
                familyId = null,
                financeScope = "PERSONAL",
                name = localGoal.title,
                title = localGoal.title,
                targetAmount = localGoal.targetAmount,
                currentAmount = localGoal.currentAmount,
                targetDate = IsoDateUtils.toIsoString(localGoal.targetDateMillis),
                iconName = localGoal.iconName,
                colorHex = localGoal.colorHex,
                updatedAt = IsoDateUtils.toIsoString(localGoal.updatedAt),
                isDeleted = false
            )
            try {
                SupabaseClientConfig.supabase.postgrest["savings_goals"].upsert(dto)
                sDao.updateGoal(
                    localGoal.copy(serverId = serverId, syncStatus = "SYNCED", updatedAt = System.currentTimeMillis())
                )
            } catch (e: Exception) {
                Log.e(tag, "Failed to push savings goal update: ${e.message}")
                success = false
            }
        }

        val pendingDeletes = sDao.getPendingDeletes()
        for (localGoal in pendingDeletes) {
            val serverId = localGoal.serverId
            if (serverId != null && isValidUuid(serverId)) {
                var cloudDeleted = false
                try {
                    SupabaseClientConfig.supabase.postgrest["savings_goals"].delete {
                        filter {
                            eq("id", serverId)
                            eq("user_id", userId)
                        }
                    }
                    cloudDeleted = true
                } catch (_: Exception) {
                    try {
                        SupabaseClientConfig.supabase.postgrest["savings_goals"].update({
                            set("is_deleted", true)
                            set("updated_at", IsoDateUtils.toIsoString(System.currentTimeMillis()))
                        }) {
                            filter {
                                eq("id", serverId)
                                eq("user_id", userId)
                            }
                        }
                        cloudDeleted = true
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to delete savings goal on cloud: ${e.message}")
                        success = false
                    }
                }
                if (cloudDeleted) {
                    sDao.deleteGoal(localGoal)
                }
            } else {
                sDao.deleteGoal(localGoal)
            }
        }

        try {
            val remoteGoals = SupabaseClientConfig.supabase.postgrest["savings_goals"]
                .select {
                    filter { eq("user_id", userId) }
                }
                .decodeList<SavingsGoalDto>()

            for (dto in remoteGoals) {
                val existing = sDao.getGoalByServerId(dto.id)
                if (dto.isDeleted) {
                    if (existing != null) sDao.deleteGoal(existing)
                } else {
                    val goalTitle = dto.title ?: dto.name ?: "Goal"
                    val targetDate = dto.targetDate?.let { parseIsoTimestamp(it) } ?: (System.currentTimeMillis() + 30L * 86400000)
                    val updatedMillis = parseIsoTimestamp(dto.updatedAt ?: dto.createdAt)

                    if (existing == null) {
                        val newEntity = SavingsGoalEntity(
                            title = goalTitle,
                            targetAmount = dto.targetAmount,
                            currentAmount = dto.currentAmount,
                            targetDateMillis = targetDate,
                            iconName = dto.iconName ?: "Savings",
                            colorHex = dto.colorHex ?: "#059669",
                            financeScope = FinanceScope.PERSONAL,
                            familyId = null,
                            serverId = dto.id,
                            syncStatus = "SYNCED",
                            updatedAt = updatedMillis,
                            isDeleted = false
                        )
                        sDao.insertOrUpdateGoal(newEntity)
                    } else if (existing.syncStatus == "SYNCED" && updatedMillis > existing.updatedAt) {
                        val updated = existing.copy(
                            title = goalTitle,
                            targetAmount = dto.targetAmount,
                            currentAmount = dto.currentAmount,
                            targetDateMillis = targetDate,
                            iconName = dto.iconName ?: existing.iconName,
                            colorHex = dto.colorHex ?: existing.colorHex,
                            syncStatus = "SYNCED",
                            updatedAt = updatedMillis
                        )
                        sDao.updateGoal(updated)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to pull remote savings goals: ${e.message}")
            success = false
        }
        return success
    }

    // =============================================================================
    // PERSONAL AUTO-PAY REMINDERS SYNC
    // =============================================================================
    private suspend fun syncPersonalAutoPayReminders(userId: String): Boolean {
        val aDao = autoPayReminderDao ?: return true
        var success = true

        val pendingCreates = aDao.getPendingCreates()
        for (local in pendingCreates) {
            val serverId = local.serverId?.takeIf { it.isNotBlank() && isValidUuid(it) } ?: UUID.randomUUID().toString()
            val dto = AutoPayReminderDto(
                id = serverId,
                userId = userId,
                financeScope = "PERSONAL",
                title = local.title,
                amount = local.amount,
                category = local.category,
                paymentMethod = local.paymentMethod,
                frequency = local.frequency,
                dueDayOfMonth = local.dueDayOfMonth,
                nextDueDate = IsoDateUtils.toIsoString(local.nextDueDateMillis),
                isAutoDebit = local.isAutoDebit,
                reminderDaysBefore = local.reminderDaysBefore,
                isPaused = local.isPaused,
                lastPaidDate = local.lastPaidDateMillis?.let { IsoDateUtils.toIsoString(it) },
                notes = local.notes,
                isDeleted = false,
                updatedAt = IsoDateUtils.toIsoString(local.updatedAt)
            )
            try {
                SupabaseClientConfig.supabase.postgrest["auto_pay_reminders"].upsert(dto)
                aDao.updateReminder(
                    local.copy(serverId = serverId, syncStatus = "SYNCED", updatedAt = System.currentTimeMillis())
                )
            } catch (e: Exception) {
                Log.e(tag, "Failed to push auto-pay create: ${e.message}")
                success = false
            }
        }

        val pendingUpdates = aDao.getPendingUpdates()
        for (local in pendingUpdates) {
            val serverId = local.serverId?.takeIf { it.isNotBlank() && isValidUuid(it) } ?: UUID.randomUUID().toString()
            val dto = AutoPayReminderDto(
                id = serverId,
                userId = userId,
                financeScope = "PERSONAL",
                title = local.title,
                amount = local.amount,
                category = local.category,
                paymentMethod = local.paymentMethod,
                frequency = local.frequency,
                dueDayOfMonth = local.dueDayOfMonth,
                nextDueDate = IsoDateUtils.toIsoString(local.nextDueDateMillis),
                isAutoDebit = local.isAutoDebit,
                reminderDaysBefore = local.reminderDaysBefore,
                isPaused = local.isPaused,
                lastPaidDate = local.lastPaidDateMillis?.let { IsoDateUtils.toIsoString(it) },
                notes = local.notes,
                isDeleted = false,
                updatedAt = IsoDateUtils.toIsoString(local.updatedAt)
            )
            try {
                SupabaseClientConfig.supabase.postgrest["auto_pay_reminders"].upsert(dto)
                aDao.updateReminder(
                    local.copy(serverId = serverId, syncStatus = "SYNCED", updatedAt = System.currentTimeMillis())
                )
            } catch (e: Exception) {
                Log.e(tag, "Failed to push auto-pay update: ${e.message}")
                success = false
            }
        }

        val pendingDeletes = aDao.getPendingDeletes()
        for (local in pendingDeletes) {
            val serverId = local.serverId
            if (serverId != null && isValidUuid(serverId)) {
                var cloudDeleted = false
                try {
                    SupabaseClientConfig.supabase.postgrest["auto_pay_reminders"].delete {
                        filter {
                            eq("id", serverId)
                            eq("user_id", userId)
                        }
                    }
                    cloudDeleted = true
                } catch (_: Exception) {
                    try {
                        SupabaseClientConfig.supabase.postgrest["auto_pay_reminders"].update({
                            set("is_deleted", true)
                            set("updated_at", IsoDateUtils.toIsoString(System.currentTimeMillis()))
                        }) {
                            filter {
                                eq("id", serverId)
                                eq("user_id", userId)
                            }
                        }
                        cloudDeleted = true
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to delete auto-pay reminder on cloud: ${e.message}")
                        success = false
                    }
                }
                if (cloudDeleted) {
                    aDao.deleteReminder(local)
                }
            } else {
                aDao.deleteReminder(local)
            }
        }

        try {
            val remoteReminders = SupabaseClientConfig.supabase.postgrest["auto_pay_reminders"]
                .select {
                    filter { eq("user_id", userId) }
                }
                .decodeList<AutoPayReminderDto>()

            for (dto in remoteReminders) {
                val existing = aDao.getReminderByServerId(dto.id)
                if (dto.isDeleted) {
                    if (existing != null) aDao.deleteReminder(existing)
                } else {
                    val updatedMillis = parseIsoTimestamp(dto.updatedAt ?: dto.createdAt)
                    val nextDueMillis = dto.nextDueDate?.let { parseIsoTimestamp(it) } ?: System.currentTimeMillis()
                    val lastPaidMillis = dto.lastPaidDate?.let { parseIsoTimestamp(it) }

                    if (existing == null) {
                        val newEntity = AutoPayReminderEntity(
                            title = dto.title,
                            amount = dto.amount,
                            category = dto.category ?: "Bills & Utilities",
                            paymentMethod = dto.paymentMethod ?: "UPI",
                            frequency = dto.frequency ?: "MONTHLY",
                            dueDayOfMonth = dto.dueDayOfMonth,
                            nextDueDateMillis = nextDueMillis,
                            isAutoDebit = dto.isAutoDebit,
                            reminderDaysBefore = dto.reminderDaysBefore,
                            financeScope = FinanceScope.PERSONAL,
                            familyId = null,
                            isPaused = dto.isPaused,
                            lastPaidDateMillis = lastPaidMillis,
                            notes = dto.notes ?: "",
                            serverId = dto.id,
                            syncStatus = "SYNCED",
                            updatedAt = updatedMillis,
                            isDeleted = false
                        )
                        aDao.insertReminder(newEntity)
                    } else if (existing.syncStatus == "SYNCED" && updatedMillis > existing.updatedAt) {
                        aDao.updateReminder(
                            existing.copy(
                                title = dto.title,
                                amount = dto.amount,
                                category = dto.category ?: existing.category,
                                paymentMethod = dto.paymentMethod ?: existing.paymentMethod,
                                frequency = dto.frequency ?: existing.frequency,
                                dueDayOfMonth = dto.dueDayOfMonth,
                                nextDueDateMillis = nextDueMillis,
                                isAutoDebit = dto.isAutoDebit,
                                reminderDaysBefore = dto.reminderDaysBefore,
                                isPaused = dto.isPaused,
                                lastPaidDateMillis = lastPaidMillis,
                                notes = dto.notes ?: existing.notes,
                                syncStatus = "SYNCED",
                                updatedAt = updatedMillis
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to pull remote auto-pay reminders: ${e.message}")
            success = false
        }
        return success
    }

    suspend fun clearCloudPersonalLedger(explicitUserId: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext true
        val userId = explicitUserId
            ?: authService.currentUser.value?.id
            ?: try { SupabaseClientConfig.supabase.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
        if (userId.isNullOrBlank() || !isValidUuid(userId)) {
            return@withContext true
        }
        syncMutex.withLock {
            return@withLock try {
                SupabaseClientConfig.supabase.postgrest.rpc("clear_my_personal_ledger")
                true
            } catch (_: Exception) {
                var ok = true
                for (table in listOf("transactions", "budgets", "savings_goals", "auto_pay_reminders")) {
                    try {
                        SupabaseClientConfig.supabase.postgrest[table].delete {
                            filter { eq("user_id", userId) }
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "Direct delete failed on $table, retrying soft-delete: ${e.message}")
                        try {
                            SupabaseClientConfig.supabase.postgrest[table].update({
                                set("is_deleted", true)
                                set("updated_at", IsoDateUtils.toIsoString(System.currentTimeMillis()))
                            }) {
                                filter { eq("user_id", userId) }
                            }
                        } catch (e2: Exception) {
                            Log.e(tag, "Cloud clear failed on $table: ${e2.message}")
                            ok = false
                        }
                    }
                }
                ok
            }
        }
    }

    private fun parseIsoTimestamp(isoString: String?): Long {
        return IsoDateUtils.fromIsoString(isoString)
    }
}
