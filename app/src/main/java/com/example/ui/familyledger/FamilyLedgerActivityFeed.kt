package com.example.ui.familyledger

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.familyledger.LedgerReactionUtils
import com.example.data.familyledger.LedgerTransaction
import com.example.data.models.TransactionType
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun FamilyLedgerActivityFeed(
    transactions: List<LedgerTransaction>,
    currentUserName: String,
    currencySymbol: String = "₹",
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    filterType: TransactionType?,
    onFilterTypeChange: (TransactionType?) -> Unit,
    canModifyTransaction: (LedgerTransaction) -> Boolean,
    onEditTransaction: (LedgerTransaction) -> Unit,
    onDeleteTransaction: (LedgerTransaction) -> Unit,
    onToggleReaction: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    val filteredTransactions = remember(transactions, searchQuery, filterType) {
        transactions.filter { tx ->
            val matchesSearch = searchQuery.isBlank() ||
                    tx.title.contains(searchQuery, ignoreCase = true) ||
                    tx.description.contains(searchQuery, ignoreCase = true) ||
                    tx.category.contains(searchQuery, ignoreCase = true) ||
                    tx.paidByName.contains(searchQuery, ignoreCase = true)
            val matchesType = filterType == null || tx.type == filterType
            matchesSearch && matchesType
        }
    }

    val groupedTransactions = remember(filteredTransactions) {
        filteredTransactions.groupBy { tx ->
            val date = Date(tx.dateMillis)
            val now = Calendar.getInstance()
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val txCal = Calendar.getInstance().apply { time = date }
            when {
                now.get(Calendar.YEAR) == txCal.get(Calendar.YEAR) &&
                        now.get(Calendar.DAY_OF_YEAR) == txCal.get(Calendar.DAY_OF_YEAR) -> "Today"
                yesterday.get(Calendar.YEAR) == txCal.get(Calendar.YEAR) &&
                        yesterday.get(Calendar.DAY_OF_YEAR) == txCal.get(Calendar.DAY_OF_YEAR) -> "Yesterday"
                else -> SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(date)
            }
        }
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Search & Filter Header
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF121722),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = Color(0xFF64748B),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text("Search family activity…", color = Color(0xFF64748B), fontSize = 13.sp) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { onSearchChange("") },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Type Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filterType == null,
                onClick = { onFilterTypeChange(null) },
                label = { Text("All Feed", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF1E293B),
                    selectedLabelColor = Color(0xFF38BDF8),
                    containerColor = Color(0xFF121722),
                    labelColor = Color(0xFF94A3B8)
                ),
                border = BorderStroke(1.dp, if (filterType == null) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.05f))
            )

            FilterChip(
                selected = filterType == TransactionType.EXPENSE,
                onClick = { onFilterTypeChange(if (filterType == TransactionType.EXPENSE) null else TransactionType.EXPENSE) },
                label = { Text("Expenses", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFFEF4444).copy(alpha = 0.2f),
                    selectedLabelColor = Color(0xFFF87171),
                    containerColor = Color(0xFF121722),
                    labelColor = Color(0xFF94A3B8)
                ),
                border = BorderStroke(1.dp, if (filterType == TransactionType.EXPENSE) Color(0xFFEF4444) else Color.White.copy(alpha = 0.05f))
            )

            FilterChip(
                selected = filterType == TransactionType.INCOME,
                onClick = { onFilterTypeChange(if (filterType == TransactionType.INCOME) null else TransactionType.INCOME) },
                label = { Text("Income", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF10B981).copy(alpha = 0.2f),
                    selectedLabelColor = Color(0xFF34D399),
                    containerColor = Color(0xFF121722),
                    labelColor = Color(0xFF94A3B8)
                ),
                border = BorderStroke(1.dp, if (filterType == TransactionType.INCOME) Color(0xFF10B981) else Color.White.copy(alpha = 0.05f))
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Activity Feed List
        if (filteredTransactions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                        contentDescription = null,
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "No family transactions found",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF64748B)
                    )
                    Text(
                        text = "Tap '+ Add Entry' to record the first family expense",
                        fontSize = 12.sp,
                        color = Color(0xFF475569)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                groupedTransactions.forEach { (dateHeader, txsInGroup) ->
                    item {
                        Text(
                            text = dateHeader.uppercase(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B),
                            letterSpacing = 1.1.sp,
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
                        )
                    }

                    items(txsInGroup, key = { it.transactionId }) { tx ->
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
        }
    }
}

@Composable
fun SocialTransactionCard(
    tx: LedgerTransaction,
    currentUserName: String,
    currencySymbol: String,
    memberGradients: List<Brush>,
    canModify: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleReaction: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }

    val reactionsMap = remember(tx.reactions) {
        LedgerReactionUtils.parseReactions(tx.reactions)
    }

    val availableEmojis = listOf("👍", "❤️", "💸", "🔥", "👏", "👀")

    val memberHash = tx.paidByName.hashCode().let { if (it < 0) -it else it }
    val avatarGradient = memberGradients[memberHash % memberGradients.size]

    val relativeTime = remember(tx.dateMillis) {
        val diff = System.currentTimeMillis() - tx.dateMillis
        when {
            diff < 60_000L -> "Just now"
            diff < 3600_000L -> "${diff / 60_000L}m ago"
            diff < 86400_000L -> "${diff / 3600_000L}h ago"
            else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(tx.dateMillis))
        }
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF0F141F),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable { onEdit() }
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header Row: Avatar + Name + Time + 3-Dot Options
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(avatarGradient),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tx.paidByName.take(1).uppercase(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = tx.paidByName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = relativeTime,
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(Color(0xFF1E2536))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit Entry", color = Color.White, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp)) },
                            onClick = {
                                showMenu = false
                                onEdit()
                            }
                        )
                        if (canModify) {
                            DropdownMenuItem(
                                text = { Text("Delete Entry", color = Color(0xFFEF4444), fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp)) },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Body Row: Title + Category + Payment Tag & Bold Amount
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tx.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (tx.description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = tx.description,
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF161E2E)
                        ) {
                            Text(
                                text = tx.category,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF818CF8),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF161E2E)
                        ) {
                            Text(
                                text = tx.paymentMethod,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Bold Amount
                Text(
                    text = "${if (tx.type == TransactionType.EXPENSE) "-" else "+"}$currencySymbol%,.2f".format(tx.amount),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = if (tx.type == TransactionType.EXPENSE) Color(0xFFF87171) else Color(0xFF34D399),
                    letterSpacing = (-0.3).sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Emoji Reactions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Existing Active Reaction Pills
                reactionsMap.forEach { (emoji, userList) ->
                    val hasMyReaction = userList.contains(currentUserName)
                    val count = userList.size

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (hasMyReaction) Color(0xFF1E293B) else Color(0xFF141A26),
                        border = BorderStroke(
                            1.dp,
                            if (hasMyReaction) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.05f)
                        ),
                        modifier = Modifier.clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleReaction(emoji)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = emoji, fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "$count",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (hasMyReaction) Color(0xFF38BDF8) else Color(0xFF94A3B8)
                            )
                        }
                    }
                }

                // Add Reaction Button
                Box {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF141A26),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                        modifier = Modifier.clickable { showEmojiPicker = !showEmojiPicker }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddReaction,
                                contentDescription = "Add reaction",
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    // Emoji Popup
                    DropdownMenu(
                        expanded = showEmojiPicker,
                        onDismissRequest = { showEmojiPicker = false },
                        modifier = Modifier.background(Color(0xFF1E2536))
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            availableEmojis.forEach { emoji ->
                                Text(
                                    text = emoji,
                                    fontSize = 20.sp,
                                    modifier = Modifier
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            showEmojiPicker = false
                                            onToggleReaction(emoji)
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
