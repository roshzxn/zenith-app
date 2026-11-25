package com.zenith.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zenith.app.data.database.CashFlowDatabase
import com.zenith.app.data.models.*
import com.zenith.app.data.util.CategoryIconHelper
import com.zenith.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TransactionDetailModal(
    transaction: TransactionEntity,
    currencySymbol: String,
    onDismiss: () -> Unit,
    onDelete: (TransactionEntity) -> Unit,
    onUpdate: ((TransactionEntity) -> Unit)? = null
) {
    val context = LocalContext.current
    val database = remember { CashFlowDatabase.getDatabase(context) }
    val receiptState by database.receiptDao().getReceiptForTransaction(transaction.id).collectAsState(initial = null)
    val receiptItemsState by database.receiptDao().getItemsForTransaction(transaction.id).collectAsState(initial = emptyList())

    var isEditing by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showFullReceiptModal by remember { mutableStateOf(false) }
    var showImageViewerModal by remember { mutableStateOf(false) }

    // Edit fields
    var editTitle by remember(transaction) { mutableStateOf(transaction.title) }
    var editAmountText by remember(transaction) { mutableStateOf(String.format(Locale.US, "%.2f", transaction.amount)) }
    var editCategory by remember(transaction) { mutableStateOf(transaction.category) }
    var editPaymentMethod by remember(transaction) { mutableStateOf(transaction.paymentMethod) }
    var editType by remember(transaction) { mutableStateOf(transaction.type) }
    var editNote by remember(transaction) { mutableStateOf(transaction.note) }

    val isExpense = transaction.type == TransactionType.EXPENSE
    val icon = categoryVectorFor(transaction.categoryIconName, transaction.category)
    val fullDateFormatted = remember(transaction.dateMillis) {
        SimpleDateFormat("EEEE, dd MMMM yyyy 'at' hh:mm a", Locale.getDefault()).format(Date(transaction.dateMillis))
    }

    val categoryList = listOf(
        "Food & Dining",
        "Transportation",
        "Shopping",
        "Bills & Utilities",
        "Housing & Rent",
        "Healthcare",
        "Entertainment",
        "Education",
        "Salary & Income"
    )

    val paymentMethods = listOf("UPI", "Cash", "Credit Card", "Debit Card", "Bank Transfer")
    val imageUriToDisplay = receiptState?.imageUri ?: transaction.receiptImageUri
    val hasReceipt = receiptState != null || receiptItemsState.isNotEmpty() || !imageUriToDisplay.isNullOrBlank()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ZenithTheme.cardSurface,
            border = BorderStroke(1.dp, ZenithTheme.borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
                .testTag("transaction_detail_modal")
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ZenithTheme.accentGreen.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, ZenithTheme.accentGreen.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = "PERSONAL LEDGER",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.accentGreen,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                letterSpacing = 1.sp
                            )
                        }

                        if (hasReceipt) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = ZenithTheme.accentPurple.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, ZenithTheme.accentPurple.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = "Receipt Attached",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ZenithTheme.accentPurple,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("close_detail_modal")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = ZenithTheme.textSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (!isEditing) {
                    val typeColor = if (isExpense) ZenithTheme.accentRed else ZenithTheme.accentGreen

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(typeColor.copy(alpha = 0.14f))
                                .border(1.5.dp, typeColor.copy(alpha = 0.38f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = transaction.category,
                                tint = typeColor,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Text(
                                text = transaction.title,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.textPrimary,
                                maxLines = 2
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${if (isExpense) "-" else "+"}$currencySymbol${String.format(Locale.US, "%,.2f", transaction.amount)}",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = typeColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = ZenithTheme.borderColor)
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "TRANSACTION BREAKDOWN",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ZenithTheme.textSecondary,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    DetailItemRow(label = "Category", value = transaction.category, icon = Icons.Default.Category)
                    DetailItemRow(label = "Payment Method", value = transaction.paymentMethod, icon = Icons.Default.Payment)
                    DetailItemRow(label = "Type", value = if (isExpense) "Expense (Outflow)" else "Income (Inflow)", icon = if (isExpense) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward)
                    DetailItemRow(label = "Date & Time", value = fullDateFormatted, icon = Icons.Default.CalendarToday)
                    DetailItemRow(
                        label = "Sync Status",
                        value = if (transaction.syncStatus == "SYNCED") "Cloud Synced" else "On-Device Storage",
                        icon = Icons.Default.CloudDone
                    )

                    if (hasReceipt) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = ZenithTheme.borderColor)
                        Spacer(modifier = Modifier.height(14.dp))

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = ZenithTheme.glassCardBg,
                            border = BorderStroke(1.dp, ZenithTheme.accentPurple.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, tint = ZenithTheme.accentPurple, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "ATTACHED RECEIPT",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ZenithTheme.accentPurple,
                                            letterSpacing = 1.sp
                                        )
                                    }

                                    if (!receiptState?.receiptNumber.isNullOrBlank()) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = ZenithTheme.cardVariantSurface
                                        ) {
                                            Text(
                                                text = "#${receiptState?.receiptNumber}",
                                                fontSize = 10.sp,
                                                color = ZenithTheme.textSecondary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                val currentReceipt = receiptState
                                if (currentReceipt != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = currentReceipt.merchantName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ZenithTheme.textPrimary
                                    )
                                    if (currentReceipt.receiptDate.isNotBlank()) {
                                        Text(
                                            text = "${currentReceipt.receiptDate} ${currentReceipt.receiptTime ?: ""}",
                                            fontSize = 11.sp,
                                            color = ZenithTheme.textSecondary
                                        )
                                    }
                                }

                                if (receiptItemsState.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "ITEMS (${receiptItemsState.size})",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ZenithTheme.textSecondary,
                                        letterSpacing = 0.5.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))

                                    receiptItemsState.forEach { item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.name,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = ZenithTheme.textPrimary
                                                )
                                                Text(
                                                    text = "${if (item.quantity % 1.0 == 0.0) item.quantity.toInt().toString() else item.quantity} × $currencySymbol${String.format(Locale.US, "%.2f", item.unitPrice)}",
                                                    fontSize = 10.sp,
                                                    color = ZenithTheme.textSecondary
                                                )
                                            }

                                            Text(
                                                text = "$currencySymbol${String.format(Locale.US, "%.2f", item.totalPrice)}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = ZenithTheme.textPrimary
                                            )
                                        }
                                    }

                                    val rState = receiptState
                                    if (rState != null && ((rState.tax > 0) || (rState.discount > 0) || (rState.subtotal > 0))) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        HorizontalDivider(color = ZenithTheme.borderColor)
                                        Spacer(modifier = Modifier.height(6.dp))

                                        if (rState.subtotal > 0) {
                                            ReceiptSummaryRow(label = "Subtotal", amount = "$currencySymbol${String.format(Locale.US, "%.2f", rState.subtotal)}")
                                        }
                                        if (rState.tax > 0) {
                                            ReceiptSummaryRow(label = "Tax / GST", amount = "+$currencySymbol${String.format(Locale.US, "%.2f", rState.tax)}")
                                        }
                                        if (rState.discount > 0) {
                                            ReceiptSummaryRow(label = "Discount", amount = "-$currencySymbol${String.format(Locale.US, "%.2f", rState.discount)}", color = ZenithTheme.accentGreen)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { showFullReceiptModal = true },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Receipt, contentDescription = null, modifier = Modifier.size(14.dp), tint = ZenithTheme.accentPurple)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Full Receipt", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ZenithTheme.accentPurple)
                                    }

                                    if (!imageUriToDisplay.isNullOrBlank()) {
                                        OutlinedButton(
                                            onClick = { showImageViewerModal = true },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(38.dp),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(14.dp), tint = ZenithTheme.accentGreen)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Receipt Image", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ZenithTheme.accentGreen)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (transaction.note.isNotBlank()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Note / Transcription",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ZenithTheme.textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ZenithTheme.cardVariantSurface,
                            border = BorderStroke(1.dp, ZenithTheme.borderColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = transaction.note,
                                fontSize = 13.sp,
                                color = ZenithTheme.textPrimary,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onUpdate != null) {
                            OutlinedButton(
                                onClick = { isEditing = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp)
                                    .testTag("btn_edit_tx"),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Edit", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                val summary = "Zenith Transaction\nTitle: ${transaction.title}\nAmount: ${if (isExpense) "-" else "+"}$currencySymbol${transaction.amount}\nCategory: ${transaction.category}\nDate: $fullDateFormatted\nMethod: ${transaction.paymentMethod}"
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Zenith Transaction", summary))
                                Toast.makeText(context, "Transaction details copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                                .testTag("btn_share_tx"),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
                            }
                        }

                        Button(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                                .testTag("btn_delete_modal_tx"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ZenithTheme.accentRed.copy(alpha = 0.16f),
                                contentColor = ZenithTheme.accentRed
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                } else {
                    Text(
                        text = "EDIT TRANSACTION",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = ZenithTheme.accentGreen
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ZenithTheme.cardVariantSurface, RoundedCornerShape(10.dp))
                            .padding(3.dp)
                    ) {
                        val isExp = editType == TransactionType.EXPENSE
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isExp) ZenithTheme.solidRed else Color.Transparent)
                                .clickable { editType = TransactionType.EXPENSE },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Expense", fontWeight = FontWeight.Bold, color = if (isExp) Color.White else ZenithTheme.textSecondary, fontSize = 13.sp)
                        }

                        val isInc = editType == TransactionType.INCOME
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isInc) ZenithTheme.solidGreen else Color.Transparent)
                                .clickable { editType = TransactionType.INCOME },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Income", fontWeight = FontWeight.Bold, color = if (isInc) Color.White else ZenithTheme.textSecondary, fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = editAmountText,
                        onValueChange = { input ->
                            val normalized = input.replace(',', '.')
                            val clean = normalized.filter { it.isDigit() || it == '.' }
                            if (clean.count { it == '.' } <= 1) {
                                editAmountText = clean
                            }
                        },
                        label = { Text("Amount ($currencySymbol)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Category", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ZenithTheme.textSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(categoryList) { cat ->
                            FilterChip(
                                selected = editCategory == cat,
                                onClick = { editCategory = cat },
                                label = { Text(text = cat, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    PaymentMethodDropdown(
                        selectedMethod = editPaymentMethod,
                        onMethodSelected = { editPaymentMethod = it },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = editNote,
                        onValueChange = { editNote = it },
                        label = { Text("Note (Optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { isEditing = false },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 44.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Cancel", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }

                        Button(
                            onClick = {
                                val parsedAmt = editAmountText.replace(',', '.').toDoubleOrNull() ?: transaction.amount
                                if (editTitle.isNotBlank() && parsedAmt > 0) {
                                    val updated = transaction.copy(
                                        title = editTitle,
                                        amount = parsedAmt,
                                        type = editType,
                                        category = editCategory,
                                        categoryIconName = CategoryIconHelper.iconNameForCategory(editCategory),
                                        paymentMethod = editPaymentMethod,
                                        note = editNote,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                    onUpdate?.invoke(updated)
                                    Toast.makeText(context, "Transaction updated!", Toast.LENGTH_SHORT).show()
                                    isEditing = false
                                    onDismiss()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 44.dp)
                                .testTag("btn_save_tx_edit"),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen)
                        ) {
                            Text("Save Changes", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, color = Color.White)
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Transaction?", fontWeight = FontWeight.Bold, color = ZenithTheme.textPrimary) },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${transaction.title}\"? This transaction and its attached receipt details will be removed.",
                    color = ZenithTheme.textSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(transaction)
                        showDeleteConfirm = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidRed)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            },
            containerColor = ZenithTheme.cardSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // --- FULL RECEIPT VIEWER MODAL (Paper receipt styling) ---
    if (showFullReceiptModal) {
        FullReceiptViewerDialog(
            transaction = transaction,
            receipt = receiptState,
            items = receiptItemsState,
            currencySymbol = currencySymbol,
            onDismiss = { showFullReceiptModal = false }
        )
    }

    // --- FULL SCREEN ZOOMABLE IMAGE VIEWER MODAL ---
    if (showImageViewerModal && !imageUriToDisplay.isNullOrBlank()) {
        ReceiptImageViewerDialog(
            imageUriString = imageUriToDisplay,
            onDismiss = { showImageViewerModal = false }
        )
    }
}

@Composable
private fun ReceiptSummaryRow(label: String, amount: String, color: Color = ZenithTheme.textPrimary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 11.sp, color = ZenithTheme.textSecondary)
        Text(text = amount, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun DetailItemRow(label: String, value: String, icon: ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ZenithTheme.textSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                color = ZenithTheme.textSecondary,
                maxLines = 1
            )
        }

        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = ZenithTheme.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
            softWrap = true,
            lineHeight = 18.sp
        )
    }
}

@Composable
fun FullReceiptViewerDialog(
    transaction: TransactionEntity,
    receipt: ReceiptEntity?,
    items: List<ReceiptItemEntity>,
    currencySymbol: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ZenithTheme.cardSurface,
            border = BorderStroke(1.dp, ZenithTheme.accentPurple.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RECEIPT VOUCHER",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = ZenithTheme.accentPurple,
                        letterSpacing = 1.5.sp
                    )

                    IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = ZenithTheme.textSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ZenithTheme.cardVariantSurface,
                    border = BorderStroke(1.dp, ZenithTheme.borderColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = receipt?.merchantName ?: transaction.title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = ZenithTheme.textPrimary,
                            textAlign = TextAlign.Center
                        )

                        if (!receipt?.receiptNumber.isNullOrBlank()) {
                            Text(
                                text = "Invoice: ${receipt?.receiptNumber}",
                                fontSize = 11.sp,
                                color = ZenithTheme.textSecondary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        Text(
                            text = "Date: ${receipt?.receiptDate?.ifBlank { "N/A" }} ${receipt?.receiptTime ?: ""}",
                            fontSize = 11.sp,
                            color = ZenithTheme.textSecondary,
                            modifier = Modifier.padding(top = 2.dp)
                        )

                        Text(
                            text = "Payment: ${receipt?.paymentMethod ?: transaction.paymentMethod}",
                            fontSize = 11.sp,
                            color = ZenithTheme.textSecondary,
                            modifier = Modifier.padding(top = 2.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "- - - - - - - - - - - - - - - - - - - - - - - - - - - -",
                            fontSize = 10.sp,
                            color = ZenithTheme.textMuted,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("ITEM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ZenithTheme.textSecondary)
                            Text("QTY × PRICE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ZenithTheme.textSecondary)
                            Text("TOTAL", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ZenithTheme.textSecondary)
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        if (items.isNotEmpty()) {
                            items.forEach { itm ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = itm.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = ZenithTheme.textPrimary,
                                        modifier = Modifier.weight(1.2f)
                                    )
                                    Text(
                                        text = "${if (itm.quantity % 1.0 == 0.0) itm.quantity.toInt().toString() else itm.quantity} × $currencySymbol${String.format(Locale.US, "%.2f", itm.unitPrice)}",
                                        fontSize = 11.sp,
                                        color = ZenithTheme.textSecondary,
                                        modifier = Modifier.weight(1f),
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = "$currencySymbol${String.format(Locale.US, "%.2f", itm.totalPrice)}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ZenithTheme.textPrimary,
                                        modifier = Modifier.weight(0.8f),
                                        textAlign = TextAlign.End
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(transaction.title, fontSize = 12.sp, color = ZenithTheme.textPrimary)
                                Text("1 × $currencySymbol${transaction.amount}", fontSize = 11.sp, color = ZenithTheme.textSecondary)
                                Text("$currencySymbol${transaction.amount}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ZenithTheme.textPrimary)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "- - - - - - - - - - - - - - - - - - - - - - - - - - - -",
                            fontSize = 10.sp,
                            color = ZenithTheme.textMuted,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val subtotal = receipt?.subtotal ?: transaction.amount
                        val tax = receipt?.tax ?: 0.0
                        val discount = receipt?.discount ?: 0.0
                        val total = receipt?.total ?: transaction.amount

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Subtotal", fontSize = 12.sp, color = ZenithTheme.textSecondary)
                            Text("$currencySymbol${String.format(Locale.US, "%.2f", subtotal)}", fontSize = 12.sp, color = ZenithTheme.textPrimary)
                        }

                        if (tax > 0) {
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Tax / GST", fontSize = 12.sp, color = ZenithTheme.textSecondary)
                                Text("+$currencySymbol${String.format(Locale.US, "%.2f", tax)}", fontSize = 12.sp, color = ZenithTheme.textPrimary)
                            }
                        }

                        if (discount > 0) {
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Discount", fontSize = 12.sp, color = ZenithTheme.accentGreen)
                                Text("-$currencySymbol${String.format(Locale.US, "%.2f", discount)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ZenithTheme.accentGreen)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("GRAND TOTAL", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = ZenithTheme.textPrimary)
                            Text(
                                text = "$currencySymbol${String.format(Locale.US, "%.2f", total)}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = ZenithTheme.accentGreen
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "ZENITH SECURE LEDGER VERIFIED",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZenithTheme.textMuted,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen)
                ) {
                    Text("Close Receipt", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                }
            }
        }
    }
}

// --- FULL SCREEN ZOOMABLE IMAGE VIEWER MODAL ---
@Composable
fun ReceiptImageViewerDialog(
    imageUriString: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(imageUriString) {
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(imageUriString)
                val bm = if (Build.VERSION.SDK_INT >= 28) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.isMutableRequired = true }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                bitmap = bm
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
        ) {
            // Top Bar with Close and Reset
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp, start = 20.dp, end = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Receipt Photo",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("Reset Zoom", fontSize = 11.sp)
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }

            // Image Container with Zoom & Pan Gestures
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 90.dp, horizontal = 16.dp)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val b = bitmap
                if (b != null) {
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = "Original Receipt Image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY
                            )
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }

            // Bottom Hint
            Text(
                text = "Pinch to Zoom • Drag to Pan",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 30.dp)
            )
        }
    }
}
