package com.zenith.app

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.zenith.app.data.export.DateFilterType
import com.zenith.app.data.export.ExportFormat
import com.zenith.app.data.export.ExportImportService
import com.zenith.app.data.export.ExportOptions
import com.zenith.app.data.models.BudgetEntity
import com.zenith.app.data.models.FinanceScope
import com.zenith.app.data.models.TransactionEntity
import com.zenith.app.data.models.TransactionType
import com.zenith.app.data.repository.FamilyTxAttribution
import com.zenith.app.domain.*
import com.zenith.app.ui.screens.AnalyticsScreen
import com.zenith.app.ui.screens.BudgetsAndGoalsScreen
import com.zenith.app.ui.screens.calculateBudgetSpent
import com.zenith.app.ui.screens.computeBudgetOverviewTotals
import com.zenith.app.ui.theme.CashFlowTheme
import com.zenith.app.ui.viewmodel.CashFlowUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FamilyAnalyticsBudgetsReportsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testOverallSharedBudgetCalculationAndRoleGatingOnBudgetsScreen() {
        val monthYear = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val famId = UUID.randomUUID().toString()
        val u1 = UUID.randomUUID().toString()
        val overallBudget = BudgetEntity(
            id = 1L,
            categoryName = "Overall",
            monthlyLimit = 25000.0,
            monthYear = monthYear,
            periodType = "MONTHLY",
            financeScope = FinanceScope.FAMILY,
            familyId = famId
        )
        val txs = listOf(
            TransactionEntity(id = 10L, title = "Groceries", amount = 4500.0, type = TransactionType.EXPENSE, category = "Groceries", financeScope = FinanceScope.FAMILY, familyId = famId),
            TransactionEntity(id = 11L, title = "Rent", amount = 12000.0, type = TransactionType.EXPENSE, category = "Housing & Rent", financeScope = FinanceScope.FAMILY, familyId = famId),
            TransactionEntity(id = 12L, title = "Bonus", amount = 5000.0, type = TransactionType.INCOME, category = "Family Income", financeScope = FinanceScope.FAMILY, familyId = famId)
        )

        // Overall budget sums all expenses (4500 + 12000 = 16500), excluding Income
        assertEquals(16500.0, calculateBudgetSpent(overallBudget, txs), 0.01)

        val memberState = CashFlowUiState(
            allTransactions = txs,
            budgets = listOf(overallBudget),
            activeMode = FinancialMode.FAMILY,
            activeFamily = FamilyInfo(famId, "Green Home", "🌿", u1, 25000.0),
            activeFamilyRole = FamilyRole.MEMBER
        )

        composeTestRule.setContent {
            CashFlowTheme {
                BudgetsAndGoalsScreen(
                    state = memberState,
                    onSaveBudget = { _, _, _, _, _ -> },
                    onDeleteBudget = {},
                    onSaveSavingsGoal = {},
                    onDeleteSavingsGoal = {},
                    onDepositToGoal = { _, _ -> }
                )
            }
        }

        // Read-only MEMBER cannot add/create budgets & Personal Savings Goals are isolated out
        composeTestRule.onNodeWithText("Shared Family Budget").assertExists()
        composeTestRule.onNodeWithTag("add_budget_btn").assertDoesNotExist()
        composeTestRule.onNodeWithTag("add_goal_btn").assertDoesNotExist()
        composeTestRule.onNodeWithTag("budgets_lazy_column")
            .performScrollToNode(hasTestTag("family_budgets_scope_note"))
        composeTestRule.onNodeWithTag("family_budgets_scope_note").assertExists()
    }

    @Test
    fun testAnalyticsRendersMemberSpendingBreakdownAndSupportsYearlyRange() {
        val famId = UUID.randomUUID().toString()
        val u1 = UUID.randomUUID().toString()
        val u2 = UUID.randomUUID().toString()
        val tx1Id = UUID.randomUUID().toString()
        val tx2Id = UUID.randomUUID().toString()

        val members = listOf(
            FamilyMemberInfo("m1", famId, u1, "Jordan", "🦁", role = FamilyRole.OWNER, memberLabel = "Organizer"),
            FamilyMemberInfo("m2", famId, u2, "Taylor", "☕", role = FamilyRole.ADMIN, memberLabel = "Partner")
        )
        val txs = listOf(
            TransactionEntity(id = 1L, title = "Market", amount = 3000.0, type = TransactionType.EXPENSE, category = "Groceries", financeScope = FinanceScope.FAMILY, familyId = famId, createdByUserId = u1, serverId = tx1Id),
            TransactionEntity(id = 2L, title = "Electric", amount = 1000.0, type = TransactionType.EXPENSE, category = "Bills & Utilities", financeScope = FinanceScope.FAMILY, familyId = famId, createdByUserId = u2, serverId = tx2Id)
        )
        val attr = mapOf(
            tx1Id to FamilyTxAttribution(tx1Id, famId, u1, "Jordan", u1, "Jordan", "c1", paidByLabel = "Organizer"),
            tx2Id to FamilyTxAttribution(tx2Id, famId, u2, "Taylor", u2, "Taylor", "c2", paidByLabel = "Partner")
        )

        val state = CashFlowUiState(
            allTransactions = txs,
            activeMode = FinancialMode.FAMILY,
            activeFamily = FamilyInfo(famId, "Oceanview Loft", "🏖️", u1),
            activeFamilyMembers = members,
            txAttributionByServerId = attr
        )

        composeTestRule.setContent {
            CashFlowTheme {
                AnalyticsScreen(
                    state = state,
                    totalIncome = 0.0,
                    totalExpense = 4000.0,
                    onGenerateAiAdvice = { _, _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithText("This Year").assertExists()
        composeTestRule.onNodeWithTag("analytics_lazy_column")
            .performScrollToNode(hasTestTag("member_spending_breakdown_card"))
        composeTestRule.onNodeWithTag("member_spending_breakdown_card").assertExists()
        composeTestRule.onNodeWithText("🦁 Jordan (Organizer)").assertExists()
        composeTestRule.onNodeWithText("☕ Taylor (Partner)").assertExists()
    }

    @Test
    fun testExportReportsStrictlyIsolatePersonalAndFamilyScopesWithAttribution() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val famId = UUID.randomUUID().toString()
        val s1 = UUID.randomUUID().toString()

        val personalTx = TransactionEntity(
            id = 1L, title = "Secret Personal Watch", amount = 8500.0,
            type = TransactionType.EXPENSE, category = "Shopping",
            financeScope = FinanceScope.PERSONAL
        )
        val familyTx = TransactionEntity(
            id = 2L, title = "Family Dinner", amount = 2200.0,
            type = TransactionType.EXPENSE, category = "Food & Dining",
            financeScope = FinanceScope.FAMILY, familyId = famId, serverId = s1, customLabel = "🍕 Treat"
        )
        val attr = mapOf(
            s1 to FamilyTxAttribution(s1, famId, "u1", "Jordan", "u2", "Taylor", "c1")
        )

        // Family CSV export filters strictly FinanceScope.FAMILY and includes PaidBy/EnteredBy/Label
        val familyCsvResult = ExportImportService.exportCsv(
            context = context,
            transactions = listOf(personalTx, familyTx),
            currencyCode = "INR",
            options = ExportOptions(ExportFormat.CSV, DateFilterType.ALL_TIME, scopeFilter = FinanceScope.FAMILY),
            txAttributionByServerId = attr,
            workspaceName = "Oceanview Loft"
        )
        val famCsv = familyCsvResult.file.readText()
        assertFalse(famCsv.contains("Secret Personal Watch"))
        assertTrue(famCsv.contains("Family Dinner"))
        assertTrue(famCsv.contains("Jordan"))
        assertTrue(famCsv.contains("Taylor"))
        assertTrue(famCsv.contains("🍕 Treat"))

        // Personal Excel export filters strictly FinanceScope.PERSONAL
        val personalXlsx = ExportImportService.exportExcel(
            context = context,
            transactions = listOf(personalTx, familyTx),
            currencyCode = "INR",
            options = ExportOptions(ExportFormat.EXCEL, DateFilterType.ALL_TIME, scopeFilter = FinanceScope.PERSONAL)
        )
        val pXml = personalXlsx.file.readText()
        assertTrue(pXml.contains("Secret Personal Watch"))
        assertFalse(pXml.contains("Family Dinner"))
    }

    @Test
    fun testComputeBudgetOverviewTotalsAvoidsDoubleCountingAndShowsFamilyMonthSpendWhenEmpty() {
        val monthYear = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val famId = UUID.randomUUID().toString()
        val overallBudget = BudgetEntity(
            id = 1L,
            categoryName = "Overall",
            monthlyLimit = 20000.0,
            monthYear = monthYear,
            periodType = "MONTHLY",
            financeScope = FinanceScope.FAMILY,
            familyId = famId
        )
        val groceriesBudget = BudgetEntity(
            id = 2L,
            categoryName = "Groceries",
            monthlyLimit = 5000.0,
            monthYear = monthYear,
            periodType = "MONTHLY",
            financeScope = FinanceScope.FAMILY,
            familyId = famId
        )
        val txs = listOf(
            TransactionEntity(id = 10L, title = "Veggies", amount = 3000.0, type = TransactionType.EXPENSE, category = "Groceries", financeScope = FinanceScope.FAMILY, familyId = famId),
            TransactionEntity(id = 11L, title = "Snacks", amount = 1000.0, type = TransactionType.EXPENSE, category = "Food & Dining", financeScope = FinanceScope.FAMILY, familyId = famId),
            TransactionEntity(id = 12L, title = "Salary", amount = 50000.0, type = TransactionType.INCOME, category = "Income", financeScope = FinanceScope.FAMILY, familyId = famId)
        )

        // When both Overall (20000) and Groceries (5000) exist, spend is 4000 (not 3000+4000=7000) and cap is 20000 (not 25000)
        val withOverall = computeBudgetOverviewTotals(
            filteredBudgets = listOf(overallBudget, groceriesBudget),
            transactions = txs,
            isFamilyMode = true,
            familyFallbackOverall = null,
            selectedPeriodFilter = "ALL"
        )
        assertEquals(20000.0, withOverall.totalBudgeted, 0.01)
        assertEquals(4000.0, withOverall.totalSpent, 0.01)
        assertTrue(withOverall.hasOverallCap)

        // When Family mode has zero explicit budgets, totalSpent still surfaces actual monthly family spend (4000)
        val emptyFamily = computeBudgetOverviewTotals(
            filteredBudgets = emptyList(),
            transactions = txs,
            isFamilyMode = true,
            familyFallbackOverall = null,
            selectedPeriodFilter = "ALL"
        )
        assertEquals(0.0, emptyFamily.totalBudgeted, 0.01)
        assertEquals(4000.0, emptyFamily.totalSpent, 0.01)
        assertFalse(emptyFamily.hasOverallCap)
    }

    @Test
    fun testEmptyFamilyBudgetShowsMemberSpendAndChatCtaForMember() {
        val famId = UUID.randomUUID().toString()
        val u1 = UUID.randomUUID().toString()
        val u2 = UUID.randomUUID().toString()
        val txId = UUID.randomUUID().toString()
        var chatOpened = false

        val memberState = CashFlowUiState(
            allTransactions = listOf(
                TransactionEntity(
                    id = 10L,
                    title = "Groceries",
                    amount = 1500.0,
                    type = TransactionType.EXPENSE,
                    category = "Groceries",
                    financeScope = FinanceScope.FAMILY,
                    familyId = famId,
                    createdByUserId = u1,
                    serverId = txId
                )
            ),
            budgets = emptyList(),
            activeMode = FinancialMode.FAMILY,
            activeFamily = FamilyInfo(famId, "Rosh Family", "🏡", u1, 0.0),
            activeFamilyMembers = listOf(
                FamilyMemberInfo("m1", famId, u1, "Arun", "🦁", role = FamilyRole.OWNER),
                FamilyMemberInfo("m2", famId, u2, "Sandeep", "🙂", role = FamilyRole.MEMBER)
            ),
            activeFamilyRole = FamilyRole.MEMBER,
            txAttributionByServerId = mapOf(
                txId to FamilyTxAttribution(txId, famId, u1, "Arun", u1, "Arun", "c1")
            )
        )

        composeTestRule.setContent {
            CashFlowTheme {
                BudgetsAndGoalsScreen(
                    state = memberState,
                    onSaveBudget = { _, _, _, _, _ -> },
                    onDeleteBudget = {},
                    onSaveSavingsGoal = {},
                    onDeleteSavingsGoal = {},
                    onDepositToGoal = { _, _ -> },
                    onOpenFamilyChat = { chatOpened = true }
                )
            }
        }

        composeTestRule.onNodeWithText("Spent This Month").assertExists()
        composeTestRule.onNodeWithTag("family_budget_member_breakdown").assertExists()
        composeTestRule.onNodeWithTag("budgets_lazy_column")
            .performScrollToNode(hasTestTag("family_budget_chat_cta"))
        composeTestRule.onNodeWithTag("family_budget_chat_cta").performClick()
        assertTrue(chatOpened)
    }
}
