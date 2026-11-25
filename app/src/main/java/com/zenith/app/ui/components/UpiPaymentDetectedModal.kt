package com.zenith.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.zenith.app.data.upi.ExtractedUpiPayment
import com.zenith.app.ui.theme.LightIncomeGreen
import com.zenith.app.ui.theme.ZenithTheme
import java.util.Locale

@Composable
fun UpiPaymentDetectedModal(
    payment: ExtractedUpiPayment,
    currencySymbol: String,
    onDismiss: () -> Unit,
    onConfirmSave: (
        title: String,
        amount: Double,
        category: String,
        upiId: String?,
        upiTransactionId: String?
    ) -> Unit
) {
    var titleText by remember(payment) { mutableStateOf(payment.payeeName) }
    var selectedCategory by remember(payment) { mutableStateOf(payment.detectedCategory) }
    var isSubmitting by remember(payment) { mutableStateOf(false) }

    val categories = listOf(
        "Food & Dining",
        "Transportation",
        "Shopping",
        "Bills & Utilities",
        "Entertainment",
        "Healthcare",
        "Investments",
        "Other"
    )

    val accentColor = if (payment.isDebit) LightIncomeGreen else ZenithTheme.accentGreen

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ZenithTheme.cardSurface,
            border = BorderStroke(1.dp, ZenithTheme.accentGreen.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(ZenithTheme.accentGreen.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Payment,
                                contentDescription = null,
                                tint = ZenithTheme.accentGreen,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (payment.isDebit) "UPI Payment Detected" else "UPI Credit Detected",
                                color = ZenithTheme.textPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "via ${payment.sourceApp}",
                                color = ZenithTheme.accentGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = ZenithTheme.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Amount Display Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = ZenithTheme.cardVariantSurface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (payment.isDebit) "Amount Paid" else "Amount Received",
                            color = ZenithTheme.textSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$currencySymbol${String.format(Locale.US, "%.2f", payment.amount)}",
                            color = ZenithTheme.textPrimary,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (!payment.upiTransactionId.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "UTR: ${payment.upiTransactionId}",
                                color = ZenithTheme.textSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Merchant / Payee Field
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    label = {
                        Text(
                            text = if (payment.isDebit) "Merchant / Title" else "Sender / Title",
                            color = ZenithTheme.textSecondary,
                            fontSize = 13.sp
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ZenithTheme.textPrimary,
                        unfocusedTextColor = ZenithTheme.textPrimary,
                        focusedBorderColor = ZenithTheme.accentGreen,
                        unfocusedBorderColor = ZenithTheme.borderColor,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Category Chips
                Text(
                    text = "Category",
                    color = ZenithTheme.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(categories) { category ->
                        val isSelected = selectedCategory == category
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (isSelected) LightIncomeGreen else ZenithTheme.cardVariantSurface)
                                .clickable { selectedCategory = category }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = category,
                                color = if (isSelected) Color.White else ZenithTheme.textPrimary,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ZenithTheme.borderColor),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Ignore", color = ZenithTheme.textSecondary)
                    }

                    Button(
                        onClick = {
                            if (!isSubmitting) {
                                isSubmitting = true
                                onConfirmSave(
                                    titleText.ifBlank { payment.payeeName },
                                    payment.amount,
                                    selectedCategory,
                                    payment.upiId,
                                    payment.upiTransactionId
                                )
                            }
                        },
                        enabled = !isSubmitting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LightIncomeGreen,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1.5f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (payment.isDebit) "Record Expense" else "Record Income",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
