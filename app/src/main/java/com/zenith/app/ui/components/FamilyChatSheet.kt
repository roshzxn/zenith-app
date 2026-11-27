package com.zenith.app.ui.components

import android.util.Patterns
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.zenith.app.data.models.FamilyMessageEntity
import com.zenith.app.data.models.TransactionEntity
import com.zenith.app.data.models.TransactionType
import com.zenith.app.data.repository.FamilyTxAttribution
import com.zenith.app.data.util.AmountFormatter
import com.zenith.app.domain.FamilyInfo
import com.zenith.app.domain.FamilyMemberInfo
import com.zenith.app.domain.FamilyRole
import com.zenith.app.ui.theme.ZenithAccentPreset
import com.zenith.app.ui.theme.ZenithTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

private val QUICK_CHAT_EMOJIS = listOf("👍", "❤️", "😂", "🙏", "🎉", "✅", "💡", "🔥", "💰", "🙌")

private data class ChatPalette(
    val canvasBg: Color,
    val patternTint: Color,
    val headerBg: Color,
    val headerBorder: Color,
    val datePillBg: Color,
    val incomingBubbleBg: Color,
    val incomingBubbleBorder: Color,
    val outgoingBubbleBg: Color,
    val outgoingBubbleBorder: Color,
    val bubbleTextPrimary: Color,
    val bubbleTextSecondary: Color,
    val timestampMuted: Color,
    val syncedTickTint: Color,
    val linkColor: Color,
    val composerDockBg: Color,
    val inputPillBg: Color,
    val inputPillBorder: Color
)

@Composable
private fun rememberChatPalette(): ChatPalette {
    val isDark = ZenithTheme.isDark
    val isOled = ZenithTheme.isOled
    val accent = ZenithTheme.accentPreset
    return remember(isDark, isOled, accent) {
        val outgoingDarkBg = when (accent) {
            ZenithAccentPreset.INDIGO -> Color(0xFF1D2448)
            ZenithAccentPreset.EMERALD -> Color(0xFF0B352D)
            ZenithAccentPreset.ROSHZEN -> Color(0xFF281842)
            ZenithAccentPreset.CYAN -> Color(0xFF0C2E3D)
            ZenithAccentPreset.AMBER -> Color(0xFF33230C)
        }
        val outgoingDarkBorder = accent.swatchColor.copy(alpha = 0.45f)
        val outgoingLightBg = when (accent) {
            ZenithAccentPreset.INDIGO -> Color(0xFFE8EDFF)
            ZenithAccentPreset.EMERALD -> Color(0xFFE4F8F0)
            ZenithAccentPreset.ROSHZEN -> Color(0xFFF3E8FF)
            ZenithAccentPreset.CYAN -> Color(0xFFE3F7FB)
            ZenithAccentPreset.AMBER -> Color(0xFFFEF3DC)
        }
        val outgoingLightBorder = accent.lightAccent.copy(alpha = 0.28f)

        when {
            isOled -> ChatPalette(
                canvasBg = Color(0xFF000000),
                patternTint = Color.White.copy(alpha = 0.032f),
                headerBg = Color(0xFF07090E),
                headerBorder = Color(0xFF1E2430),
                datePillBg = Color(0xFF10141D),
                incomingBubbleBg = Color(0xFF11151E),
                incomingBubbleBorder = Color(0xFF222938),
                outgoingBubbleBg = outgoingDarkBg,
                outgoingBubbleBorder = outgoingDarkBorder,
                bubbleTextPrimary = Color(0xFFF8FAFC),
                bubbleTextSecondary = Color(0xFFA7B4C4),
                timestampMuted = Color(0xFF94A3B8),
                syncedTickTint = accent.darkAccent,
                linkColor = Color(0xFF38BDF8),
                composerDockBg = Color(0xFF090C12),
                inputPillBg = Color(0xFF121722),
                inputPillBorder = Color(0xFF283142)
            )
            isDark -> ChatPalette(
                canvasBg = Color(0xFF090E1A),
                patternTint = Color.White.copy(alpha = 0.035f),
                headerBg = Color(0xFF101726),
                headerBorder = Color(0xFF243045),
                datePillBg = Color(0xFF151E2E),
                incomingBubbleBg = Color(0xFF172033),
                incomingBubbleBorder = Color(0xFF29364F),
                outgoingBubbleBg = outgoingDarkBg,
                outgoingBubbleBorder = outgoingDarkBorder,
                bubbleTextPrimary = Color(0xFFF8FAFC),
                bubbleTextSecondary = Color(0xFFC2CED9),
                timestampMuted = Color(0xFFA2B3C6),
                syncedTickTint = accent.darkAccent,
                linkColor = Color(0xFF38BDF8),
                composerDockBg = Color(0xFF0E1524),
                inputPillBg = Color(0xFF182235),
                inputPillBorder = Color(0xFF31415E)
            )
            else -> ChatPalette(
                canvasBg = Color(0xFFF2F5FA),
                patternTint = Color(0xFF64748B).copy(alpha = 0.045f),
                headerBg = Color(0xFFFFFFFF),
                headerBorder = Color(0xFFD6DFEB),
                datePillBg = Color(0xFFFFFFFF),
                incomingBubbleBg = Color(0xFFFFFFFF),
                incomingBubbleBorder = Color(0xFFD6DFEB),
                outgoingBubbleBg = outgoingLightBg,
                outgoingBubbleBorder = outgoingLightBorder,
                bubbleTextPrimary = Color(0xFF0F172A),
                bubbleTextSecondary = Color(0xFF475569),
                timestampMuted = Color(0xFF526374),
                syncedTickTint = accent.lightAccent,
                linkColor = accent.lightAccent,
                composerDockBg = Color(0xFFF2F5FA),
                inputPillBg = Color(0xFFFFFFFF),
                inputPillBorder = Color(0xFFCBD5E1)
            )
        }
    }
}

private data class ChatRowItem(
    val message: FamilyMessageEntity,
    val isFirstInGroup: Boolean,
    val topSpacingDp: Int
)

private data class DayFeedGroup(
    val dayLabel: String,
    val rows: List<ChatRowItem>
)

private data class ChatFeedProjection(
    val groups: List<DayFeedGroup>,
    val messageIndexById: Map<String, Int>,
    val totalItemCount: Int
)

/**
 * WhatsApp-style Family Group Chat modal integrated seamlessly with Zenith's
 * design tokens. Supports live sync indicators, reply quotes, 1-tap expense sharing,
 * delivery status ticks (Clock vs Double Check), swipe-to-reply, search, and creator/admin message deletion.
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
    onDismiss: () -> Unit,
    onViewSharedTransaction: ((TransactionEntity) -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { win ->
                WindowCompat.setDecorFitsSystemWindows(win, false)
                win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                @Suppress("DEPRECATION")
                win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            }
        }

        FamilyChatContent(
            family = family,
            members = members,
            messages = messages,
            familyTransactions = familyTransactions,
            txAttributionByServerId = txAttributionByServerId,
            currentUserId = currentUserId,
            currentUserRole = currentUserRole,
            currencySymbol = currencySymbol,
            isRealtimeConnected = isRealtimeConnected,
            onSendMessage = onSendMessage,
            onDeleteMessage = onDeleteMessage,
            onOpenManageFamily = onOpenManageFamily,
            onDismiss = onDismiss,
            onViewSharedTransaction = onViewSharedTransaction
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FamilyChatContent(
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
    onDismiss: () -> Unit,
    onViewSharedTransaction: ((TransactionEntity) -> Unit)? = null
) {
    val palette = rememberChatPalette()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var composerText by remember { mutableStateOf("") }
    var replyTarget by remember { mutableStateOf<FamilyMessageEntity?>(null) }
    var attachedTx by remember { mutableStateOf<TransactionEntity?>(null) }
    var showExpensePicker by remember { mutableStateOf(false) }
    var showEmojiStrip by remember { mutableStateOf(false) }

    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedContextMessage by remember { mutableStateOf<FamilyMessageEntity?>(null) }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var unreadBelowCount by remember { mutableIntStateOf(0) }
    var lastSeenMessageCount by remember { mutableIntStateOf(messages.size) }

    val listState = rememberLazyListState()
    val messagesById = remember(messages) { messages.associateBy { it.id } }
    val memberLabelsByUserId = remember(members) { members.associate { it.userId to it.memberLabel } }
    val txByServerId = remember(familyTransactions) {
        familyTransactions.mapNotNull { tx -> tx.serverId?.let { it to tx } }.toMap()
    }

    val visibleMessages = remember(messages, isSearchActive, searchQuery) {
        val q = searchQuery.trim()
        if (!isSearchActive || q.isBlank()) {
            messages
        } else {
            messages.filter { msg ->
                msg.body.contains(q, ignoreCase = true) ||
                    msg.senderName.contains(q, ignoreCase = true)
            }
        }
    }

    val feedProjection = remember(visibleMessages) {
        buildChatFeedProjection(visibleMessages)
    }

    val isFarFromBottom by remember {
        derivedStateOf {
            val total = feedProjection.totalItemCount
            if (total == 0) return@derivedStateOf false
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            (total - 1 - lastVisible) >= 3
        }
    }

    LaunchedEffect(Unit) {
        if (feedProjection.totalItemCount > 0) {
            listState.scrollToItem(feedProjection.totalItemCount - 1)
        }
    }

    LaunchedEffect(isFarFromBottom) {
        if (!isFarFromBottom) {
            unreadBelowCount = 0
        }
    }

    LaunchedEffect(messages.size, isSearchActive, searchQuery) {
        val total = feedProjection.totalItemCount
        if (total > 0) {
            val addedCount = (messages.size - lastSeenMessageCount).coerceAtLeast(0)
            if (isSearchActive && searchQuery.isNotBlank()) {
                listState.scrollToItem(total - 1)
            } else if (!isFarFromBottom) {
                listState.animateScrollToItem(total - 1)
                unreadBelowCount = 0
            } else if (addedCount > 0) {
                val newest = messages.lastOrNull()
                if (newest?.senderUserId == currentUserId) {
                    listState.animateScrollToItem(total - 1)
                    unreadBelowCount = 0
                } else {
                    unreadBelowCount += addedCount
                }
            }
        }
        lastSeenMessageCount = messages.size
    }

    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    var keepPinnedOnExpand by remember { mutableStateOf(true) }
    LaunchedEffect(
        imeBottomPx,
        isFarFromBottom,
        replyTarget != null,
        attachedTx != null,
        showEmojiStrip,
        showExpensePicker
    ) {
        if (imeBottomPx == 0 && replyTarget == null && attachedTx == null && !showEmojiStrip && !showExpensePicker) {
            keepPinnedOnExpand = !isFarFromBottom
        }
    }

    LaunchedEffect(
        imeBottomPx,
        replyTarget != null,
        attachedTx != null,
        showEmojiStrip,
        showExpensePicker
    ) {
        val total = feedProjection.totalItemCount
        if (total > 0 && !isSearchActive && keepPinnedOnExpand &&
            (imeBottomPx > 0 || replyTarget != null || attachedTx != null || showEmojiStrip || showExpensePicker)
        ) {
            delay(35L)
            listState.scrollToItem(total - 1)
        }
    }

    LaunchedEffect(highlightedMessageId) {
        if (highlightedMessageId != null) {
            delay(850L)
            highlightedMessageId = null
        }
    }

    BackHandler(enabled = selectedContextMessage != null || isSearchActive || showEmojiStrip || showExpensePicker) {
        when {
            selectedContextMessage != null -> selectedContextMessage = null
            isSearchActive -> {
                isSearchActive = false
                searchQuery = ""
            }
            showEmojiStrip -> showEmojiStrip = false
            showExpensePicker -> showExpensePicker = false
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
            showEmojiStrip = false
        }
    }

    fun scrollToMessageAndFlash(targetId: String) {
        val idx = feedProjection.messageIndexById[targetId] ?: return
        coroutineScope.launch {
            listState.animateScrollToItem(idx)
            highlightedMessageId = targetId
        }
    }

    Surface(
        color = palette.canvasBg,
        modifier = Modifier
            .fillMaxSize()
            .testTag("family_chat_sheet")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FamilyChatHeader(
                family = family,
                memberCount = members.size,
                isRealtimeConnected = isRealtimeConnected,
                palette = palette,
                isSearchActive = isSearchActive,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onToggleSearch = {
                    isSearchActive = !isSearchActive
                    if (!isSearchActive) searchQuery = ""
                    selectedContextMessage = null
                },
                selectedMessage = selectedContextMessage,
                canDeleteSelected = selectedContextMessage?.let {
                    it.senderUserId == currentUserId || (currentUserRole?.canAdmin() == true)
                } == true,
                onClearSelection = { selectedContextMessage = null },
                onContextReply = {
                    selectedContextMessage?.let { msg ->
                        replyTarget = msg
                        selectedContextMessage = null
                    }
                },
                onContextCopy = {
                    selectedContextMessage?.let { msg ->
                        clipboard.setText(AnnotatedString(msg.body))
                        Toast.makeText(context, "Message copied", Toast.LENGTH_SHORT).show()
                        selectedContextMessage = null
                    }
                },
                onContextDelete = {
                    selectedContextMessage?.let { msg ->
                        onDeleteMessage(msg)
                        selectedContextMessage = null
                    }
                },
                onOpenShareExpense = {
                    showEmojiStrip = false
                    showExpensePicker = true
                },
                onJumpToLatest = {
                    if (feedProjection.totalItemCount > 0) {
                        coroutineScope.launch {
                            listState.animateScrollToItem(feedProjection.totalItemCount - 1)
                            unreadBelowCount = 0
                        }
                    }
                },
                onOpenManageFamily = onOpenManageFamily,
                onDismiss = onDismiss
            )

            // Non-blocking Offline / Syncing Status Strip
            AnimatedVisibility(
                visible = !isRealtimeConnected,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ZenithTheme.accentAmber.copy(alpha = 0.12f))
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "Cloud sync active • Messages queue offline automatically",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = ZenithTheme.accentAmber,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // --- Message Canvas + Subtle Vector Pattern + Floating Frosted Capsule Dock ---
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(palette.canvasBg)
            ) {
                ChatDoodleBackground(
                    tint = palette.patternTint,
                    modifier = Modifier.matchParentSize()
                )

                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        if (visibleMessages.isEmpty()) {
                            ChatEmptyState(
                                family = family,
                                isSearching = isSearchActive && searchQuery.isNotBlank(),
                                searchQuery = searchQuery,
                                palette = palette,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp)
                            )
                        } else {
                            FamilyChatFeedList(
                                listState = listState,
                                groups = feedProjection.groups,
                                messagesById = messagesById,
                                memberLabelsByUserId = memberLabelsByUserId,
                                txByServerId = txByServerId,
                                txAttributionByServerId = txAttributionByServerId,
                                currentUserId = currentUserId,
                                currentUserRole = currentUserRole,
                                currencySymbol = currencySymbol,
                                palette = palette,
                                selectedContextMessageId = selectedContextMessage?.id,
                                highlightedMessageId = highlightedMessageId,
                                bottomContentPadding = 16.dp,
                                onSelectContextMessage = { msg ->
                                    selectedContextMessage = if (selectedContextMessage?.id == msg.id) null else msg
                                },
                                onQuoteClick = { quotedId -> scrollToMessageAndFlash(quotedId) },
                                onReply = { msg ->
                                    replyTarget = msg
                                    selectedContextMessage = null
                                },
                                onCopy = { msg ->
                                    clipboard.setText(AnnotatedString(msg.body))
                                    Toast.makeText(context, "Message copied", Toast.LENGTH_SHORT).show()
                                },
                                onViewSharedTransaction = onViewSharedTransaction,
                                onDelete = { msg ->
                                    onDeleteMessage(msg)
                                    if (selectedContextMessage?.id == msg.id) selectedContextMessage = null
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Soft bottom edge vignette right above the floating capsule dock
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(18.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, palette.canvasBg.copy(alpha = 0.72f))
                                    )
                                )
                        )

                        // Floating Jump-to-Bottom Pill above the floating dock
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isFarFromBottom && feedProjection.totalItemCount > 0,
                            enter = fadeIn(tween(ZenithTheme.motionSpec.durationMs(180))) + scaleIn(initialScale = 0.85f),
                            exit = fadeOut(tween(ZenithTheme.motionSpec.durationMs(150))) + scaleOut(targetScale = 0.85f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 14.dp, bottom = 12.dp)
                        ) {
                            JumpToBottomButton(
                                unreadCount = unreadBelowCount,
                                palette = palette,
                                onClick = {
                                    coroutineScope.launch {
                                        if (feedProjection.totalItemCount > 0) {
                                            listState.animateScrollToItem(feedProjection.totalItemCount - 1)
                                        }
                                        unreadBelowCount = 0
                                    }
                                }
                            )
                        }
                    }

                    // --- Floating Frosted Capsule Composer Dock ---
                    FamilyComposerDock(
                        familyName = family.name,
                        composerText = composerText,
                        onComposerTextChange = { composerText = it },
                        replyTarget = replyTarget,
                        onCancelReply = { replyTarget = null },
                        attachedTx = attachedTx,
                        onRemoveAttachedTx = { attachedTx = null },
                        showExpensePicker = showExpensePicker,
                        onToggleExpensePicker = {
                            showEmojiStrip = false
                            showExpensePicker = !showExpensePicker
                        },
                        showEmojiStrip = showEmojiStrip,
                        onToggleEmojiStrip = {
                            showExpensePicker = false
                            showEmojiStrip = !showEmojiStrip
                        },
                        onInsertEmoji = { emoji -> composerText += emoji },
                        familyTransactions = familyTransactions,
                        currencySymbol = currencySymbol,
                        onSelectTransaction = { tx ->
                            attachedTx = tx
                            if (composerText.isBlank()) {
                                composerText = if (tx.type == TransactionType.EXPENSE) {
                                    "Shared expense: ${tx.title}"
                                } else {
                                    "Shared income: ${tx.title}"
                                }
                            }
                            showExpensePicker = false
                        },
                        onSend = { sendCurrentMessage() },
                        palette = palette
                    )
                }
            }
        }
    }
}

@Composable
private fun FamilyChatHeader(
    family: FamilyInfo,
    memberCount: Int,
    isRealtimeConnected: Boolean,
    palette: ChatPalette,
    isSearchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    selectedMessage: FamilyMessageEntity?,
    canDeleteSelected: Boolean,
    onClearSelection: () -> Unit,
    onContextReply: () -> Unit,
    onContextCopy: () -> Unit,
    onContextDelete: () -> Unit,
    onOpenShareExpense: () -> Unit,
    onJumpToLatest: () -> Unit,
    onOpenManageFamily: () -> Unit,
    onDismiss: () -> Unit
) {
    var overflowExpanded by remember { mutableStateOf(false) }

    Surface(
        color = palette.headerBg,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            AnimatedContent(
                targetState = when {
                    selectedMessage != null -> 2
                    isSearchActive -> 1
                    else -> 0
                },
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(120)) },
                label = "chat_header_mode"
            ) { mode ->
                when (mode) {
                    // Contextual Long-Press Bar
                    2 -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = onClearSelection,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear selection",
                                        tint = ZenithTheme.textPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "1 selected",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ZenithTheme.textPrimary
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = onContextReply,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .testTag("ctx_chat_reply")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Reply,
                                        contentDescription = "Reply",
                                        tint = ZenithTheme.accentCyan
                                    )
                                }
                                IconButton(
                                    onClick = onContextCopy,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .testTag("ctx_chat_copy")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy",
                                        tint = ZenithTheme.textPrimary
                                    )
                                }
                                if (canDeleteSelected) {
                                    IconButton(
                                        onClick = onContextDelete,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .testTag("ctx_chat_delete")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete",
                                            tint = ZenithTheme.accentRed
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Inline Search Mode
                    1 -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onToggleSearch,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Close Search",
                                    tint = ZenithTheme.textPrimary
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 6.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(palette.inputPillBg)
                                    .border(1.dp, palette.inputPillBorder, RoundedCornerShape(20.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search messages or member...",
                                        fontSize = 14.sp,
                                        color = ZenithTheme.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = onSearchQueryChange,
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        fontSize = 14.sp,
                                        color = ZenithTheme.textPrimary
                                    ),
                                    cursorBrush = SolidColor(ZenithTheme.accentGreen),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("family_chat_search_input")
                                )
                            }
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { onSearchQueryChange("") },
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = ZenithTheme.textSecondary
                                    )
                                }
                            }
                        }
                    }

                    // Standard Group Header Mode
                    else -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 58.dp)
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left Cluster: Tight Back Arrow + Circular Avatar + Center Title/Subtitle
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 48.dp, minHeight = 44.dp)
                                        .clip(RoundedCornerShape(22.dp))
                                        .clickable(onClick = onDismiss)
                                        .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                                        .testTag("btn_close_family_chat")
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Close Chat",
                                        tint = ZenithTheme.textPrimary,
                                        modifier = Modifier.size(21.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(ZenithTheme.accentCyan.copy(alpha = 0.16f))
                                            .border(1.dp, ZenithTheme.accentCyan.copy(alpha = 0.35f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(family.avatarEmoji.ifBlank { "🏠" }, fontSize = 18.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onOpenManageFamily() }
                                ) {
                                    Text(
                                        text = family.name,
                                        fontSize = 16.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ZenithTheme.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(1.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val livePulseAlpha = if (isRealtimeConnected && !ZenithTheme.motionSpec.isReduced) {
                                            val inf = rememberInfiniteTransition(label = "chat_live_pulse")
                                            val a by inf.animateFloat(
                                                initialValue = 1f,
                                                targetValue = 0.35f,
                                                animationSpec = infiniteRepeatable(
                                                    animation = tween(1100, easing = LinearEasing),
                                                    repeatMode = RepeatMode.Reverse
                                                ),
                                                label = "pulse_alpha"
                                            )
                                            a
                                        } else 1f
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    (if (isRealtimeConnected) ZenithTheme.accentGreen else ZenithTheme.accentAmber)
                                                        .copy(alpha = livePulseAlpha)
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        val cleanLabel = family.familyLabel
                                            .replace(Regex("^[^\\p{L}\\p{N}]+"), "")
                                            .trim()
                                        val labelPrefix = if (cleanLabel.isNotBlank()) "$cleanLabel · " else ""
                                        val statusLabel = if (isRealtimeConnected) "Live" else "Sync"
                                        Text(
                                            text = "$labelPrefix$memberCount member${if (memberCount == 1) "" else "s"} · $statusLabel",
                                            fontSize = 10.5.sp,
                                            letterSpacing = (-0.2).sp,
                                            color = ZenithTheme.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            // Right Cluster: Compact 360dp-safe Search + Manage Family + 3-dot Menu
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = onToggleSearch,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .testTag("btn_chat_search")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search Messages",
                                        tint = ZenithTheme.textPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
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
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Box {
                                    IconButton(
                                        onClick = { overflowExpanded = true },
                                        modifier = Modifier
                                            .size(38.dp)
                                            .testTag("btn_chat_more")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "Chat Options",
                                            tint = ZenithTheme.textSecondary,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = overflowExpanded,
                                        onDismissRequest = { overflowExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Share Expense") },
                                            leadingIcon = { Icon(Icons.Default.Receipt, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                            onClick = {
                                                overflowExpanded = false
                                                onOpenShareExpense()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Manage Family") },
                                            leadingIcon = { Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                            onClick = {
                                                overflowExpanded = false
                                                onOpenManageFamily()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Jump to Latest") },
                                            leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                            onClick = {
                                                overflowExpanded = false
                                                onJumpToLatest()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = palette.headerBorder)
        }
    }
}

@Composable
private fun ChatDoodleBackground(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val stepX = 68.dp.toPx()
        val stepY = 68.dp.toPx()
        val strokeWidth = 1.1.dp.toPx()
        val cols = (size.width / stepX).toInt() + 2
        val rows = (size.height / stepY).toInt() + 2

        for (r in 0 until rows) {
            val offsetX = if (r % 2 == 0) 0f else stepX * 0.5f
            for (c in 0 until cols) {
                val cx = c * stepX + offsetX
                val cy = r * stepY + 24.dp.toPx()
                when ((r + c) % 3) {
                    0 -> drawCircle(
                        color = tint,
                        radius = 7.dp.toPx(),
                        center = Offset(cx, cy),
                        style = Stroke(width = strokeWidth)
                    )
                    1 -> drawLine(
                        color = tint,
                        start = Offset(cx - 6.dp.toPx(), cy + 4.dp.toPx()),
                        end = Offset(cx + 6.dp.toPx(), cy - 4.dp.toPx()),
                        strokeWidth = strokeWidth
                    )
                    else -> drawRoundRect(
                        color = tint,
                        topLeft = Offset(cx - 6.dp.toPx(), cy - 5.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(12.dp.toPx(), 10.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                        style = Stroke(width = strokeWidth)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatEmptyState(
    family: FamilyInfo,
    isSearching: Boolean,
    searchQuery: String,
    palette: ChatPalette,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = palette.datePillBg.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, palette.incomingBubbleBorder)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                Text(if (isSearching) "🔍" else family.avatarEmoji.ifBlank { "🏠" }, fontSize = 32.sp)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (isSearching) "No matching messages" else "Welcome to ${family.name} Chat",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZenithTheme.textPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isSearching) {
                        "Nothing matched \"$searchQuery\"."
                    } else {
                        "Discuss shared expenses, bills, or household budgets together."
                    },
                    fontSize = 12.sp,
                    color = ZenithTheme.textSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FamilyChatFeedList(
    listState: LazyListState,
    groups: List<DayFeedGroup>,
    messagesById: Map<String, FamilyMessageEntity>,
    memberLabelsByUserId: Map<String, String>,
    txByServerId: Map<String, TransactionEntity>,
    txAttributionByServerId: Map<String, FamilyTxAttribution>,
    currentUserId: String,
    currentUserRole: FamilyRole?,
    currencySymbol: String,
    palette: ChatPalette,
    selectedContextMessageId: String?,
    highlightedMessageId: String?,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 96.dp,
    onSelectContextMessage: (FamilyMessageEntity) -> Unit,
    onQuoteClick: (String) -> Unit,
    onReply: (FamilyMessageEntity) -> Unit,
    onCopy: (FamilyMessageEntity) -> Unit,
    onViewSharedTransaction: ((TransactionEntity) -> Unit)? = null,
    onDelete: (FamilyMessageEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 10.dp,
            end = 10.dp,
            top = 10.dp,
            bottom = bottomContentPadding
        )
    ) {
        groups.forEach { group ->
            item(key = "day_${group.dayLabel}") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = palette.datePillBg.copy(alpha = 0.94f),
                        border = BorderStroke(1.dp, palette.incomingBubbleBorder.copy(alpha = 0.8f)),
                        shadowElevation = 1.dp
                    ) {
                        Text(
                            text = group.dayLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.bubbleTextSecondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            items(group.rows, key = { it.message.id }) { row ->
                val msg = row.message
                val isSelected = selectedContextMessageId == msg.id
                val isHighlighted = highlightedMessageId == msg.id
                val rowTint by animateColorAsState(
                    targetValue = when {
                        isSelected -> ZenithTheme.accentCyan.copy(alpha = 0.16f)
                        isHighlighted -> ZenithTheme.accentAmber.copy(alpha = 0.22f)
                        else -> Color.Transparent
                    },
                    animationSpec = tween(250),
                    label = "chat_row_tint"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .clip(RoundedCornerShape(10.dp))
                        .background(rowTint)
                        .padding(top = row.topSpacingDp.dp)
                ) {
                    if (msg.messageType == "SYSTEM") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = palette.datePillBg.copy(alpha = 0.62f)
                            ) {
                                Text(
                                    text = msg.body,
                                    fontSize = 11.5.sp,
                                    color = palette.bubbleTextSecondary,
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

                        SwipeableChatBubbleRow(
                            message = msg,
                            isMe = isMe,
                            canDelete = canDelete,
                            showSenderAndAvatar = !isMe && row.isFirstInGroup,
                            isFirstInGroup = row.isFirstInGroup,
                            senderLabel = memberLabelsByUserId[msg.senderUserId].orEmpty(),
                            quotedMessage = quotedMsg,
                            sharedTx = sharedTx,
                            sharedAttr = sharedAttr,
                            currencySymbol = currencySymbol,
                            palette = palette,
                            onQuoteClick = {
                                msg.replyToMessageId?.let(onQuoteClick)
                            },
                            onReply = { onReply(msg) },
                            onCopy = { onCopy(msg) },
                            onViewSharedTransaction = onViewSharedTransaction,
                            onLongPress = { onSelectContextMessage(msg) },
                            onDelete = { onDelete(msg) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JumpToBottomButton(
    unreadCount: Int,
    palette: ChatPalette,
    onClick: () -> Unit
) {
    Box(contentAlignment = Alignment.TopEnd) {
        Surface(
            shape = CircleShape,
            color = palette.headerBg,
            border = BorderStroke(1.dp, palette.headerBorder),
            shadowElevation = 6.dp,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .testTag("family_chat_jump_bottom")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Jump to latest",
                    tint = ZenithTheme.textPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        if (unreadCount > 0) {
            Surface(
                shape = CircleShape,
                color = ZenithTheme.solidGreen,
                modifier = Modifier.offset(x = 2.dp, y = (-4).dp)
            ) {
                Text(
                    text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun FamilyComposerDock(
    familyName: String,
    composerText: String,
    onComposerTextChange: (String) -> Unit,
    replyTarget: FamilyMessageEntity?,
    onCancelReply: () -> Unit,
    attachedTx: TransactionEntity?,
    onRemoveAttachedTx: () -> Unit,
    showExpensePicker: Boolean,
    onToggleExpensePicker: () -> Unit,
    showEmojiStrip: Boolean,
    onToggleEmojiStrip: () -> Unit,
    onInsertEmoji: (String) -> Unit,
    familyTransactions: List<TransactionEntity>,
    currencySymbol: String,
    onSelectTransaction: (TransactionEntity) -> Unit,
    onSend: () -> Unit,
    palette: ChatPalette
) {
    val canSend = composerText.trim().isNotEmpty() || attachedTx != null
    val sendBtnBg by animateColorAsState(
        targetValue = if (canSend) ZenithTheme.solidIndigo else palette.inputPillBg,
        animationSpec = tween(ZenithTheme.motionSpec.durationMs(180)),
        label = "chat_send_bg"
    )
    val sendBtnScale by animateFloatAsState(
        targetValue = if (canSend || ZenithTheme.motionSpec.isReduced) 1.0f else 0.93f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "chat_send_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        palette.canvasBg.copy(alpha = 0.84f),
                        palette.canvasBg.copy(alpha = 0.96f)
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Quick Reply Preview Banner
        AnimatedVisibility(
            visible = replyTarget != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            replyTarget?.let { reply ->
                Surface(
                    color = palette.inputPillBg,
                    border = BorderStroke(1.dp, ZenithTheme.accentCyan.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.5.dp)
                                .fillMaxHeight()
                                .background(ZenithTheme.accentCyan)
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = "Replying to ${reply.senderName}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.accentCyan
                            )
                            Text(
                                text = reply.body,
                                fontSize = 11.5.sp,
                                color = ZenithTheme.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = onCancelReply,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel Reply",
                                tint = ZenithTheme.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Attached Transaction Preview Banner
        AnimatedVisibility(
            visible = attachedTx != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            attachedTx?.let { tx ->
                val isExp = tx.type == TransactionType.EXPENSE
                val bannerColor = if (isExp) ZenithTheme.accentRed else ZenithTheme.accentGreen
                Surface(
                    color = palette.inputPillBg,
                    border = BorderStroke(1.dp, bannerColor.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.5.dp)
                                .fillMaxHeight()
                                .background(bannerColor)
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = "${if (isExp) "Sharing Expense" else "Sharing Income"}: ${tx.title}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = bannerColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${if (isExp) "-" else "+"}$currencySymbol${String.format(Locale.US, "%,.0f", tx.amount)} • ${tx.category}",
                                fontSize = 11.sp,
                                color = ZenithTheme.textSecondary
                            )
                        }
                        IconButton(
                            onClick = onRemoveAttachedTx,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove Expense",
                                tint = ZenithTheme.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Lightweight Quick Emoji Strip (Essential only — no GIFs or Stickers)
        AnimatedVisibility(
            visible = showEmojiStrip,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = palette.inputPillBg,
                border = BorderStroke(1.dp, palette.inputPillBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QUICK_CHAT_EMOJIS.forEach { emoji ->
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .clickable { onInsertEmoji(emoji) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(emoji, fontSize = 20.sp)
                        }
                    }
                }
            }
        }

        // Quick Share Family Transaction Picker
        AnimatedVisibility(
            visible = showExpensePicker,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = palette.inputPillBg,
                border = BorderStroke(1.dp, palette.inputPillBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Share a Family Transaction",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZenithTheme.textSecondary
                        )
                        IconButton(
                            onClick = onToggleExpensePicker,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close Picker",
                                tint = ZenithTheme.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val pickable = familyTransactions.filter { !it.serverId.isNullOrBlank() }.take(12)
                        if (pickable.isEmpty()) {
                            item {
                                Text(
                                    text = "No family transactions to attach yet.",
                                    fontSize = 11.5.sp,
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
                                    onClick = { onSelectTransaction(tx) },
                                    label = {
                                        Text(
                                            "${tx.title} ($sign$currencySymbol${String.format(Locale.US, "%,.0f", tx.amount)})",
                                            fontSize = 11.5.sp,
                                            maxLines = 1
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Main Floating Frosted Capsule Input Pill + 48dp Circular Send FAB
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("family_chat_floating_composer"),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = palette.inputPillBg.copy(alpha = if (ZenithTheme.isOled) 0.98f else 0.95f),
                border = BorderStroke(1.dp, palette.inputPillBorder),
                shadowElevation = if (ZenithTheme.isOled) 0.dp else 8.dp,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    IconButton(
                        onClick = onToggleEmojiStrip,
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("family_chat_emoji_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mood,
                            contentDescription = "Quick Emojis",
                            tint = if (showEmojiStrip) ZenithTheme.accentCyan else ZenithTheme.textSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    BasicTextField(
                        value = composerText,
                        onValueChange = onComposerTextChange,
                        maxLines = 5,
                        textStyle = TextStyle(
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            color = ZenithTheme.textPrimary
                        ),
                        cursorBrush = SolidColor(ZenithTheme.accentGreen),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 42.dp)
                            .testTag("family_chat_input"),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 10.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (composerText.isEmpty()) {
                                    Text(
                                        text = "Message $familyName",
                                        fontSize = 15.sp,
                                        lineHeight = 20.sp,
                                        color = ZenithTheme.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    IconButton(
                        onClick = onToggleExpensePicker,
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("family_chat_attach_expense")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Receipt,
                            contentDescription = "Share Transaction",
                            tint = if (showExpensePicker || attachedTx != null) ZenithTheme.accentCyan else ZenithTheme.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Surface(
                shape = CircleShape,
                color = sendBtnBg,
                border = if (canSend) null else BorderStroke(1.dp, palette.inputPillBorder),
                shadowElevation = if (canSend && !ZenithTheme.isOled) 6.dp else 0.dp,
                modifier = Modifier
                    .size(48.dp)
                    .scale(sendBtnScale)
                    .clip(CircleShape)
                    .clickable(enabled = canSend, onClick = onSend)
                    .testTag("family_chat_send_btn")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) Color.White else ZenithTheme.textMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeableChatBubbleRow(
    message: FamilyMessageEntity,
    isMe: Boolean,
    canDelete: Boolean,
    showSenderAndAvatar: Boolean,
    isFirstInGroup: Boolean,
    senderLabel: String,
    quotedMessage: FamilyMessageEntity?,
    sharedTx: TransactionEntity?,
    sharedAttr: FamilyTxAttribution?,
    currencySymbol: String,
    palette: ChatPalette,
    onQuoteClick: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onViewSharedTransaction: ((TransactionEntity) -> Unit)? = null,
    onLongPress: () -> Unit,
    onDelete: () -> Unit
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val maxSwipePx = with(density) { 56.dp.toPx() }
    val triggerPx = with(density) { 36.dp.toPx() }

    var dragOffsetPx by remember(message.id) { mutableFloatStateOf(0f) }
    var isDragging by remember(message.id) { mutableStateOf(false) }
    var hapticFired by remember(message.id) { mutableStateOf(false) }
    val animatedOffsetPx by animateFloatAsState(
        targetValue = dragOffsetPx,
        animationSpec = if (isDragging || ZenithTheme.motionSpec.isReduced) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        },
        label = "chat_swipe_offset"
    )

    val dragState = rememberDraggableState { delta ->
        isDragging = true
        val next = (dragOffsetPx + delta * 0.65f).coerceIn(0f, maxSwipePx)
        dragOffsetPx = next
        if (next >= triggerPx && !hapticFired) {
            hapticFired = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } else if (next < triggerPx) {
            hapticFired = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStopped = {
                    val triggered = dragOffsetPx >= triggerPx
                    isDragging = false
                    dragOffsetPx = 0f
                    hapticFired = false
                    if (triggered) {
                        onReply()
                    }
                }
            )
    ) {
        val swipeFraction = (animatedOffsetPx / triggerPx).coerceIn(0f, 1f)
        if (swipeFraction > 0.08f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(palette.headerBg.copy(alpha = 0.85f * swipeFraction)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = ZenithTheme.accentCyan.copy(alpha = swipeFraction),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedOffsetPx.roundToInt(), 0) }
        ) {
            FamilyChatBubble(
                message = message,
                isMe = isMe,
                canDelete = canDelete,
                showSenderAndAvatar = showSenderAndAvatar,
                isFirstInGroup = isFirstInGroup,
                senderLabel = senderLabel,
                quotedMessage = quotedMsgOrNull(quotedMessage),
                sharedTx = sharedTx,
                sharedAttr = sharedAttr,
                currencySymbol = currencySymbol,
                palette = palette,
                onQuoteClick = onQuoteClick,
                onReply = onReply,
                onCopy = onCopy,
                onViewSharedTransaction = onViewSharedTransaction,
                onLongPress = onLongPress,
                onDelete = onDelete
            )
        }
    }
}

private fun quotedMsgOrNull(msg: FamilyMessageEntity?): FamilyMessageEntity? = msg

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FamilyChatBubble(
    message: FamilyMessageEntity,
    isMe: Boolean,
    canDelete: Boolean,
    showSenderAndAvatar: Boolean,
    isFirstInGroup: Boolean,
    senderLabel: String,
    quotedMessage: FamilyMessageEntity?,
    sharedTx: TransactionEntity?,
    sharedAttr: FamilyTxAttribution?,
    currencySymbol: String,
    palette: ChatPalette,
    onQuoteClick: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onViewSharedTransaction: ((TransactionEntity) -> Unit)? = null,
    onLongPress: () -> Unit,
    onDelete: () -> Unit
) {
    val timeLabel = remember(message.createdAtMillis) {
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(message.createdAtMillis))
    }
    var showInlineActions by remember(message.id) { mutableStateOf(false) }

    val bubbleShape = remember(isMe, isFirstInGroup) {
        when {
            isMe && isFirstInGroup -> RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
            !isMe && isFirstInGroup -> RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
            else -> RoundedCornerShape(15.dp)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = (maxWidth * 0.78f).coerceAtMost(320.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            if (!isMe) {
                if (showSenderAndAvatar) {
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp, top = 2.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(palette.headerBg)
                            .border(0.75.dp, palette.incomingBubbleBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(message.senderAvatar.ifBlank { "🙂" }, fontSize = 15.sp)
                    }
                } else {
                    Spacer(modifier = Modifier.width(34.dp))
                }
            }

            Column(
                horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
                modifier = Modifier.widthIn(min = 68.dp, max = maxBubbleWidth)
            ) {
                Surface(
                    shape = bubbleShape,
                    color = if (isMe) palette.outgoingBubbleBg else palette.incomingBubbleBg,
                    border = BorderStroke(
                        0.75.dp,
                        if (isMe) palette.outgoingBubbleBorder else palette.incomingBubbleBorder
                    ),
                    shadowElevation = if (ZenithTheme.isOled) 0.dp else 1.dp,
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { showInlineActions = !showInlineActions },
                            onLongClick = {
                                showInlineActions = false
                                onLongPress()
                            }
                        )
                        .testTag("chat_bubble_${message.id}")
                ) {
                    Column(
                        modifier = Modifier
                            .animateContentSize(
                                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)
                            )
                            .padding(start = 11.dp, end = 10.dp, top = 7.dp, bottom = 6.dp)
                    ) {
                        if (showSenderAndAvatar) {
                            Text(
                                text = if (senderLabel.isNotBlank()) "${message.senderName} • $senderLabel" else message.senderName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = senderAccentColor(message.senderUserId),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }

                        quotedMessage?.let { q ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = palette.canvasBg.copy(alpha = if (ZenithTheme.isDark) 0.45f else 0.72f),
                                modifier = Modifier
                                    .padding(bottom = 5.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClick = onQuoteClick)
                            ) {
                                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                                    Box(
                                        modifier = Modifier
                                            .width(3.5.dp)
                                            .fillMaxHeight()
                                            .background(senderAccentColor(q.senderUserId))
                                    )
                                    Column(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = q.senderName,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = senderAccentColor(q.senderUserId),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = q.body,
                                            fontSize = 11.5.sp,
                                            color = palette.bubbleTextSecondary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        sharedTx?.let { tx ->
                            val isExp = tx.type == TransactionType.EXPENSE
                            val amtColor = if (isExp) ZenithTheme.accentRed else ZenithTheme.accentGreen
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = palette.canvasBg.copy(alpha = if (ZenithTheme.isDark) 0.48f else 0.78f),
                                border = BorderStroke(0.75.dp, amtColor.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 5.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .then(
                                        if (onViewSharedTransaction != null) {
                                            Modifier.clickable { onViewSharedTransaction(tx) }
                                        } else {
                                            Modifier
                                        }
                                    )
                            ) {
                                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                                    Box(
                                        modifier = Modifier
                                            .width(3.5.dp)
                                            .fillMaxHeight()
                                            .background(amtColor)
                                    )
                                    Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = tx.title,
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = palette.bubbleTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "${if (isExp) "-" else "+"}$currencySymbol${AmountFormatter.formatNumber(tx.amount, 0, ZenithTheme.numberFormat)}",
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = amtColor
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(1.dp))
                                        Text(
                                            text = "${tx.category} • ${if (isExp) "Paid by" else "Received by"} ${sharedAttr?.paidByName ?: tx.paymentMethod}",
                                            fontSize = 10.5.sp,
                                            color = palette.bubbleTextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        MessageBodyWithInlineMeta(
                            body = message.body,
                            timeLabel = timeLabel,
                            isMe = isMe,
                            isSynced = message.syncStatus == "SYNCED",
                            maxContentWidth = maxBubbleWidth - 22.dp,
                            palette = palette
                        )

                        AnimatedVisibility(
                            visible = showInlineActions,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column {
                                HorizontalDivider(
                                    color = palette.bubbleTextSecondary.copy(alpha = 0.22f),
                                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                showInlineActions = false
                                                onReply()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                            .testTag("chat_reply_${message.id}"),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Reply,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = ZenithTheme.accentCyan
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "Reply",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = ZenithTheme.accentCyan
                                        )
                                    }
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                showInlineActions = false
                                                onCopy()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                            .testTag("chat_copy_${message.id}"),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                            tint = palette.bubbleTextSecondary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "Copy",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = palette.bubbleTextSecondary
                                        )
                                    }
                                    if (canDelete) {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    showInlineActions = false
                                                    onDelete()
                                                }
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                                .testTag("chat_delete_${message.id}"),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = ZenithTheme.accentRed
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                "Delete",
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = ZenithTheme.accentRed
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun MessageBodyWithInlineMeta(
    body: String,
    timeLabel: String,
    isMe: Boolean,
    isSynced: Boolean,
    maxContentWidth: androidx.compose.ui.unit.Dp,
    palette: ChatPalette
) {
    val density = LocalDensity.current
    val uriHandler = LocalUriHandler.current
    val textMeasurer = rememberTextMeasurer()

    val bodyStyle = TextStyle(
        fontSize = 14.5.sp,
        lineHeight = 19.sp,
        color = palette.bubbleTextPrimary
    )
    val annotatedBody = remember(body, palette.linkColor) {
        buildLinkAnnotatedText(body, palette.linkColor)
    }

    val maxWidthPx = with(density) { maxContentWidth.roundToPx().coerceAtLeast(100) }
    val metaReservePx = with(density) { (if (isMe) 76.dp else 56.dp).roundToPx() }
    val textLayout = remember(annotatedBody, maxWidthPx, palette.bubbleTextPrimary) {
        textMeasurer.measure(
            text = annotatedBody,
            style = bodyStyle,
            constraints = Constraints(maxWidth = maxWidthPx)
        )
    }
    val lineCount = textLayout.lineCount.coerceAtLeast(1)
    val lastLineRight = textLayout.getLineRight(lineCount - 1)
    val fitsInlineOnLastLine = (lastLineRight + metaReservePx) <= maxWidthPx

    val hasUrlAnnotation = remember(annotatedBody) {
        annotatedBody.getStringAnnotations(tag = "URL", start = 0, end = annotatedBody.length).isNotEmpty()
    }

    val renderText: @Composable () -> Unit = {
        if (hasUrlAnnotation) {
            ClickableText(
                text = annotatedBody,
                style = bodyStyle,
                onClick = { offset ->
                    annotatedBody.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()?.let { ann ->
                            val url = if (ann.item.startsWith("http", ignoreCase = true)) ann.item else "https://${ann.item}"
                            runCatching { uriHandler.openUri(url) }
                        }
                }
            )
        } else {
            Text(
                text = annotatedBody,
                style = bodyStyle
            )
        }
    }

    if (lineCount == 1 && fitsInlineOnLastLine) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            renderText()
            MessageTimestampAndTicks(timeLabel = timeLabel, isMe = isMe, isSynced = isSynced, palette = palette)
        }
    } else if (fitsInlineOnLastLine) {
        val neededMinDp = with(density) {
            maxOf(textLayout.size.width.toFloat(), lastLineRight + metaReservePx).toDp()
        }
        Box(modifier = Modifier.widthIn(min = neededMinDp)) {
            renderText()
            Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                MessageTimestampAndTicks(timeLabel = timeLabel, isMe = isMe, isSynced = isSynced, palette = palette)
            }
        }
    } else {
        Column {
            renderText()
            Spacer(modifier = Modifier.height(2.dp))
            Box(modifier = Modifier.align(Alignment.End)) {
                MessageTimestampAndTicks(timeLabel = timeLabel, isMe = isMe, isSynced = isSynced, palette = palette)
            }
        }
    }
}

@Composable
private fun MessageTimestampAndTicks(
    timeLabel: String,
    isMe: Boolean,
    isSynced: Boolean,
    palette: ChatPalette
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = timeLabel,
            fontSize = 10.sp,
            color = palette.timestampMuted
        )
        if (isMe) {
            Icon(
                imageVector = if (isSynced) Icons.Default.DoneAll else Icons.Default.Schedule,
                contentDescription = if (isSynced) "Synced" else "Pending Sync",
                tint = if (isSynced) palette.syncedTickTint else ZenithTheme.accentAmber,
                modifier = Modifier.size(13.5.dp)
            )
        }
    }
}

private fun buildLinkAnnotatedText(raw: String, linkColor: Color): AnnotatedString {
    return buildAnnotatedString {
        append(raw)
        val matcher = Patterns.WEB_URL.matcher(raw)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val url = raw.substring(start, end)
            addStyle(
                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                start = start,
                end = end
            )
            addStringAnnotation(tag = "URL", annotation = url, start = start, end = end)
        }
    }
}

private fun senderAccentColor(userId: String): Color {
    val palette = listOf(
        Color(0xFF06B6D4),
        Color(0xFF10B981),
        Color(0xFFF59E0B),
        Color(0xFFEC4899),
        Color(0xFF8B5CF6),
        Color(0xFF3B82F6)
    )
    return palette[abs(userId.hashCode()) % palette.size]
}

private fun buildChatFeedProjection(messages: List<FamilyMessageEntity>): ChatFeedProjection {
    if (messages.isEmpty()) {
        return ChatFeedProjection(emptyList(), emptyMap(), 0)
    }
    val now = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val sameYearFmt = SimpleDateFormat("dd MMM", Locale.getDefault())
    val fullFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    val groupedByDay = messages.groupBy { msg ->
        val cal = Calendar.getInstance().apply { timeInMillis = msg.createdAtMillis }
        when {
            now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR) -> "Today"
            yesterday.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                yesterday.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR) -> "Yesterday"
            now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) -> sameYearFmt.format(Date(msg.createdAtMillis))
            else -> fullFmt.format(Date(msg.createdAtMillis))
        }
    }

    val dayGroups = ArrayList<DayFeedGroup>(groupedByDay.size)
    val indexById = HashMap<String, Int>(messages.size)
    var runningIndex = 0

    groupedByDay.forEach { (dayLabel, dayMsgs) ->
        runningIndex++ // stickyHeader item
        val rowItems = ArrayList<ChatRowItem>(dayMsgs.size)
        var prevSenderId: String? = null
        dayMsgs.forEachIndexed { idx, msg ->
            val isSystem = msg.messageType == "SYSTEM"
            val isFirst = isSystem || msg.senderUserId != prevSenderId
            val topGap = when {
                idx == 0 -> 2
                isSystem || prevSenderId == null -> 8
                isFirst -> 10
                else -> 3
            }
            rowItems.add(
                ChatRowItem(
                    message = msg,
                    isFirstInGroup = isFirst,
                    topSpacingDp = topGap
                )
            )
            indexById[msg.id] = runningIndex++
            prevSenderId = if (isSystem) null else msg.senderUserId
        }
        dayGroups.add(DayFeedGroup(dayLabel = dayLabel, rows = rowItems))
    }

    return ChatFeedProjection(
        groups = dayGroups,
        messageIndexById = indexById,
        totalItemCount = runningIndex
    )
}
