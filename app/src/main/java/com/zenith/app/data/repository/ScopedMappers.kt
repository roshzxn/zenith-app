package com.zenith.app.data.repository

import com.zenith.app.data.models.*
import com.zenith.app.data.util.CategoryIconHelper
import java.util.UUID
import kotlin.math.abs

data class FamilyTxAttribution(
    val serverId: String,
    val familyId: String,
    val createdByUserId: String,
    val createdByName: String,
    val paidByUserId: String?,
    val paidByName: String,
    val clientTxId: String,
    val createdByLabel: String = "",
    val paidByLabel: String = "",
    val customLabel: String = ""
)

/**
 * Pure deterministic projections mapping isolated Family entities onto the unified UI
 * models (`TransactionEntity`, `CategoryEntity`, `BudgetEntity`) so Personal screens,
 * charts, search, filters, and export reuse identical layouts with zero table leakage.
 */
object ScopedMappers {

    fun stableLongId(uuid: String): Long {
        val raw = runCatching {
            val u = UUID.fromString(uuid)
            u.mostSignificantBits xor u.leastSignificantBits
        }.getOrElse { uuid.hashCode().toLong() }
        val nonZero = abs(raw)
        return if (nonZero == 0L) 1L else nonZero
    }

    fun toUiTransaction(entity: FamilyTransactionEntity): TransactionEntity {
        return TransactionEntity(
            id = stableLongId(entity.id),
            title = entity.title,
            amount = entity.amount,
            type = entity.transactionType,
            category = entity.categoryName,
            categoryIconName = CategoryIconHelper.iconNameForCategory(entity.categoryName),
            note = entity.description,
            paymentMethod = entity.paymentMethod,
            dateMillis = entity.transactionDateMillis,
            upiId = entity.upiId,
            upiTransactionId = entity.upiTransactionId,
            financeScope = FinanceScope.FAMILY,
            familyId = entity.familyId,
            createdByUserId = entity.createdBy,
            serverId = entity.id,
            syncStatus = entity.syncStatus,
            updatedAt = entity.updatedAt,
            isDeleted = entity.isDeleted,
            customLabel = entity.customLabel
        )
    }

    fun toAttribution(
        entity: FamilyTransactionEntity,
        memberLabelsByUserId: Map<String, String> = emptyMap()
    ): FamilyTxAttribution {
        return FamilyTxAttribution(
            serverId = entity.id,
            familyId = entity.familyId,
            createdByUserId = entity.createdBy,
            createdByName = entity.createdByName,
            paidByUserId = entity.paidBy,
            paidByName = entity.paidByName,
            clientTxId = entity.clientTxId,
            createdByLabel = memberLabelsByUserId[entity.createdBy].orEmpty(),
            paidByLabel = entity.paidBy?.let { memberLabelsByUserId[it] }.orEmpty(),
            customLabel = entity.customLabel
        )
    }

    fun toUiCategory(entity: FamilyCategoryEntity): CategoryEntity {
        return CategoryEntity(
            id = stableLongId(entity.id),
            name = entity.name,
            iconName = entity.iconName,
            colorHex = entity.colorHex,
            type = entity.type,
            isDefault = entity.isDefault,
            serverId = entity.id,
            syncStatus = entity.syncStatus,
            updatedAt = entity.updatedAt,
            isDeleted = entity.isDeleted
        )
    }

    fun toUiBudget(entity: FamilyBudgetEntity): BudgetEntity {
        return BudgetEntity(
            id = stableLongId(entity.id),
            categoryName = entity.categoryName,
            monthlyLimit = entity.monthlyLimit,
            monthYear = entity.monthYear,
            periodType = entity.periodType,
            customPeriodName = "",
            financeScope = FinanceScope.FAMILY,
            familyId = entity.familyId,
            serverId = entity.id,
            syncStatus = entity.syncStatus,
            updatedAt = entity.updatedAt,
            isDeleted = entity.isDeleted
        )
    }

    fun buildDefaultFamilyCategories(familyId: String, creatorUserId: String?): List<FamilyCategoryEntity> {
        val defaults = listOf(
            Triple("Food & Dining", "Restaurant", "#F59E0B") to TransactionType.EXPENSE,
            Triple("Groceries", "ShoppingCart", "#10B981") to TransactionType.EXPENSE,
            Triple("Housing & Rent", "Home", "#6366F1") to TransactionType.EXPENSE,
            Triple("Bills & Utilities", "Receipt", "#EF4444") to TransactionType.EXPENSE,
            Triple("Transportation", "DirectionsCar", "#3B82F6") to TransactionType.EXPENSE,
            Triple("Shopping", "ShoppingBag", "#EC4899") to TransactionType.EXPENSE,
            Triple("Healthcare", "LocalHospital", "#14B8A6") to TransactionType.EXPENSE,
            Triple("Education", "School", "#8B5CF6") to TransactionType.EXPENSE,
            Triple("Entertainment", "Movie", "#06B6D4") to TransactionType.EXPENSE,
            Triple("Family Income", "AttachMoney", "#10B981") to TransactionType.INCOME,
            Triple("Other", "Category", "#64748B") to TransactionType.EXPENSE
        )
        return defaults.map { (triple, txType) ->
            val (name, icon, color) = triple
            val deterministicId = UUID.nameUUIDFromBytes("famcat_${familyId}_${name}_${txType.name}".toByteArray()).toString()
            FamilyCategoryEntity(
                id = deterministicId,
                familyId = familyId,
                name = name,
                iconName = icon,
                colorHex = color,
                type = txType,
                isDefault = true,
                createdBy = creatorUserId
            )
        }
    }
}
