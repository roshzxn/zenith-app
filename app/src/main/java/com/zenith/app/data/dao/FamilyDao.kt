package com.zenith.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zenith.app.data.models.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FamilyDao {

    // --- Families & Members ---
    @Query(
        """
        SELECT f.* FROM families f
        INNER JOIN family_members fm ON fm.familyId = f.id
        WHERE fm.userId = :userId AND fm.status = 'ACTIVE' AND f.isDeleted = 0
        LIMIT 1
        """
    )
    suspend fun getActiveFamilyForUserOnce(userId: String): FamilyEntity?

    @Query("SELECT * FROM families WHERE id = :familyId AND isDeleted = 0 LIMIT 1")
    suspend fun getFamilyByIdOnce(familyId: String): FamilyEntity?

    @Query("SELECT * FROM family_members WHERE familyId = :familyId AND status = 'ACTIVE' ORDER BY joinedAt ASC")
    fun observeActiveMembers(familyId: String): Flow<List<FamilyMemberEntity>>

    @Query("SELECT * FROM family_members WHERE familyId = :familyId AND status = 'ACTIVE' ORDER BY joinedAt ASC")
    suspend fun getActiveMembersOnce(familyId: String): List<FamilyMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFamily(family: FamilyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(members: List<FamilyMemberEntity>)

    @Query("DELETE FROM family_members WHERE familyId = :familyId")
    suspend fun clearMembersForFamily(familyId: String)

    // --- Family Categories ---
    @Query("SELECT * FROM family_categories WHERE familyId = :familyId AND isDeleted = 0 ORDER BY name ASC")
    fun observeFamilyCategories(familyId: String): Flow<List<FamilyCategoryEntity>>

    @Query("SELECT * FROM family_categories WHERE familyId = :familyId AND isDeleted = 0")
    suspend fun getFamilyCategoriesOnce(familyId: String): List<FamilyCategoryEntity>

    @Query("SELECT * FROM family_categories WHERE familyId = :familyId")
    suspend fun getAllFamilyCategoriesForSync(familyId: String): List<FamilyCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(categories: List<FamilyCategoryEntity>)

    // --- Family Transactions ---
    @Query("SELECT * FROM family_transactions WHERE familyId = :familyId AND isDeleted = 0 ORDER BY transactionDateMillis DESC")
    fun observeFamilyTransactions(familyId: String): Flow<List<FamilyTransactionEntity>>

    @Query("SELECT * FROM family_transactions WHERE familyId = :familyId AND isDeleted = 0 ORDER BY transactionDateMillis DESC")
    suspend fun getFamilyTransactionsOnce(familyId: String): List<FamilyTransactionEntity>

    @Query("SELECT * FROM family_transactions WHERE id = :id LIMIT 1")
    suspend fun getFamilyTransactionById(id: String): FamilyTransactionEntity?

    @Query("SELECT * FROM family_transactions WHERE clientTxId = :clientTxId LIMIT 1")
    suspend fun getFamilyTransactionByClientId(clientTxId: String): FamilyTransactionEntity?

    @Query("SELECT * FROM family_transactions WHERE familyId = :familyId AND syncStatus IN ('PENDING_CREATE', 'PENDING_UPDATE', 'PENDING_DELETE', 'PENDING_CREATE_DELETED', 'FAILED')")
    suspend fun getPendingSyncTransactions(familyId: String): List<FamilyTransactionEntity>

    @Query("SELECT id FROM family_transactions WHERE familyId = :familyId AND syncStatus = 'SYNCED'")
    suspend fun getSyncedTransactionIds(familyId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFamilyTransaction(tx: FamilyTransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFamilyTransactions(txs: List<FamilyTransactionEntity>)

    @Query("UPDATE family_transactions SET isDeleted = 1, syncStatus = CASE WHEN syncStatus = 'PENDING_CREATE' THEN 'PENDING_CREATE_DELETED' ELSE 'PENDING_DELETE' END, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markFamilyTransactionDeleted(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM family_transactions WHERE id = :id")
    suspend fun hardDeleteFamilyTransaction(id: String)

    // --- Family Budgets ---
    @Query("SELECT * FROM family_budgets WHERE familyId = :familyId AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeFamilyBudgets(familyId: String): Flow<List<FamilyBudgetEntity>>

    @Query("SELECT * FROM family_budgets WHERE familyId = :familyId AND syncStatus = 'SYNCED'")
    suspend fun observeFamilyBudgetsOnceForSync(familyId: String): List<FamilyBudgetEntity>

    @Query("SELECT * FROM family_budgets WHERE id = :id LIMIT 1")
    suspend fun getFamilyBudgetById(id: String): FamilyBudgetEntity?

    @Query("SELECT * FROM family_budgets WHERE familyId = :familyId AND syncStatus IN ('PENDING_CREATE', 'PENDING_UPDATE', 'PENDING_DELETE', 'FAILED')")
    suspend fun getPendingSyncBudgets(familyId: String): List<FamilyBudgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFamilyBudget(budget: FamilyBudgetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFamilyBudgets(budgets: List<FamilyBudgetEntity>)

    @Query("UPDATE family_budgets SET isDeleted = 1, syncStatus = 'PENDING_DELETE', updatedAt = :updatedAt WHERE id = :id")
    suspend fun markFamilyBudgetDeleted(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM family_budgets WHERE id = :id")
    suspend fun hardDeleteFamilyBudget(id: String)

    // --- Family Chat Messages ---
    @Query("SELECT * FROM family_messages WHERE familyId = :familyId AND isDeleted = 0 ORDER BY createdAtMillis ASC")
    fun observeFamilyMessages(familyId: String): Flow<List<FamilyMessageEntity>>

    @Query("SELECT * FROM family_messages WHERE familyId = :familyId AND syncStatus = 'SYNCED'")
    suspend fun observeFamilyMessagesOnceForSync(familyId: String): List<FamilyMessageEntity>

    @Query("SELECT * FROM family_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): FamilyMessageEntity?

    @Query("SELECT * FROM family_messages WHERE familyId = :familyId AND syncStatus IN ('PENDING_CREATE', 'PENDING_DELETE', 'FAILED')")
    suspend fun getPendingSyncMessages(familyId: String): List<FamilyMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: FamilyMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<FamilyMessageEntity>)

    @Query("UPDATE family_messages SET isDeleted = 1, syncStatus = 'PENDING_DELETE', updatedAtMillis = :updatedAt WHERE id = :id")
    suspend fun markMessageDeleted(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM family_messages WHERE id = :id")
    suspend fun hardDeleteMessage(id: String)

    // --- Wipe Helpers ---
    @Query("DELETE FROM family_messages WHERE familyId = :familyId")
    suspend fun clearMessagesForFamily(familyId: String)

    @Query("DELETE FROM family_transactions WHERE familyId = :familyId")
    suspend fun clearTransactionsForFamily(familyId: String)

    @Query("DELETE FROM family_transactions WHERE familyId = :familyId AND createdBy = :userId")
    suspend fun clearOwnTransactionsForFamily(familyId: String, userId: String)

    @Query("DELETE FROM family_transactions WHERE familyId = :familyId AND syncStatus = 'PENDING_CREATE'")
    suspend fun deletePendingCreateTransactionsForFamily(familyId: String)

    @Query("DELETE FROM family_transactions WHERE familyId = :familyId AND createdBy = :userId AND syncStatus = 'PENDING_CREATE'")
    suspend fun deleteOwnPendingCreateTransactionsForFamily(familyId: String, userId: String)

    @Query("UPDATE family_transactions SET isDeleted = 1, syncStatus = 'PENDING_DELETE', updatedAt = :updatedAt WHERE familyId = :familyId AND syncStatus != 'PENDING_CREATE'")
    suspend fun markAllSyncedTransactionsPendingDelete(familyId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE family_transactions SET isDeleted = 1, syncStatus = 'PENDING_DELETE', updatedAt = :updatedAt WHERE familyId = :familyId AND createdBy = :userId AND syncStatus != 'PENDING_CREATE'")
    suspend fun markOwnSyncedTransactionsPendingDelete(familyId: String, userId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM family_budgets WHERE familyId = :familyId")
    suspend fun clearBudgetsForFamily(familyId: String)

    @Query("DELETE FROM family_categories WHERE familyId = :familyId")
    suspend fun clearCategoriesForFamily(familyId: String)

    @Query("DELETE FROM family_messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM family_budgets")
    suspend fun deleteAllFamilyBudgets()

    @Query("DELETE FROM family_transactions")
    suspend fun deleteAllFamilyTransactions()

    @Query("DELETE FROM family_categories")
    suspend fun deleteAllFamilyCategories()

    @Query("DELETE FROM family_members")
    suspend fun deleteAllFamilyMembers()

    @Query("DELETE FROM families")
    suspend fun deleteAllFamilies()
}
