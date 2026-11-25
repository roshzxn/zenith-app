package com.zenith.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.zenith.app.data.models.AutoPayReminderEntity
import com.zenith.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAutoPayReminderDialog(
    currencySymbol: String,
    initialReminder: AutoPayReminderEntity? = null,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        amount: Double,
        category: String,
        paymentMethod: String,
        frequency: String,
        dueDayOfMonth: Int,
        nextDueDateMillis: Long,
        isAutoDebit: Boolean,
        reminderDaysBefore: Int,
        notes: String,
        reminderId: Long
    ) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var title by remember { mutableStateOf(initialReminder?.title ?: "") }
    var amountText by remember { mutableStateOf(if (initialReminder != null && initialReminder.amount > 0) String.format(Locale.US, "%.2f", initialReminder.amount) else "") }
    var selectedCategory by remember { mutableStateOf(initialReminder?.category ?: "Bills & Utilities") }
    var selectedPaymentMethod by remember { mutableStateOf(initialReminder?.paymentMethod ?: "UPI") }
    var selectedFrequency by remember { mutableStateOf(initialReminder?.frequency ?: "MONTHLY") }
    var isAutoDebit by remember { mutableStateOf(initialReminder?.isAutoDebit ?: true) }
    var reminderDaysBefore by remember { mutableIntStateOf(initialReminder?.reminderDaysBefore ?: 2) }
    var notes by remember { mutableStateOf(initialReminder?.notes ?: "") }

    // Date calculation
    val calendar = remember {
        Calendar.getInstance().apply {
            if (initialReminder != null) {
                timeInMillis = initialReminder.nextDueDateMillis
            } else {
                add(Calendar.DAY_OF_MONTH, 5) // Default to 5 days ahead
            }
        }
    }
    var dueDayOfMonth by remember { mutableIntStateOf(calendar.get(Calendar.DAY_OF_MONTH)) }

    val categories = listOf(
        "Bills & Utilities",
        "Subscriptions",
        "Housing & Rent",
        "Education",
        "Entertainment",
        "Healthcare",
        "Transportation",
        "Other"
    )

    val frequencies = listOf("MONTHLY", "WEEKLY", "QUARTERLY", "YEARLY", "ONE_TIME")
    val reminderDaysOptions = listOf(1, 2, 3, 5, 7)

    val isValid = title.isNotBlank() && (amountText.replace(',', '.').toDoubleOrNull() ?: 0.0) > 0.0

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SlateDarkSurface,
            border = BorderStroke(1.dp, GlassBorderColor),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .testTag("add_autopay_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (initialReminder != null) "Edit Auto-Pay Bill" else "Add Auto-Pay Reminder",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = SlateDarkTextPrimary
                        )
                        Text(
                            text = "Track subscriptions, utilities & avoid late fees",
                            fontSize = 11.sp,
                            color = SlateDarkTextSecondary
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp).testTag("close_autopay_dialog")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = SlateDarkTextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Service / Bill Name") },
                    placeholder = { Text("e.g. Netflix, Wi-Fi, Rent, Credit Card", color = SlateDarkTextMuted) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldDarkPrimary,
                        unfocusedBorderColor = GlassBorderColor,
                        focusedContainerColor = SlateDarkSurfaceVariant,
                        unfocusedContainerColor = SlateDarkSurfaceVariant,
                        focusedTextColor = SlateDarkTextPrimary,
                        unfocusedTextColor = SlateDarkTextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("autopay_title_field")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Amount
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { input ->
                        val normalized = input.replace(',', '.')
                        val clean = normalized.filter { it.isDigit() || it == '.' }
                        if (clean.count { it == '.' } <= 1) amountText = clean
                    },
                    label = { Text("Amount ($currencySymbol)") },
                    placeholder = { Text("0.00", color = SlateDarkTextMuted) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldDarkPrimary,
                        unfocusedBorderColor = GlassBorderColor,
                        focusedContainerColor = SlateDarkSurfaceVariant,
                        unfocusedContainerColor = SlateDarkSurfaceVariant,
                        focusedTextColor = SlateDarkTextPrimary,
                        unfocusedTextColor = SlateDarkTextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("autopay_amount_field")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Frequency Selector
                Text(
                    text = "Billing Frequency",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SlateDarkTextSecondary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(frequencies) { freq ->
                        val isSelected = selectedFrequency == freq
                        val label = when (freq) {
                            "MONTHLY" -> "Monthly"
                            "WEEKLY" -> "Weekly"
                            "QUARTERLY" -> "Quarterly"
                            "YEARLY" -> "Yearly"
                            else -> "One-Time"
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedFrequency = freq },
                            label = { Text(label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Day of Month / Due Day Selector
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SlateDarkSurfaceVariant,
                    border = BorderStroke(1.dp, GlassBorderColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Due Day of Month",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SlateDarkTextPrimary
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = EmeraldDarkPrimary.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, EmeraldDarkPrimary.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "Day $dueDayOfMonth",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldDarkPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Slider(
                            value = dueDayOfMonth.toFloat(),
                            onValueChange = { dueDayOfMonth = it.toInt().coerceIn(1, 31) },
                            valueRange = 1f..31f,
                            steps = 29,
                            colors = SliderDefaults.colors(
                                thumbColor = EmeraldDarkPrimary,
                                activeTrackColor = EmeraldDarkPrimary,
                                inactiveTrackColor = SlateDarkTextMuted.copy(alpha = 0.3f)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Auto-Debit vs Manual Switch Card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isAutoDebit) Color(0xFF8B5CF6).copy(alpha = 0.12f) else SlateDarkSurfaceVariant,
                    border = BorderStroke(1.dp, if (isAutoDebit) Color(0xFF8B5CF6).copy(alpha = 0.4f) else GlassBorderColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isAutoDebit) Icons.Default.FlashOn else Icons.Default.EditCalendar,
                                    contentDescription = null,
                                    tint = if (isAutoDebit) Color(0xFFC4B5FD) else SlateDarkTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isAutoDebit) "Bank Auto-Debit (NACH/E-Mandate)" else "Manual Bill Payment",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SlateDarkTextPrimary
                                )
                            }
                            Text(
                                text = if (isAutoDebit) "Zenith reminds you to keep sufficient balance" else "Zenith reminds you to pay manually on time",
                                fontSize = 10.sp,
                                color = SlateDarkTextSecondary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        Switch(
                            checked = isAutoDebit,
                            onCheckedChange = { isAutoDebit = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF8B5CF6)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Payment Method Dropdown
                PaymentMethodDropdown(
                    selectedMethod = selectedPaymentMethod,
                    onMethodSelected = { selectedPaymentMethod = it },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Category Chips
                Text(
                    text = "Category",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SlateDarkTextSecondary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(categories) { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat, fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Advance Warning Days
                Text(
                    text = "Remind Me Before Due Date",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SlateDarkTextSecondary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    reminderDaysOptions.forEach { days ->
                        val isSelected = reminderDaysBefore == days
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) EmeraldDarkPrimary.copy(alpha = 0.25f) else SlateDarkSurfaceVariant,
                            border = BorderStroke(1.dp, if (isSelected) EmeraldDarkPrimary else GlassBorderColor),
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { reminderDaysBefore = days }
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "$days d",
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) EmeraldDarkPrimary else SlateDarkTextPrimary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Notes
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (Optional)") },
                    placeholder = { Text("Account number, consumer ID, or card used", color = SlateDarkTextMuted) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldDarkPrimary,
                        unfocusedBorderColor = GlassBorderColor,
                        focusedContainerColor = SlateDarkSurfaceVariant,
                        unfocusedContainerColor = SlateDarkSurfaceVariant,
                        focusedTextColor = SlateDarkTextPrimary,
                        unfocusedTextColor = SlateDarkTextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(22.dp))

                // Save Button
                Button(
                    onClick = {
                        val parsedAmt = amountText.replace(',', '.').toDoubleOrNull() ?: 0.0
                        if (isValid) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            // Calculate next due date timestamp
                            val now = Calendar.getInstance()
                            val nextDueCal = Calendar.getInstance().apply {
                                set(Calendar.DAY_OF_MONTH, dueDayOfMonth.coerceAtMost(getActualMaximum(Calendar.DAY_OF_MONTH)))
                                set(Calendar.HOUR_OF_DAY, 9)
                                set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                                // If day has already passed this month, advance to next month
                                if (timeInMillis < now.timeInMillis) {
                                    add(Calendar.MONTH, 1)
                                }
                            }

                            onSave(
                                title.trim(),
                                parsedAmt,
                                selectedCategory,
                                selectedPaymentMethod,
                                selectedFrequency,
                                dueDayOfMonth,
                                nextDueCal.timeInMillis,
                                isAutoDebit,
                                reminderDaysBefore,
                                notes.trim(),
                                initialReminder?.id ?: 0L
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("btn_save_autopay"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldDarkPrimary),
                    enabled = isValid
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (initialReminder != null) "Update Reminder" else "Save Reminder",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
