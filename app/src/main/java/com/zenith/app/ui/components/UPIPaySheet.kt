package com.zenith.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zenith.app.data.upi.UpiPaymentInfo
import com.zenith.app.data.upi.UpiService
import com.zenith.app.ui.theme.ZenithTheme

@Composable
fun UPIPaySheet(
    currencySymbol: String,
    onDismiss: () -> Unit,
    onOpenScanQr: (() -> Unit)? = null,
    onSaveTransaction: (
        title: String,
        amount: Double,
        category: String,
        upiId: String?,
        upiTransactionId: String?
    ) -> Unit
) {
    val context = LocalContext.current

    var amountText by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("") }
    var vpaText by remember { mutableStateOf("") }
    var payeeNameText by remember { mutableStateOf("") }

    var selectedCategory by remember { mutableStateOf("Food & Dining") }
    var payRequest by remember { mutableStateOf<UpiPayRequest?>(null) }

    val amount = amountText.toDoubleOrNull() ?: 0.0
    val trimmedVpa = vpaText.trim()
    val vpaValid = UpiService.isValidVpa(trimmedVpa)
    val isAboveStandardP2pCap = amount > 100_000.0
    val isValid = amount > 0 && amount <= 500_000.0 && purpose.isNotBlank() && vpaValid

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ZenithTheme.cardSurface,
            border = BorderStroke(1.dp, ZenithTheme.borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 20.dp)
                .imePadding()
                .testTag("upi_pay_sheet")
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
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(ZenithTheme.accentCyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Payment,
                                contentDescription = null,
                                tint = ZenithTheme.accentCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Pay via UPI",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.textPrimary
                            )
                            Text(
                                text = "Pay through an installed UPI app",
                                fontSize = 11.sp,
                                color = ZenithTheme.textSecondary
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = ZenithTheme.textSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Amount
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { input ->
                        val clean = input.filter { it.isDigit() || it == '.' }
                        if (clean.count { it == '.' } <= 1) amountText = clean
                    },
                    label = { Text("Amount ($currencySymbol)") },
                    placeholder = { Text("0.00", color = ZenithTheme.textMuted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ZenithTheme.accentCyan,
                        unfocusedBorderColor = ZenithTheme.borderColor,
                        focusedContainerColor = ZenithTheme.cardVariantSurface,
                        unfocusedContainerColor = ZenithTheme.cardVariantSurface,
                        focusedTextColor = ZenithTheme.textPrimary,
                        unfocusedTextColor = ZenithTheme.textPrimary
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("upi_amount_field"),
                    singleLine = true
                )

                // Quick Amount Chips
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(50, 100, 200, 500, 1000).forEach { chipAmount ->
                        SuggestionChip(
                            onClick = { amountText = chipAmount.toString() },
                            label = { Text("$currencySymbol$chipAmount", fontSize = 11.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = ZenithTheme.cardVariantSurface,
                                labelColor = ZenithTheme.textPrimary
                            ),
                            border = BorderStroke(1.dp, ZenithTheme.borderColor),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Purpose / Merchant
                OutlinedTextField(
                    value = purpose,
                    onValueChange = { purpose = it },
                    label = { Text("Purpose / Merchant") },
                    placeholder = { Text("e.g. Food, Grocery, Swiggy", color = ZenithTheme.textMuted) },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ZenithTheme.accentCyan,
                        unfocusedBorderColor = ZenithTheme.borderColor,
                        focusedContainerColor = ZenithTheme.cardVariantSurface,
                        unfocusedContainerColor = ZenithTheme.cardVariantSurface,
                        focusedTextColor = ZenithTheme.textPrimary,
                        unfocusedTextColor = ZenithTheme.textPrimary
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("upi_purpose_field"),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Payee Name (Optional - improves NPCI `pn` parameter accuracy)
                OutlinedTextField(
                    value = payeeNameText,
                    onValueChange = { payeeNameText = it },
                    label = { Text("Recipient Name (Optional)") },
                    placeholder = { Text("e.g. Rahul, Cafe Coffee Day", color = ZenithTheme.textMuted) },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ZenithTheme.accentCyan,
                        unfocusedBorderColor = ZenithTheme.borderColor,
                        focusedContainerColor = ZenithTheme.cardVariantSurface,
                        unfocusedContainerColor = ZenithTheme.cardVariantSurface,
                        focusedTextColor = ZenithTheme.textPrimary,
                        unfocusedTextColor = ZenithTheme.textPrimary
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("upi_payee_name_field"),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Payee UPI ID (VPA)
                OutlinedTextField(
                    value = vpaText,
                    onValueChange = { vpaText = it.replace(" ", "") },
                    label = { Text("Payee UPI ID (VPA)") },
                    placeholder = { Text("e.g. merchant@upi or 10-digit number", color = ZenithTheme.textMuted) },
                    isError = trimmedVpa.isNotBlank() && !vpaValid && !(trimmedVpa.length == 10 && trimmedVpa.all { it.isDigit() }),
                    supportingText = {
                        if (trimmedVpa.isNotBlank() && !vpaValid && !(trimmedVpa.length == 10 && trimmedVpa.all { it.isDigit() })) {
                            Text("Enter a valid UPI ID like name@bank", fontSize = 11.sp, color = ZenithTheme.accentRed)
                        } else if (trimmedVpa.length == 10 && trimmedVpa.all { it.isDigit() }) {
                            Text("Phone number detected. Select a UPI bank handle below:", fontSize = 11.sp, color = ZenithTheme.accentCyan)
                        }
                    },
                    trailingIcon = {
                        if (onOpenScanQr != null) {
                            IconButton(onClick = {
                                onDismiss()
                                onOpenScanQr()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = "Scan QR Code",
                                    tint = ZenithTheme.accentCyan
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ZenithTheme.accentCyan,
                        unfocusedBorderColor = ZenithTheme.borderColor,
                        focusedContainerColor = ZenithTheme.cardVariantSurface,
                        unfocusedContainerColor = ZenithTheme.cardVariantSurface,
                        focusedTextColor = ZenithTheme.textPrimary,
                        unfocusedTextColor = ZenithTheme.textPrimary
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("upi_vpa_field"),
                    singleLine = true
                )

                // Phone number quick handle chips
                if (trimmedVpa.length == 10 && trimmedVpa.all { it.isDigit() }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("@okhdfcbank", "@okaxis", "@paytm", "@ybl").forEach { handle ->
                            SuggestionChip(
                                onClick = { vpaText = "$trimmedVpa$handle" },
                                label = { Text(handle, fontSize = 10.sp) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = ZenithTheme.accentCyan.copy(alpha = 0.15f),
                                    labelColor = ZenithTheme.accentCyan
                                ),
                                border = BorderStroke(1.dp, ZenithTheme.accentCyan.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }

                if (isAboveStandardP2pCap) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Standard peer-to-peer UPI cap is ₹1,00,000 per transaction; higher amounts require verified merchant/bank limits.",
                        fontSize = 11.sp,
                        color = ZenithTheme.accentAmber
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                UpiTransactionFields(
                    selectedCategory = selectedCategory,
                    onCategorySelected = { selectedCategory = it },
                    purpose = purpose
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Clear NPCI P2P policy notice for transparency
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ZenithTheme.accentIndigo.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, ZenithTheme.accentIndigo.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = ZenithTheme.accentIndigo,
                            modifier = Modifier.size(16.dp).padding(top = 1.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Note: Google Pay & PhonePe block third-party P2P intent links for security. If declined, tap \"Open UPI App Directly\" to pay using mobile number, UPI ID, or QR.",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = ZenithTheme.textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Pay button
                Button(
                    onClick = {
                        val candidateInfo = UpiPaymentInfo(
                            payeeAddress = trimmedVpa,
                            payeeName = payeeNameText.trim(),
                            amount = amountText.trim(),
                            note = purpose.trim()
                        )
                        val validation = UpiService.validatePaymentInfo(candidateInfo)
                        if (!validation.isValid) {
                            Toast.makeText(context, validation.errorMessage ?: "Invalid payment request.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (purpose.isBlank()) {
                            Toast.makeText(context, "Enter a purpose or note.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        payRequest = UpiPayRequest(
                            amount = amount,
                            purpose = purpose.trim(),
                            vpa = trimmedVpa,
                            payeeName = payeeNameText.trim().takeIf { it.isNotBlank() }
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("btn_pay_via_upi"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ZenithTheme.solidIndigo,
                        contentColor = Color.White
                    ),
                    enabled = isValid
                ) {
                    Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pay via UPI", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(UpiService.buildUpiScannerIntent(context, null))
                        } catch (_: Exception) {
                            Toast.makeText(context, "No UPI app installed.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp).testTag("btn_open_upi_app_direct"),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ZenithTheme.borderColor)
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp), tint = ZenithTheme.accentCyan)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open UPI App Directly (Mobile / ID / QR)", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = ZenithTheme.textPrimary)
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Zenith opens your UPI app. Payment is verified in that app — Zenith never stores your UPI PIN or bank credentials.",
                    fontSize = 10.sp,
                    color = ZenithTheme.textMuted,
                    lineHeight = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                )
            }
        }
    }

    UpiPaymentFlow(
        payRequest = payRequest,
        currencySymbol = currencySymbol,
        onSaveConfirmed = { upiTransactionId ->
            val req = payRequest
            if (req != null) {
                onSaveTransaction(
                    req.purpose.trim(),
                    req.amount,
                    selectedCategory,
                    req.vpa.trim().ifBlank { null },
                    upiTransactionId
                )
                payRequest = null
                onDismiss()
            }
        },
        onDismiss = {
            payRequest = null
            onDismiss()
        }
    )
}
