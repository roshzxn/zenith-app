package com.zenith.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.app.data.models.TransactionType
import com.zenith.app.ui.components.CleanCard
import com.zenith.app.ui.components.GlassCard
import com.zenith.app.ui.theme.ZenithTheme
import com.zenith.app.ui.viewmodel.CashFlowUiState
import java.util.*

enum class AnalyticsTimeRange(val label: String) {
    THIS_MONTH("This Month"),
    LAST_7_DAYS("7 Days"),
    LAST_30_DAYS("30 Days"),
    ALL_TIME("All Time")
}

@Composable
fun AnalyticsScreen(
    state: CashFlowUiState,
    totalIncome: Double,
    totalExpense: Double,
    onGenerateAiAdvice: (Double, Double, String) -> Unit
) {
    var selectedTimeRange by remember { mutableStateOf(AnalyticsTimeRange.THIS_MONTH) }

    val filteredTransactions = remember(state.allTransactions, selectedTimeRange) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        when (selectedTimeRange) {
            AnalyticsTimeRange.LAST_7_DAYS -> {
                val cutOff = now - (7L * 24 * 60 * 60 * 1000)
                state.allTransactions.filter { it.dateMillis >= cutOff }
            }
            AnalyticsTimeRange.LAST_30_DAYS -> {
                val cutOff = now - (30L * 24 * 60 * 60 * 1000)
                state.allTransactions.filter { it.dateMillis >= cutOff }
            }
            AnalyticsTimeRange.THIS_MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val startOfMonth = cal.timeInMillis
                state.allTransactions.filter { it.dateMillis >= startOfMonth }
            }
            AnalyticsTimeRange.ALL_TIME -> state.allTransactions
        }
    }

    val rangeIncome = remember(filteredTransactions) {
        filteredTransactions.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
    }
    val rangeExpense = remember(filteredTransactions) {
        filteredTransactions.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
    }

    val netSavings = remember(rangeIncome, rangeExpense) { rangeIncome - rangeExpense }
    val savingsRate = remember(rangeIncome, netSavings) {
        if (rangeIncome > 0) ((netSavings / rangeIncome) * 100).toInt().coerceAtLeast(0) else 0
    }

    val expensesByCategory = remember(filteredTransactions) {
        filteredTransactions
            .filter { it.type == TransactionType.EXPENSE }
            .groupBy { it.category }
            .mapValues { entry -> entry.value.sumOf { it.amount } }
    }

    val paymentMethodBreakdown = remember(filteredTransactions) {
        filteredTransactions
            .filter { it.type == TransactionType.EXPENSE }
            .groupBy { it.paymentMethod }
            .mapValues { entry -> entry.value.sumOf { it.amount } }
    }

    val topCategory = remember(expensesByCategory) {
        expensesByCategory.maxByOrNull { it.value }?.key ?: "Food & Dining"
    }

    val palette = remember {
        listOf(
            Color(0xFF10B981), // Emerald
            Color(0xFF38BDF8), // Sky
            Color(0xFF8B5CF6), // Violet
            Color(0xFFF59E0B), // Amber
            Color(0xFFEC4899), // Pink
            Color(0xFFEF4444), // Red
            Color(0xFF6366F1), // Indigo
            Color(0xFF14B8A6)  // Teal
        )
    }

    fun formatMoneyOrMask(amount: Double, prefix: String = ""): String =
        if (state.isBalanceHidden) "$prefix${state.currencySymbol}••••"
        else "$prefix${state.currencySymbol}${String.format(Locale.US, "%,.0f", amount)}"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 144.dp)
    ) {
        // --- 1. TITLE HEADER & TIME RANGE SELECTOR ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Financial Analytics",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = ZenithTheme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Visual spending breakdown & health metrics",
                        fontSize = 12.sp,
                        color = ZenithTheme.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Time Range Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ZenithTheme.cardVariantSurface)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AnalyticsTimeRange.entries.forEach { range ->
                    val isSelected = selectedTimeRange == range
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) ZenithTheme.solidGreen else Color.Transparent)
                            .clickable { selectedTimeRange = range },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = range.label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color.White else ZenithTheme.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // --- 2. 4-METRIC SUMMARY CARDS GRID ---
        if (filteredTransactions.isEmpty()) {
            item {
                CleanCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    backgroundColor = ZenithTheme.cardGlassBackground
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.PieChart,
                            contentDescription = null,
                            tint = ZenithTheme.textMuted,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No records for selected period",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZenithTheme.textPrimary
                        )
                        Text(
                            text = "Add income or expenses to see real-time chart insights.",
                            fontSize = 12.sp,
                            color = ZenithTheme.textSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Income Summary Card
                    CleanCard(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        backgroundColor = ZenithTheme.cardSurface,
                        contentPadding = PaddingValues(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ZenithTheme.accentGreen.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = ZenithTheme.accentGreen, modifier = Modifier.size(14.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Income", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ZenithTheme.textSecondary, maxLines = 1)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = formatMoneyOrMask(rangeIncome, "+"),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = ZenithTheme.accentGreen,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Expenses Summary Card
                    CleanCard(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        backgroundColor = ZenithTheme.cardSurface,
                        contentPadding = PaddingValues(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ZenithTheme.accentRed.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = ZenithTheme.accentRed, modifier = Modifier.size(14.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Expenses", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ZenithTheme.textSecondary, maxLines = 1)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = formatMoneyOrMask(rangeExpense, "-"),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = ZenithTheme.accentRed,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Net Savings Card
                    CleanCard(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        backgroundColor = ZenithTheme.cardSurface,
                        contentPadding = PaddingValues(14.dp)
                    ) {
                        Text("Net Cash Flow", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ZenithTheme.textSecondary)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = formatMoneyOrMask(kotlin.math.abs(netSavings), if (netSavings >= 0) "+" else "-"),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (netSavings >= 0) ZenithTheme.accentGreen else ZenithTheme.accentRed,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Savings Rate Card
                    CleanCard(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        backgroundColor = ZenithTheme.cardSurface,
                        contentPadding = PaddingValues(14.dp)
                    ) {
                        Text("Savings Rate", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ZenithTheme.textSecondary)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "$savingsRate%",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = ZenithTheme.accentAmber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // --- 3. CASH FLOW COMPARISON CHART (INCOME VS EXPENSES) ---
            item {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("income_vs_expense_chart_card"),
                    backgroundColor = ZenithTheme.glassCardBg
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Income vs. Expenses Flow",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.textPrimary
                            )
                            Text(
                                text = "${selectedTimeRange.label} cash flow ratio",
                                fontSize = 11.sp,
                                color = ZenithTheme.textSecondary
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (netSavings >= 0) ZenithTheme.accentGreen.copy(alpha = 0.15f) else ZenithTheme.accentRed.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (netSavings >= 0) "Surplus" else "Deficit",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (netSavings >= 0) ZenithTheme.accentGreen else ZenithTheme.accentRed,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    val maxVal = maxOf(rangeIncome, rangeExpense, 1.0)
                    val incomeRatio = (rangeIncome / maxVal).toFloat().coerceIn(0.1f, 1.0f)
                    val expenseRatio = (rangeExpense / maxVal).toFloat().coerceIn(0.1f, 1.0f)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Income Column
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f)
                        ) {
                            Text(
                                text = formatMoneyOrMask(rangeIncome),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.accentGreen,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(52.dp)
                                        .fillMaxHeight(incomeRatio)
                                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(ZenithTheme.accentGreen, Color(0xFF059669))
                                            )
                                        )
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Total Income",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ZenithTheme.textSecondary,
                                maxLines = 1
                            )
                        }

                        // Expense Column
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f)
                        ) {
                            Text(
                                text = formatMoneyOrMask(rangeExpense),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.accentRed,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(52.dp)
                                        .fillMaxHeight(expenseRatio)
                                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(ZenithTheme.accentRed, Color(0xFFB91C1C))
                                            )
                                        )
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Total Expenses",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ZenithTheme.textSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // --- 4. EXPENSE BREAKDOWN DONUT & CATEGORY LIST ---
            item {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("category_breakdown_card"),
                    backgroundColor = ZenithTheme.glassCardBg
                ) {
                    Text(
                        text = "Expense Breakdown",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = ZenithTheme.textPrimary
                    )
                    Text(
                        text = "Where your money went in ${selectedTimeRange.label.lowercase()}",
                        fontSize = 11.sp,
                        color = ZenithTheme.textSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (expensesByCategory.isEmpty() || rangeExpense <= 0) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.PieChart,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = ZenithTheme.textMuted
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No expenses recorded",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.textPrimary
                            )
                        }
                    } else {
                        val sortedAll = expensesByCategory.entries.sortedByDescending { it.value }
                        val chartEntries = if (sortedAll.size <= 5) {
                            sortedAll.map { it.key to it.value }
                        } else {
                            val topFour = sortedAll.take(4).map { it.key to it.value }
                            val otherSum = sortedAll.drop(4).sumOf { it.value }
                            topFour + ("Other" to otherSum)
                        }
                        val totalCatExpense = chartEntries.sumOf { it.second }.coerceAtLeast(1.0)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Donut Canvas with Center Summary — iterates exact same sorted order as legend
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(130.dp)
                            ) {
                                Canvas(modifier = Modifier.size(130.dp)) {
                                    var startAngle = -90f
                                    chartEntries.forEachIndexed { idx, (_, value) ->
                                        val sweep = ((value / totalCatExpense) * 360f).toFloat().coerceIn(0f, 360f)
                                        val color = palette[idx % palette.size]

                                        if (sweep > 0f) {
                                            drawArc(
                                                color = color,
                                                startAngle = startAngle,
                                                sweepAngle = sweep,
                                                useCenter = false,
                                                style = Stroke(
                                                    width = 18.dp.toPx(),
                                                    cap = StrokeCap.Butt
                                                )
                                            )
                                        }
                                        startAngle += sweep
                                    }
                                }

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = formatMoneyOrMask(rangeExpense),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = ZenithTheme.textPrimary,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "Spent",
                                        fontSize = 10.sp,
                                        color = ZenithTheme.textSecondary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            // Category Legend Column — uses exact same chartEntries & palette indices
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                chartEntries.forEachIndexed { idx, (catName, value) ->
                                    val color = palette[idx % palette.size]
                                    val exactPct = (value / totalCatExpense) * 100.0
                                    val pct = kotlin.math.round(exactPct).toInt().coerceIn(0, 100)

                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f, fill = false)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(color)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = catName,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = ZenithTheme.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text(
                                                text = "$pct% • ${formatMoneyOrMask(value)}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ZenithTheme.textSecondary,
                                                maxLines = 1
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(3.dp))
                                        LinearProgressIndicator(
                                            progress = { (value / totalCatExpense).toFloat().coerceIn(0.02f, 1f) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp)),
                                            color = color,
                                            trackColor = ZenithTheme.cardVariantSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // --- 5. PAYMENT METHOD DISTRIBUTION ---
            if (paymentMethodBreakdown.isNotEmpty()) {
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = ZenithTheme.glassCardBg
                    ) {
                        Text(
                            text = "Payment Method Breakdown",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZenithTheme.textPrimary
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val totalPayExpense = paymentMethodBreakdown.values.sum().coerceAtLeast(1.0)
                        paymentMethodBreakdown.entries.sortedByDescending { it.value }.forEach { entry ->
                            val pct = ((entry.value / totalPayExpense) * 100).toInt()
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = when {
                                                entry.key.contains("UPI", ignoreCase = true) -> Icons.Default.QrCodeScanner
                                                entry.key.contains("Card", ignoreCase = true) -> Icons.Default.CreditCard
                                                entry.key.contains("Bank", ignoreCase = true) -> Icons.Default.AccountBalance
                                                else -> Icons.Default.Payments
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = ZenithTheme.accentCyan
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(entry.key, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ZenithTheme.textPrimary)
                                    }
                                    Text(
                                        text = "${formatMoneyOrMask(entry.value)} ($pct%)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ZenithTheme.textSecondary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { (pct / 100f).coerceIn(0.02f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = ZenithTheme.accentCyan,
                                    trackColor = ZenithTheme.cardVariantSurface
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // --- 6. GEMINI FINANCIAL COACH CARD ---
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = ZenithTheme.cardSurface),
                    border = BorderStroke(
                        1.5.dp,
                        Brush.linearGradient(
                            listOf(
                                ZenithTheme.accentGreen.copy(alpha = 0.55f),
                                ZenithTheme.accentCyan.copy(alpha = 0.55f)
                            )
                        )
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_financial_coach_card")
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Brush.linearGradient(listOf(ZenithTheme.solidGreen, ZenithTheme.solidIndigo))),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = "AI Coach",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Gemini Financial Coach",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ZenithTheme.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "AI-Powered Budget & Cashflow Insights",
                                        fontSize = 11.sp,
                                        color = ZenithTheme.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            IconButton(
                                onClick = { onGenerateAiAdvice(rangeIncome, rangeExpense, topCategory) },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("refresh_ai_coach_btn")
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Refresh Advice",
                                    tint = ZenithTheme.textPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        if (state.isAiCoachLoading) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 12.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = ZenithTheme.accentGreen,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Analyzing your spending patterns...",
                                    color = ZenithTheme.textSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        } else {
                            val topCatEntry = expensesByCategory.maxByOrNull { it.value }
                            val adviceText = state.aiCoachAdvice?.ifBlank { null }
                                ?: if (filteredTransactions.isNotEmpty() && topCatEntry != null && rangeExpense > 0) {
                                    val topPct = kotlin.math.round((topCatEntry.value / rangeExpense) * 100.0).toInt()
                                    val netFlowStr = if (netSavings >= 0) "surplus of ${formatMoneyOrMask(netSavings)}" else "deficit of ${formatMoneyOrMask(-netSavings)}"
                                    "• Top Category Driver: ${topCatEntry.key} accounts for $topPct% (${formatMoneyOrMask(topCatEntry.value)}) of your ${selectedTimeRange.label.lowercase()} spending.\n" +
                                        "• Cash Flow Position: Running a $netFlowStr with a $savingsRate% savings rate.\n" +
                                        "• Tap refresh above to generate tailored Gemini AI recommendations."
                                } else {
                                    "• Spending Velocity: Log your daily expenses to unlock actionable savings recommendations.\n• Rule of 50/30/20: Strive to keep fixed living costs under 50% of your net income.\n• Savings Cushion: Build a 3-6 month reserve fund in high-yield liquid savings."
                                }

                            Text(
                                text = adviceText,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                color = ZenithTheme.textPrimary.copy(alpha = 0.90f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
