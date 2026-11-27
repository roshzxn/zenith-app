package com.zenith.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zenith.app.data.models.AutoPayReminderEntity
import com.zenith.app.data.models.BudgetEntity
import com.zenith.app.data.models.CategoryEntity
import com.zenith.app.data.models.SavingsGoalEntity
import com.zenith.app.data.models.TransactionEntity
import com.zenith.app.data.models.TransactionType
import com.zenith.app.data.repository.FamilyTxAttribution
import com.zenith.app.data.upi.RecurringSuggestion
import com.zenith.app.domain.FamilyMemberInfo
import com.zenith.app.domain.FinancialMode
import com.zenith.app.ui.components.AddAutoPayReminderDialog
import com.zenith.app.ui.components.CleanCard
import com.zenith.app.ui.components.GlassCard
import com.zenith.app.ui.theme.ZenithTheme
import com.zenith.app.ui.viewmodel.CashFlowUiState
import java.text.SimpleDateFormat
import java.util.*

private val BUDGET_MONTH_FMT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy-MM", Locale.US)
}
private val BUDGET_YEAR_FMT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy", Locale.US)
}

internal data class BudgetOverviewTotals(
    val totalBudgeted: Double,
    val totalSpent: Double,
    val hasOverallCap: Boolean
)

internal data class MemberSpendContribution(
    val userId: String,
    val displayName: String,
    val emoji: String,
    val amount: Double
)

private fun TransactionEntity.matchesBudgetWindow(
    budget: BudgetEntity,
    sdfMonth: SimpleDateFormat,
    sdfYear: SimpleDateFormat,
    nowCal: Calendar
): Boolean {
    if (type != TransactionType.EXPENSE) return false
    if (!budget.categoryName.equals("Overall", ignoreCase = true) && category != budget.categoryName) {
        return false
    }
    val date = Date(dateMillis)
    return when (budget.periodType) {
        "MONTHLY" -> budget.monthYear.isNotBlank() && sdfMonth.format(date) == budget.monthYear
        "YEARLY" -> budget.monthYear.length >= 4 && sdfYear.format(date) == budget.monthYear.take(4)
        "WEEKLY" -> {
            val txCal = Calendar.getInstance().apply { timeInMillis = dateMillis }
            txCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                txCal.get(Calendar.WEEK_OF_YEAR) == nowCal.get(Calendar.WEEK_OF_YEAR)
        }
        else -> true
    }
}

/**
 * Computes total expenses for [budget] inside its configured period window
 * (`MONTHLY`, `WEEKLY`, `YEARLY`, or `CUSTOM`).
 */
internal fun calculateBudgetSpent(
    budget: BudgetEntity,
    transactions: List<TransactionEntity>,
    nowMillis: Long = System.currentTimeMillis()
): Double {
    val sdfMonth = BUDGET_MONTH_FMT.get()!!
    val sdfYear = BUDGET_YEAR_FMT.get()!!
    val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }

    return transactions
        .asSequence()
        .filter { it.matchesBudgetWindow(budget, sdfMonth, sdfYear, nowCal) }
        .sumOf { it.amount }
}

internal fun calculateBudgetSpentByMember(
    budget: BudgetEntity,
    transactions: List<TransactionEntity>,
    attributionByServerId: Map<String, FamilyTxAttribution>,
    members: List<FamilyMemberInfo>,
    nowMillis: Long = System.currentTimeMillis()
): List<MemberSpendContribution> {
    val sdfMonth = BUDGET_MONTH_FMT.get()!!
    val sdfYear = BUDGET_YEAR_FMT.get()!!
    val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val memberByUserId = members.associateBy { it.userId }
    val totals = LinkedHashMap<String, MemberSpendContribution>()

    transactions.forEach { tx ->
        if (!tx.matchesBudgetWindow(budget, sdfMonth, sdfYear, nowCal)) return@forEach
        val attr = tx.serverId?.let { attributionByServerId[it] }
        val userId = attr?.paidByUserId ?: tx.createdByUserId ?: "unknown"
        val member = memberByUserId[userId]
        val name = (member?.displayName ?: attr?.paidByName ?: "Member").trim().ifBlank { "Member" }
        val emoji = member?.avatarEmoji ?: "🙂"
        val prev = totals[userId]
        totals[userId] = if (prev == null) {
            MemberSpendContribution(userId = userId, displayName = name, emoji = emoji, amount = tx.amount)
        } else {
            prev.copy(amount = prev.amount + tx.amount)
        }
    }
    return totals.values.sortedByDescending { it.amount }
}

internal fun computeBudgetOverviewTotals(
    filteredBudgets: List<BudgetEntity>,
    transactions: List<TransactionEntity>,
    isFamilyMode: Boolean,
    familyFallbackOverall: Double?,
    selectedPeriodFilter: String,
    nowMillis: Long = System.currentTimeMillis()
): BudgetOverviewTotals {
    val sdfMonth = BUDGET_MONTH_FMT.get()!!
    val currentMonthYear = sdfMonth.format(Date(nowMillis))
    val overallBudgets = filteredBudgets.filter { it.categoryName.equals("Overall", ignoreCase = true) }
    val categoryBudgets = filteredBudgets.filterNot { it.categoryName.equals("Overall", ignoreCase = true) }

    val explicitOverall = overallBudgets.firstOrNull()
    if (explicitOverall != null) {
        val categoryLimitSum = categoryBudgets.sumOf { it.monthlyLimit }
        return BudgetOverviewTotals(
            totalBudgeted = maxOf(explicitOverall.monthlyLimit, categoryLimitSum),
            totalSpent = calculateBudgetSpent(explicitOverall, transactions, nowMillis),
            hasOverallCap = true
        )
    }

    if (familyFallbackOverall != null && (selectedPeriodFilter == "ALL" || selectedPeriodFilter == "MONTHLY")) {
        val fallbackBudget = BudgetEntity(
            categoryName = "Overall",
            monthlyLimit = familyFallbackOverall,
            monthYear = currentMonthYear,
            periodType = "MONTHLY"
        )
        return BudgetOverviewTotals(
            totalBudgeted = maxOf(familyFallbackOverall, categoryBudgets.sumOf { it.monthlyLimit }),
            totalSpent = calculateBudgetSpent(fallbackBudget, transactions, nowMillis),
            hasOverallCap = true
        )
    }

    if (categoryBudgets.isNotEmpty()) {
        return BudgetOverviewTotals(
            totalBudgeted = categoryBudgets.sumOf { it.monthlyLimit },
            totalSpent = categoryBudgets.sumOf { calculateBudgetSpent(it, transactions, nowMillis) },
            hasOverallCap = false
        )
    }

    val familyMonthSpend = if (isFamilyMode) {
        calculateBudgetSpent(
            BudgetEntity(
                categoryName = "Overall",
                monthlyLimit = 0.0,
                monthYear = currentMonthYear,
                periodType = "MONTHLY"
            ),
            transactions,
            nowMillis
        )
    } else 0.0

    return BudgetOverviewTotals(
        totalBudgeted = 0.0,
        totalSpent = familyMonthSpend,
        hasOverallCap = false
    )
}

@Composable
fun BudgetsAndGoalsScreen(
    state: CashFlowUiState,
    onSaveBudget: (categoryName: String, limit: Double, periodType: String, customPeriodName: String, budgetId: Long) -> Unit,
    onDeleteBudget: (BudgetEntity) -> Unit,
    onSaveSavingsGoal: (SavingsGoalEntity) -> Unit,
    onDeleteSavingsGoal: (SavingsGoalEntity) -> Unit,
    onDepositToGoal: (SavingsGoalEntity, Double) -> Unit,
    onSaveAutoPayReminder: ((title: String, amount: Double, category: String, paymentMethod: String, frequency: String, dueDayOfMonth: Int, nextDueDateMillis: Long, isAutoDebit: Boolean, reminderDaysBefore: Int, notes: String, reminderId: Long) -> Unit)? = null,
    onDeleteAutoPayReminder: ((AutoPayReminderEntity) -> Unit)? = null,
    onTogglePauseAutoPayReminder: ((AutoPayReminderEntity) -> Unit)? = null,
    onMarkAutoPayPaid: ((AutoPayReminderEntity) -> Unit)? = null,
    recurringSuggestions: List<RecurringSuggestion> = emptyList(),
    onAddRecurringSuggestion: ((RecurringSuggestion) -> Unit)? = null,
    onDismissRecurringSuggestion: ((RecurringSuggestion) -> Unit)? = null,
    onOpenFamilyChat: (() -> Unit)? = null,
    bottomContentPadding: Dp = 144.dp
) {
    var showAddBudgetModal by remember { mutableStateOf(false) }
    var editingBudget by remember { mutableStateOf<BudgetEntity?>(null) }
    var budgetToDelete by remember { mutableStateOf<BudgetEntity?>(null) }

    var showAddGoalModal by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<SavingsGoalEntity?>(null) }
    var goalToDelete by remember { mutableStateOf<SavingsGoalEntity?>(null) }
    var selectedGoalForDeposit by remember { mutableStateOf<SavingsGoalEntity?>(null) }

    var showAddAutoPayModal by remember { mutableStateOf(false) }
    var editingAutoPay by remember { mutableStateOf<AutoPayReminderEntity?>(null) }
    var autoPayToDelete by remember { mutableStateOf<AutoPayReminderEntity?>(null) }

    var selectedPeriodFilter by remember { mutableStateOf("ALL") }
    val isFamilyMode = state.activeMode == FinancialMode.FAMILY
    val canEditBudgets = !isFamilyMode || (state.activeFamilyRole?.canAdmin() == true)

    val filteredBudgets = remember(state.budgets, selectedPeriodFilter, isFamilyMode) {
        when (selectedPeriodFilter) {
            "MONTHLY" -> state.budgets.filter { it.periodType == "MONTHLY" }
            "WEEKLY" -> state.budgets.filter { it.periodType == "WEEKLY" }
            "YEARLY" -> state.budgets.filter { it.periodType == "YEARLY" }
            "CUSTOM" -> state.budgets.filter { it.periodType == "CUSTOM" || it.periodType == "YEARLY" }
            else -> state.budgets
        }
    }

    val familyFallbackOverall = remember(isFamilyMode, state.budgets, state.activeFamily) {
        if (isFamilyMode && state.budgets.none { it.categoryName.equals("Overall", ignoreCase = true) }) {
            (state.activeFamily?.monthlyBudgetLimit ?: 0.0).takeIf { it > 0.0 }
        } else null
    }

    // Use allTransactions so Activity filters never skew Budget burn rates, and avoid double-counting Overall + categories
    val overviewTotals = remember(
        filteredBudgets,
        state.allTransactions,
        isFamilyMode,
        familyFallbackOverall,
        selectedPeriodFilter
    ) {
        computeBudgetOverviewTotals(
            filteredBudgets = filteredBudgets,
            transactions = state.allTransactions,
            isFamilyMode = isFamilyMode,
            familyFallbackOverall = familyFallbackOverall,
            selectedPeriodFilter = selectedPeriodFilter
        )
    }
    val totalBudgeted = overviewTotals.totalBudgeted
    val totalSpentOnBudgets = overviewTotals.totalSpent
    val totalRemaining = remember(totalBudgeted, totalSpentOnBudgets) { totalBudgeted - totalSpentOnBudgets }
    val overallProgress = remember(totalBudgeted, totalSpentOnBudgets) {
        if (totalBudgeted > 0) (totalSpentOnBudgets / totalBudgeted).toFloat().coerceIn(0f, 1f) else 0f
    }
    val overbudgetCount = remember(filteredBudgets, state.allTransactions) {
        filteredBudgets.count { budget -> calculateBudgetSpent(budget, state.allTransactions) > budget.monthlyLimit }
    }
    val overviewMemberSpend = remember(
        isFamilyMode,
        filteredBudgets,
        state.allTransactions,
        state.txAttributionByServerId,
        state.activeFamilyMembers
    ) {
        if (!isFamilyMode) emptyList()
        else {
            val sdfMonth = BUDGET_MONTH_FMT.get()!!
            val overallOrMonth = filteredBudgets.firstOrNull { it.categoryName.equals("Overall", ignoreCase = true) }
                ?: BudgetEntity(
                    categoryName = "Overall",
                    monthlyLimit = 0.0,
                    monthYear = sdfMonth.format(Date()),
                    periodType = "MONTHLY"
                )
            calculateBudgetSpentByMember(
                budget = overallOrMonth,
                transactions = state.allTransactions,
                attributionByServerId = state.txAttributionByServerId,
                members = state.activeFamilyMembers
            )
        }
    }

    val numberFormat = ZenithTheme.numberFormat
    fun formatMoneyOrMask(amount: Double, decimals: Int = 2): String =
        if (state.isBalanceHidden) "${state.currencySymbol}••••"
        else "${state.currencySymbol}${com.zenith.app.data.util.AmountFormatter.formatNumber(amount, decimals, numberFormat)}"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("budgets_lazy_column"),
        contentPadding = PaddingValues(top = 16.dp, bottom = bottomContentPadding)
    ) {
        // --- 1. BUDGET HEALTH OVERVIEW CLEAN CARD ---
        item {
            CleanCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                backgroundColor = ZenithTheme.cardGlassBackground
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = if (isFamilyMode) "Shared Family Budget" else "Budget Overview",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZenithTheme.textPrimary
                        )
                        Text(
                            text = if (isFamilyMode && state.activeFamily != null) {
                                val modeHint = if (canEditBudgets) "Admin controls active" else "Member view • Shared limits"
                                "${state.activeFamily.avatarEmoji} ${state.activeFamily.name} • $modeHint"
                            } else {
                                "Tracking ${state.budgets.size} active spending limits"
                            },
                            fontSize = 12.sp,
                            color = if (isFamilyMode) ZenithTheme.accentCyan else ZenithTheme.textSecondary
                        )
                    }

                    AnimatedVisibility(visible = overbudgetCount > 0) {
                        Surface(
                            color = ZenithTheme.accentRed.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, ZenithTheme.accentRed.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = ZenithTheme.accentRed,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "$overbudgetCount Over",
                                    color = ZenithTheme.accentRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = if (totalBudgeted <= 0.0 && isFamilyMode) "Spent This Month" else "Total Spent",
                            fontSize = 11.sp,
                            color = ZenithTheme.textSecondary
                        )
                        Text(
                            text = formatMoneyOrMask(totalSpentOnBudgets),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (totalSpentOnBudgets > totalBudgeted && totalBudgeted > 0) ZenithTheme.accentRed else ZenithTheme.textPrimary
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Total Budgeted",
                            fontSize = 11.sp,
                            color = ZenithTheme.textSecondary
                        )
                        Text(
                            text = if (totalBudgeted > 0.0) formatMoneyOrMask(totalBudgeted) else "Not Set",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (totalBudgeted > 0.0) ZenithTheme.textPrimary else ZenithTheme.textMuted
                        )
                    }
                }

                if (totalBudgeted > 0.0) {
                    Spacer(modifier = Modifier.height(12.dp))

                    LinearProgressIndicator(
                        progress = { overallProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(CircleShape),
                        color = when {
                            overallProgress >= 1.0f -> ZenithTheme.accentRed
                            overallProgress >= 0.8f -> ZenithTheme.accentAmber
                            else -> ZenithTheme.accentGreen
                        },
                        trackColor = ZenithTheme.cardVariantSurface,
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (totalRemaining >= 0) {
                                "Remaining: ${formatMoneyOrMask(totalRemaining)}"
                            } else {
                                "Over budget by ${formatMoneyOrMask(-totalRemaining)}"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (totalRemaining < 0) ZenithTheme.accentRed else ZenithTheme.accentGreen
                        )

                        Text(
                            text = "${(overallProgress * 100).toInt()}% Used",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZenithTheme.textPrimary
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (isFamilyMode && !canEditBudgets) {
                            "No monthly cap set yet — showing live shared spend for this month."
                        } else {
                            "Create a category or overall cap below to track % used."
                        },
                        fontSize = 11.sp,
                        color = ZenithTheme.textSecondary
                    )
                }

                if (isFamilyMode && overviewMemberSpend.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Monthly Spend by Member",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ZenithTheme.textSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("family_budget_member_breakdown"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(overviewMemberSpend, key = { "overview_member_${it.userId}" }) { contrib ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ZenithTheme.cardVariantSurface,
                                border = BorderStroke(1.dp, ZenithTheme.borderColor)
                            ) {
                                Text(
                                    text = "${contrib.emoji} ${contrib.displayName}: ${formatMoneyOrMask(contrib.amount, decimals = 0)}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ZenithTheme.textPrimary,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- 2. CATEGORY BUDGETS HEADER ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = if (isFamilyMode) "Shared Category Budgets" else "Category Budgets",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = ZenithTheme.textPrimary
                    )
                    Text(
                        text = if (canEditBudgets) {
                            if (isFamilyMode) "Set shared limits for monthly, weekly or yearly periods"
                            else "Set spending limits for monthly, weekly or custom periods"
                        } else {
                            "Only Family Admins/Owner can create or edit shared budgets"
                        },
                        fontSize = 12.sp,
                        color = ZenithTheme.textSecondary
                    )
                }

                if (canEditBudgets) {
                    Button(
                        onClick = {
                            editingBudget = null
                            showAddBudgetModal = true
                        },
                        modifier = Modifier
                            .height(42.dp)
                            .testTag("add_budget_btn"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Budget", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Horizontally Scrollable Period Filter Chips
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedPeriodFilter == "ALL",
                        onClick = { selectedPeriodFilter = "ALL" },
                        label = { Text("All (${state.budgets.size})", fontSize = 12.sp) }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedPeriodFilter == "MONTHLY",
                        onClick = { selectedPeriodFilter = "MONTHLY" },
                        label = { Text("Monthly", fontSize = 12.sp) }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedPeriodFilter == "WEEKLY",
                        onClick = { selectedPeriodFilter = "WEEKLY" },
                        label = { Text("Weekly", fontSize = 12.sp) }
                    )
                }
                item {
                    val lastFilterKey = if (isFamilyMode) "YEARLY" else "CUSTOM"
                    val lastFilterLabel = if (isFamilyMode) "Yearly" else "Custom"
                    FilterChip(
                        selected = selectedPeriodFilter == lastFilterKey,
                        onClick = { selectedPeriodFilter = lastFilterKey },
                        label = { Text(lastFilterLabel, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        // --- 3. BUDGET ITEMS LIST ---
        if (filteredBudgets.isEmpty()) {
            item {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    backgroundColor = ZenithTheme.glassCardBg
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = if (isFamilyMode && !canEditBudgets) Icons.Default.Groups else Icons.Default.PieChart,
                            contentDescription = null,
                            tint = if (isFamilyMode) ZenithTheme.accentCyan else ZenithTheme.textMuted,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isFamilyMode) "No shared budgets yet" else "No budgets yet",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = ZenithTheme.textPrimary
                        )
                        Text(
                            text = when {
                                isFamilyMode && !canEditBudgets ->
                                    "Your Family Admin hasn't added category spending limits yet. You can ask them in Family Chat."
                                isFamilyMode ->
                                    "Create an Overall or category limit so all family members can track shared burn rates."
                                else ->
                                    "Set your first spending limit to start controlling expenses."
                            },
                            fontSize = 12.sp,
                            color = ZenithTheme.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        if (isFamilyMode && !canEditBudgets && onOpenFamilyChat != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = onOpenFamilyChat,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("family_budget_chat_cta")
                            ) {
                                Icon(
                                    Icons.Default.ChatBubbleOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Ask in Family Chat", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        } else {
            items(filteredBudgets, key = { "budget_${it.id}" }) { budget ->
                val categorySpent = remember(state.allTransactions, budget) {
                    calculateBudgetSpent(budget, state.allTransactions)
                }
                val memberSpendForBudget = remember(
                    isFamilyMode,
                    state.allTransactions,
                    state.txAttributionByServerId,
                    state.activeFamilyMembers,
                    budget
                ) {
                    if (!isFamilyMode) emptyList()
                    else calculateBudgetSpentByMember(
                        budget = budget,
                        transactions = state.allTransactions,
                        attributionByServerId = state.txAttributionByServerId,
                        members = state.activeFamilyMembers
                    )
                }

                val remaining = budget.monthlyLimit - categorySpent
                val progress = if (budget.monthlyLimit > 0) (categorySpent / budget.monthlyLimit).toFloat().coerceIn(0f, 1f) else 0f
                val isOver = categorySpent > budget.monthlyLimit
                val isNearLimit = progress >= 0.8f && !isOver

                val periodLabel = when (budget.periodType) {
                    "WEEKLY" -> "Weekly"
                    "YEARLY" -> "Yearly"
                    "CUSTOM" -> if (budget.customPeriodName.isNotBlank()) budget.customPeriodName else "Custom"
                    else -> "Monthly"
                }

                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .animateItem(),
                    backgroundColor = if (isOver) ZenithTheme.accentRed.copy(alpha = 0.06f) else ZenithTheme.glassCardBg
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = budget.categoryName,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ZenithTheme.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = ZenithTheme.cardVariantSurface,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = periodLabel,
                                        fontSize = 10.sp,
                                        color = ZenithTheme.textSecondary,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                        maxLines = 1
                                    )
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                val badgeColor = when {
                                    isOver -> ZenithTheme.accentRed
                                    isNearLimit -> ZenithTheme.accentAmber
                                    else -> ZenithTheme.accentGreen
                                }
                                Surface(
                                    color = badgeColor.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = when {
                                            isOver -> "Over Budget"
                                            isNearLimit -> "Near Limit"
                                            else -> "Healthy"
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = badgeColor,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                        maxLines = 1
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            if (isOver) {
                                Text(
                                    text = "Over limit by ${formatMoneyOrMask(categorySpent - budget.monthlyLimit)}!",
                                    fontSize = 12.sp,
                                    color = ZenithTheme.accentRed,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                Text(
                                    text = "${formatMoneyOrMask(remaining)} left of ${formatMoneyOrMask(budget.monthlyLimit)} limit",
                                    fontSize = 12.sp,
                                    color = ZenithTheme.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (canEditBudgets) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 4.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        editingBudget = budget
                                        showAddBudgetModal = true
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Edit",
                                        tint = ZenithTheme.accentGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                IconButton(
                                    onClick = { budgetToDelete = budget },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = ZenithTheme.accentRed.copy(alpha = 0.8f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = progress.coerceIn(0f, 1f),
                        animationSpec = androidx.compose.animation.core.tween(500),
                        label = "budget_progress"
                    )
                    val progressColor = when {
                        isOver -> ZenithTheme.accentRed
                        progress >= 0.75f -> ZenithTheme.accentAmber
                        else -> ZenithTheme.accentGreen
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Spent: ${formatMoneyOrMask(categorySpent)}",
                            fontSize = 12.sp,
                            color = ZenithTheme.textSecondary,
                            maxLines = 1
                        )
                        Text(
                            text = "${(progress * 100).toInt()}% Used",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = progressColor,
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                        color = progressColor,
                        trackColor = ZenithTheme.cardVariantSurface,
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )

                    if (isFamilyMode && memberSpendForBudget.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(memberSpendForBudget, key = { "b_${budget.id}_m_${it.userId}" }) { contrib ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = ZenithTheme.cardVariantSurface
                                ) {
                                    Text(
                                        text = "${contrib.emoji} ${contrib.displayName}: ${formatMoneyOrMask(contrib.amount, decimals = 0)}",
                                        fontSize = 10.sp,
                                        color = ZenithTheme.textSecondary,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (isFamilyMode) {
            item {
                Spacer(modifier = Modifier.height(14.dp))
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("family_budgets_scope_note"),
                    backgroundColor = ZenithTheme.glassCardBg
                ) {
                    Text(
                        text = "Personal Savings Goals & Auto-Pay reminders remain isolated inside Personal Mode.",
                        fontSize = 12.sp,
                        color = ZenithTheme.textSecondary,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        // --- 4. SAVINGS GOALS SECTION (Personal Mode Only) ---
        if (!isFamilyMode) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Savings Goals",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = ZenithTheme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            editingGoal = null
                            showAddGoalModal = true
                        },
                        modifier = Modifier
                            .height(38.dp)
                            .testTag("add_goal_btn"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Goal", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Track your savings progress towards big purchases",
                    fontSize = 12.sp,
                    color = ZenithTheme.textSecondary,
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        if (state.savingsGoals.isEmpty()) {
            item {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    backgroundColor = ZenithTheme.glassCardBg
                ) {
                    Text(
                        text = "No savings goals added yet. Click 'New Goal' to set target savings milestones!",
                        fontSize = 13.sp,
                        color = ZenithTheme.textSecondary,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(state.savingsGoals, key = { "goal_${it.id}" }) { goal ->
                val progress = if (goal.targetAmount > 0) (goal.currentAmount / goal.targetAmount).toFloat().coerceIn(0f, 1f) else 0f
                val percentInt = (progress * 100).toInt()

                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .animateItem(),
                    backgroundColor = ZenithTheme.glassCardBg
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(ZenithTheme.accentGreen.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Savings,
                                    contentDescription = null,
                                    tint = ZenithTheme.accentGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = goal.title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ZenithTheme.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "$percentInt% Achieved",
                                    fontSize = 12.sp,
                                    color = ZenithTheme.accentGreen,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            OutlinedButton(
                                onClick = { selectedGoalForDeposit = goal },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("+ Deposit", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }

                            IconButton(
                                onClick = {
                                    editingGoal = goal
                                    showAddGoalModal = true
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = ZenithTheme.accentGreen, modifier = Modifier.size(15.dp))
                            }

                            IconButton(
                                onClick = { goalToDelete = goal },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = ZenithTheme.accentRed.copy(alpha = 0.8f), modifier = Modifier.size(15.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Saved: ${formatMoneyOrMask(goal.currentAmount)}",
                            fontSize = 12.sp,
                            color = ZenithTheme.textSecondary
                        )
                        Text(
                            text = "Target: ${formatMoneyOrMask(goal.targetAmount)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ZenithTheme.textPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    val animatedGoalProgress by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = progress.coerceIn(0f, 1f),
                        animationSpec = androidx.compose.animation.core.tween(500),
                        label = "goal_progress"
                    )

                    LinearProgressIndicator(
                        progress = { animatedGoalProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                        color = if (progress >= 1f) ZenithTheme.accentAmber else ZenithTheme.accentGreen,
                        trackColor = ZenithTheme.cardVariantSurface,
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )
                }
            }
        }

        // --- 5. AUTO-PAY & RECURRING BILLS SECTION ---
        item {
            Spacer(modifier = Modifier.height(22.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Auto-Pay & Bills",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZenithTheme.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ZenithTheme.accentPurple.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, ZenithTheme.accentPurple.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = "${state.autoPayReminders.count { !it.isPaused }} Active",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.accentPurple,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            editingAutoPay = null
                            showAddAutoPayModal = true
                        },
                        modifier = Modifier
                            .height(38.dp)
                            .testTag("add_autopay_btn"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Reminder", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Track subscriptions, utilities & avoid late fees",
                    fontSize = 12.sp,
                    color = ZenithTheme.textSecondary,
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        if (recurringSuggestions.isNotEmpty()) {
            items(recurringSuggestions, key = { "rec_suggest_${it.title.lowercase()}_${it.frequency}" }) { suggestion ->
                CleanCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag("card_recurring_suggestion"),
                    backgroundColor = ZenithTheme.accentAmber.copy(alpha = 0.09f),
                    borderColor = ZenithTheme.accentAmber.copy(alpha = 0.30f),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = suggestion.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ZenithTheme.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = ZenithTheme.accentAmber.copy(alpha = 0.18f)
                                ) {
                                    Text(
                                        text = suggestion.frequency,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ZenithTheme.accentAmber,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Detected ${suggestion.occurrenceCount}× around ${formatMoneyOrMask(suggestion.amount)} • Track as bill?",
                                fontSize = 11.5.sp,
                                color = ZenithTheme.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { onAddRecurringSuggestion?.invoke(suggestion) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Track",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ZenithTheme.accentGreen
                                )
                            }
                            IconButton(
                                onClick = { onDismissRecurringSuggestion?.invoke(suggestion) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss suggestion",
                                    tint = ZenithTheme.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.autoPayReminders.isEmpty()) {
            item {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    backgroundColor = ZenithTheme.glassCardBg
                ) {
                    Text(
                        text = "No recurring bills or subscriptions added yet. Tap '+ New Reminder' to track Wi-Fi, Netflix, rent, or electricity deadlines!",
                        fontSize = 13.sp,
                        color = ZenithTheme.textSecondary,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(state.autoPayReminders, key = { "autopay_${it.id}" }) { reminder ->
                val now = System.currentTimeMillis()
                val diffDays = ((reminder.nextDueDateMillis - now) / (1000 * 60 * 60 * 24)).toInt()
                val dueDateFormatted = remember(reminder.nextDueDateMillis) {
                    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(reminder.nextDueDateMillis))
                }

                CleanCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    backgroundColor = ZenithTheme.cardGlassBackground
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(if (reminder.isAutoDebit) ZenithTheme.accentPurple.copy(alpha = 0.16f) else ZenithTheme.accentGreen.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (reminder.isAutoDebit) Icons.Default.FlashOn else Icons.Default.Receipt,
                                    contentDescription = null,
                                    tint = if (reminder.isAutoDebit) ZenithTheme.accentPurple else ZenithTheme.accentGreen,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = reminder.title,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ZenithTheme.textPrimary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (reminder.isAutoDebit) ZenithTheme.accentPurple.copy(alpha = 0.16f) else ZenithTheme.accentCyan.copy(alpha = 0.16f)
                                    ) {
                                        Text(
                                            text = if (reminder.isAutoDebit) "Auto-Debit" else "Manual Pay",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (reminder.isAutoDebit) ZenithTheme.accentPurple else ZenithTheme.accentCyan,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = "${reminder.frequency.lowercase().replaceFirstChar { it.uppercase() }} • Due $dueDateFormatted",
                                    fontSize = 11.sp,
                                    color = ZenithTheme.textSecondary
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = formatMoneyOrMask(reminder.amount),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.textPrimary
                            )

                            val badgeColor = when {
                                reminder.isPaused -> ZenithTheme.textMuted
                                diffDays <= 0 -> ZenithTheme.accentRed
                                diffDays <= reminder.reminderDaysBefore -> ZenithTheme.accentAmber
                                else -> ZenithTheme.accentGreen
                            }
                            val badgeText = when {
                                reminder.isPaused -> "Paused"
                                diffDays < 0 -> "Overdue"
                                diffDays == 0 -> "Due Today"
                                else -> "In $diffDays days"
                            }

                            Text(
                                text = badgeText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = {
                                    editingAutoPay = reminder
                                    showAddAutoPayModal = true
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = ZenithTheme.textSecondary, modifier = Modifier.size(16.dp))
                            }

                            IconButton(
                                onClick = { onTogglePauseAutoPayReminder?.invoke(reminder) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (reminder.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (reminder.isPaused) "Resume" else "Pause",
                                    tint = ZenithTheme.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            IconButton(
                                onClick = { autoPayToDelete = reminder },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = ZenithTheme.accentRed.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                            }
                        }

                        Button(
                            onClick = { onMarkAutoPayPaid?.invoke(reminder) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ZenithTheme.accentGreen.copy(alpha = 0.18f),
                                contentColor = ZenithTheme.accentGreen
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Mark Paid", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        }
    }

    // --- MODALS & DIALOGS ---

    if (showAddBudgetModal) {
        AddOrEditBudgetModal(
            categories = state.categories,
            existingBudget = editingBudget,
            currencySymbol = state.currencySymbol,
            isFamilyMode = isFamilyMode,
            onDismiss = {
                showAddBudgetModal = false
                editingBudget = null
            },
            onSave = { category, limit, periodType, customName ->
                onSaveBudget(
                    category,
                    limit,
                    periodType,
                    customName,
                    editingBudget?.id ?: 0L
                )
                showAddBudgetModal = false
                editingBudget = null
            }
        )
    }

    budgetToDelete?.let { budget ->
        AlertDialog(
            onDismissRequest = { budgetToDelete = null },
            title = { Text("Delete Budget", color = ZenithTheme.textPrimary) },
            text = { Text("Are you sure you want to delete the budget for '${budget.categoryName}'?", color = ZenithTheme.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteBudget(budget)
                        budgetToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidRed)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { budgetToDelete = null }) {
                    Text("Cancel", color = ZenithTheme.textSecondary)
                }
            },
            containerColor = ZenithTheme.cardSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showAddGoalModal) {
        AddOrEditGoalModal(
            currencySymbol = state.currencySymbol,
            existingGoal = editingGoal,
            onDismiss = {
                showAddGoalModal = false
                editingGoal = null
            },
            onSave = { title, target, current ->
                val goal = editingGoal?.copy(
                    title = title,
                    targetAmount = target,
                    currentAmount = current
                ) ?: SavingsGoalEntity(
                    title = title,
                    targetAmount = target,
                    currentAmount = current
                )
                onSaveSavingsGoal(goal)
                showAddGoalModal = false
                editingGoal = null
            }
        )
    }

    goalToDelete?.let { goal ->
        AlertDialog(
            onDismissRequest = { goalToDelete = null },
            title = { Text("Delete Goal", color = ZenithTheme.textPrimary) },
            text = { Text("Are you sure you want to delete the goal '${goal.title}'?", color = ZenithTheme.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSavingsGoal(goal)
                        goalToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidRed)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { goalToDelete = null }) {
                    Text("Cancel", color = ZenithTheme.textSecondary)
                }
            },
            containerColor = ZenithTheme.cardSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    selectedGoalForDeposit?.let { goal ->
        DepositGoalModal(
            goal = goal,
            currencySymbol = state.currencySymbol,
            onDismiss = { selectedGoalForDeposit = null },
            onDeposit = { amount ->
                onDepositToGoal(goal, amount)
                selectedGoalForDeposit = null
            }
        )
    }

    if (showAddAutoPayModal) {
        AddAutoPayReminderDialog(
            currencySymbol = state.currencySymbol,
            initialReminder = editingAutoPay,
            onDismiss = {
                showAddAutoPayModal = false
                editingAutoPay = null
            },
            onSave = { title, amount, category, paymentMethod, frequency, dueDayOfMonth, nextDueDateMillis, isAutoDebit, reminderDaysBefore, notes, reminderId ->
                onSaveAutoPayReminder?.invoke(
                    title, amount, category, paymentMethod, frequency, dueDayOfMonth, nextDueDateMillis, isAutoDebit, reminderDaysBefore, notes, reminderId
                )
                showAddAutoPayModal = false
                editingAutoPay = null
            }
        )
    }

    autoPayToDelete?.let { reminder ->
        AlertDialog(
            onDismissRequest = { autoPayToDelete = null },
            title = { Text("Delete Reminder?", fontWeight = FontWeight.Bold, color = ZenithTheme.textPrimary) },
            text = { Text("Are you sure you want to delete the reminder for '${reminder.title}'?", color = ZenithTheme.textSecondary, fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteAutoPayReminder?.invoke(reminder)
                        autoPayToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidRed)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { autoPayToDelete = null }) {
                    Text("Cancel")
                }
            },
            containerColor = ZenithTheme.cardSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
fun AddOrEditBudgetModal(
    categories: List<CategoryEntity>,
    existingBudget: BudgetEntity?,
    currencySymbol: String,
    isFamilyMode: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (categoryName: String, limit: Double, periodType: String, customPeriodName: String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val selectableCategories = remember(categories) {
        val overallEntry = CategoryEntity(name = "Overall", iconName = "AccountBalanceWallet", colorHex = "#06B6D4", type = TransactionType.EXPENSE)
        val expenseCats = categories.filter { it.type == TransactionType.EXPENSE || !it.name.equals("Income", ignoreCase = true) }
        (listOf(overallEntry) + expenseCats.filterNot { it.name.equals("Overall", ignoreCase = true) }).ifEmpty { listOf(overallEntry) }
    }
    var selectedCat by remember {
        mutableStateOf(existingBudget?.categoryName ?: selectableCategories.firstOrNull()?.name ?: "Food & Dining")
    }
    var limitText by remember {
        mutableStateOf(existingBudget?.monthlyLimit?.let { if (it > 0) it.toString() else "" } ?: "")
    }
    var selectedPeriodType by remember {
        mutableStateOf(
            existingBudget?.periodType?.let {
                if (isFamilyMode && it == "CUSTOM") "MONTHLY" else it
            } ?: "MONTHLY"
        )
    }
    var customPeriodTitle by remember {
        mutableStateOf(existingBudget?.customPeriodName ?: "")
    }
    val periodOptions = remember(isFamilyMode) {
        if (isFamilyMode) listOf("MONTHLY" to "Monthly", "WEEKLY" to "Weekly", "YEARLY" to "Yearly")
        else listOf("MONTHLY" to "Monthly", "WEEKLY" to "Weekly", "CUSTOM" to "Custom")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false, usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ZenithTheme.cardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .imePadding(),
            border = BorderStroke(1.dp, ZenithTheme.borderColor)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Text(
                    text = if (existingBudget == null) "Create Budget Limit" else "Edit Budget Limit",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZenithTheme.textPrimary
                )
                Text(
                    text = if (isFamilyMode) "Track shared spending for any category" else "Track and limit spending for any category",
                    fontSize = 12.sp,
                    color = ZenithTheme.textSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = limitText,
                    onValueChange = { limitText = it },
                    label = { Text("Budget Limit ($currencySymbol)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("budget_limit_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text("Budgeting Period", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ZenithTheme.textPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    periodOptions.forEach { (typeKey, label) ->
                        FilterChip(
                            selected = selectedPeriodType == typeKey,
                            onClick = { selectedPeriodType = typeKey },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }

                if (!isFamilyMode && selectedPeriodType == "CUSTOM") {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customPeriodTitle,
                        onValueChange = { customPeriodTitle = it },
                        label = { Text("Custom Period Title (e.g. Vacation, Q3)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text("Select Category", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ZenithTheme.textPrimary)
                Spacer(modifier = Modifier.height(6.dp))

                // Scrollable list of ALL categories (no .take(6) truncation)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    selectableCategories.forEach { cat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedCat = cat.name }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedCat == cat.name,
                                onClick = { selectedCat = cat.name }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(cat.name, fontSize = 14.sp, color = ZenithTheme.textPrimary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val limit = limitText.toDoubleOrNull() ?: 0.0
                        if (limit > 0) {
                            onSave(selectedCat, limit, selectedPeriodType, if (isFamilyMode) "" else customPeriodTitle)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("save_budget_modal_btn"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen)
                ) {
                    Text(if (existingBudget == null) "Save Budget" else "Update Budget", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun AddOrEditGoalModal(
    currencySymbol: String,
    existingGoal: SavingsGoalEntity?,
    onDismiss: () -> Unit,
    onSave: (title: String, targetAmount: Double, currentAmount: Double) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var title by remember { mutableStateOf(existingGoal?.title ?: "") }
    var targetText by remember { mutableStateOf(existingGoal?.targetAmount?.toString() ?: "") }
    var currentText by remember { mutableStateOf(existingGoal?.currentAmount?.toString() ?: "0") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false, usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ZenithTheme.cardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .imePadding(),
            border = BorderStroke(1.dp, ZenithTheme.borderColor)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Text(
                    if (existingGoal == null) "Create Savings Goal" else "Edit Savings Goal",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZenithTheme.textPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Goal Title (e.g. Emergency Fund, Trip)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("goal_title_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it },
                    label = { Text("Target Amount ($currencySymbol)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("goal_target_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = currentText,
                    onValueChange = { currentText = it },
                    label = { Text("Current Saved ($currencySymbol)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val target = targetText.toDoubleOrNull() ?: 0.0
                        val current = currentText.toDoubleOrNull() ?: 0.0
                        if (title.isNotBlank() && target > 0) onSave(title, target, current)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("save_goal_modal_btn"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen)
                ) {
                    Text(if (existingGoal == null) "Create Goal" else "Update Goal", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun DepositGoalModal(
    goal: SavingsGoalEntity,
    currencySymbol: String,
    onDismiss: () -> Unit,
    onDeposit: (Double) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var depositText by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false, usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ZenithTheme.cardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .imePadding(),
            border = BorderStroke(1.dp, ZenithTheme.borderColor)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Text("Deposit to ${goal.title}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ZenithTheme.textPrimary)
                Text(
                    "Current Saved: $currencySymbol${String.format(Locale.US, "%,.2f", goal.currentAmount)} / $currencySymbol${String.format(Locale.US, "%,.2f", goal.targetAmount)}",
                    fontSize = 12.sp,
                    color = ZenithTheme.textSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = depositText,
                    onValueChange = { depositText = it },
                    label = { Text("Deposit Amount ($currencySymbol)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("deposit_amount_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Fast deposit shortcut chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(500.0, 1000.0, 2500.0, 5000.0).forEach { quickAmt ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ZenithTheme.cardVariantSurface,
                            border = BorderStroke(1.dp, ZenithTheme.borderColor),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val current = depositText.toDoubleOrNull() ?: 0.0
                                    depositText = (current + quickAmt).toInt().toString()
                                }
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "+$currencySymbol${quickAmt.toInt()}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ZenithTheme.accentGreen
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val amount = depositText.toDoubleOrNull() ?: 0.0
                        if (amount > 0) onDeposit(amount)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("confirm_deposit_btn"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen)
                ) {
                    Text("Add Funds", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}
