package com.zenith.app.domain

enum class FinancialMode {
    PERSONAL,
    FAMILY
}

enum class FamilyRole {
    OWNER,
    ADMIN,
    MEMBER;

    fun canAdmin(): Boolean = this == OWNER || this == ADMIN
    fun isOwner(): Boolean = this == OWNER

    companion object {
        fun fromString(raw: String?): FamilyRole {
            return when (raw?.trim()?.uppercase()) {
                "OWNER" -> OWNER
                "ADMIN" -> ADMIN
                else -> MEMBER
            }
        }
    }
}

enum class FamilyMembershipStatus {
    DISABLED,
    LOADING,
    NONE,
    ACTIVE,
    ERROR
}

data class FamilyInfo(
    val id: String,
    val name: String,
    val avatarEmoji: String = "🏠",
    val ownerId: String? = null,
    val monthlyBudgetLimit: Double = 0.0,
    val familyLabel: String = ""
)

data class FamilyMemberInfo(
    val id: String,
    val familyId: String,
    val userId: String,
    val displayName: String,
    val avatarEmoji: String = "🙂",
    val avatarColor: String = "#10B981",
    val role: FamilyRole = FamilyRole.MEMBER,
    val status: String = "ACTIVE",
    val memberLabel: String = ""
)

data class FamilyInvitationInfo(
    val id: String,
    val familyId: String,
    val inviteCode: String,
    val inviteToken: String,
    val invitedEmail: String = "",
    val role: FamilyRole = FamilyRole.MEMBER,
    val status: String = "PENDING",
    val expiresAt: String? = null
)

/**
 * Centralized application-level financial workspace state.
 * Keeps Personal and Family mode visibility, active family metadata, role,
 * and membership status isolated from screen-local filters.
 */
data class FinancialContext(
    val mode: FinancialMode = FinancialMode.PERSONAL,
    val isFamilyEnabled: Boolean = false,
    val activeFamilyId: String? = null,
    val activeFamily: FamilyInfo? = null,
    val activeMembers: List<FamilyMemberInfo> = emptyList(),
    val currentUserId: String = "local_user_1",
    val membershipRole: FamilyRole? = null,
    val familyMembershipStatus: FamilyMembershipStatus = FamilyMembershipStatus.DISABLED,
    val membershipError: String? = null
) {
    val isFamilyModeActive: Boolean
        get() = isFamilyEnabled &&
            mode == FinancialMode.FAMILY &&
            !activeFamilyId.isNullOrBlank() &&
            familyMembershipStatus == FamilyMembershipStatus.ACTIVE
}

object FamilyAvatarCatalog {
    val FAMILY_EMOJIS = listOf(
        "🏠", "🏡", "👨‍👩‍👧‍👦", "👨‍👩‍👦", "🌿", "💛", "🌟", "🚀", "🏖️", "🛒", "🏔️", "🎉"
    )
    val MEMBER_EMOJIS = listOf(
        "🙂", "😎", "👩", "👨", "🧑", "👵", "👴", "✨", "🦁", "🐼", "☕", "🎮"
    )
    val FAMILY_LABEL_SUGGESTIONS = listOf(
        "🏠 Household", "🛋️ Roommates", "💛 Couple", "🏡 Shared Home", "👨‍👩‍👧‍👦 Family & Kids", "✈️ Travel Group"
    )
    val MEMBER_LABEL_SUGGESTIONS = listOf(
        "👩 Mom", "👨 Dad", "❤️ Partner", "🧑 Sibling", "🏠 Flatmate", "🌟 Organizer", "👶 Child"
    )
    val EXPENSE_LABEL_SUGGESTIONS = listOf(
        "⭐ Essential", "🤝 Split", "🧾 Reimbursable", "🍕 Treat", "🚨 Urgent", "🔁 Recurring", "🎁 Gift"
    )
}
