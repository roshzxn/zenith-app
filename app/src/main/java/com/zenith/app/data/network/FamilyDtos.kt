package com.zenith.app.data.network

import com.zenith.app.data.util.IsoDateUtils
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal fun parseIsoTimestampMillis(raw: String?, fallback: Long = System.currentTimeMillis()): Long {
    val clean = raw?.trim().orEmpty()
    if (clean.isBlank()) return fallback
    return IsoDateUtils.fromIsoString(clean, fallback)
}

internal fun epochMillisToIso(millis: Long): String = IsoDateUtils.toIsoString(millis)

@Serializable
data class FamilyCategoryDto(
    val id: String,
    @SerialName("family_id") val familyId: String,
    val name: String,
    @SerialName("icon_name") val iconName: String = "Category",
    @SerialName("color_hex") val colorHex: String = "#10B981",
    val type: String = "EXPENSE",
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class FamilyTransactionDto(
    val id: String,
    @SerialName("family_id") val familyId: String,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("paid_by") val paidBy: String? = null,
    @SerialName("created_by_name") val createdByName: String = "Member",
    @SerialName("paid_by_name") val paidByName: String = "Member",
    @SerialName("transaction_type") val transactionType: String = "EXPENSE",
    val amount: Double = 0.0,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("category_name") val categoryName: String = "Other",
    val title: String = "Transaction",
    val description: String = "",
    @SerialName("custom_label") val customLabel: String = "",
    @SerialName("payment_method") val paymentMethod: String = "UPI",
    @SerialName("upi_id") val upiId: String? = null,
    @SerialName("upi_transaction_id") val upiTransactionId: String? = null,
    @SerialName("transaction_date") val transactionDate: String? = null,
    @SerialName("client_tx_id") val clientTxId: String,
    @SerialName("sync_version") val syncVersion: Long = 1L,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class FamilyBudgetDto(
    val id: String,
    @SerialName("family_id") val familyId: String,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("category_name") val categoryName: String = "Overall",
    @SerialName("monthly_limit") val monthlyLimit: Double = 0.0,
    @SerialName("month_year") val monthYear: String = "2026-07",
    @SerialName("period_type") val periodType: String = "MONTHLY",
    @SerialName("updated_by") val updatedBy: String? = null,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class FamilyMessageDto(
    val id: String,
    @SerialName("family_id") val familyId: String,
    @SerialName("sender_user_id") val senderUserId: String? = null,
    @SerialName("sender_name") val senderName: String = "Member",
    @SerialName("sender_avatar") val senderAvatar: String = "🙂",
    @SerialName("message_type") val messageType: String = "TEXT",
    val body: String = "",
    @SerialName("related_transaction_id") val relatedTransactionId: String? = null,
    @SerialName("reply_to_message_id") val replyToMessageId: String? = null,
    @SerialName("client_msg_id") val clientMsgId: String,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
