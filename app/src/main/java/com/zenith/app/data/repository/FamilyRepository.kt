package com.zenith.app.data.repository

import android.util.Log
import com.zenith.app.data.dao.FamilyDao
import com.zenith.app.data.models.*
import com.zenith.app.data.network.ActiveFamilyBundle
import com.zenith.app.data.network.FamilyBudgetDto
import com.zenith.app.data.network.FamilyCategoryDto
import com.zenith.app.data.network.FamilyMessageDto
import com.zenith.app.data.network.FamilySupabaseClient
import com.zenith.app.data.network.FamilyTransactionDto
import com.zenith.app.data.network.SupabaseClientConfig
import com.zenith.app.data.network.epochMillisToIso
import com.zenith.app.data.network.parseIsoTimestampMillis
import com.zenith.app.domain.FamilyMemberInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class FamilySyncResult(
    val syncedOk: Boolean,
    val pushedTransactions: Int = 0,
    val pushedBudgets: Int = 0,
    val pushedMessages: Int = 0,
    val pulledTransactions: Int = 0,
    val createdTxIds: List<String> = emptyList(),
    val updatedTxIds: List<String> = emptyList(),
    val deletedTxIds: List<String> = emptyList(),
    val changedBudget: Boolean = false,
    val createdMessageIds: List<String> = emptyList(),
    val deletedMessageIds: List<String> = emptyList(),
    val errorMessage: String? = null
)

/**
 * Isolated Room + Supabase synchronization repository for Family Mode.
 * Zero reads/writes touch the Personal tables (`transactions`, `budgets`, `savings_goals`, etc.).
 * Pending offline mutations retain PENDING_* status until Supabase acknowledges writes.
 */
class FamilyRepository(private val familyDao: FamilyDao) {

    private val syncMutex = Mutex()
    private val tag = "FamilyRepository"

    fun observeActiveMembers(familyId: String): Flow<List<FamilyMemberEntity>> =
        familyDao.observeActiveMembers(familyId)

    fun observeFamilyCategories(familyId: String): Flow<List<FamilyCategoryEntity>> =
        familyDao.observeFamilyCategories(familyId)

    fun observeFamilyTransactions(familyId: String): Flow<List<FamilyTransactionEntity>> =
        familyDao.observeFamilyTransactions(familyId)

    fun observeFamilyBudgets(familyId: String): Flow<List<FamilyBudgetEntity>> =
        familyDao.observeFamilyBudgets(familyId)

    fun observeFamilyMessages(familyId: String): Flow<List<FamilyMessageEntity>> =
        familyDao.observeFamilyMessages(familyId)

    suspend fun getActiveFamilyForUserOnce(userId: String): FamilyEntity? =
        familyDao.getActiveFamilyForUserOnce(userId)

    suspend fun getActiveMembersOnce(familyId: String): List<FamilyMemberEntity> =
        familyDao.getActiveMembersOnce(familyId)

    suspend fun getTransactionById(id: String): FamilyTransactionEntity? =
        familyDao.getFamilyTransactionById(id)

    suspend fun getBudgetById(id: String): FamilyBudgetEntity? =
        familyDao.getFamilyBudgetById(id)

    suspend fun getMessageById(id: String): FamilyMessageEntity? =
        familyDao.getMessageById(id)

    suspend fun cacheActiveBundle(bundle: ActiveFamilyBundle) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        familyDao.upsertFamily(
            FamilyEntity(
                id = bundle.family.id,
                name = bundle.family.name,
                avatarEmoji = bundle.family.avatarEmoji,
                ownerId = bundle.family.ownerId.orEmpty(),
                monthlyBudgetLimit = bundle.family.monthlyBudgetLimit,
                syncStatus = "SYNCED",
                isDeleted = false,
                updatedAt = now,
                familyLabel = bundle.family.familyLabel
            )
        )
        familyDao.clearMembersForFamily(bundle.family.id)
        familyDao.upsertMembers(
            bundle.members.map { m ->
                FamilyMemberEntity(
                    id = m.id,
                    familyId = m.familyId,
                    userId = m.userId,
                    displayName = m.displayName,
                    avatarEmoji = m.avatarEmoji,
                    avatarColor = m.avatarColor,
                    role = m.role.name,
                    status = m.status,
                    joinedAt = now,
                    updatedAt = now,
                    memberLabel = m.memberLabel
                )
            }
        )
        if (familyDao.getFamilyCategoriesOnce(bundle.family.id).isEmpty()) {
            familyDao.upsertCategories(
                ScopedMappers.buildDefaultFamilyCategories(bundle.family.id, bundle.family.ownerId)
            )
        }
    }

    suspend fun addOrUpdateTransaction(entity: FamilyTransactionEntity) = withContext(Dispatchers.IO) {
        val existing = familyDao.getFamilyTransactionById(entity.id)
            ?: familyDao.getFamilyTransactionByClientId(entity.clientTxId)
        val preservedStatus = when {
            existing == null -> "PENDING_CREATE"
            existing.syncStatus == "PENDING_CREATE" -> "PENDING_CREATE"
            else -> "PENDING_UPDATE"
        }
        val toStore = if (existing != null && existing.id != entity.id) {
            entity.copy(
                id = existing.id,
                clientTxId = existing.clientTxId,
                createdBy = existing.createdBy,
                createdByName = existing.createdByName,
                syncStatus = preservedStatus,
                isDeleted = false,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            entity.copy(
                syncStatus = preservedStatus,
                isDeleted = false,
                updatedAt = System.currentTimeMillis()
            )
        }
        familyDao.upsertFamilyTransaction(toStore)
    }

    suspend fun softDeleteTransaction(id: String) = withContext(Dispatchers.IO) {
        val existing = familyDao.getFamilyTransactionById(id) ?: return@withContext
        val deletedStatus = if (existing.syncStatus == "PENDING_CREATE") {
            "PENDING_CREATE_DELETED"
        } else {
            "PENDING_DELETE"
        }
        familyDao.upsertFamilyTransaction(
            existing.copy(
                isDeleted = true,
                syncStatus = deletedStatus,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun restoreTransaction(id: String) = withContext(Dispatchers.IO) {
        val existing = familyDao.getFamilyTransactionById(id) ?: return@withContext
        val restoredStatus = if (existing.syncStatus == "PENDING_CREATE_DELETED") {
            "PENDING_CREATE"
        } else {
            "PENDING_UPDATE"
        }
        familyDao.upsertFamilyTransaction(
            existing.copy(
                isDeleted = false,
                syncStatus = restoredStatus,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun saveBudget(
        id: String,
        familyId: String,
        categoryId: String?,
        categoryName: String,
        monthlyLimit: Double,
        monthYear: String,
        periodType: String,
        updatedBy: String,
        customPeriodName: String = ""
    ) = withContext(Dispatchers.IO) {
        val existing = familyDao.getFamilyBudgetById(id)
        val status = if (existing == null || existing.syncStatus == "PENDING_CREATE") {
            "PENDING_CREATE"
        } else {
            "PENDING_UPDATE"
        }
        familyDao.upsertFamilyBudget(
            FamilyBudgetEntity(
                id = existing?.id ?: id,
                familyId = familyId,
                categoryId = categoryId,
                categoryName = categoryName,
                monthlyLimit = monthlyLimit,
                monthYear = monthYear,
                periodType = periodType,
                customPeriodName = customPeriodName.trim(),
                updatedBy = updatedBy,
                syncStatus = status,
                isDeleted = false,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun softDeleteBudget(id: String) = withContext(Dispatchers.IO) {
        familyDao.markFamilyBudgetDeleted(id)
    }

    suspend fun updateLocalMonthlyBudgetLimit(familyId: String, limit: Double) = withContext(Dispatchers.IO) {
        val existing = familyDao.getFamilyByIdOnce(familyId) ?: return@withContext
        familyDao.upsertFamily(
            existing.copy(
                monthlyBudgetLimit = limit.coerceAtLeast(0.0),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun addCategory(
        familyId: String,
        name: String,
        iconName: String,
        colorHex: String,
        type: TransactionType,
        createdBy: String?
    ) = withContext(Dispatchers.IO) {
        val id = UUID.nameUUIDFromBytes("famcat_${familyId}_${name.trim().lowercase()}_${type.name}".toByteArray()).toString()
        familyDao.upsertCategories(
            listOf(
                FamilyCategoryEntity(
                    id = id,
                    familyId = familyId,
                    name = name.trim(),
                    iconName = iconName,
                    colorHex = colorHex,
                    type = type,
                    isDefault = false,
                    createdBy = createdBy,
                    isDeleted = false,
                    syncStatus = "PENDING_CREATE",
                    updatedAt = System.currentTimeMillis()
                )
            )
        )
    }

    suspend fun enqueueMessage(message: FamilyMessageEntity) = withContext(Dispatchers.IO) {
        familyDao.upsertMessage(
            message.copy(
                syncStatus = "PENDING_CREATE",
                isDeleted = false,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun softDeleteMessage(id: String) = withContext(Dispatchers.IO) {
        familyDao.markMessageDeleted(id)
    }

    suspend fun syncFamilyWorkspace(
        familyId: String,
        members: List<FamilyMemberInfo> = emptyList()
    ): FamilySyncResult = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext FamilySyncResult(syncedOk = false, errorMessage = "Offline / cloud unconfigured")
        }
        syncMutex.withLock {
            syncFamilyWorkspaceLocked(familyId, members)
        }
    }

    private suspend fun syncFamilyWorkspaceLocked(
        familyId: String,
        members: List<FamilyMemberInfo>
    ): FamilySyncResult {
        val memberNames = members.associate { it.userId to it.displayName }
        val createdTxIds = mutableListOf<String>()
        val updatedTxIds = mutableListOf<String>()
        val deletedTxIds = mutableListOf<String>()
        var pushedBudgets = 0
        val createdMsgIds = mutableListOf<String>()
        val deletedMsgIds = mutableListOf<String>()
        var lastError: String? = null

        // 1. Push pending categories (including soft-deleted ones)
        val pendingCats = familyDao.getAllFamilyCategoriesForSync(familyId).filter { it.syncStatus != "SYNCED" }
        for (cat in pendingCats) {
            val dto = FamilyCategoryDto(
                id = cat.id,
                familyId = cat.familyId,
                name = cat.name,
                iconName = cat.iconName,
                colorHex = cat.colorHex,
                type = cat.type.name,
                isDefault = cat.isDefault,
                createdBy = cat.createdBy,
                isDeleted = cat.isDeleted,
                updatedAt = epochMillisToIso(cat.updatedAt)
            )
            FamilySupabaseClient.upsertFamilyCategory(dto).onSuccess {
                familyDao.upsertCategories(listOf(cat.copy(syncStatus = "SYNCED")))
            }.onFailure { lastError = it.message }
        }

        // 2. Push pending transactions
        val pendingTxs = familyDao.getPendingSyncTransactions(familyId)
        for (tx in pendingTxs) {
            if (tx.syncStatus == "PENDING_CREATE_DELETED") {
                familyDao.hardDeleteFamilyTransaction(tx.id)
                continue
            }
            if (tx.syncStatus == "PENDING_DELETE" || tx.isDeleted) {
                val res = FamilySupabaseClient.softDeleteFamilyTransaction(tx.id, familyId)
                if (res.isSuccess) {
                    familyDao.hardDeleteFamilyTransaction(tx.id)
                    deletedTxIds.add(tx.id)
                } else {
                    lastError = res.exceptionOrNull()?.message
                }
            } else {
                val dto = FamilyTransactionDto(
                    id = tx.id,
                    familyId = tx.familyId,
                    createdBy = tx.createdBy,
                    paidBy = tx.paidBy,
                    createdByName = tx.createdByName,
                    paidByName = tx.paidByName,
                    transactionType = tx.transactionType.name,
                    amount = tx.amount,
                    categoryId = tx.categoryId,
                    categoryName = tx.categoryName,
                    title = tx.title,
                    description = tx.description,
                    customLabel = tx.customLabel,
                    paymentMethod = tx.paymentMethod,
                    upiId = tx.upiId,
                    upiTransactionId = tx.upiTransactionId,
                    transactionDate = epochMillisToIso(tx.transactionDateMillis),
                    clientTxId = tx.clientTxId,
                    syncVersion = tx.syncVersion,
                    isDeleted = false,
                    createdAt = epochMillisToIso(tx.createdAt),
                    updatedAt = epochMillisToIso(tx.updatedAt)
                )
                val wasCreate = tx.syncStatus == "PENDING_CREATE"
                val remoteResult = if (wasCreate) {
                    FamilySupabaseClient.upsertFamilyTransaction(dto)
                } else {
                    FamilySupabaseClient.updateFamilyTransaction(dto)
                }
                remoteResult.onSuccess {
                    familyDao.upsertFamilyTransaction(
                        tx.copy(syncStatus = "SYNCED", updatedAt = System.currentTimeMillis())
                    )
                    if (wasCreate) createdTxIds.add(tx.id) else updatedTxIds.add(tx.id)
                }.onFailure {
                    lastError = it.message
                    Log.w(tag, "Pending tx sync deferred (${tx.id}): $lastError")
                }
            }
        }

        // 3. Push pending budgets
        val pendingBudgets = familyDao.getPendingSyncBudgets(familyId)
        for (budget in pendingBudgets) {
            if (budget.syncStatus == "PENDING_DELETE" || budget.isDeleted) {
                FamilySupabaseClient.softDeleteFamilyBudget(budget.id, familyId).onSuccess {
                    familyDao.hardDeleteFamilyBudget(budget.id)
                    pushedBudgets++
                }.onFailure { lastError = it.message }
            } else {
                val dto = FamilyBudgetDto(
                    id = budget.id,
                    familyId = budget.familyId,
                    categoryId = budget.categoryId,
                    categoryName = budget.categoryName,
                    monthlyLimit = budget.monthlyLimit,
                    monthYear = budget.monthYear,
                    periodType = budget.periodType,
                    customPeriodName = budget.customPeriodName,
                    updatedBy = budget.updatedBy,
                    isDeleted = false,
                    updatedAt = epochMillisToIso(budget.updatedAt)
                )
                FamilySupabaseClient.upsertFamilyBudget(dto).onSuccess {
                    familyDao.upsertFamilyBudget(
                        budget.copy(syncStatus = "SYNCED", updatedAt = System.currentTimeMillis())
                    )
                    pushedBudgets++
                }.onFailure { lastError = it.message }
            }
        }

        // 4. Push pending chat messages
        val pendingMessages = familyDao.getPendingSyncMessages(familyId)
        for (msg in pendingMessages) {
            if (msg.syncStatus == "PENDING_DELETE" || msg.isDeleted) {
                FamilySupabaseClient.softDeleteFamilyMessage(msg.id, familyId).onSuccess {
                    familyDao.hardDeleteMessage(msg.id)
                    deletedMsgIds.add(msg.id)
                }.onFailure { lastError = it.message }
            } else {
                val dto = FamilyMessageDto(
                    id = msg.id,
                    familyId = msg.familyId,
                    senderUserId = msg.senderUserId,
                    senderName = msg.senderName,
                    senderAvatar = msg.senderAvatar,
                    messageType = msg.messageType,
                    body = msg.body,
                    relatedTransactionId = msg.relatedTransactionId,
                    replyToMessageId = msg.replyToMessageId,
                    clientMsgId = msg.clientMsgId,
                    isDeleted = false,
                    createdAt = epochMillisToIso(msg.createdAtMillis),
                    updatedAt = epochMillisToIso(msg.updatedAtMillis)
                )
                FamilySupabaseClient.upsertFamilyMessage(dto).onSuccess {
                    familyDao.upsertMessage(
                        msg.copy(syncStatus = "SYNCED", updatedAtMillis = System.currentTimeMillis())
                    )
                    createdMsgIds.add(msg.id)
                }.onFailure { lastError = it.message }
            }
        }

        // 5. Pull & Reconcile Remote Categories (only upsert changed rows to avoid unnecessary Room emissions)
        FamilySupabaseClient.fetchFamilyCategories(familyId).onSuccess { remoteCats ->
            val existingCats = familyDao.getFamilyCategoriesOnce(familyId)
            val existingById = existingCats.associateBy { it.id }
            val pendingCatIds = existingCats
                .filter { it.syncStatus != "SYNCED" }
                .map { it.id }
                .toSet()
            val mapped = remoteCats.filter { it.id !in pendingCatIds }.map { dto ->
                FamilyCategoryEntity(
                    id = dto.id,
                    familyId = dto.familyId,
                    name = dto.name,
                    iconName = dto.iconName,
                    colorHex = dto.colorHex,
                    type = runCatching { TransactionType.valueOf(dto.type) }.getOrDefault(TransactionType.EXPENSE),
                    isDefault = dto.isDefault,
                    createdBy = dto.createdBy,
                    isDeleted = dto.isDeleted,
                    syncStatus = "SYNCED",
                    updatedAt = parseIsoTimestampMillis(dto.updatedAt)
                )
            }.filter { incoming -> existingById[incoming.id] != incoming }
            if (mapped.isNotEmpty()) {
                familyDao.upsertCategories(mapped)
            }
        }.onFailure { lastError = it.message }

        // 6. Pull & Reconcile Remote Transactions (preserving offline pending edits)
        var pulledTxCount = 0
        FamilySupabaseClient.fetchFamilyTransactions(familyId).onSuccess { remoteTxs ->
            pulledTxCount = remoteTxs.size
            reconcilePulledTransactions(familyId, remoteTxs, memberNames)
        }.onFailure { lastError = it.message }

        // 7. Pull & Reconcile Remote Budgets
        FamilySupabaseClient.fetchFamilyBudgets(familyId).onSuccess { remoteBudgets ->
            reconcilePulledBudgets(familyId, remoteBudgets)
        }.onFailure { lastError = it.message }

        // 8. Pull & Reconcile Remote Messages
        FamilySupabaseClient.fetchFamilyMessages(familyId).onSuccess { remoteMsgs ->
            reconcilePulledMessages(familyId, remoteMsgs)
        }.onFailure { lastError = it.message }

        return FamilySyncResult(
            syncedOk = lastError == null,
            pushedTransactions = createdTxIds.size + updatedTxIds.size + deletedTxIds.size,
            pushedBudgets = pushedBudgets,
            pushedMessages = createdMsgIds.size + deletedMsgIds.size,
            pulledTransactions = pulledTxCount,
            createdTxIds = createdTxIds,
            updatedTxIds = updatedTxIds,
            deletedTxIds = deletedTxIds,
            changedBudget = pushedBudgets > 0,
            createdMessageIds = createdMsgIds,
            deletedMessageIds = deletedMsgIds,
            errorMessage = lastError
        )
    }

    internal suspend fun reconcilePulledTransactions(
        familyId: String,
        remoteTxs: List<FamilyTransactionDto>,
        memberNames: Map<String, String> = emptyMap()
    ) {
        val pendingIds = familyDao.getPendingSyncTransactions(familyId).map { it.id }.toSet()
        val remoteIds = remoteTxs.map { it.id }.toSet()
        val existingSyncedIds = familyDao.getSyncedTransactionIds(familyId)

        for (localSyncedId in existingSyncedIds) {
            if (localSyncedId !in remoteIds) {
                familyDao.hardDeleteFamilyTransaction(localSyncedId)
            }
        }

        val existingById = familyDao.getFamilyTransactionsOnce(familyId).associateBy { it.id }
        val toUpsert = remoteTxs.filter { it.id !in pendingIds }.map { dto ->
            val resolvedCreator = dto.createdBy.orEmpty()
            val resolvedPaidBy = dto.paidBy ?: dto.createdBy
            FamilyTransactionEntity(
                id = dto.id,
                familyId = dto.familyId,
                createdBy = resolvedCreator,
                paidBy = resolvedPaidBy,
                createdByName = dto.createdByName.ifBlank { memberNames[resolvedCreator] ?: "Member" },
                paidByName = dto.paidByName.ifBlank { memberNames[resolvedPaidBy.orEmpty()] ?: dto.createdByName.ifBlank { "Member" } },
                transactionType = runCatching { TransactionType.valueOf(dto.transactionType) }.getOrDefault(TransactionType.EXPENSE),
                amount = dto.amount,
                categoryId = dto.categoryId,
                categoryName = dto.categoryName.ifBlank { "Other" },
                title = dto.title.ifBlank { "Transaction" },
                description = dto.description,
                customLabel = dto.customLabel,
                paymentMethod = dto.paymentMethod.ifBlank { "UPI" },
                upiId = dto.upiId,
                upiTransactionId = dto.upiTransactionId,
                transactionDateMillis = parseIsoTimestampMillis(dto.transactionDate),
                clientTxId = dto.clientTxId,
                syncVersion = dto.syncVersion,
                syncStatus = "SYNCED",
                isDeleted = dto.isDeleted,
                createdAt = parseIsoTimestampMillis(dto.createdAt),
                updatedAt = parseIsoTimestampMillis(dto.updatedAt ?: dto.createdAt, 0L)
            )
        }.filter { incoming -> existingById[incoming.id] != incoming }
        if (toUpsert.isNotEmpty()) {
            familyDao.upsertFamilyTransactions(toUpsert)
        }
    }

    internal suspend fun reconcilePulledBudgets(
        familyId: String,
        remoteBudgets: List<FamilyBudgetDto>
    ) {
        val pendingIds = familyDao.getPendingSyncBudgets(familyId).map { it.id }.toSet()
        val remoteIds = remoteBudgets.map { it.id }.toSet()
        val currentBudgets = familyDao.observeFamilyBudgetsOnceForSync(familyId)
        for (local in currentBudgets) {
            if (local.syncStatus == "SYNCED" && local.id !in remoteIds) {
                familyDao.hardDeleteFamilyBudget(local.id)
            }
        }
        val existingBudgetsById = currentBudgets.associateBy { it.id }
        val toUpsert = remoteBudgets.filter { it.id !in pendingIds }.map { dto ->
            FamilyBudgetEntity(
                id = dto.id,
                familyId = dto.familyId,
                categoryId = dto.categoryId,
                categoryName = dto.categoryName.ifBlank { "Overall" },
                monthlyLimit = dto.monthlyLimit,
                monthYear = dto.monthYear.ifBlank { "2026-07" },
                periodType = dto.periodType.ifBlank { "MONTHLY" },
                customPeriodName = dto.customPeriodName,
                updatedBy = dto.updatedBy,
                syncStatus = "SYNCED",
                isDeleted = dto.isDeleted,
                updatedAt = parseIsoTimestampMillis(dto.updatedAt)
            )
        }.filter { incoming -> existingBudgetsById[incoming.id] != incoming }
        if (toUpsert.isNotEmpty()) {
            familyDao.upsertFamilyBudgets(toUpsert)
        }
    }

    internal suspend fun reconcilePulledMessages(
        familyId: String,
        remoteMsgs: List<FamilyMessageDto>
    ) {
        val pendingIds = familyDao.getPendingSyncMessages(familyId).map { it.id }.toSet()
        val remoteIds = remoteMsgs.map { it.id }.toSet()
        val currentMsgs = familyDao.observeFamilyMessagesOnceForSync(familyId)
        for (local in currentMsgs) {
            if (local.syncStatus == "SYNCED" && local.id !in remoteIds) {
                familyDao.hardDeleteMessage(local.id)
            }
        }
        val existingMsgsById = currentMsgs.associateBy { it.id }
        val toUpsert = remoteMsgs.filter { it.id !in pendingIds }.map { dto ->
            FamilyMessageEntity(
                id = dto.id,
                familyId = dto.familyId,
                senderUserId = dto.senderUserId.orEmpty(),
                senderName = dto.senderName.ifBlank { "Member" },
                senderAvatar = dto.senderAvatar.ifBlank { "🙂" },
                messageType = dto.messageType.ifBlank { "TEXT" },
                body = dto.body,
                relatedTransactionId = dto.relatedTransactionId,
                replyToMessageId = dto.replyToMessageId,
                clientMsgId = dto.clientMsgId,
                syncStatus = "SYNCED",
                isDeleted = dto.isDeleted,
                createdAtMillis = parseIsoTimestampMillis(dto.createdAt),
                updatedAtMillis = parseIsoTimestampMillis(dto.updatedAt)
            )
        }.filter { incoming -> existingMsgsById[incoming.id] != incoming }
        if (toUpsert.isNotEmpty()) {
            familyDao.upsertMessages(toUpsert)
        }
    }

    suspend fun wipeAllFamilyData() = withContext(Dispatchers.IO) {
        familyDao.deleteAllMessages()
        familyDao.deleteAllFamilyBudgets()
        familyDao.deleteAllFamilyTransactions()
        familyDao.deleteAllFamilyCategories()
        familyDao.deleteAllFamilyMembers()
        familyDao.deleteAllFamilies()
    }

    /**
     * Clears Family transactions cleanly across Supabase DB and Room.
     * When offline or cloud delete fails, retains PENDING_DELETE tombstones so they
     * never resurrect when sync runs later.
     */
    suspend fun clearFamilyTransactionsEverywhere(
        familyId: String,
        userId: String,
        isAdmin: Boolean,
        alsoClearCloud: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        if (alsoClearCloud && SupabaseClientConfig.isConfigured) {
            val cloudRes = FamilySupabaseClient.clearFamilyTransactions(familyId, userId, isAdmin)
            if (cloudRes.isSuccess) {
                if (isAdmin) {
                    familyDao.clearTransactionsForFamily(familyId)
                } else {
                    familyDao.clearOwnTransactionsForFamily(familyId, userId)
                }
                return@withContext true
            }
        }
        if (!alsoClearCloud) {
            if (isAdmin) {
                familyDao.clearTransactionsForFamily(familyId)
            } else {
                familyDao.clearOwnTransactionsForFamily(familyId, userId)
            }
            return@withContext true
        }
        if (isAdmin) {
            familyDao.deletePendingCreateTransactionsForFamily(familyId)
            familyDao.markAllSyncedTransactionsPendingDelete(familyId)
        } else {
            familyDao.deleteOwnPendingCreateTransactionsForFamily(familyId, userId)
            familyDao.markOwnSyncedTransactionsPendingDelete(familyId, userId)
        }
        false
    }
}
