package com.zenith.app.ui.familyledger

import android.app.DatePickerDialog
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.app.data.familyledger.FamilyVaultMember
import com.zenith.app.data.familyledger.LedgerTransaction
import com.zenith.app.data.models.TransactionType
import com.zenith.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditLedgerTransactionSheet(
    existing: LedgerTransaction? = null,
    initialType: TransactionType = TransactionType.EXPENSE,
    members: List<FamilyVaultMember>,
    currentUserId: String,
    currentUserName: String,
    currencySymbol: String = "₹",
    isSaving: Boolean = false,
    saveResult: String? = null,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        description: String,
        amount: Double,
        category: String,
        type: TransactionType,
        paymentMethod: String,
        paidByMemberId: String,
        paidByName: String,
        dateMillis: Long,
        receiptUrl: String?
    ) -> Unit
) {
    val isEdit = existing != null
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedType by remember { mutableStateOf(existing?.type ?: initialType) }
    var amountDigits by remember {
        mutableStateOf(if (existing != null) "%.2f".format(existing.amount).removeSuffix(".00") else "")
    }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var selectedCategory by remember { mutableStateOf(existing?.category ?: "Food & Dining") }
    var selectedPaymentMethod by remember { mutableStateOf(existing?.paymentMethod ?: "UPI") }
    var selectedDateMillis by remember { mutableStateOf(existing?.dateMillis ?: System.currentTimeMillis()) }

    val defaultMember = remember(members, existing) {
        if (existing != null) {
            members.find { it.memberId == existing.paidByMemberId || it.userId == existing.paidByMemberId }
                ?: members.firstOrNull()
        } else {
            members.find { it.userId == currentUserId } ?: members.firstOrNull()
        }
    }
    var selectedMember by remember { mutableStateOf<FamilyVaultMember?>(defaultMember) }
    var titleError by remember { mutableStateOf<String?>(null) }
    var amountError by remember { mutableStateOf<String?>(null) }

    val categories = remember {
        listOf(
            "Food & Dining" to "Restaurant",
            "Groceries" to "LocalGroceryStore",
            "Shopping" to "ShoppingBag",
            "Housing & Rent" to "Home",
            "Transportation" to "DirectionsCar",
            "Bills & Utilities" to "Receipt",
            "Entertainment" to "Movie",
            "Healthcare" to "MedicalServices",
            "Salary & Income" to "Payments",
            "Freelance / Business" to "Work",
            "Investments" to "TrendingUp",
            "General" to "Category"
        )
    }

    val paymentMethods = listOf("UPI", "Cash", "Card", "Net Banking")
    val quickAddAmounts = listOf(100, 500, 1000, 2000, 5000)

    val memberGradients = remember {
        listOf(
            Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))),
            Brush.linearGradient(listOf(Color(0xFF06B6D4), Color(0xFF3B82F6))),
            Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFEF4444))),
            Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF059669))),
            Brush.linearGradient(listOf(Color(0xFFEC4899), Color(0xFF8B5CF6)))
        )
    }

    val formattedDate = remember(selectedDateMillis) {
        SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).format(Date(selectedDateMillis))
    }

    fun handleNumpadPress(key: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        amountError = null
        when (key) {
            "⌫" -> {
                if (amountDigits.isNotEmpty()) {
                    amountDigits = amountDigits.dropLast(1)
                }
            }
            "." -> {
                if (!amountDigits.contains(".")) {
                    amountDigits = if (amountDigits.isEmpty()) "0." else "$amountDigits."
                }
            }
            "00" -> {
                if (amountDigits.isNotEmpty() && !amountDigits.contains(".")) {
                    amountDigits += "00"
                }
            }
            else -> {
                if (amountDigits == "0") {
                    amountDigits = key
                } else if (!amountDigits.contains(".") || amountDigits.substringAfter(".").length < 2) {
                    amountDigits += key
                }
            }
        }
    }

    fun validateAndSubmit() {
        val cleanTitle = title.trim()
        val parsedAmount = amountDigits.toDoubleOrNull()

        var hasError = false
        if (cleanTitle.isBlank()) {
            titleError = "Please enter a title"
            hasError = true
        } else {
            titleError = null
        }

        if (parsedAmount == null || parsedAmount <= 0.0) {
            amountError = "Enter an amount > 0"
            hasError = true
        } else {
            amountError = null
        }

        if (hasError || parsedAmount == null) return

        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val payerId = selectedMember?.memberId ?: selectedMember?.userId ?: currentUserId
        val payerName = selectedMember?.name ?: currentUserName
        onSave(
            cleanTitle,
            description.trim(),
            parsedAmount,
            selectedCategory,
            selectedType,
            selectedPaymentMethod,
            payerId,
            payerName,
            selectedDateMillis,
            null
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0B0E14),
        scrimColor = Color.Black.copy(alpha = 0.7f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f))
            )
        },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header Row: Type Toggle (Expense vs Income)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isEdit) "Edit Entry" else "New Entry",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                // iOS Segmented Pill Switcher
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF161B26),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier.height(36.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (selectedType == TransactionType.EXPENSE) Color(0xFFEF4444).copy(alpha = 0.25f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedType = TransactionType.EXPENSE
                                }
                                .padding(horizontal = 14.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "Expense",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedType == TransactionType.EXPENSE) Color(0xFFF87171) else Color(0xFF94A3B8)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (selectedType == TransactionType.INCOME) Color(0xFF10B981).copy(alpha = 0.25f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedType = TransactionType.INCOME
                                }
                                .padding(horizontal = 14.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "Income",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedType == TransactionType.INCOME) Color(0xFF34D399) else Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Large Digital Display
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF121722))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(24.dp))
                    .padding(vertical = 18.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (selectedType == TransactionType.EXPENSE) "TOTAL EXPENSE" else "INCOME DEPOSIT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64748B),
                    letterSpacing = 1.2.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = currencySymbol,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedType == TransactionType.EXPENSE) Color(0xFFF87171) else Color(0xFF34D399)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (amountDigits.isEmpty()) "0" else amountDigits,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = (-0.5).sp
                    )
                }

                if (amountError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = amountError!!,
                        color = Color(0xFFEF4444),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quick Add Increment Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(quickAddAmounts) { delta ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF161B26),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier.clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val current = amountDigits.toDoubleOrNull() ?: 0.0
                            val next = current + delta
                            amountDigits = if (next % 1.0 == 0.0) next.toLong().toString() else "%.2f".format(next)
                        }
                    ) {
                        Text(
                            text = "+$currencySymbol$delta",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // iOS Touch Numpad (3x4)
            val numpadRows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf(".", "0", "⌫")
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                numpadRows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { key ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (key == "⌫") Color(0xFF1E2430) else Color(0xFF131823),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { handleNumpadPress(key) }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (key == "⌫") {
                                        Icon(
                                            imageVector = Icons.Default.Backspace,
                                            contentDescription = "Backspace",
                                            tint = Color(0xFF94A3B8),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        Text(
                                            text = key,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Paid By Member Selector (Carousel)
            Text(
                text = "PAID BY MEMBER",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF64748B),
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(members.size) { idx ->
                    val mem = members[idx]
                    val isSelected = selectedMember?.memberId == mem.memberId ||
                            selectedMember?.userId == mem.userId
                    val gradient = memberGradients[idx % memberGradients.size]

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) Color(0xFF1E293B) else Color(0xFF121722),
                        border = BorderStroke(
                            if (isSelected) 1.5.dp else 1.dp,
                            if (isSelected) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.06f)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedMember = mem
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(gradient),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = mem.name.take(1).uppercase(),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = mem.name,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Details: Title & Notes
            Text(
                text = "DESCRIPTION",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF64748B),
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it; titleError = null },
                placeholder = { Text("e.g. Dinner, Weekly Groceries, Electricity", color = Color(0xFF64748B), fontSize = 14.sp) },
                singleLine = true,
                isError = titleError != null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF38BDF8),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                    focusedContainerColor = Color(0xFF121722),
                    unfocusedContainerColor = Color(0xFF121722),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_ledger_title")
            )
            if (titleError != null) {
                Text(
                    text = titleError!!,
                    color = Color(0xFFEF4444),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Category Squircles
            Text(
                text = "CATEGORY",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF64748B),
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(categories) { (catName, _) ->
                    val isSelected = selectedCategory == catName
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) Color(0xFF1E293B) else Color(0xFF121722),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) Color(0xFF818CF8) else Color.White.copy(alpha = 0.06f)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedCategory = catName
                            }
                    ) {
                        Text(
                            text = catName,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color(0xFF818CF8) else Color(0xFF94A3B8),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Payment Method & Date Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Payment Method
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "METHOD",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B),
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF121722),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val curIdx = paymentMethods.indexOf(selectedPaymentMethod)
                                val nextIdx = (curIdx + 1) % paymentMethods.size
                                selectedPaymentMethod = paymentMethods[nextIdx]
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = selectedPaymentMethod,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Icon(
                                imageVector = Icons.Default.SwapHoriz,
                                contentDescription = null,
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Date Picker
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DATE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B),
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF121722),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val cal = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
                                DatePickerDialog(
                                    context,
                                    { _, year, month, dayOfMonth ->
                                        val newCal = Calendar.getInstance().apply {
                                            set(Calendar.YEAR, year)
                                            set(Calendar.MONTH, month)
                                            set(Calendar.DAY_OF_MONTH, dayOfMonth)
                                        }
                                        selectedDateMillis = newCal.timeInMillis
                                    },
                                    cal.get(Calendar.YEAR),
                                    cal.get(Calendar.MONTH),
                                    cal.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(selectedDateMillis)),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Icon(
                                imageVector = Icons.Default.CalendarToday,
                                contentDescription = null,
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Primary Glowing Action Button
            Button(
                onClick = { validateAndSubmit() },
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("btn_save_ledger_transaction"),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedType == TransactionType.EXPENSE) Color(0xFFEF4444) else Color(0xFF10B981)
                )
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Syncing with Vault…", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isEdit) Icons.Default.Check else Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isEdit) "Update Entry" else "Record ${selectedType.name.lowercase().replaceFirstChar { it.uppercase() }}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
