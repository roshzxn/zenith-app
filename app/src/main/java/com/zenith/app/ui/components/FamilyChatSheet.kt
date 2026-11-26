package com.zenith.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zenith.app.data.models.FamilyMessageEntity
import com.zenith.app.data.models.TransactionEntity
import com.zenith.app.data.models.TransactionType
import com.zenith.app.data.repository.FamilyTxAttribution
import com.zenith.app.domain.FamilyInfo
import com.zenith.app.domain.FamilyMemberInfo
import com.zenith.app.domain.FamilyRole
import com.zenith.app.ui.theme.ZenithTheme
import java.text.SimpleDateFormat
import java.util.*

/**
 * WhatsApp-style Family Group Chat modal integrated seamlessly with Zenith's
 * design tokens. Supports live sync indicators, reply quotes, 1-tap expense sharing,
 * delivery status ticks (Clock vs Double Check), and creator/admin message deletion.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FamilyChatSheet(
    family: FamilyInfo,
    members: List<FamilyMemberInfo>,
    messages: List<FamilyMessageEntity>,
    familyTransactions: List<TransactionEntity>,
    txAttributionByServerId: Map<String, FamilyTxAttribution>,
    currentUserId: String,
    currentUserRole: FamilyRole?,
    currencySymbol: String,
    isRealtimeConnected: Boolean = false,
    onSendMessage: (body: String, replyToMessageId: String?, relatedTransactionId: String?) -> Unit,
    onDeleteMessage: (FamilyMessageEntity) -> Unit,
    onOpenManageFamily: () -> Unit,
    onDismiss: () -> Unit
) {
    var composerText by remember { mutableStateOf("") }
    var replyTarget by remember { mutableStateOf<FamilyMessageEntity?>(null) }
    var attachedTx by remember { mutableStateOf<TransactionEntity?>(null) }
    var showExpensePicker by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val messagesById = remember(messages) { messages.associateBy { it.id } }
    val memberLabelsByUserId = remember(members) { members.associate { it.userId to it.memberLabel } }
    val txByServerId = remember(familyTransactions) {
        familyTransactions.mapNotNull { tx -> tx.serverId?.let { it to tx } }.toMap()
    }

    val groupedFeed = remember(messages) {
        val now = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val dayFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        messages.groupBy { msg ->
            val cal = Calendar.getInstance().apply { timeInMillis = msg.createdAtMillis }
            when {
                now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                    now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR) -> "Today"
                yesterday.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                    yesterday.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR) -> "Yesterday"
                else -> dayFmt.format(Date(msg.createdAtMillis))
            }
        }
    }

    LaunchedEffect(messages.size, groupedFeed.size) {
        if (messages.isNotEmpty()) {
            val totalItems = messages.size + groupedFeed.size
            listState.animateScrollToItem((totalItems - 1).coerceAtLeast(0))
        }
    }

    fun sendCurrentMessage() {
        val defaultLabel = attachedTx?.let { tx ->
            if (tx.type == TransactionType.EXPENSE) "Shared expense: ${tx.title}" else "Shared income: ${tx.title}"
        }.orEmpty()
        val textToSend = composerText.trim().ifBlank { defaultLabel }
        if (textToSend.isNotBlank()) {
            onSendMessage(
                textToSend,
                replyTarget?.id,
                attachedTx?.serverId
            )
            composerText = ""
            replyTarget = null
            attachedTx = null
            showExpensePicker = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            color = ZenithTheme.cardSurface,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .testTag("family_chat_sheet")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // --- WhatsApp-Style Header Bar ---
                Surface(
                    color = ZenithTheme.cardVariantSurface,
                    tonalElevation = 2.dp
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("btn_close_family_chat")
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Close Chat",
                                        tint = ZenithTheme.textPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(ZenithTheme.accentCyan.copy(alpha = 0.16f))
                                        .border(1.dp, ZenithTheme.accentCyan.copy(alpha = 0.35f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(family.avatarEmoji, fontSize = 20.sp)
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = family.name,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = ZenithTheme.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (family.familyLabel.isNotBlank()) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = ZenithTheme.accentCyan.copy(alpha = 0.14f)
                                            ) {
                                                Text(
                                                    text = family.familyLabel,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = ZenithTheme.accentCyan,
                                                    maxLines = 1,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(if (isRealtimeConnected) ZenithTheme.accentGreen else ZenithTheme.accentAmber)
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = "${members.size} members • ${if (isRealtimeConnected) "Live" else "Cloud Sync"}",
                                            fontSize = 11.sp,
                                            color = ZenithTheme.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = onOpenManageFamily,
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("btn_chat_manage_family")
                            ) {
                                Icon(
                                    Icons.Default.Group,
                                    contentDescription = "Manage Family",
                                    tint = ZenithTheme.accentCyan,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                        }
                        HorizontalDivider(color = ZenithTheme.borderColor)
                    }
                }

                // --- Chat Feed ---
                if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(family.avatarEmoji, fontSize = 36.sp)
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Welcome to ${family.name} Chat",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.textPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Discuss shared expenses, bills, or household budgets together.",
                                fontSize = 12.sp,
                                color = ZenithTheme.textSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        groupedFeed.forEach { (dayLabel, dayMsgs) ->
                            item(key = "day_$dayLabel") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = ZenithTheme.cardVariantSurface,
                                        border = BorderStroke(1.dp, ZenithTheme.borderColor)
                                    ) {
                                        Text(
                                            text = dayLabel,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = ZenithTheme.textSecondary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }

                            items(dayMsgs, key = { it.id }) { msg ->
                                if (msg.messageType == "SYSTEM") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = ZenithTheme.accentCyan.copy(alpha = 0.12f)
                                        ) {
                                            Text(
                                                text = msg.body,
                                                fontSize = 11.sp,
                                                color = ZenithTheme.accentCyan,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                            )
                                        }
                                    }
                                } else {
                                    val isMe = msg.senderUserId == currentUserId
                                    val canDelete = isMe || (currentUserRole?.canAdmin() == true)
                                    val quotedMsg = msg.replyToMessageId?.let { messagesById[it] }
                                    val sharedTx = msg.relatedTransactionId?.let { txByServerId[it] }
                                    val sharedAttr = msg.relatedTransactionId?.let { txAttributionByServerId[it] }

                                    FamilyChatBubble(
                                        message = msg,
                                        isMe = isMe,
                                        canDelete = canDelete,
                                        senderLabel = memberLabelsByUserId[msg.senderUserId].orEmpty(),
                                        quotedMessage = quotedMsg,
                                        sharedTx = sharedTx,
                                        sharedAttr = sharedAttr,
                                        currencySymbol = currencySymbol,
                                        onReply = { replyTarget = msg },
                                        onDelete = { onDeleteMessage(msg) }
                                    )
                                }
                            }
                        }
                    }
                }

                // --- Reply Quote Banner ---
                replyTarget?.let { reply ->
                    Surface(
                        color = ZenithTheme.cardVariantSurface,
                        border = BorderStroke(1.dp, ZenithTheme.accentCyan.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Replying to ${reply.senderName}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ZenithTheme.accentCyan
                                )
                                Text(
                                    text = reply.body,
                                    fontSize = 11.sp,
                                    color = ZenithTheme.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { replyTarget = null }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel Reply", tint = ZenithTheme.textSecondary, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                // --- Attached Transaction Preview Banner ---
                attachedTx?.let { tx ->
                    val isExp = tx.type == TransactionType.EXPENSE
                    val bannerColor = if (isExp) ZenithTheme.accentRed else ZenithTheme.accentGreen
                    Surface(
                        color = bannerColor.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, bannerColor.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${if (isExp) "Sharing Expense" else "Sharing Income"}: ${tx.title}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = bannerColor
                                )
                                Text(
                                    text = "${if (isExp) "-" else "+"}$currencySymbol${String.format(Locale.US, "%,.0f", tx.amount)} • ${tx.category}",
                                    fontSize = 10.5.sp,
                                    color = ZenithTheme.textSecondary
                                )
                            }
                            IconButton(onClick = { attachedTx = null }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Remove Expense", tint = ZenithTheme.textSecondary, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                // --- Quick Share Family Transaction Picker ---
                if (showExpensePicker) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ZenithTheme.cardVariantSurface)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Share a Family Transaction",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.textSecondary
                            )
                            IconButton(
                                onClick = { showExpensePicker = false },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close Picker", tint = ZenithTheme.textSecondary, modifier = Modifier.size(14.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val pickable = familyTransactions.filter { !it.serverId.isNullOrBlank() }.take(12)
                            if (pickable.isEmpty()) {
                                item {
                                    Text(
                                        text = "No family transactions to attach yet.",
                                        fontSize = 11.sp,
                                        color = ZenithTheme.textSecondary,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                                    )
                                }
                            } else {
                                items(pickable, key = { "pick_${it.id}" }) { tx ->
                                    val isExp = tx.type == TransactionType.EXPENSE
                                    val sign = if (isExp) "-" else "+"
                                    FilterChip(
                                        selected = attachedTx?.id == tx.id,
                                        onClick = {
                                            attachedTx = tx
                                            if (composerText.isBlank()) {
                                                composerText = if (isExp) "Shared expense: ${tx.title}" else "Shared income: ${tx.title}"
                                            }
                                            showExpensePicker = false
                                        },
                                        label = {
                                            Text(
                                                "${tx.title} ($sign$currencySymbol${String.format(Locale.US, "%,.0f", tx.amount)})",
                                                fontSize = 11.sp,
                                                maxLines = 1
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // --- Composer Bar ---
                HorizontalDivider(color = ZenithTheme.borderColor)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ZenithTheme.cardVariantSurface.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showExpensePicker = !showExpensePicker },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("family_chat_attach_expense")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Receipt,
                            contentDescription = "Share Transaction",
                            tint = if (showExpensePicker || attachedTx != null) ZenithTheme.accentCyan else ZenithTheme.textSecondary
                        )
                    }

                    OutlinedTextField(
                        value = composerText,
                        onValueChange = { composerText = it },
                        placeholder = { Text("Message ${family.name}...", fontSize = 13.sp, color = ZenithTheme.textMuted) },
                        shape = RoundedCornerShape(22.dp),
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendCurrentMessage() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ZenithTheme.accentGreen,
                            unfocusedBorderColor = ZenithTheme.borderColor,
                            focusedContainerColor = ZenithTheme.cardSurface,
                            unfocusedContainerColor = ZenithTheme.cardSurface,
                            focusedTextColor = ZenithTheme.textPrimary,
                            unfocusedTextColor = ZenithTheme.textPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("family_chat_input")
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    val canSend = composerText.trim().isNotEmpty() || attachedTx != null
                    Surface(
                        shape = CircleShape,
                        color = if (canSend) ZenithTheme.solidGreen else ZenithTheme.cardVariantSurface,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable(enabled = canSend) { sendCurrentMessage() }
                            .testTag("family_chat_send_btn")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (canSend) Color.White else ZenithTheme.textMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FamilyChatBubble(
    message: FamilyMessageEntity,
    isMe: Boolean,
    canDelete: Boolean,
    senderLabel: String = "",
    quotedMessage: FamilyMessageEntity?,
    sharedTx: TransactionEntity?,
    sharedAttr: FamilyTxAttribution?,
    currencySymbol: String,
    onReply: () -> Unit,
    onDelete: () -> Unit
) {
    val timeLabel = remember(message.createdAtMillis) {
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(message.createdAtMillis))
    }
    var showActions by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isMe) {
            Box(
                modifier = Modifier
                    .padding(end = 6.dp, bottom = 2.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(ZenithTheme.cardVariantSurface),
                contentAlignment = Alignment.Center
            ) {
                Text(message.senderAvatar.ifBlank { "🙂" }, fontSize = 15.sp)
            }
        }

        Column(
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Surface(
                shape = if (isMe) {
                    RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
                } else {
                    RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
                },
                color = if (isMe) ZenithTheme.accentGreen.copy(alpha = 0.20f) else ZenithTheme.cardVariantSurface,
                border = BorderStroke(
                    1.dp,
                    if (isMe) ZenithTheme.accentGreen.copy(alpha = 0.40f) else ZenithTheme.borderColor
                ),
                modifier = Modifier
                    .combinedClickable(
                        onClick = { showActions = !showActions },
                        onLongClick = { showActions = true }
                    )
                    .testTag("chat_bubble_${message.id}")
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (!isMe) {
                        Text(
                            text = if (senderLabel.isNotBlank()) "${message.senderName} • $senderLabel" else message.senderName,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZenithTheme.accentCyan
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    quotedMessage?.let { q ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ZenithTheme.cardSurface.copy(alpha = 0.7f),
                            border = BorderStroke(1.dp, ZenithTheme.accentCyan.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text(q.senderName, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ZenithTheme.accentCyan)
                                Text(q.body, fontSize = 11.sp, color = ZenithTheme.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    sharedTx?.let { tx ->
                        val isExp = tx.type == TransactionType.EXPENSE
                        val amtColor = if (isExp) ZenithTheme.accentRed else ZenithTheme.accentGreen
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ZenithTheme.cardSurface,
                            border = BorderStroke(1.dp, amtColor.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = tx.title,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ZenithTheme.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${if (isExp) "-" else "+"}$currencySymbol${String.format(Locale.US, "%,.0f", tx.amount)}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = amtColor
                                    )
                                }
                                Text(
                                    text = "${tx.category} • ${if (isExp) "Paid by" else "Received by"} ${sharedAttr?.paidByName ?: tx.paymentMethod}",
                                    fontSize = 10.sp,
                                    color = ZenithTheme.textSecondary
                                )
                            }
                        }
                    }

                    Text(
                        text = message.body,
                        fontSize = 13.5.sp,
                        color = ZenithTheme.textPrimary
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = timeLabel,
                            fontSize = 9.5.sp,
                            color = ZenithTheme.textMuted
                        )
                        if (isMe) {
                            val synced = message.syncStatus == "SYNCED"
                            Icon(
                                imageVector = if (synced) Icons.Default.DoneAll else Icons.Default.Schedule,
                                contentDescription = if (synced) "Synced" else "Pending Sync",
                                tint = if (synced) ZenithTheme.accentCyan else ZenithTheme.accentAmber,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    if (showActions) {
                        HorizontalDivider(
                            color = ZenithTheme.borderColor.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        showActions = false
                                        onReply()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                                    .testTag("chat_reply_${message.id}"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(13.dp), tint = ZenithTheme.accentCyan)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reply", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ZenithTheme.accentCyan)
                            }
                            if (canDelete) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            showActions = false
                                            onDelete()
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                        .testTag("chat_delete_${message.id}"),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(13.dp), tint = ZenithTheme.accentRed)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Delete", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ZenithTheme.accentRed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
