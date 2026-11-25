package com.zenith.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.app.data.models.TransactionEntity
import com.zenith.app.data.models.TransactionType
import com.zenith.app.data.upi.ExtractedUpiPayment
import com.zenith.app.ui.components.CleanCard
import com.zenith.app.ui.components.RollingCounterText
import com.zenith.app.ui.components.TransactionDetailModal
import com.zenith.app.ui.components.TransactionItemCard
import com.zenith.app.ui.theme.ZenithTheme
import com.zenith.app.ui.viewmodel.CashFlowUiState
import com.zenith.app.ui.viewmodel.SyncUiState
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(
    state: CashFlowUiState,
    totalIncome: Double,
    totalExpense: Double,
    netBalance: Double,
    onOpenAddTransaction: () -> Unit,
    onOpenVoiceAssistant: () -> Unit,
    onOpenReceiptScanner: () -> Unit,
    onOpenUpiPay: () -> Unit = {},
    onOpenUpiScanner: () -> Unit = {},
    onDeleteTransaction: (TransactionEntity) -> Unit,
    onNavigateToActivity: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToBudgets: () -> Unit = {},
    onUpdateTransaction: ((TransactionEntity) -> Unit)? = null,
    currentUserName: String = "You",
    syncUiState: SyncUiState = SyncUiState.Idle,
    onSyncNow: (() -> Unit)? = null,
    onToggleHideBalance: () -> Unit = {},
    pendingDetections: List<ExtractedUpiPayment> = emptyList(),
    onReviewPendingDetection: ((ExtractedUpiPayment) -> Unit)? = null,
    onQuickLogPendingDetection: ((ExtractedUpiPayment) -> Unit)? = null,
    onDismissPendingDetection: ((ExtractedUpiPayment) -> Unit)? = null,
    onDismissAllPendingDetections: (() -> Unit)? = null
) {
    var selectedTransactionForDetails by remember { mutableStateOf<TransactionEntity?>(null) }

    // Home always reflects allTransactions so Activity filters never alter the dashboard
    val displayedTransactions = state.allTransactions

    // Dynamic Spending Insight
    val spendingInsight = remember(displayedTransactions, totalExpense, totalIncome) {
        if (displayedTransactions.isEmpty()) {
            "Add a few transactions to unlock your spending insights."
        } else {
            val topCategory = displayedTransactions
                .filter { it.type == TransactionType.EXPENSE }
                .groupBy { it.category }
                .maxByOrNull { entry -> entry.value.sumOf { it.amount } }
            if (topCategory != null && totalExpense > 0) {
                val pct = ((topCategory.value.sumOf { it.amount } / totalExpense) * 100).toInt()
                "$pct% of your current spending is in ${topCategory.key}."
            } else if (totalIncome > 0) {
                val savingsRate = (((totalIncome - totalExpense) / totalIncome) * 100).toInt().coerceAtLeast(0)
                "Net savings rate is $savingsRate% this cycle."
            } else {
                "Track your daily cash flow to maintain financial clarity."
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "sync_spin_home")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin_home"
    )

    val timeBasedGreeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Welcome back"
        }
    }

    val formattedTodayDate = remember {
        SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())
    }

    val userInitial = remember(currentUserName) {
        currentUserName.trim().firstOrNull()?.uppercase() ?: "Y"
    }

    val upcomingBills = remember(state.autoPayReminders) {
        val now = System.currentTimeMillis()
        val sevenDaysLater = now + (7L * 24 * 60 * 60 * 1000)
        state.autoPayReminders.filter { !it.isPaused && it.nextDueDateMillis <= sevenDaysLater }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 116.dp)
    ) {
        // --- 1. MODERN FINTECH HEADER ---
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onNavigateToProfile() }
                        .padding(vertical = 2.dp)
                        .testTag("header_user_profile")
                ) {
                    Box(
                        modifier = Modifier.size(42.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.sweepGradient(
                                        listOf(
                                            ZenithTheme.accentGreen,
                                            ZenithTheme.accentIndigo,
                                            ZenithTheme.accentCyan,
                                            ZenithTheme.accentGreen
                                        )
                                    )
                                )
                                .padding(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(ZenithTheme.cardVariantSurface),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = userInitial,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Black,
                                    color = ZenithTheme.textPrimary
                                )
                            }
                        }

                        val syncDotColor = when (syncUiState) {
                            is SyncUiState.Syncing -> ZenithTheme.accentCyan
                            is SyncUiState.Success, is SyncUiState.Idle -> ZenithTheme.accentGreen
                            is SyncUiState.Error -> ZenithTheme.accentAmber
                        }
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .align(Alignment.BottomEnd)
                                .clip(CircleShape)
                                .background(ZenithTheme.cardSurface)
                                .padding(1.5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(syncDotColor)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(11.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "$timeBasedGreeting • $formattedTodayDate",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ZenithTheme.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = currentUserName,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.3).sp,
                            color = ZenithTheme.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ZenithTheme.cardSurface,
                        border = BorderStroke(1.dp, ZenithTheme.borderColor),
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onNavigateToActivity() }
                            .testTag("btn_header_search")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search Transactions",
                                tint = ZenithTheme.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    val isCurrentlySyncing = syncUiState is SyncUiState.Syncing
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ZenithTheme.cardSurface,
                        border = BorderStroke(
                            1.dp,
                            if (isCurrentlySyncing) ZenithTheme.accentCyan.copy(alpha = 0.5f) else ZenithTheme.borderColor
                        ),
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSyncNow?.invoke() }
                            .testTag("btn_header_sync")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isCurrentlySyncing) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = "Syncing",
                                    tint = ZenithTheme.accentCyan,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .rotate(spinAngle)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (syncUiState is SyncUiState.Error) ZenithTheme.accentAmber
                                            else ZenithTheme.accentGreen
                                        )
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (syncUiState is SyncUiState.Error) "Offline" else "Sync",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ZenithTheme.textPrimary,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ZenithTheme.accentIndigo.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, ZenithTheme.accentIndigo.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenVoiceAssistant() }
                            .testTag("btn_voice_entry_header")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Voice Assistant",
                                tint = ZenithTheme.accentIndigo,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }

        // --- 2. HERO BALANCE CARD ---
        item {
            Card(
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("net_balance_card")
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = ZenithTheme.heroGradient,
                            shape = RoundedCornerShape(26.dp)
                        )
                        .border(1.dp, ZenithTheme.heroBorderColor, RoundedCornerShape(26.dp))
                        .padding(22.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "TOTAL NET BALANCE",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.3.sp,
                                    color = ZenithTheme.accentIndigo
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                IconButton(
                                    onClick = onToggleHideBalance,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = if (state.isBalanceHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle Balance Privacy",
                                        tint = ZenithTheme.accentIndigo.copy(alpha = 0.85f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            if (displayedTransactions.isNotEmpty()) {
                                val badgeColor = if (netBalance >= 0) ZenithTheme.accentGreen else ZenithTheme.accentRed
                                Surface(
                                    color = badgeColor.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (netBalance >= 0) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                                            contentDescription = null,
                                            tint = badgeColor,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (netBalance >= 0) "Surplus" else "Deficit",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = badgeColor
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        RollingCounterText(
                            amount = netBalance,
                            currencySymbol = state.currencySymbol,
                            isMasked = state.isBalanceHidden,
                            decimalPlaces = 2,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Black,
                            color = ZenithTheme.textPrimary,
                            letterSpacing = (-0.5).sp,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = ZenithTheme.cardSurface.copy(alpha = if (ZenithTheme.isDark) 0.72f else 0.90f),
                                border = BorderStroke(1.dp, ZenithTheme.borderColor),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(ZenithTheme.accentGreen.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowDownward,
                                            contentDescription = null,
                                            tint = ZenithTheme.accentGreen,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f, fill = true)) {
                                        Text(
                                            "Income",
                                            fontSize = 11.sp,
                                            color = ZenithTheme.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        RollingCounterText(
                                            amount = totalIncome,
                                            currencySymbol = state.currencySymbol,
                                            prefix = "+",
                                            isMasked = state.isBalanceHidden,
                                            decimalPlaces = 0,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ZenithTheme.accentGreen,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = ZenithTheme.cardSurface.copy(alpha = if (ZenithTheme.isDark) 0.72f else 0.90f),
                                border = BorderStroke(1.dp, ZenithTheme.borderColor),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(ZenithTheme.accentRed.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowUpward,
                                            contentDescription = null,
                                            tint = ZenithTheme.accentRed,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f, fill = true)) {
                                        Text(
                                            "Spent",
                                            fontSize = 11.sp,
                                            color = ZenithTheme.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        RollingCounterText(
                                            amount = totalExpense,
                                            currencySymbol = state.currencySymbol,
                                            prefix = "-",
                                            isMasked = state.isBalanceHidden,
                                            decimalPlaces = 0,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ZenithTheme.accentRed,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // --- 3. QUICK ACTIONS HUB (4 Cards: Scan QR, UPI Pay, Receipt, Voice Entry) ---
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActionCard(
                    icon = Icons.Default.QrCodeScanner,
                    label = "Scan QR",
                    accentColor = ZenithTheme.accentCyan,
                    containerColor = ZenithTheme.accentCyan.copy(alpha = 0.14f),
                    testTag = "btn_scan_upi_qr",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenUpiScanner
                )

                QuickActionCard(
                    icon = Icons.Default.Payment,
                    label = "UPI Pay",
                    accentColor = ZenithTheme.accentGreen,
                    containerColor = ZenithTheme.accentGreen.copy(alpha = 0.14f),
                    testTag = "btn_pay_upi",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenUpiPay
                )

                QuickActionCard(
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    label = "Receipt",
                    accentColor = ZenithTheme.accentAmber,
                    containerColor = ZenithTheme.accentAmber.copy(alpha = 0.14f),
                    testTag = "btn_scan_receipt",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenReceiptScanner
                )

                QuickActionCard(
                    icon = Icons.Default.Mic,
                    label = "Voice Entry",
                    accentColor = ZenithTheme.accentIndigo,
                    containerColor = ZenithTheme.accentIndigo.copy(alpha = 0.14f),
                    testTag = "btn_voice_entry",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenVoiceAssistant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // --- 3B. MISSED / PENDING DETECTED UPI PAYMENTS RECOVERY QUEUE ---
        if (pendingDetections.isNotEmpty()) {
            item {
                val latest = pendingDetections.first()
                CleanCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onReviewPendingDetection?.invoke(latest) }
                        .testTag("card_pending_upi_detections"),
                    backgroundColor = ZenithTheme.accentCyan.copy(alpha = 0.10f),
                    borderColor = ZenithTheme.accentCyan.copy(alpha = 0.30f),
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(ZenithTheme.accentCyan.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = ZenithTheme.accentCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Pending UPI Detection",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ZenithTheme.textPrimary
                                    )
                                    if (pendingDetections.size > 1) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = ZenithTheme.accentCyan.copy(alpha = 0.18f)
                                        ) {
                                            Text(
                                                text = "${pendingDetections.size} queued",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ZenithTheme.accentCyan,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                val amtLabel = if (state.isBalanceHidden) {
                                    "${state.currencySymbol}••••"
                                } else {
                                    "${state.currencySymbol}${String.format(Locale.US, "%,.0f", latest.amount)}"
                                }
                                Text(
                                    text = "$amtLabel ${if (latest.isDebit) "at" else "from"} ${latest.payeeName} (${latest.sourceApp})",
                                    fontSize = 11.5.sp,
                                    color = ZenithTheme.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { onQuickLogPendingDetection?.invoke(latest) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Log",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ZenithTheme.accentGreen
                                )
                            }
                            if (pendingDetections.size > 1 && onDismissAllPendingDetections != null) {
                                TextButton(
                                    onClick = { onDismissAllPendingDetections.invoke() },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "Clear",
                                        fontSize = 11.sp,
                                        color = ZenithTheme.textSecondary
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onDismissPendingDetection?.invoke(latest) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss detection",
                                    tint = ZenithTheme.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }
        }

        // --- 4. UPCOMING AUTO-PAY & BILLS SMART CARD ---
        if (upcomingBills.isNotEmpty()) {
            item {
                val totalUpcomingAmt = upcomingBills.sumOf { it.amount }
                val earliestBill = upcomingBills.minByOrNull { it.nextDueDateMillis }!!
                val now = System.currentTimeMillis()
                val diffDays = ((earliestBill.nextDueDateMillis - now) / (1000 * 60 * 60 * 24)).toInt()
                val dueText = when {
                    diffDays < 0 -> "Overdue"
                    diffDays == 0 -> "Due today"
                    diffDays == 1 -> "Due tomorrow"
                    else -> "Due in $diffDays days"
                }

                CleanCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToBudgets() },
                    backgroundColor = ZenithTheme.accentPurple.copy(alpha = 0.10f),
                    borderColor = ZenithTheme.accentPurple.copy(alpha = 0.28f),
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ZenithTheme.accentPurple.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = ZenithTheme.accentPurple,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Upcoming Auto-Pay & Bills",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ZenithTheme.textPrimary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    val dueBadgeColor = if (diffDays <= 1) ZenithTheme.accentRed else ZenithTheme.accentAmber
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = dueBadgeColor.copy(alpha = 0.16f)
                                    ) {
                                        Text(
                                            text = dueText,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = dueBadgeColor,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = "${earliestBill.title}${if (upcomingBills.size > 1) " + ${upcomingBills.size - 1} more" else ""} • Tap to view",
                                    fontSize = 11.sp,
                                    color = ZenithTheme.textSecondary
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = if (state.isBalanceHidden) "${state.currencySymbol}••••" else "${state.currencySymbol}${String.format(Locale.US, "%,.0f", totalUpcomingAmt)}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.accentPurple
                            )
                            Text(
                                text = "Committed",
                                fontSize = 10.sp,
                                color = ZenithTheme.accentPurple.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
            }
        }

        // --- 5. SPENDING INSIGHT CARD ---
        item {
            CleanCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = ZenithTheme.cardGlassBackground,
                contentPadding = PaddingValues(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ZenithTheme.accentAmber.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lightbulb,
                            contentDescription = null,
                            tint = ZenithTheme.accentAmber,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Smart Insight",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZenithTheme.textPrimary
                        )
                        Text(
                            text = spendingInsight,
                            fontSize = 12.sp,
                            color = ZenithTheme.textSecondary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
        }

        // --- 6. RECENT TRANSACTIONS HEADER & LIST ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Recent Activity",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = ZenithTheme.textPrimary
                    )
                    Text(
                        text = "Tap any entry for full breakdown",
                        fontSize = 11.sp,
                        color = ZenithTheme.textSecondary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = ZenithTheme.solidGreen,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenAddTransaction() }
                            .testTag("fab_add_transaction")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add Transaction", tint = Color.White, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    TextButton(
                        onClick = onNavigateToActivity,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "See all →",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZenithTheme.accentGreen
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        if (displayedTransactions.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ZenithTheme.cardGlassBackground,
                    border = BorderStroke(1.dp, ZenithTheme.borderColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(ZenithTheme.accentIndigo.copy(alpha = 0.14f))
                                .border(1.dp, ZenithTheme.accentIndigo.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                                contentDescription = null,
                                tint = ZenithTheme.accentIndigo,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No transactions yet",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZenithTheme.textPrimary
                        )
                        Text(
                            text = "Track your income, daily spends, or scan a UPI QR code to get started.",
                            fontSize = 12.sp,
                            color = ZenithTheme.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                        )
                        Button(
                            onClick = onOpenAddTransaction,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Transaction", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                        }
                    }
                }
            }
        } else {
            items(displayedTransactions.take(6), key = { "recent_${it.id}" }) { tx ->
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
}

@Composable
fun QuickActionCard(
    icon: ImageVector,
    label: String,
    accentColor: Color,
    containerColor: Color,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = ZenithTheme.cardSurface,
        border = BorderStroke(1.dp, ZenithTheme.borderColor),
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onClick()
            }
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(containerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = ZenithTheme.textPrimary,
                maxLines = 1
            )
        }
    }
}

@Composable
fun GlassActionButton(
    icon: ImageVector,
    contentDescription: String? = null,
    label: String? = null,
    modifier: Modifier = Modifier,
    accentColor: Color = ZenithTheme.accentGreen,
    onClick: () -> Unit
) {
    val description = contentDescription ?: label
    Surface(
        shape = CircleShape,
        color = ZenithTheme.glassCardBg,
        border = BorderStroke(1.dp, ZenithTheme.borderColor),
        modifier = modifier
            .fillMaxHeight()
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = description,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
