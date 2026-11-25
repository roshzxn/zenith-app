package com.zenith.app

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import com.zenith.app.data.models.CategoryEntity
import com.zenith.app.data.models.TransactionEntity
import com.zenith.app.data.models.TransactionType
import com.zenith.app.ui.components.*
import com.zenith.app.ui.screens.*
import com.zenith.app.ui.theme.CashFlowTheme
import com.zenith.app.ui.viewmodel.CashFlowUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiWorkflowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testPaymentMethodDropdown() {
        var selectedMethod = "UPI"

        composeTestRule.setContent {
            CashFlowTheme {
                PaymentMethodDropdown(
                    selectedMethod = selectedMethod,
                    onMethodSelected = { selectedMethod = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("dropdown_payment_method").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("payment_option_Cash").performClick()
        composeTestRule.waitForIdle()
        assertTrue(selectedMethod == "Cash")
    }

    @Test
    fun testFloatingNavigationBarTabs() {
        val selectedTab = mutableStateOf(0)

        composeTestRule.setContent {
            CashFlowTheme {
                ZenithFloatingNavigationBar(
                    selectedTab = selectedTab.value,
                    onTabSelected = { selectedTab.value = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("nav_transactions").performClick()
        composeTestRule.waitForIdle()
        assertTrue(selectedTab.value == 1)

        composeTestRule.onNodeWithTag("nav_budgets").performClick()
        composeTestRule.waitForIdle()
        assertTrue(selectedTab.value == 2)

        composeTestRule.onNodeWithTag("nav_analytics").performClick()
        composeTestRule.waitForIdle()
        assertTrue(selectedTab.value == 3)

        composeTestRule.onNodeWithTag("nav_profile").performClick()
        composeTestRule.waitForIdle()
        assertTrue(selectedTab.value == 4)

        composeTestRule.onNodeWithTag("nav_home").performClick()
        composeTestRule.waitForIdle()
        assertTrue(selectedTab.value == 0)
    }

    @Test
    fun testAddTransactionDialogButtons() {
        var dismissed = false

        composeTestRule.setContent {
            CashFlowTheme {
                AddTransactionDialog(
                    categories = emptyList(),
                    currencySymbol = "₹",
                    onDismiss = { dismissed = true },
                    onAdd = { _, _, _, _, _, _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithTag("type_income_button").performClick()
        composeTestRule.onNodeWithTag("type_expense_button").performClick()

        composeTestRule.onNodeWithTag("close_add_dialog").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun testHomeScreenInteractions() {
        var voiceOpened = false
        var profileOpened = false
        var activityOpened = false
        var syncTriggered = false

        composeTestRule.setContent {
            CashFlowTheme(isDarkMode = false, isOledBlack = false) {
                HomeScreen(
                    state = CashFlowUiState(),
                    totalIncome = 10000.0,
                    totalExpense = 2500.0,
                    netBalance = 7500.0,
                    onOpenAddTransaction = {},
                    onOpenVoiceAssistant = { voiceOpened = true },
                    onOpenReceiptScanner = {},
                    onDeleteTransaction = {},
                    onNavigateToActivity = { activityOpened = true },
                    onNavigateToProfile = { profileOpened = true },
                    onSyncNow = { syncTriggered = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("btn_voice_entry_header").performClick()
        assertTrue(voiceOpened)

        composeTestRule.onNodeWithTag("header_user_profile").performClick()
        assertTrue(profileOpened)

        composeTestRule.onNodeWithTag("btn_header_search").performClick()
        assertTrue(activityOpened)

        composeTestRule.onNodeWithTag("btn_header_sync").performClick()
        assertTrue(syncTriggered)
    }

    @Test
    fun testUpiQrScanModalDirectEntrySavesTransaction() {
        var savedTitle = ""
        var savedAmount = 0.0

        composeTestRule.setContent {
            CashFlowTheme {
                UpiQrScanModal(
                    currencySymbol = "₹",
                    onDismiss = {},
                    onSaveTransaction = { title, amount, _, _, _ ->
                        savedTitle = title
                        savedAmount = amount
                    }
                )
            }
        }

        composeTestRule.onNodeWithTag("upi_qr_scan_modal").assertExists()
        composeTestRule.onNodeWithTag("btn_enter_upi_directly").performScrollTo().performClick()
        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("upi_expense_amount_input").performScrollTo().performTextInput("320")
        composeTestRule.onNodeWithTag("upi_expense_title_input").performScrollTo().performTextReplacement("Tea Stall")
        composeTestRule.onNodeWithTag("btn_save_upi_expense").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals("Tea Stall", savedTitle)
        assertEquals(320.0, savedAmount, 0.01)
    }

    @Test
    fun testActivityScreenUnifiedScrollAndDeduplicatedCategoriesInLightTheme() {
        val sampleTxs = (1..20).map { idx ->
            TransactionEntity(
                id = idx.toLong(),
                title = "Activity Item $idx",
                amount = 100.0 * idx,
                type = TransactionType.EXPENSE,
                category = "Shopping",
                categoryIconName = "ShoppingBag",
                dateMillis = System.currentTimeMillis() - idx * 60_000L
            )
        }
        val duplicateCategories = listOf(
            CategoryEntity(name = "Bills & Utilities", iconName = "Receipt", colorHex = "#06B6D4", type = TransactionType.EXPENSE),
            CategoryEntity(name = "Bills & Utilities", iconName = "Bolt", colorHex = "#0EA5E9", type = TransactionType.EXPENSE),
            CategoryEntity(name = "Shopping", iconName = "ShoppingBag", colorHex = "#F43F5E", type = TransactionType.EXPENSE)
        )

        composeTestRule.setContent {
            CashFlowTheme(isDarkMode = false, isOledBlack = false) {
                Box(modifier = Modifier.size(width = 390.dp, height = 780.dp)) {
                    TransactionsScreen(
                        state = CashFlowUiState(
                            transactions = sampleTxs,
                            categories = duplicateCategories
                        ),
                        onSearchQueryChange = {},
                        onFilterTypeChange = {},
                        onFilterCategoryChange = {},
                        onDeleteTransaction = {},
                        onOpenAddTransaction = {},
                        onOpenVoiceAssistant = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("fab_add_transaction").assertIsDisplayed()
        composeTestRule.onNodeWithText("All Categories").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Bills & Utilities").assertCountEquals(1)

        // Scroll the vertical Activity LazyColumn past the early filter headers to the bottom item
        composeTestRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Activity Item 20"))
        composeTestRule.waitForIdle()

        // Fixed header Add button remains anchored while category filters scroll out of the list viewport
        composeTestRule.onNodeWithTag("fab_add_transaction").assertIsDisplayed()
        val categoryNodes = composeTestRule.onAllNodesWithText("All Categories").fetchSemanticsNodes()
        assertTrue(categoryNodes.isEmpty() || categoryNodes.first().boundsInRoot.bottom <= 96f)
    }

    private fun fakeRegistryOwner(resultCode: Int, responseExtra: String?): ActivityResultRegistryOwner {
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?
            ) {
                val data = responseExtra?.let { extra ->
                    Intent().putExtra("response", extra)
                }
                dispatchResult(requestCode, resultCode, data)
            }
        }
        return object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
    }

    @Test
    fun testUpiFlowSecurityDeclineBlocksSaveAndOffersDirectAppFallback() {
        var savedCount = 0
        val owner = fakeRegistryOwner(
            Activity.RESULT_OK,
            "txnId=G1&responseCode=U16&Status=FAILURE&message=Declined for security reasons"
        )

        composeTestRule.setContent {
            CashFlowTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    UpiPaymentFlow(
                        payRequest = UpiPayRequest(
                            amount = 100.0,
                            purpose = "Tea",
                            vpa = "friend@okhdfcbank",
                            targetPackage = "com.google.android.apps.nbu.paisa.user"
                        ),
                        currencySymbol = "₹",
                        onSaveConfirmed = { savedCount++ },
                        onDismiss = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("upi_result_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Declined for Security").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_upi_open_direct").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_upi_save").assertDoesNotExist()
        assertEquals(0, savedCount)
    }

    @Test
    fun testUpiFlowPendingDoesNotRecordConfirmedExpense() {
        var savedCount = 0
        val owner = fakeRegistryOwner(
            Activity.RESULT_OK,
            "txnId=P1&Status=SUBMITTED&txnRef=PENDING99"
        )

        composeTestRule.setContent {
            CashFlowTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    UpiPaymentFlow(
                        payRequest = UpiPayRequest(
                            amount = 250.0,
                            purpose = "Snacks",
                            vpa = "store@ybl",
                            targetPackage = "com.phonepe.app"
                        ),
                        currencySymbol = "₹",
                        onSaveConfirmed = { savedCount++ },
                        onDismiss = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Payment Pending").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_upi_pending_ack").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_upi_save").assertDoesNotExist()
        assertEquals(0, savedCount)
    }

    @Test
    fun testUpiFlowCancelledHidesSaveAction() {
        var savedCount = 0
        val owner = fakeRegistryOwner(Activity.RESULT_CANCELED, "")

        composeTestRule.setContent {
            CashFlowTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    UpiPaymentFlow(
                        payRequest = UpiPayRequest(
                            amount = 75.0,
                            purpose = "Bus",
                            vpa = "user@paytm",
                            targetPackage = "net.one97.paytm"
                        ),
                        currencySymbol = "₹",
                        onSaveConfirmed = { savedCount++ },
                        onDismiss = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Payment Cancelled").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_upi_save").assertDoesNotExist()
        assertEquals(0, savedCount)
    }

    @Test
    fun testUpiFlowSuccessSavesVerifiedTransactionAndUtrOnce() {
        var savedCount = 0
        var recordedUtr: String? = null
        val owner = fakeRegistryOwner(
            Activity.RESULT_OK,
            "txnId=G99&responseCode=00&Status=SUCCESS&ApprovalRefNo=432109876543"
        )

        composeTestRule.setContent {
            CashFlowTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    UpiPaymentFlow(
                        payRequest = UpiPayRequest(
                            amount = 340.0,
                            purpose = "Dinner",
                            vpa = "cafe@okaxis",
                            targetPackage = "com.google.android.apps.nbu.paisa.user"
                        ),
                        currencySymbol = "₹",
                        onSaveConfirmed = { utr ->
                            savedCount++
                            recordedUtr = utr
                        },
                        onDismiss = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Payment Successful").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ref / UTR: 432109876543").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_upi_save").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, savedCount)
        assertEquals("432109876543", recordedUtr)
    }
}
