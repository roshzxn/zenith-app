package com.zenith.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.zenith.app.data.models.FamilyMessageEntity
import com.zenith.app.data.models.FinanceScope
import com.zenith.app.data.models.TransactionEntity
import com.zenith.app.data.models.TransactionType
import com.zenith.app.data.repository.FamilyTxAttribution
import com.zenith.app.domain.FamilyInfo
import com.zenith.app.domain.FamilyMemberInfo
import com.zenith.app.domain.FamilyRole
import com.zenith.app.ui.components.AddTransactionDialog
import com.zenith.app.ui.components.FamilyChatSheet
import com.zenith.app.ui.components.TransactionDetailModal
import com.zenith.app.ui.components.TransactionItemCard
import com.zenith.app.ui.theme.CashFlowTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FamilyUiReuseAndChatTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testReusedTransactionCardAndDetailModalDisplayAttributionPermissionAndPendingSync() {
        val famId = UUID.randomUUID().toString()
        val txServerId = UUID.randomUUID().toString()
        val motherId = UUID.randomUUID().toString()
        val fatherId = UUID.randomUUID().toString()

        val tx = TransactionEntity(
            id = 42L,
            title = "Supermarket Basket",
            amount = 1450.0,
            type = TransactionType.EXPENSE,
            category = "Groceries",
            paymentMethod = "Card",
            note = "Weekly staples",
            financeScope = FinanceScope.FAMILY,
            familyId = famId,
            createdByUserId = motherId,
            serverId = txServerId,
            syncStatus = "PENDING_CREATE"
        )
        val attr = FamilyTxAttribution(
            serverId = txServerId,
            familyId = famId,
            createdByUserId = motherId,
            createdByName = "Mother",
            paidByUserId = fatherId,
            paidByName = "Father",
            clientTxId = UUID.randomUUID().toString()
        )
        var canModifyState by mutableStateOf(false)

        composeTestRule.setContent {
            CashFlowTheme {
                TransactionItemCard(
                    transaction = tx,
                    currencySymbol = "₹",
                    familyAttribution = attr
                )
                TransactionDetailModal(
                    transaction = tx,
                    currencySymbol = "₹",
                    onDismiss = {},
                    onDelete = {},
                    onUpdate = {},
                    familyAttribution = attr,
                    canModify = canModifyState,
                    familyMembers = listOf(
                        FamilyMemberInfo(UUID.randomUUID().toString(), famId, motherId, "Mother", "👩", role = FamilyRole.OWNER),
                        FamilyMemberInfo(UUID.randomUUID().toString(), famId, fatherId, "Father", "👨", role = FamilyRole.ADMIN)
                    )
                )
            }
        }

        // Card shows Paid by + Added by + Pending Sync indicator
        composeTestRule.onNodeWithText("Paid by Father • Added by Mother").assertExists()
        composeTestRule.onNodeWithText("Pending • ", substring = true).assertExists()

        // Detail Modal shows FAMILY LEDGER, Entered by, Paid by, Pending Cloud Sync
        composeTestRule.onNodeWithText("FAMILY LEDGER").assertExists()
        composeTestRule.onNodeWithText("Entered by").assertExists()
        composeTestRule.onNodeWithText("Paid by").assertExists()
        composeTestRule.onNodeWithText("Pending Cloud Sync (Offline)").assertExists()

        // Read-only member cannot see Edit/Delete actions
        composeTestRule.onNodeWithTag("btn_edit_tx").assertDoesNotExist()
        composeTestRule.onNodeWithTag("btn_delete_modal_tx").assertDoesNotExist()

        // Admin/Creator sees Edit/Delete actions
        canModifyState = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("btn_edit_tx").assertExists()
        composeTestRule.onNodeWithTag("btn_delete_modal_tx").assertExists()
    }

    @Test
    fun testAddTransactionDialogShowsPaidByPickerOnlyInFamilyMode() {
        val famId = UUID.randomUUID().toString()
        val u1 = UUID.randomUUID().toString()
        val u2 = UUID.randomUUID().toString()
        var isFamily by mutableStateOf(false)
        var capturedPaidBy: String? = null

        composeTestRule.setContent {
            CashFlowTheme {
                AddTransactionDialog(
                    categories = emptyList(),
                    currencySymbol = "₹",
                    onDismiss = {},
                    onAdd = { _, _, _, _, _, _, _ -> },
                    familyMembers = listOf(
                        FamilyMemberInfo(UUID.randomUUID().toString(), famId, u1, "Mother", "👩"),
                        FamilyMemberInfo(UUID.randomUUID().toString(), famId, u2, "Father", "👨")
                    ),
                    currentUserId = u1,
                    isFamilyMode = isFamily,
                    onAddWithPaidBy = { _, _, _, _, _, _, _, paidBy ->
                        capturedPaidBy = paidBy
                    }
                )
            }
        }

        composeTestRule.onNodeWithText("👩 Mother").assertDoesNotExist()
        isFamily = true
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("paid_by_row").performScrollTo()
        composeTestRule.onNodeWithText("👩 Mother").assertExists()
        composeTestRule.onNodeWithTag("paid_by_chip_$u2").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("add_transaction_amount_field").performScrollTo().performTextInput("550")
        composeTestRule.onNodeWithTag("add_transaction_title_field").performScrollTo().performTextInput("Electricity Bill")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("save_transaction_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(u2, capturedPaidBy)
    }

    @Test
    fun testWhatsAppStyleFamilyChatSupportsMessagesReplyExpenseShareAndDelete() {
        val famId = UUID.randomUUID().toString()
        val motherId = UUID.randomUUID().toString()
        val fatherId = UUID.randomUUID().toString()
        val txServerId = UUID.randomUUID().toString()

        val fam = FamilyInfo(famId, "Sharma Family", "🏡", motherId)
        val members = listOf(
            FamilyMemberInfo(UUID.randomUUID().toString(), famId, motherId, "Mother", "👩", role = FamilyRole.OWNER),
            FamilyMemberInfo(UUID.randomUUID().toString(), famId, fatherId, "Father", "👨", role = FamilyRole.MEMBER)
        )
        val existingMsg = FamilyMessageEntity(
            id = "m1",
            familyId = famId,
            senderUserId = motherId,
            senderName = "Mother",
            senderAvatar = "👩",
            body = "Bought vegetables today",
            clientMsgId = UUID.randomUUID().toString(),
            syncStatus = "SYNCED"
        )
        var messagesState by mutableStateOf(listOf(existingMsg))
        var sentTuple: Triple<String, String?, String?>? = null
        var deletedMsgId: String? = null

        val familyTx = TransactionEntity(
            id = 99L,
            title = "Farm Produce",
            amount = 720.0,
            type = TransactionType.EXPENSE,
            category = "Groceries",
            paymentMethod = "UPI",
            financeScope = FinanceScope.FAMILY,
            familyId = famId,
            createdByUserId = fatherId,
            serverId = txServerId,
            syncStatus = "PENDING_CREATE"
        )

        composeTestRule.setContent {
            CashFlowTheme {
                FamilyChatSheet(
                    family = fam,
                    members = members,
                    messages = messagesState,
                    familyTransactions = listOf(familyTx),
                    txAttributionByServerId = mapOf(
                        txServerId to FamilyTxAttribution(txServerId, famId, fatherId, "Father", fatherId, "Father", "c1")
                    ),
                    currentUserId = fatherId,
                    currentUserRole = FamilyRole.MEMBER,
                    currencySymbol = "₹",
                    isRealtimeConnected = true,
                    onSendMessage = { body, replyId, txId ->
                        sentTuple = Triple(body, replyId, txId)
                        messagesState = messagesState + FamilyMessageEntity(
                            id = "m2",
                            familyId = famId,
                            senderUserId = fatherId,
                            senderName = "Father",
                            senderAvatar = "👨",
                            messageType = if (txId != null) "EXPENSE_SHARE" else "TEXT",
                            body = body,
                            relatedTransactionId = txId,
                            replyToMessageId = replyId,
                            clientMsgId = UUID.randomUUID().toString(),
                            syncStatus = "PENDING_CREATE"
                        )
                    },
                    onDeleteMessage = { msg -> deletedMsgId = msg.id },
                    onOpenManageFamily = {},
                    onDismiss = {}
                )
            }
        }

        // Open reply on Mother's message
        composeTestRule.onNodeWithTag("chat_bubble_m1").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("chat_reply_m1").performClick()
        composeTestRule.onNodeWithText("Replying to Mother").assertExists()

        // Floating composer is visible and accepts input + expense share
        composeTestRule.onNodeWithTag("family_chat_floating_composer").assertIsDisplayed()

        // Attach a shared family expense
        composeTestRule.onNodeWithTag("family_chat_attach_expense").performClick()
        composeTestRule.onNodeWithText("Farm Produce", substring = true).performClick()

        // Send reply + expense share
        composeTestRule.onNodeWithTag("family_chat_input").performTextReplacement("Paid via UPI")
        composeTestRule.onNodeWithTag("family_chat_send_btn").performClick()
        composeTestRule.waitForIdle()

        assertEquals(Triple("Paid via UPI", "m1", txServerId), sentTuple)
        composeTestRule.onNodeWithText("Paid via UPI").assertExists()

        // Father (own message) can delete his own message
        composeTestRule.onNodeWithTag("chat_bubble_m2").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("chat_delete_m2").performScrollTo().performClick()
        assertEquals("m2", deletedMsgId)
    }

    @Test
    fun testFamilyChatGroupsConsecutiveSendersAndSupportsContextualLongPressAndSearch() {
        val famId = UUID.randomUUID().toString()
        val motherId = UUID.randomUUID().toString()
        val fatherId = UUID.randomUUID().toString()
        val now = 1_760_000_000_000L

        val fam = FamilyInfo(famId, "Sharma Family", "🏡", motherId)
        val members = listOf(
            FamilyMemberInfo(UUID.randomUUID().toString(), famId, motherId, "Mother", "👩", role = FamilyRole.OWNER),
            FamilyMemberInfo(UUID.randomUUID().toString(), famId, fatherId, "Father", "👨", role = FamilyRole.MEMBER)
        )
        val messages = listOf(
            FamilyMessageEntity(
                id = "m1",
                familyId = famId,
                senderUserId = motherId,
                senderName = "Mother",
                senderAvatar = "👩",
                body = "Morning groceries",
                createdAtMillis = now,
                clientMsgId = "c1",
                syncStatus = "SYNCED"
            ),
            FamilyMessageEntity(
                id = "m2",
                familyId = famId,
                senderUserId = motherId,
                senderName = "Mother",
                senderAvatar = "👩",
                body = "Also got milk",
                createdAtMillis = now + 60_000L,
                clientMsgId = "c2",
                syncStatus = "SYNCED"
            ),
            FamilyMessageEntity(
                id = "m3",
                familyId = famId,
                senderUserId = fatherId,
                senderName = "Father",
                senderAvatar = "👨",
                body = "Got it thanks",
                createdAtMillis = now + 120_000L,
                clientMsgId = "c3",
                syncStatus = "PENDING_CREATE"
            )
        )
        var deletedMsgId: String? = null

        composeTestRule.setContent {
            CashFlowTheme {
                FamilyChatSheet(
                    family = fam,
                    members = members,
                    messages = messages,
                    familyTransactions = emptyList(),
                    txAttributionByServerId = emptyMap(),
                    currentUserId = fatherId,
                    currentUserRole = FamilyRole.MEMBER,
                    currencySymbol = "₹",
                    isRealtimeConnected = true,
                    onSendMessage = { _, _, _ -> },
                    onDeleteMessage = { msg -> deletedMsgId = msg.id },
                    onOpenManageFamily = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Consecutive incoming messages from Mother share a single sender header
        composeTestRule.onAllNodesWithText("Mother").assertCountEquals(1)
        composeTestRule.onNodeWithText("Morning groceries").assertIsDisplayed()
        composeTestRule.onNodeWithText("Also got milk").assertIsDisplayed()

        // Long-press Father's message opens contextual header bar with Reply/Copy/Delete
        composeTestRule.onNodeWithTag("chat_bubble_m3").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeTestRule.onNodeWithTag("ctx_chat_reply").assertIsDisplayed()
        composeTestRule.onNodeWithTag("ctx_chat_copy").assertIsDisplayed()
        composeTestRule.onNodeWithTag("ctx_chat_delete").assertIsDisplayed()

        // Contextual reply sets quote target in composer dock
        composeTestRule.onNodeWithTag("ctx_chat_reply").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Replying to Father").assertIsDisplayed()

        // Long-press again and delete via contextual header action
        composeTestRule.onNodeWithTag("chat_bubble_m3").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_bubble_m3").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("ctx_chat_delete").performClick()
        assertEquals("m3", deletedMsgId)

        // Inline search filters feed cleanly
        composeTestRule.onNodeWithTag("btn_chat_search").performClick()
        composeTestRule.onNodeWithTag("family_chat_search_input").performTextInput("milk")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Also got milk").assertIsDisplayed()
        composeTestRule.onNodeWithText("Morning groceries").assertDoesNotExist()
    }

    @Test
    fun testFamilyChatQuickEmojiAndOfflineStatusBanner() {
        val famId = UUID.randomUUID().toString()
        val fatherId = UUID.randomUUID().toString()
        val fam = FamilyInfo(famId, "Sharma Family", "🏡", fatherId)
        val members = listOf(
            FamilyMemberInfo(UUID.randomUUID().toString(), famId, fatherId, "Father", "👨", role = FamilyRole.OWNER)
        )
        var sentBody: String? = null

        composeTestRule.setContent {
            CashFlowTheme {
                FamilyChatSheet(
                    family = fam,
                    members = members,
                    messages = emptyList(),
                    familyTransactions = emptyList(),
                    txAttributionByServerId = emptyMap(),
                    currentUserId = fatherId,
                    currentUserRole = FamilyRole.OWNER,
                    currencySymbol = "₹",
                    isRealtimeConnected = false,
                    onSendMessage = { body, _, _ -> sentBody = body },
                    onDeleteMessage = {},
                    onOpenManageFamily = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Cloud sync active • Messages queue offline automatically").assertIsDisplayed()

        composeTestRule.onNodeWithTag("family_chat_emoji_btn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("👍").performClick()
        composeTestRule.onNodeWithTag("family_chat_send_btn").performClick()
        assertEquals("👍", sentBody)
    }
}
