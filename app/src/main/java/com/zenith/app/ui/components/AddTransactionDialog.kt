package com.zenith.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zenith.app.data.models.CategoryEntity
import com.zenith.app.data.models.TransactionType
import com.zenith.app.domain.FamilyAvatarCatalog
import com.zenith.app.domain.FamilyMemberInfo
import com.zenith.app.ui.theme.ZenithTheme
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionDialog(
    categories: List<CategoryEntity>,
    currencySymbol: String,
    onDismiss: () -> Unit,
    onAdd: (
        title: String,
        amount: Double,
        type: TransactionType,
        category: String,
        dateMillis: Long,
        note: String,
        paymentMethod: String
    ) -> Unit,
    familyMembers: List<FamilyMemberInfo> = emptyList(),
    currentUserId: String = "local_user_1",
    isFamilyMode: Boolean = false,
    onAddWithPaidBy: ((
        title: String,
        amount: Double,
        type: TransactionType,
        category: String,
        dateMillis: Long,
        note: String,
        paymentMethod: String,
        paidByUserId: String?
    ) -> Unit)? = null,
    onAddWithLabel: ((
        title: String,
        amount: Double,
        type: TransactionType,
        category: String,
        dateMillis: Long,
        note: String,
        paymentMethod: String,
        paidByUserId: String?,
        customLabel: String
    ) -> Unit)? = null,
    onAddCustomCategory: ((name: String, colorHex: String) -> Unit)? = null
) {
    var title by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var customLabelText by remember { mutableStateOf("") }
    var newCustomCategoryText by remember { mutableStateOf("") }

    var selectedType by remember { mutableStateOf(TransactionType.EXPENSE) }
    var selectedCategory by remember { mutableStateOf("Food & Dining") }
    var selectedPaymentMethod by remember { mutableStateOf("UPI") }
    var selectedPaidByUserId by remember(currentUserId) {
        mutableStateOf(
            familyMembers.firstOrNull { it.userId == currentUserId }?.userId
                ?: familyMembers.firstOrNull()?.userId
                ?: currentUserId
        )
    }
    LaunchedEffect(familyMembers, currentUserId) {
        if (familyMembers.isNotEmpty() && familyMembers.none { it.userId == selectedPaidByUserId }) {
            selectedPaidByUserId = familyMembers.firstOrNull { it.userId == currentUserId }?.userId
                ?: familyMembers.first().userId
        }
    }
    var showCategoryPickerSheet by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    val todayFormatted = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date()) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val fullCategoryList = remember(categories) {
        val defaults = listOf(
            "Food & Dining" to Icons.Default.Restaurant,
            "Transportation" to Icons.Default.DirectionsCar,
            "Shopping" to Icons.Default.ShoppingBag,
            "Bills & Utilities" to Icons.Default.Receipt,
            "Entertainment" to Icons.Default.Movie,
            "Healthcare" to Icons.Default.MedicalServices,
            "Housing & Rent" to Icons.Default.Home,
            "Education" to Icons.Default.School,
            "Subscriptions" to Icons.Default.Subscriptions,
            "Personal Care" to Icons.Default.Spa,
            "Travel" to Icons.Default.Flight,
            "Salary & Income" to Icons.Default.Payments,
            "Freelance / Business" to Icons.Default.Work,
            "Other" to Icons.Default.Category
        )
        val extraCats = categories
            .filter { c -> defaults.none { it.first.equals(c.name, ignoreCase = true) } }
            .map { it.name to Icons.Default.Category }
        defaults + extraCats
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ZenithTheme.cardSurface,
            border = BorderStroke(1.dp, ZenithTheme.borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .imePadding()
                .testTag("add_transaction_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Add Transaction",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZenithTheme.textPrimary
                        )
                        Text(
                            text = if (isFamilyMode) "$todayFormatted • Family Ledger" else todayFormatted,
                            fontSize = 12.sp,
                            color = if (isFamilyMode) ZenithTheme.accentCyan else ZenithTheme.textSecondary
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("close_add_dialog")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = ZenithTheme.textSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Expense / Income Segmented Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ZenithTheme.cardVariantSurface, RoundedCornerShape(16.dp))
                        .padding(4.dp)
                ) {
                    val isExpense = selectedType == TransactionType.EXPENSE
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isExpense) ZenithTheme.accentRed else Color.Transparent)
                            .clickable {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                selectedType = TransactionType.EXPENSE
                            }
                            .testTag("type_expense_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Expense",
                            fontWeight = FontWeight.Bold,
                            color = if (isExpense) Color.White else ZenithTheme.textSecondary,
                            fontSize = 13.sp
                        )
                    }

                    val isIncome = selectedType == TransactionType.INCOME
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isIncome) ZenithTheme.accentGreen else Color.Transparent)
                            .clickable {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                selectedType = TransactionType.INCOME
                                if (selectedCategory == "Food & Dining") selectedCategory = "Salary & Income"
                            }
                            .testTag("type_income_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Income",
                            fontWeight = FontWeight.Bold,
                            color = if (isIncome) Color.White else ZenithTheme.textSecondary,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Amount Field
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { input ->
                        val normalized = input.replace(',', '.')
                        val clean = normalized.filter { it.isDigit() || it == '.' }
                        if (clean.count { it == '.' } <= 1) {
                            amountText = clean
                        }
                    },
                    label = { Text("Amount ($currencySymbol)") },
                    placeholder = { Text("0.00", color = ZenithTheme.textMuted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (selectedType == TransactionType.EXPENSE) ZenithTheme.accentRed else ZenithTheme.accentGreen,
                        unfocusedBorderColor = ZenithTheme.borderColor,
                        focusedContainerColor = ZenithTheme.cardVariantSurface,
                        unfocusedContainerColor = ZenithTheme.cardVariantSurface,
                        focusedTextColor = ZenithTheme.textPrimary,
                        unfocusedTextColor = ZenithTheme.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_transaction_amount_field"),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Title Field
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title / Description") },
                    placeholder = { Text("e.g. Grocery, Lunch, Salary", color = ZenithTheme.textMuted) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ZenithTheme.accentGreen,
                        unfocusedBorderColor = ZenithTheme.borderColor,
                        focusedContainerColor = ZenithTheme.cardVariantSurface,
                        unfocusedContainerColor = ZenithTheme.cardVariantSurface,
                        focusedTextColor = ZenithTheme.textPrimary,
                        unfocusedTextColor = ZenithTheme.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_transaction_title_field"),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Category Selector
                Text(
                    text = "Category",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ZenithTheme.textSecondary,
                    modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                )

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ZenithTheme.cardVariantSurface,
                    border = BorderStroke(1.dp, ZenithTheme.borderColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showCategoryPickerSheet = true }
                        .testTag("category_picker_button")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(ZenithTheme.accentGreen.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                val catIcon = fullCategoryList.firstOrNull { it.first == selectedCategory }?.second ?: Icons.Default.Category
                                Icon(catIcon, contentDescription = null, tint = ZenithTheme.accentGreen, modifier = Modifier.size(16.dp))
                            }

                            Text(
                                text = selectedCategory,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.textPrimary
                            )
                        }

                        Text("Change ▾", fontSize = 12.sp, color = ZenithTheme.accentGreen, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                PaymentMethodDropdown(
                    selectedMethod = selectedPaymentMethod,
                    onMethodSelected = { selectedPaymentMethod = it },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                if (isFamilyMode && familyMembers.isNotEmpty()) {
                    Text(
                        text = if (selectedType == TransactionType.EXPENSE) "Paid by" else "Received by",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ZenithTheme.textSecondary,
                        modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("paid_by_row")
                    ) {
                        items(familyMembers, key = { it.userId }) { member ->
                            val chipLabel = if (member.memberLabel.isNotBlank()) {
                                "${member.avatarEmoji} ${member.displayName} (${member.memberLabel})"
                            } else {
                                "${member.avatarEmoji} ${member.displayName}"
                            }
                            FilterChip(
                                selected = selectedPaidByUserId == member.userId,
                                onClick = { selectedPaidByUserId = member.userId },
                                modifier = Modifier.testTag("paid_by_chip_${member.userId}"),
                                label = {
                                    Text(
                                        chipLabel,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Custom Label / Tag Section
                Text(
                    text = "Label (Optional)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ZenithTheme.textSecondary,
                    modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(FamilyAvatarCatalog.EXPENSE_LABEL_SUGGESTIONS) { preset ->
                        FilterChip(
                            selected = customLabelText.equals(preset, ignoreCase = true),
                            onClick = {
                                customLabelText = if (customLabelText.equals(preset, ignoreCase = true)) "" else preset
                            },
                            label = { Text(preset, fontSize = 11.sp, maxLines = 1) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = customLabelText,
                    onValueChange = { customLabelText = it },
                    label = { Text("Custom Label / Tag") },
                    placeholder = { Text("e.g. Urgent, Shared, Reimbursable", color = ZenithTheme.textMuted) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ZenithTheme.accentIndigo,
                        unfocusedBorderColor = ZenithTheme.borderColor,
                        focusedContainerColor = ZenithTheme.cardVariantSurface,
                        unfocusedContainerColor = ZenithTheme.cardVariantSurface,
                        focusedTextColor = ZenithTheme.textPrimary,
                        unfocusedTextColor = ZenithTheme.textPrimary
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_tx_custom_label")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Note Field
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (Optional)") },
                    placeholder = { Text("Additional details...", color = ZenithTheme.textMuted) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ZenithTheme.accentGreen,
                        unfocusedBorderColor = ZenithTheme.borderColor,
                        focusedContainerColor = ZenithTheme.cardVariantSurface,
                        unfocusedContainerColor = ZenithTheme.cardVariantSurface,
                        focusedTextColor = ZenithTheme.textPrimary,
                        unfocusedTextColor = ZenithTheme.textPrimary
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(22.dp))

                val isValid = (amountText.toDoubleOrNull() ?: 0.0) > 0 && title.isNotBlank() && !isSubmitting

                Button(
                    onClick = {
                        val parsedAmount = amountText.toDoubleOrNull() ?: 0.0
                        if (isValid && !isSubmitting) {
                            isSubmitting = true
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            val cleanLabel = customLabelText.trim()
                            when {
                                onAddWithLabel != null -> {
                                    onAddWithLabel.invoke(
                                        title.trim(),
                                        parsedAmount,
                                        selectedType,
                                        selectedCategory,
                                        System.currentTimeMillis(),
                                        note.trim(),
                                        selectedPaymentMethod,
                                        if (isFamilyMode) selectedPaidByUserId else null,
                                        cleanLabel
                                    )
                                }
                                onAddWithPaidBy != null -> {
                                    onAddWithPaidBy.invoke(
                                        title.trim(),
                                        parsedAmount,
                                        selectedType,
                                        selectedCategory,
                                        System.currentTimeMillis(),
                                        note.trim(),
                                        selectedPaymentMethod,
                                        if (isFamilyMode) selectedPaidByUserId else null
                                    )
                                }
                                else -> {
                                    onAdd(
                                        title.trim(),
                                        parsedAmount,
                                        selectedType,
                                        selectedCategory,
                                        System.currentTimeMillis(),
                                        note.trim(),
                                        selectedPaymentMethod
                                    )
                                }
                            }
                        }
                    },
                    enabled = isValid && !isSubmitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("save_transaction_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedType == TransactionType.EXPENSE) ZenithTheme.accentRed else ZenithTheme.accentGreen
                    )
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (selectedType == TransactionType.EXPENSE) "Save Expense" else "Save Income",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }
            }
        }
    }

    if (showCategoryPickerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCategoryPickerSheet = false },
            containerColor = ZenithTheme.cardSurface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Select Category",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZenithTheme.textPrimary
                )
                if (onAddCustomCategory != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newCustomCategoryText,
                            onValueChange = { newCustomCategoryText = it },
                            label = { Text("Custom Category Name") },
                            placeholder = { Text("e.g. Pets, Gifts") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val cleanCat = newCustomCategoryText.trim()
                                if (cleanCat.isNotBlank()) {
                                    onAddCustomCategory.invoke(cleanCat, "#10B981")
                                    selectedCategory = cleanCat
                                    newCustomCategoryText = ""
                                    showCategoryPickerSheet = false
                                }
                            },
                            enabled = newCustomCategoryText.trim().isNotEmpty(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen)
                        ) {
                            Text("Add", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.heightIn(max = 380.dp)
                ) {
                    items(fullCategoryList) { (catName, icon) ->
                        val isSelected = selectedCategory == catName
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) ZenithTheme.accentGreen.copy(alpha = 0.18f) else ZenithTheme.cardVariantSurface,
                            border = BorderStroke(1.dp, if (isSelected) ZenithTheme.accentGreen else ZenithTheme.borderColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clickable {
                                    selectedCategory = catName
                                    showCategoryPickerSheet = false
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(icon, contentDescription = null, tint = if (isSelected) ZenithTheme.accentGreen else ZenithTheme.textSecondary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = catName,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) ZenithTheme.accentGreen else ZenithTheme.textPrimary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
}
