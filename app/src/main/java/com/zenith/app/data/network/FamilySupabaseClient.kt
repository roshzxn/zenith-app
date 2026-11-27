package com.zenith.app.data.network

import com.zenith.app.domain.FamilyInfo
import com.zenith.app.domain.FamilyInvitationInfo
import com.zenith.app.domain.FamilyMemberInfo
import com.zenith.app.domain.FamilyRole
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ActiveFamilyBundle(
    val family: FamilyInfo,
    val membership: FamilyMemberInfo,
    val members: List<FamilyMemberInfo> = listOf(membership)
)

/**
 * Real Supabase PostgREST / RPC client for Family membership, invitations,
 * ownership transfer, and atomic DB operations (enforcing RLS server-side).
 */
object FamilySupabaseClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun fetchMyActiveFamily(): Result<ActiveFamilyBundle?> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(
                IllegalStateException("Supabase cloud configuration is missing.")
            )
        }
        return@withContext try {
            val res = SupabaseClientConfig.supabase.postgrest.rpc("get_my_active_family")
            Result.success(parseActiveFamilyResponse(res.data))
        } catch (e: Exception) {
            Result.failure(IllegalStateException(extractFriendlyRpcError(e)))
        }
    }

    suspend fun createFamilyAtomic(
        name: String,
        avatarEmoji: String,
        creatorName: String,
        idempotencyKey: String,
        monthlyBudgetLimit: Double = 0.0,
        familyLabel: String = "",
        creatorLabel: String = "",
        creatorEmoji: String = "🙂"
    ): Result<ActiveFamilyBundle> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(
                IllegalStateException("Cloud configuration required to create a family in Supabase.")
            )
        }
        return@withContext try {
            val res = SupabaseClientConfig.supabase.postgrest.rpc(
                function = "create_family_atomic",
                parameters = buildJsonObject {
                    put("p_name", name.trim())
                    put("p_avatar_emoji", avatarEmoji.ifBlank { "🏠" })
                    put("p_creator_name", creatorName.trim())
                    put("p_idempotency_key", idempotencyKey)
                    put("p_monthly_budget_limit", monthlyBudgetLimit)
                    put("p_family_label", familyLabel.trim())
                    put("p_creator_label", creatorLabel.trim())
                    put("p_creator_emoji", creatorEmoji.ifBlank { "🙂" })
                }
            )
            val bundle = parseActiveFamilyResponse(res.data)
                ?: return@withContext Result.failure(IllegalStateException("Family creation confirmed empty payload."))
            Result.success(bundle)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(extractFriendlyRpcError(e)))
        }
    }

    suspend fun createFamilyInvitation(
        familyId: String,
        invitedEmail: String = "",
        role: FamilyRole = FamilyRole.MEMBER,
        expiresHours: Int = 168,
        maxUses: Int = 5
    ): Result<FamilyInvitationInfo> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud configuration missing."))
        }
        return@withContext try {
            val res = SupabaseClientConfig.supabase.postgrest.rpc(
                function = "create_family_invitation",
                parameters = buildJsonObject {
                    put("p_family_id", familyId)
                    put("p_invited_email", invitedEmail.trim())
                    put("p_role", role.name)
                    put("p_expires_hours", expiresHours)
                    put("p_max_uses", maxUses.coerceIn(1, 25))
                }
            )
            Result.success(parseInvitation(res.data))
        } catch (e: Exception) {
            Result.failure(IllegalStateException(extractFriendlyRpcError(e)))
        }
    }

    suspend fun acceptFamilyInvitation(
        codeOrToken: String,
        displayName: String = "",
        avatarEmoji: String = "🙂",
        memberLabel: String = ""
    ): Result<ActiveFamilyBundle> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud configuration missing."))
        }
        return@withContext try {
            val cleanInput = extractInviteTokenOrCode(codeOrToken)
            val res = SupabaseClientConfig.supabase.postgrest.rpc(
                function = "accept_family_invitation",
                parameters = buildJsonObject {
                    put("p_code_or_token", cleanInput)
                    put("p_display_name", displayName.trim())
                    put("p_avatar_emoji", avatarEmoji.ifBlank { "🙂" })
                    put("p_member_label", memberLabel.trim())
                }
            )
            val bundle = parseActiveFamilyResponse(res.data)
                ?: return@withContext Result.failure(IllegalStateException("Invalid invitation response."))
            Result.success(bundle)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(extractFriendlyRpcError(e)))
        }
    }

    suspend fun updateFamilyProfile(
        familyId: String,
        name: String,
        avatarEmoji: String,
        familyLabel: String
    ): Result<Boolean> = executeBooleanRpc(
        "update_family_profile",
        buildJsonObject {
            put("p_family_id", familyId)
            put("p_name", name.trim())
            put("p_avatar_emoji", avatarEmoji.ifBlank { "🏠" })
            put("p_family_label", familyLabel.trim())
        }
    )

    suspend fun updateFamilyMemberProfile(
        familyId: String,
        targetUserId: String,
        displayName: String,
        memberLabel: String,
        avatarEmoji: String? = null
    ): Result<Boolean> = executeBooleanRpc(
        "update_family_member_profile",
        buildJsonObject {
            put("p_family_id", familyId)
            put("p_target_user_id", targetUserId)
            put("p_display_name", displayName.trim())
            put("p_member_label", memberLabel.trim())
            if (!avatarEmoji.isNullOrBlank()) {
                put("p_avatar_emoji", avatarEmoji.trim())
            }
        }
    )

    suspend fun updateMemberRole(familyId: String, targetUserId: String, newRole: FamilyRole): Result<Boolean> =
        executeBooleanRpc("update_family_member_role", buildJsonObject {
            put("p_family_id", familyId)
            put("p_target_user_id", targetUserId)
            put("p_new_role", newRole.name)
        })

    suspend fun removeMember(familyId: String, targetUserId: String): Result<Boolean> =
        executeBooleanRpc("remove_family_member", buildJsonObject {
            put("p_family_id", familyId)
            put("p_target_user_id", targetUserId)
        })

    suspend fun transferOwnership(familyId: String, newOwnerUserId: String): Result<Boolean> =
        executeBooleanRpc("transfer_family_ownership", buildJsonObject {
            put("p_family_id", familyId)
            put("p_new_owner_user_id", newOwnerUserId)
        })

    suspend fun leaveFamily(familyId: String): Result<Boolean> =
        executeBooleanRpc("leave_family", buildJsonObject {
            put("p_family_id", familyId)
        })

    suspend fun closeFamily(familyId: String): Result<Boolean> =
        executeBooleanRpc("close_family", buildJsonObject {
            put("p_family_id", familyId)
        })

    suspend fun deleteMyAccount(): Result<Boolean> =
        executeBooleanRpc("delete_my_account", buildJsonObject { })

    suspend fun updateFamilyMonthlyBudgetLimit(familyId: String, limit: Double): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud configuration missing."))
        }
        return@withContext try {
            SupabaseClientConfig.supabase.postgrest["families"].update({
                set("monthly_budget_limit", limit.coerceAtLeast(0.0))
                set("updated_at", epochMillisToIso(System.currentTimeMillis()))
            }) {
                filter { eq("id", familyId) }
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(extractFriendlyRpcError(e)))
        }
    }

    suspend fun fetchFamilyCategories(familyId: String): Result<List<FamilyCategoryDto>> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud configuration missing."))
        }
        return@withContext try {
            val list = SupabaseClientConfig.supabase.postgrest["family_categories"]
                .select {
                    filter {
                        eq("family_id", familyId)
                        eq("is_deleted", false)
                    }
                }
                .decodeList<FamilyCategoryDto>()
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(extractFriendlyRpcError(e)))
        }
    }

    suspend fun upsertFamilyCategory(dto: FamilyCategoryDto): Result<Unit> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud configuration missing."))
        }
        return@withContext try {
            SupabaseClientConfig.supabase.postgrest["family_categories"].upsert(dto)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(extractFriendlyRpcError(e)))
        }
    }

    suspend fun fetchFamilyTransactions(familyId: String): Result<List<FamilyTransactionDto>> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud configuration missing."))
        }
        return@withContext try {
            val list = SupabaseClientConfig.supabase.postgrest["family_transactions"]
                .select {
                    filter {
                        eq("family_id", familyId)
                        eq("is_deleted", false)
                    }
                }
                .decodeList<FamilyTransactionDto>()
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(extractFriendlyRpcError(e)))
        }
    }

    suspend fun upsertFamilyTransaction(dto: FamilyTransactionDto): Result<Unit> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud configuration missing."))
        }
        return@withContext try {
            SupabaseClientConfig.supabase.postgrest["family_transactions"].upsert(dto)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(extractFriendlyRpcError(e)))
        }
    }

    suspend fun updateFamilyTransaction(dto: FamilyTransactionDto): Result<Unit> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud configuration missing."))
        }
        return@withContext try {
            SupabaseClientConfig.supabase.postgrest["family_transactions"].update({
                set("paid_by", dto.paidBy)
                set("paid_by_name", dto.paidByName)
                set("transaction_type", dto.transactionType)
                set("amount", dto.amount)
                set("category_id", dto.categoryId)
                set("category_name", dto.categoryName)
                set("title", dto.title)
                set("description", dto.description)
                set("custom_label", dto.customLabel)
                set("payment_method", dto.paymentMethod)
                set("upi_id", dto.upiId)
                set("upi_transaction_id", dto.upiTransactionId)
                set("transaction_date", dto.transactionDate)
                set("is_deleted", false)
                set("updated_at", dto.updatedAt ?: epochMillisToIso(System.currentTimeMillis()))
            }) {
                filter {
                    eq("id", dto.id)
                    eq("family_id", dto.familyId)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(extractFriendlyRpcError(e)))
        }
    }

    suspend fun softDeleteFamilyTransaction(id: String, familyId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud configuration missing."))
        }
        return@withContext try {
            SupabaseClientConfig.supabase.postgrest["family_transactions"].delete {
                filter {
                    eq("id", id)
                    eq("family_id", familyId)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            try {
                SupabaseClientConfig.supabase.postgrest["family_transactions"].update({
                    set("is_deleted", true)
                    set("updated_at", epochMillisToIso(System.currentTimeMillis()))
                }) {
                    filter {
                        eq("id", id)
                        eq("family_id", familyId)
                    }
                }
                Result.success(Unit)
            } catch (e2: Exception) {
                Result.failure(IllegalStateException(extractFriendlyRpcError(e2)))
            }
        }
    }

    suspend fun clearFamilyTransactions(familyId: String, currentUserId: String, isAdmin: Boolean): Result<Int> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud configuration missing."))
        }
        return@withContext try {
            val res = SupabaseClientConfig.supabase.postgrest.rpc(
                function = "clear_family_transactions_atomic",
                parameters = buildJsonObject {
                    put("p_family_id", familyId)
                }
            )
            val count = res.data.trim().toIntOrNull() ?: 0
            Result.success(count)
        } catch (_: Exception) {
            try {
                SupabaseClientConfig.supabase.postgrest["family_transactions"].delete {
                    filter {
                        eq("family_id", familyId)
                        if (!isAdmin) {
                            eq("created_by", currentUserId)
                        }
                    }
                }
                Result.success(1)
            } catch (e2: Exception) {
                Result.failure(IllegalStateException(extractFriendlyRpcError(e2)))
            }
        }
    }

    suspend fun fetchFamilyBudgets(familyId: String): Result<List<FamilyBudgetDto>> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud configuration missing."))
        }
        return@withContext try {
            val list = SupabaseClientConfig.supabase.postgrest["family_budgets"]
                .select {
                    filter {
                        eq("family_id", familyId)
                        eq("is_deleted", false)
                    }
                }
                .decodeList<FamilyBudgetDto>()
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(extractFriendlyRpcError(e)))
        }
    }

    suspend fun upsertFamilyBudget(dto: FamilyBudgetDto): Result<Unit> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud configuration missing."))
        }
        return@withContext try {
            SupabaseClientConfig.supabase.postgrest["family_budgets"].upsert(dto)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(extractFriendlyRpcError(e)))
        }
    }

    suspend fun softDeleteFamilyBudget(id: String, familyId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud configuration missing."))
        }
        return@withContext try {
            SupabaseClientConfig.supabase.postgrest["family_budgets"].update({
                set("is_deleted", true)
                set("updated_at", epochMillisToIso(System.currentTimeMillis()))
            }) {
                filter {
                    eq("id", id)
                    eq("family_id", familyId)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(extractFriendlyRpcError(e)))
        }
    }

    suspend fun fetchFamilyMessages(familyId: String): Result<List<FamilyMessageDto>> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud configuration missing."))
        }
        return@withContext try {
            val list = SupabaseClientConfig.supabase.postgrest["family_messages"]
                .select {
                    filter {
                        eq("family_id", familyId)
                        eq("is_deleted", false)
                    }
                }
                .decodeList<FamilyMessageDto>()
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(extractFriendlyRpcError(e)))
        }
    }

    suspend fun upsertFamilyMessage(dto: FamilyMessageDto): Result<Unit> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud configuration missing."))
        }
        return@withContext try {
            SupabaseClientConfig.supabase.postgrest["family_messages"].upsert(dto)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(extractFriendlyRpcError(e)))
        }
    }

    suspend fun softDeleteFamilyMessage(id: String, familyId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud configuration missing."))
        }
        return@withContext try {
            SupabaseClientConfig.supabase.postgrest["family_messages"].update({
                set("is_deleted", true)
                set("updated_at", epochMillisToIso(System.currentTimeMillis()))
            }) {
                filter {
                    eq("id", id)
                    eq("family_id", familyId)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(extractFriendlyRpcError(e)))
        }
    }

    private suspend fun executeBooleanRpc(fn: String, params: JsonObject): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud configuration missing."))
        }
        return@withContext try {
            val res = SupabaseClientConfig.supabase.postgrest.rpc(function = fn, parameters = params)
            val body = res.data.trim()
            if (body.equals("false", ignoreCase = true)) {
                Result.success(false)
            } else {
                Result.success(true)
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(extractFriendlyRpcError(e)))
        }
    }

    internal fun extractInviteTokenOrCode(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.contains("token=", ignoreCase = true)) {
            return trimmed.substringAfter("token=").substringBefore("&").substringBefore("#").trim()
        }
        if (trimmed.contains("code=", ignoreCase = true)) {
            return trimmed.substringAfter("code=").substringBefore("&").substringBefore("#").trim()
        }
        return trimmed
    }

    internal fun parseActiveFamilyResponse(rawJson: String?): ActiveFamilyBundle? {
        val cleaned = rawJson?.trim().orEmpty()
        if (cleaned.isBlank() || cleaned == "null") return null
        val element = json.parseToJsonElement(cleaned)
        if (element is JsonNull) return null
        val root = element.jsonObject
        val famObj = root["family"]?.takeIf { it !is JsonNull }?.jsonObject ?: return null
        val memObj = root["membership"]?.takeIf { it !is JsonNull }?.jsonObject ?: return null

        val family = FamilyInfo(
            id = famObj.str("id"),
            name = famObj.str("name", "Family"),
            avatarEmoji = famObj.str("avatar_emoji", "🏠").ifBlank { "🏠" },
            ownerId = famObj["owner_id"]?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull },
            monthlyBudgetLimit = famObj["monthly_budget_limit"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            familyLabel = famObj.str("family_label", "")
        )
        val membership = parseMemberObj(memObj)
        val membersArr = root["members"]?.takeIf { it is JsonArray }?.jsonArray
        val members = membersArr?.map { parseMemberObj(it.jsonObject) } ?: listOf(membership)
        return ActiveFamilyBundle(
            family = family,
            membership = membership,
            members = members.ifEmpty { listOf(membership) }
        )
    }

    internal fun parseInvitation(rawJson: String): FamilyInvitationInfo {
        val obj = json.parseToJsonElement(rawJson).jsonObject
        return FamilyInvitationInfo(
            id = obj.str("id"),
            familyId = obj.str("family_id"),
            inviteCode = obj.str("invite_code"),
            inviteToken = obj.str("invite_token"),
            invitedEmail = obj.str("invited_email"),
            role = FamilyRole.fromString(obj.str("role", "MEMBER")),
            status = obj.str("status", "PENDING"),
            expiresAt = obj["expires_at"]?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull },
            maxUses = obj["max_uses"]?.let { if (it is JsonNull) null else it.jsonPrimitive.intOrNull } ?: 5,
            usesCount = obj["uses_count"]?.let { if (it is JsonNull) null else it.jsonPrimitive.intOrNull } ?: 0
        )
    }

    private fun parseMemberObj(obj: JsonObject): FamilyMemberInfo {
        return FamilyMemberInfo(
            id = obj.str("id"),
            familyId = obj.str("family_id"),
            userId = obj.str("user_id"),
            displayName = obj.str("display_name").ifBlank { obj.str("name", "Member") },
            avatarEmoji = obj.str("avatar_emoji", "🙂").ifBlank { "🙂" },
            avatarColor = obj.str("avatar_color", "#10B981").ifBlank { "#10B981" },
            role = FamilyRole.fromString(obj.str("role", "MEMBER")),
            status = obj.str("status", "ACTIVE"),
            memberLabel = obj.str("member_label", "")
        )
    }

    private fun JsonObject.str(key: String, fallback: String = ""): String {
        val el = this[key] ?: return fallback
        if (el is JsonNull) return fallback
        return (el as? JsonPrimitive)?.contentOrNull ?: fallback
    }

    internal fun extractFriendlyRpcError(e: Exception): String {
        val raw = e.message.orEmpty().trim()
        val jsonMsg = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.getOrNull(1)
        if (!jsonMsg.isNullOrBlank()) return jsonMsg
        return raw.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
            ?: "Request failed. Check your connection and try again."
    }
}
