package com.example.ui.familyledger

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.familyledger.FamilyVaultMember
import com.example.data.familyledger.LedgerTransaction
import com.example.data.models.TransactionType
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun FamilyLedgerDashboard(
    state: FamilyLedgerUiState,
    currentUserName: String,
    currencySymbol: String = "₹",
    canModifyTransaction: (LedgerTransaction) -> Boolean = { true },
    onOpenAddTransaction: (TransactionType) -> Unit,
    onEditTransaction: (LedgerTransaction) -> Unit,
    onDeleteTransaction: (LedgerTransaction) -> Unit,
    onToggleReaction: (String, String) -> Unit,
    onNavigateToActivity: () -> Unit,
    onNavigateToMembers: () -> Unit,
    onNavigateToBudgets: () -> Unit,
    onSyncNow: () -> Unit,
    onUpdateBudgetLimit: (Double) -> Unit
) {
    val totalIncome = remember(state.transactions) {
        state.transactions.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
    }
    val totalExpense = remember(state.transactions) {
        state.transactions.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
    }
    val netBalance = totalIncome - totalExpense
    val monthlyLimit = state.activeVault?.monthlyBudgetLimit ?: 50000.0

    var isBalanceHidden by remember { mutableStateOf(false) }
    var showBudgetLimitDialog by remember { mutableStateOf(false) }
    var tempBudgetInput by remember { mutableStateOf(monthlyLimit.toInt().toString()) }

    val daysInMonth = remember {
        val cal = Calendar.getInstance()
        cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }
    val dayOfMonth = remember {
        val cal = Calendar.getInstance()
        cal.get(Calendar.DAY_OF_MONTH)
    }
    val daysRemaining = (daysInMonth - dayOfMonth + 1).coerceAtLeast(1)
    val remainingBudget = (monthlyLimit - totalExpense).coerceAtLeast(0.0)
    val dailyBurnAllowance = remainingBudget / daysRemaining

    val recentTransactions = remember(state.transactions) {
        state.transactions.take(5)
    }

    val memberGradients = remember {
        listOf(
            Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))),
            Brush.linearGradient(listOf(Color(0xFF06B6D4), Color(0xFF3B82F6))),
            Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFEF4444))),
            Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF059669))),
            Brush.linearGradient(listOf(Color(0xFFEC4899), Color(0xFF8B5CF6)))
        )
    }

    // Top categories calculation
    val topCategories = remember(state.transactions) {
        state.transactions
            .filter { it.type == TransactionType.EXPENSE }
            .groupBy { it.category }
            .mapValues { entry -> entry.value.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }
            .take(4)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- 1. APPLE-STYLE UNIFIED HOUSEHOLD BUDGET HERO CARD ---
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF0F1524),
                                Color(0xFF090D16)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFF38BDF8).copy(alpha = 0.35f),
                                    Color(0xFF818CF8).copy(alpha = 0.15f),
                                    Color.Transparent
                                )
                            )
                        ),
                        RoundedCornerShape(26.dp)
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // Header: Vault Name + Members Pill
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF38BDF8).copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AllInclusive,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = state.activeVault?.familyName ?: "Family Vault",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Unified Household Pool",
                                    fontSize = 10.sp,
                                    color = Color(0xFF64748B),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF1E293B),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier.clickable { onNavigateToMembers() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${state.members.size} Active",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF38BDF8)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Ring & Stats Side-by-Side
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left: Circular Progress Ring
                        HouseholdBudgetRing(
                            totalSpent = totalExpense,
                            budgetLimit = monthlyLimit,
                            currencySymbol = currencySymbol
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        // Right: Net Pool & Daily Burn Details
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "NET POOL BALANCE",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF64748B),
                                    letterSpacing = 1.1.sp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { isBalanceHidden = !isBalanceHidden },
                                    modifier = Modifier.size(16.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isBalanceHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null,
                                        tint = Color(0xFF64748B),
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (isBalanceHidden) "••••••" else "$currencySymbol%,.0f".format(netBalance),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Daily Burn Allowance Pill
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF131A26),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Text(
                                        text = "DAILY BURN RATE",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF94A3B8)
                                    )
                                    Text(
                                        text = "$currencySymbol%,.0f / day".format(dailyBurnAllowance),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF38BDF8)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Edit Limit Button
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        tempBudgetInput = monthlyLimit.toInt().toString()
                                        showBudgetLimitDialog = true
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = Color(0xFF818CF8),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Adjust Limit",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF818CF8)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Inflow vs Outflow Mini-Pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF10B981).copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.25f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text("Deposits", fontSize = 9.sp, color = Color(0xFF94A3B8))
                                    Text("+$currencySymbol%,.0f".format(totalIncome), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFFEF4444).copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.25f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text("Spent", fontSize = 9.sp, color = Color(0xFF94A3B8))
                                    Text("-$currencySymbol%,.0f".format(totalExpense), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444))
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- 2. QUICK ACTIONS ROW ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuickActionPill(
                    icon = Icons.Default.Remove,
                    label = "Expense",
                    accentColor = Color(0xFFF43F5E),
                    onClick = { onOpenAddTransaction(TransactionType.EXPENSE) }
                )
                QuickActionPill(
                    icon = Icons.Default.Add,
                    label = "Deposit",
                    accentColor = Color(0xFF10B981),
                    onClick = { onOpenAddTransaction(TransactionType.INCOME) }
                )
                QuickActionPill(
                    icon = Icons.Default.People,
                    label = "Members",
                    accentColor = Color(0xFF818CF8),
                    onClick = { onNavigateToMembers() }
                )
                QuickActionPill(
                    icon = Icons.Default.Sync,
                    label = "Sync",
                    accentColor = Color(0xFF38BDF8),
                    onClick = { onSyncNow() }
                )
            }
        }

        // --- 3. PROPORTIONAL MEMBER SPEND SHARE STRIP ---
        if (state.members.isNotEmpty() && totalExpense > 0) {
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xFF0F141F),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "MEMBER SPEND SHARE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF64748B),
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Manage →",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF818CF8),
                                modifier = Modifier.clickable { onNavigateToMembers() }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Segmented Multi-Color Progress Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        ) {
                            state.members.forEachIndexed { idx, mem ->
                                val shareWeight = (mem.totalPaid / totalExpense).toFloat().coerceAtLeast(0.01f)
                                val color = when (idx % 5) {
                                    0 -> Color(0xFF6366F1)
                                    1 -> Color(0xFF06B6D4)
                                    2 -> Color(0xFFF59E0B)
                                    3 -> Color(0xFF10B981)
                                    else -> Color(0xFFEC4899)
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(shareWeight)
                                        .fillMaxHeight()
                                        .background(color)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Member Mini Cards Row
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(state.members.size) { idx ->
                                val mem = state.members[idx]
                                val gradient = memberGradients[idx % memberGradients.size]
                                val sharePct = ((mem.totalPaid / totalExpense) * 100).toInt()

                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xFF141926),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                                    modifier = Modifier.width(130.dp)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(gradient),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = mem.name.take(1).uppercase(),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = mem.name,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "$currencySymbol%,.0f".format(mem.totalPaid),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "$sharePct% of pool",
                                            fontSize = 10.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- 4. TOP CATEGORIES BENTO GRID ---
        if (topCategories.isNotEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TOP EXPENSE CATEGORIES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B),
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Budgets →",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF818CF8),
                            modifier = Modifier.clickable { onNavigateToBudgets() }
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        topCategories.take(2).forEach { (catName, amt) ->
                            val pct = if (totalExpense > 0) ((amt / totalExpense) * 100).toInt() else 0
                            BentoCategoryCard(
                                name = catName,
                                amount = amt,
                                pct = pct,
                                currencySymbol = currencySymbol,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (topCategories.size > 2) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            topCategories.drop(2).take(2).forEach { (catName, amt) ->
                                val pct = if (totalExpense > 0) ((amt / totalExpense) * 100).toInt() else 0
                                BentoCategoryCard(
                                    name = catName,
                                    amount = amt,
                                    pct = pct,
                                    currencySymbol = currencySymbol,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- 5. RECENT ACTIVITY STREAM ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RECENT ACTIVITY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64748B),
                    letterSpacing = 1.sp
                )
                Text(
                    text = "See all (${state.transactions.size}) →",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF38BDF8),
                    modifier = Modifier.clickable { onNavigateToActivity() }
                )
            }
        }

        if (recentTransactions.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF0F141F),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier.padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No transactions yet. Tap '+ Expense' to add one.",
                            fontSize = 13.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }
            }
        } else {
            items(recentTransactions.size) { idx ->
                val tx = recentTransactions[idx]
                SocialTransactionCard(
                    tx = tx,
                    currentUserName = currentUserName,
                    currencySymbol = currencySymbol,
                    memberGradients = memberGradients,
                    canModify = canModifyTransaction(tx),
                    onEdit = { onEditTransaction(tx) },
                    onDelete = { onDeleteTransaction(tx) },
                    onToggleReaction = { emoji -> onToggleReaction(tx.transactionId, emoji) }
                )
            }
        }
    }

    // Adjust Budget Limit Dialog
    if (showBudgetLimitDialog) {
        AlertDialog(
            onDismissRequest = { showBudgetLimitDialog = false },
            containerColor = Color(0xFF161B26),
            title = {
                Text("Monthly Household Budget", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column {
                    Text(
                        "Set the target monthly spending ceiling for your family vault:",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempBudgetInput,
                        onValueChange = { tempBudgetInput = it },
                        prefix = { Text(currencySymbol, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = tempBudgetInput.toDoubleOrNull()
                        if (parsed != null && parsed > 0.0) {
                            onUpdateBudgetLimit(parsed)
                        }
                        showBudgetLimitDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                ) {
                    Text("Save Limit", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBudgetLimitDialog = false }) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            }
        )
    }
}

@Composable
private fun QuickActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF0F141F),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun BentoCategoryCard(
    name: String,
    amount: Double,
    pct: Int,
    currencySymbol: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF0F141F),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$pct%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF818CF8)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "$currencySymbol%,.0f".format(amount),
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Progress Track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF1E2433))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(pct / 100f)
                        .fillMaxHeight()
                        .background(Color(0xFF818CF8))
                )
            }
        }
    }
}
