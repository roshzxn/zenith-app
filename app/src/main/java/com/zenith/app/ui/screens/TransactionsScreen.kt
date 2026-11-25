package com.zenith.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.app.data.models.TransactionEntity
import com.zenith.app.data.models.TransactionType
import com.zenith.app.ui.components.CleanCard
import com.zenith.app.ui.components.TransactionDetailModal
import com.zenith.app.ui.components.TransactionItemCard
import com.zenith.app.ui.theme.ZenithTheme
import com.zenith.app.ui.viewmodel.CashFlowUiState
import java.util.*

@Composable
fun TransactionsScreen(
    state: CashFlowUiState,
    onSearchQueryChange: (String) -> Unit,
    onFilterTypeChange: (TransactionType?) -> Unit,
    onFilterCategoryChange: (String?) -> Unit,
    onDeleteTransaction: (TransactionEntity) -> Unit,
    onOpenAddTransaction: () -> Unit,
    onOpenVoiceAssistant: (() -> Unit)? = null,
    onUpdateTransaction: ((TransactionEntity) -> Unit)? = null
) {
    var selectedTransactionForDetails by remember { mutableStateOf<TransactionEntity?>(null) }
    var transactionToDelete by remember { mutableStateOf<TransactionEntity?>(null) }
    var selectedDateRange by remember { mutableStateOf("ALL") }

    val distinctCategories = remember(state.categories) {
        state.categories.distinctBy { it.name.trim() }
    }

    val filteredTransactions = remember(state.transactions, selectedDateRange) {
        val now = Calendar.getInstance()
        when (selectedDateRange) {
            "TODAY" -> state.transactions.filter { tx ->
                val cal = Calendar.getInstance().apply { timeInMillis = tx.dateMillis }
                cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
            }
            "THIS_WEEK" -> state.transactions.filter { tx ->
                val cal = Calendar.getInstance().apply { timeInMillis = tx.dateMillis }
                cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) && cal.get(Calendar.WEEK_OF_YEAR) == now.get(Calendar.WEEK_OF_YEAR)
            }
            "THIS_MONTH" -> state.transactions.filter { tx ->
                val cal = Calendar.getInstance().apply { timeInMillis = tx.dateMillis }
                cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) && cal.get(Calendar.MONTH) == now.get(Calendar.MONTH)
            }
            else -> state.transactions
        }
    }

    val groupedTransactions = remember(filteredTransactions) {
        val now = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val fmt = java.text.SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault())
        filteredTransactions.groupBy { tx ->
            val date = Date(tx.dateMillis)
            val txCal = Calendar.getInstance().apply { time = date }
            when {
                now.get(Calendar.YEAR) == txCal.get(Calendar.YEAR) &&
                    now.get(Calendar.DAY_OF_YEAR) == txCal.get(Calendar.DAY_OF_YEAR) -> "Today"
                yesterday.get(Calendar.YEAR) == txCal.get(Calendar.YEAR) &&
                    yesterday.get(Calendar.DAY_OF_YEAR) == txCal.get(Calendar.DAY_OF_YEAR) -> "Yesterday"
                else -> fmt.format(date)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        // --- 1. COMPACT TOP HEADER BAR (Always reachable without covering rows) ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Activity",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ZenithTheme.textPrimary
                )
                Text(
                    text = "${filteredTransactions.size} records • Tap for details",
                    fontSize = 12.sp,
                    color = ZenithTheme.textSecondary
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onOpenVoiceAssistant != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ZenithTheme.accentIndigo.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, ZenithTheme.accentIndigo.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenVoiceAssistant.invoke() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "Voice Entry",
                                tint = ZenithTheme.accentIndigo,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ZenithTheme.solidGreen,
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenAddTransaction() }
                        .testTag("fab_add_transaction")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add Transaction",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Add",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- 2. UNIFIED SCROLLABLE FEED (Search, Filters, Categories scroll cleanly with transactions) ---
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(top = 6.dp, bottom = 116.dp)
        ) {
            item(key = "activity_search") {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = {
                        Text(
                            text = "Search title, category, method or note...",
                            fontSize = 12.sp,
                            color = ZenithTheme.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = ZenithTheme.textSecondary)
                    },
                    trailingIcon = {
                        if (state.searchQuery.isNotBlank()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = ZenithTheme.textSecondary)
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = ZenithTheme.borderColor,
                        focusedBorderColor = ZenithTheme.solidGreen,
                        unfocusedContainerColor = ZenithTheme.cardSurface,
                        focusedContainerColor = ZenithTheme.cardSurface,
                        focusedTextColor = ZenithTheme.textPrimary,
                        unfocusedTextColor = ZenithTheme.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_transactions_input"),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            item(key = "activity_type_chips") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.selectedFilterType == null,
                        onClick = { onFilterTypeChange(null) },
                        label = { Text("All", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) },
                        modifier = Modifier.testTag("filter_all_chip")
                    )
                    FilterChip(
                        selected = state.selectedFilterType == TransactionType.EXPENSE,
                        onClick = { onFilterTypeChange(TransactionType.EXPENSE) },
                        label = { Text("Expenses", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) },
                        modifier = Modifier.testTag("filter_expense_chip")
                    )
                    FilterChip(
                        selected = state.selectedFilterType == TransactionType.INCOME,
                        onClick = { onFilterTypeChange(TransactionType.INCOME) },
                        label = { Text("Income", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) },
                        modifier = Modifier.testTag("filter_income_chip")
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            item(key = "activity_date_chips") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val dateRanges = listOf(
                        "ALL" to "All Time",
                        "THIS_MONTH" to "This Month",
                        "THIS_WEEK" to "This Week",
                        "TODAY" to "Today"
                    )
                    items(dateRanges, key = { it.first }) { (key, label) ->
                        FilterChip(
                            selected = selectedDateRange == key,
                            onClick = { selectedDateRange = key },
                            label = {
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selectedDateRange == key) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            item(key = "activity_category_chips") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        FilterChip(
                            selected = state.selectedFilterCategory == null,
                            onClick = { onFilterCategoryChange(null) },
                            label = { Text("All Categories", fontSize = 11.sp) }
                        )
                    }
                    items(distinctCategories, key = { "cat_${it.name}" }) { cat ->
                        FilterChip(
                            selected = state.selectedFilterCategory == cat.name,
                            onClick = {
                                if (state.selectedFilterCategory == cat.name) onFilterCategoryChange(null)
                                else onFilterCategoryChange(cat.name)
                            },
                            label = { Text(cat.name, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (filteredTransactions.isEmpty()) {
                item(key = "activity_empty") {
                    CleanCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        backgroundColor = ZenithTheme.cardGlassBackground
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = ZenithTheme.textMuted
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "No transactions found",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.textPrimary
                            )
                            Text(
                                text = if (state.searchQuery.isNotBlank() || selectedDateRange != "ALL" || state.selectedFilterCategory != null) {
                                    "Try clearing filters to see more results."
                                } else {
                                    "Your financial activity will appear here."
                                },
                                fontSize = 12.sp,
                                color = ZenithTheme.textSecondary
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = onOpenAddTransaction,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen)
                            ) {
                                Text("Add Transaction", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                            }
                        }
                    }
                }
            } else {
                groupedTransactions.forEach { (dateHeader, txList) ->
                    item(key = "header_$dateHeader") {
                        val dayExpense = txList.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 6.dp, start = 4.dp, end = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = dateHeader,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.textSecondary,
                                letterSpacing = 0.5.sp
                            )
                            if (dayExpense > 0) {
                                Text(
                                    text = if (state.isBalanceHidden) {
                                        "Spent: -${state.currencySymbol}••••"
                                    } else {
                                        "Spent: -${state.currencySymbol}${String.format(Locale.US, "%,.0f", dayExpense)}"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ZenithTheme.accentRed
                                )
                            }
                        }
                    }

                    items(txList, key = { "tx_${it.id}" }) { tx ->
                        TransactionItemCard(
                            transaction = tx,
                            currencySymbol = state.currencySymbol,
                            isMasked = state.isBalanceHidden,
                            onClick = { selectedTransactionForDetails = it },
                            onLongClick = { selectedTransactionForDetails = it },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }

    selectedTransactionForDetails?.let { tx ->
        TransactionDetailModal(
            transaction = tx,
            currencySymbol = state.currencySymbol,
            onDismiss = { selectedTransactionForDetails = null },
            onDelete = {
                onDeleteTransaction(it)
                selectedTransactionForDetails = null
            },
            onUpdate = { updated ->
                onUpdateTransaction?.invoke(updated)
                selectedTransactionForDetails = null
            }
        )
    }

    transactionToDelete?.let { tx ->
        AlertDialog(
            onDismissRequest = { transactionToDelete = null },
            title = { Text("Delete Transaction", color = ZenithTheme.textPrimary) },
            text = {
                Text(
                    "Are you sure you want to delete '${tx.title}' (${state.currencySymbol}${String.format(Locale.US, "%.2f", tx.amount)})?",
                    color = ZenithTheme.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteTransaction(tx)
                        transactionToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidRed)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { transactionToDelete = null }) {
                    Text("Cancel", color = ZenithTheme.textSecondary)
                }
            },
            containerColor = ZenithTheme.cardSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}
