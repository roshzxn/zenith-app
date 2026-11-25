package com.zenith.app.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProfileDto(
    val id: String,
    @SerialName("full_name") val fullName: String? = "",
    val email: String? = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class CategoryDto(
    val id: String,
    val name: String = "Other",
    val icon: String? = "Category",
    val color: String? = "#10B981",
    @SerialName("user_id") val userId: String? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class TransactionDto(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("family_id") val familyId: String? = null,
    @SerialName("finance_scope") val financeScope: String? = "PERSONAL",
    val amount: Double = 0.0,
    @SerialName("title") val title: String? = null,
    @SerialName("transaction_type") val transactionType: String? = "EXPENSE",
    @SerialName("type") val type: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("category") val category: String? = null,
    val description: String? = "",
    @SerialName("payment_method") val paymentMethod: String? = "UPI",
    @SerialName("paid_by_name") val paidByName: String? = null,
    @SerialName("paid_by_member_id") val paidByMemberId: String? = null,
    @SerialName("upi_id") val upiId: String? = null,
    @SerialName("upi_transaction_id") val upiTransactionId: String? = null,
    @SerialName("transaction_date") val transactionDate: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("is_deleted") val isDeleted: Boolean = false
)

@Serializable
data class BudgetDto(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("family_id") val familyId: String? = null,
    @SerialName("finance_scope") val financeScope: String? = "PERSONAL",
    val name: String? = "Budget",
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val amount: Double = 0.0,
    @SerialName("monthly_limit") val monthlyLimit: Double? = null,
    @SerialName("month_year") val monthYear: String? = null,
    @SerialName("period_type") val periodType: String? = "MONTHLY",
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("is_deleted") val isDeleted: Boolean = false
)

@Serializable
data class SavingsGoalDto(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("family_id") val familyId: String? = null,
    @SerialName("finance_scope") val financeScope: String? = "PERSONAL",
    val name: String? = "Goal",
    @SerialName("title") val title: String? = null,
    val targetAmount: Double = 0.0,
    val currentAmount: Double = 0.0,
    @SerialName("target_date") val targetDate: String? = null,
    @SerialName("icon_name") val iconName: String? = null,
    @SerialName("color_hex") val colorHex: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("is_deleted") val isDeleted: Boolean = false
)

@Serializable
data class AutoPayReminderDto(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("finance_scope") val financeScope: String? = "PERSONAL",
    val title: String = "",
    val amount: Double = 0.0,
    val category: String? = "Bills & Utilities",
    @SerialName("payment_method") val paymentMethod: String? = "UPI",
    val frequency: String? = "MONTHLY",
    @SerialName("due_day_of_month") val dueDayOfMonth: Int = 1,
    @SerialName("next_due_date") val nextDueDate: String? = null,
    @SerialName("is_auto_debit") val isAutoDebit: Boolean = true,
    @SerialName("reminder_days_before") val reminderDaysBefore: Int = 2,
    @SerialName("is_paused") val isPaused: Boolean = false,
    @SerialName("last_paid_date") val lastPaidDate: String? = null,
    val notes: String? = "",
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
