package com.zenith.app.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TransactionType {
    EXPENSE, INCOME
}

enum class FinanceScope {
    PERSONAL,
    FAMILY
}

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["dateMillis"]),
        Index(value = ["financeScope", "isDeleted"]),
        Index(value = ["familyId", "isDeleted"]),
        Index(value = ["syncStatus"]),
        Index(value = ["serverId"]),
        Index(value = ["upiTransactionId"]),
        Index(value = ["category"]),
        Index(value = ["type"])
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val amount: Double,
    val type: TransactionType,
    val category: String,
    val categoryIconName: String = "Category",
    val note: String = "",
    val paymentMethod: String = "Cash", // Cash, Credit Card, UPI, Debit Card, Bank Transfer
    val dateMillis: Long = System.currentTimeMillis(),
    val receiptImageUri: String? = null,
    val upiId: String? = null, // VPA of the payee, e.g. "someone@okhdfcbank"
    val upiTransactionId: String? = null, // UTR / reference id only when a supported source provides it
    val financeScope: FinanceScope = FinanceScope.PERSONAL,
    val familyId: String? = null,
    val createdByUserId: String? = null,
    val serverId: String? = null,
    val syncStatus: String = "PENDING_CREATE",
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val customLabel: String = ""
)

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconName: String,
    val colorHex: String,
    val type: TransactionType = TransactionType.EXPENSE,
    val isDefault: Boolean = false,
    val serverId: String? = null,
    val syncStatus: String = "PENDING_CREATE",
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)

@Entity(
    tableName = "budgets",
    indices = [
        Index(value = ["monthYear", "isDeleted"]),
        Index(value = ["financeScope", "isDeleted"]),
        Index(value = ["familyId", "isDeleted"]),
        Index(value = ["serverId"]),
        Index(value = ["syncStatus"])
    ]
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryName: String,
    val monthlyLimit: Double,
    val monthYear: String = "2026-07", // e.g. "2026-07"
    val periodType: String = "MONTHLY", // "MONTHLY", "WEEKLY", "YEARLY", "CUSTOM"
    val customPeriodName: String = "",
    val financeScope: FinanceScope = FinanceScope.PERSONAL,
    val familyId: String? = null,
    val serverId: String? = null,
    val syncStatus: String = "PENDING_CREATE",
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)

@Entity(
    tableName = "savings_goals",
    indices = [
        Index(value = ["targetDateMillis"]),
        Index(value = ["financeScope", "isDeleted"]),
        Index(value = ["familyId", "isDeleted"]),
        Index(value = ["serverId"]),
        Index(value = ["syncStatus"])
    ]
)
data class SavingsGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val targetAmount: Double,
    val currentAmount: Double = 0.0,
    val targetDateMillis: Long = System.currentTimeMillis() + (30L * 24 * 3600 * 1000),
    val iconName: String = "Savings",
    val colorHex: String = "#059669",
    val financeScope: FinanceScope = FinanceScope.PERSONAL,
    val familyId: String? = null,
    val serverId: String? = null,
    val syncStatus: String = "PENDING_CREATE",
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)

@Entity(tableName = "scanned_items")
data class ScannedItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val barcodeValue: String,
    val productName: String = "Unknown Product",
    val addedDateMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_profiles")
data class UserProfileEntity(
    @PrimaryKey
    val id: String,
    val fullName: String,
    val email: String,
    val avatarUrl: String? = null,
    val serverId: String? = null,
    val syncStatus: String = "SYNCED",
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)

@Entity(
    tableName = "receipts",
    indices = [
        Index(value = ["transactionId"])
    ]
)
data class ReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val merchantName: String,
    val receiptNumber: String? = null,
    val receiptDate: String = "",
    val receiptTime: String? = null,
    val subtotal: Double = 0.0,
    val discount: Double = 0.0,
    val tax: Double = 0.0,
    val total: Double = 0.0,
    val currency: String = "₹",
    val paymentMethod: String = "UPI",
    val imageUri: String? = null,
    val rawText: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "receipt_items",
    indices = [
        Index(value = ["transactionId"]),
        Index(value = ["receiptId"])
    ]
)
data class ReceiptItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long = 0,
    val transactionId: Long,
    val name: String,
    val quantity: Double = 1.0,
    val unitPrice: Double = 0.0,
    val totalPrice: Double = 0.0
)

@Entity(
    tableName = "auto_pay_reminders",
    indices = [
        Index(value = ["nextDueDateMillis", "isDeleted"]),
        Index(value = ["financeScope", "isDeleted"]),
        Index(value = ["familyId", "isDeleted"]),
        Index(value = ["serverId"]),
        Index(value = ["syncStatus"])
    ]
)
data class AutoPayReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val amount: Double,
    val category: String = "Bills & Utilities",
    val paymentMethod: String = "UPI",
    val frequency: String = "MONTHLY", // "MONTHLY", "WEEKLY", "QUARTERLY", "YEARLY", "ONE_TIME"
    val dueDayOfMonth: Int = 1,
    val nextDueDateMillis: Long,
    val isAutoDebit: Boolean = true, // true = bank/card auto-debits; false = manual payment required
    val reminderDaysBefore: Int = 2,
    val financeScope: FinanceScope = FinanceScope.PERSONAL,
    val familyId: String? = null,
    val isPaused: Boolean = false,
    val lastPaidDateMillis: Long? = null,
    val notes: String = "",
    val serverId: String? = null,
    val syncStatus: String = "PENDING_CREATE",
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)

@Entity(
    tableName = "families",
    indices = [
        Index(value = ["ownerId"]),
        Index(value = ["isDeleted"])
    ]
)
data class FamilyEntity(
    @PrimaryKey val id: String,
    val name: String,
    val avatarEmoji: String = "🏠",
    val description: String = "",
    val ownerId: String = "",
    val monthlyBudgetLimit: Double = 0.0,
    val syncStatus: String = "SYNCED",
    val isDeleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val familyLabel: String = ""
)

@Entity(
    tableName = "family_members",
    indices = [
        Index(value = ["familyId", "status"]),
        Index(value = ["userId"])
    ]
)
data class FamilyMemberEntity(
    @PrimaryKey val id: String,
    val familyId: String,
    val userId: String,
    val displayName: String,
    val avatarEmoji: String = "🙂",
    val avatarColor: String = "#10B981",
    val role: String = "MEMBER",
    val status: String = "ACTIVE",
    val joinedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val memberLabel: String = ""
)

@Entity(
    tableName = "family_categories",
    indices = [
        Index(value = ["familyId", "isDeleted"]),
        Index(value = ["familyId", "name", "type"], unique = true)
    ]
)
data class FamilyCategoryEntity(
    @PrimaryKey val id: String,
    val familyId: String,
    val name: String,
    val iconName: String = "Category",
    val colorHex: String = "#10B981",
    val type: TransactionType = TransactionType.EXPENSE,
    val isDefault: Boolean = false,
    val createdBy: String? = null,
    val isDeleted: Boolean = false,
    val syncStatus: String = "SYNCED",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "family_transactions",
    indices = [
        Index(value = ["familyId", "isDeleted", "transactionDateMillis"]),
        Index(value = ["familyId", "paidBy"]),
        Index(value = ["familyId", "createdBy"]),
        Index(value = ["clientTxId"], unique = true),
        Index(value = ["syncStatus"])
    ]
)
data class FamilyTransactionEntity(
    @PrimaryKey val id: String,
    val familyId: String,
    val createdBy: String,
    val paidBy: String? = null,
    val createdByName: String = "Member",
    val paidByName: String = "Member",
    val transactionType: TransactionType = TransactionType.EXPENSE,
    val amount: Double,
    val categoryId: String? = null,
    val categoryName: String = "Other",
    val title: String,
    val description: String = "",
    val paymentMethod: String = "UPI",
    val upiId: String? = null,
    val upiTransactionId: String? = null,
    val transactionDateMillis: Long = System.currentTimeMillis(),
    val clientTxId: String,
    val syncVersion: Long = 1L,
    val syncStatus: String = "PENDING_CREATE",
    val isDeleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val customLabel: String = ""
)

@Entity(
    tableName = "family_budgets",
    indices = [
        Index(value = ["familyId", "isDeleted"]),
        Index(value = ["syncStatus"])
    ]
)
data class FamilyBudgetEntity(
    @PrimaryKey val id: String,
    val familyId: String,
    val categoryId: String? = null,
    val categoryName: String,
    val monthlyLimit: Double,
    val monthYear: String = "2026-07",
    val periodType: String = "MONTHLY",
    val updatedBy: String? = null,
    val syncStatus: String = "PENDING_CREATE",
    val isDeleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "family_messages",
    indices = [
        Index(value = ["familyId", "isDeleted", "createdAtMillis"]),
        Index(value = ["clientMsgId"], unique = true),
        Index(value = ["syncStatus"])
    ]
)
data class FamilyMessageEntity(
    @PrimaryKey val id: String,
    val familyId: String,
    val senderUserId: String,
    val senderName: String,
    val senderAvatar: String = "🙂",
    val messageType: String = "TEXT",
    val body: String,
    val relatedTransactionId: String? = null,
    val replyToMessageId: String? = null,
    val clientMsgId: String,
    val syncStatus: String = "PENDING_CREATE",
    val isDeleted: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

