package com.zenith.app.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zenith.app.data.upi.UpiPaymentBus
import com.zenith.app.data.upi.UpiService
import com.zenith.app.ui.theme.ZenithTheme
import java.util.Locale

enum class UpiModalStep {
    REDIRECT_CHOOSER,
    ENTER_EXPENSE
}

@Composable
fun UpiQrScanModal(
    currencySymbol: String = "₹",
    onDismiss: () -> Unit,
    onSaveTransaction: (
        title: String,
        amount: Double,
        category: String,
        upiId: String?,
        upiTransactionId: String?
    ) -> Unit = { _, _, _, _, _ -> }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current

    var modalStep by remember { mutableStateOf(UpiModalStep.REDIRECT_CHOOSER) }
    var awaitingUpiReturn by remember { mutableStateOf(false) }
    var autoRedirectAttempted by remember { mutableStateOf(false) }
    val modalOpenedAt = remember { System.currentTimeMillis() }

    // Transaction form states (ENTER_EXPENSE after returning from UPI app or Direct Entry)
    var enteredTitle by remember { mutableStateOf("UPI Payment") }
    var enteredAmount by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Food & Dining") }
    var detectedUtr by remember { mutableStateOf<String?>(null) }
    var amountError by remember { mutableStateOf<String?>(null) }
    var defaultUpiPackage by remember { mutableStateOf(UpiService.getDefaultUpiAppPackage(context)) }

    val categories = remember {
        listOf(
            "Food & Dining",
            "Shopping",
            "Transportation",
            "Bills & Utilities",
            "Entertainment",
            "Housing & Rent",
            "Healthcare",
            "Other"
        )
    }

    val installedUpiApps = remember { UpiService.installedUpiApps(context) }
    val isGPayInstalled = remember { UpiService.isGooglePayInstalled(context) }
    val isPhonePeInstalled = remember { UpiService.isPhonePeInstalled(context) }
    val isPaytmInstalled = remember { UpiService.isPaytmInstalled(context) }
    val isBhimInstalled = remember { UpiService.isBhimInstalled(context) }

    // Listen for app resume after opening an external UPI app -> transition to expense logging
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingUpiReturn) {
                awaitingUpiReturn = false
                modalStep = UpiModalStep.ENTER_EXPENSE
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Pre-fill Amount, Payee Title, Category & UTR if background notification detector catches a fresh payment after redirect
    LaunchedEffect(Unit) {
        UpiPaymentBus.detectedPayments.collect { payment ->
            if (payment.isDebit &&
                payment.timestamp >= modalOpenedAt - 5_000L &&
                (awaitingUpiReturn || modalStep == UpiModalStep.REDIRECT_CHOOSER || modalStep == UpiModalStep.ENTER_EXPENSE)
            ) {
                if (enteredAmount.isBlank()) {
                    enteredAmount = String.format(Locale.US, "%.2f", payment.amount)
                }
                if (enteredTitle == "UPI Payment" || enteredTitle.isBlank()) {
                    enteredTitle = payment.payeeName.ifBlank { "UPI Payment" }
                }
                if (payment.detectedCategory.isNotBlank() && payment.detectedCategory != "Other") {
                    selectedCategory = payment.detectedCategory
                }
                if (!payment.upiTransactionId.isNullOrBlank()) {
                    detectedUtr = payment.upiTransactionId
                }
            }
        }
    }

    fun launchUpiApp(targetPackage: String?) {
        try {
            awaitingUpiReturn = true
            val intent = UpiService.buildUpiScannerIntent(context, targetPackage)
            context.startActivity(intent)
        } catch (e: Exception) {
            awaitingUpiReturn = false
            Toast.makeText(context, "Could not open UPI app: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Immediately redirect to default UPI app (or Android's default-apps chooser if UPI apps are installed)
    LaunchedEffect(Unit) {
        if (!autoRedirectAttempted) {
            autoRedirectAttempted = true
            val autoTarget = defaultUpiPackage
                ?: installedUpiApps.singleOrNull()?.packageName
            if (!autoTarget.isNullOrBlank()) {
                launchUpiApp(autoTarget)
            } else if (installedUpiApps.isNotEmpty()) {
                launchUpiApp(null)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = ZenithTheme.cardSurface,
            border = BorderStroke(1.dp, ZenithTheme.borderColor),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 20.dp)
                .imePadding()
                .testTag("upi_qr_scan_modal")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    when (modalStep) {
                                        UpiModalStep.REDIRECT_CHOOSER -> ZenithTheme.accentCyan.copy(alpha = 0.18f)
                                        UpiModalStep.ENTER_EXPENSE -> ZenithTheme.accentGreen.copy(alpha = 0.18f)
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when (modalStep) {
                                    UpiModalStep.REDIRECT_CHOOSER -> Icons.AutoMirrored.Filled.OpenInNew
                                    UpiModalStep.ENTER_EXPENSE -> Icons.Default.CheckCircle
                                },
                                contentDescription = null,
                                tint = when (modalStep) {
                                    UpiModalStep.REDIRECT_CHOOSER -> ZenithTheme.accentCyan
                                    UpiModalStep.ENTER_EXPENSE -> ZenithTheme.accentGreen
                                },
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = when (modalStep) {
                                    UpiModalStep.REDIRECT_CHOOSER -> "Open UPI App Scanner"
                                    UpiModalStep.ENTER_EXPENSE -> "Enter Expense Details"
                                },
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.textPrimary
                            )
                            Text(
                                text = when (modalStep) {
                                    UpiModalStep.REDIRECT_CHOOSER -> "Redirects to your UPI app directly, then log here"
                                    UpiModalStep.ENTER_EXPENSE -> "Record your completed UPI transaction"
                                },
                                fontSize = 11.5.sp,
                                color = ZenithTheme.textSecondary
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = ZenithTheme.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                AnimatedContent(
                    targetState = modalStep,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "upi_step_transition"
                ) { currentStep ->
                    when (currentStep) {
                        UpiModalStep.REDIRECT_CHOOSER -> {
                            Column {
                                Button(
                                    onClick = { launchUpiApp(defaultUpiPackage) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .testTag("btn_launch_upi_chooser"),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ZenithTheme.solidIndigo,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (defaultUpiPackage != null) {
                                            val defLabel = installedUpiApps.firstOrNull { it.packageName == defaultUpiPackage }?.label ?: "Default UPI App"
                                            "Open $defLabel"
                                        } else {
                                            "Open Default UPI App"
                                        },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "INSTALLED UPI APPS (TAP TO OPEN OR SET DEFAULT)",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                    color = ZenithTheme.textSecondary
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    UpiAppRedirectTile(
                                        appName = "Google Pay",
                                        badge = if (isGPayInstalled) "Installed" else "Available",
                                        isDefault = defaultUpiPackage == UpiService.GOOGLE_PAY_PACKAGE,
                                        canSetDefault = isGPayInstalled,
                                        icon = Icons.Default.Payment,
                                        accentColor = Color(0xFF4285F4),
                                        onToggleDefault = {
                                            val next = if (defaultUpiPackage == UpiService.GOOGLE_PAY_PACKAGE) null else UpiService.GOOGLE_PAY_PACKAGE
                                            UpiService.setDefaultUpiAppPackage(context, next)
                                            defaultUpiPackage = next
                                        },
                                        onClick = { launchUpiApp(UpiService.GOOGLE_PAY_PACKAGE) }
                                    )

                                    UpiAppRedirectTile(
                                        appName = "PhonePe",
                                        badge = if (isPhonePeInstalled) "Installed" else "Available",
                                        isDefault = defaultUpiPackage == UpiService.PHONEPE_PACKAGE,
                                        canSetDefault = isPhonePeInstalled,
                                        icon = Icons.Default.AccountBalanceWallet,
                                        accentColor = Color(0xFF5F259F),
                                        onToggleDefault = {
                                            val next = if (defaultUpiPackage == UpiService.PHONEPE_PACKAGE) null else UpiService.PHONEPE_PACKAGE
                                            UpiService.setDefaultUpiAppPackage(context, next)
                                            defaultUpiPackage = next
                                        },
                                        onClick = { launchUpiApp(UpiService.PHONEPE_PACKAGE) }
                                    )

                                    UpiAppRedirectTile(
                                        appName = "Paytm",
                                        badge = if (isPaytmInstalled) "Installed" else "Available",
                                        isDefault = defaultUpiPackage == UpiService.PAYTM_PACKAGE,
                                        canSetDefault = isPaytmInstalled,
                                        icon = Icons.Default.AccountBalanceWallet,
                                        accentColor = Color(0xFF00BAF2),
                                        onToggleDefault = {
                                            val next = if (defaultUpiPackage == UpiService.PAYTM_PACKAGE) null else UpiService.PAYTM_PACKAGE
                                            UpiService.setDefaultUpiAppPackage(context, next)
                                            defaultUpiPackage = next
                                        },
                                        onClick = { launchUpiApp(UpiService.PAYTM_PACKAGE) }
                                    )

                                    if (isBhimInstalled) {
                                        UpiAppRedirectTile(
                                            appName = "BHIM UPI",
                                            badge = "Installed",
                                            isDefault = defaultUpiPackage == UpiService.BHIM_PACKAGE,
                                            canSetDefault = true,
                                            icon = Icons.Default.AccountBalance,
                                            accentColor = Color(0xFF00897B),
                                            onToggleDefault = {
                                                val next = if (defaultUpiPackage == UpiService.BHIM_PACKAGE) null else UpiService.BHIM_PACKAGE
                                                UpiService.setDefaultUpiAppPackage(context, next)
                                                defaultUpiPackage = next
                                            },
                                            onClick = { launchUpiApp(UpiService.BHIM_PACKAGE) }
                                        )
                                    }

                                    installedUpiApps.filter { app ->
                                        app.packageName !in listOf(
                                            UpiService.GOOGLE_PAY_PACKAGE,
                                            UpiService.PHONEPE_PACKAGE,
                                            UpiService.PAYTM_PACKAGE,
                                            UpiService.BHIM_PACKAGE
                                        )
                                    }.forEach { customApp ->
                                        UpiAppRedirectTile(
                                            appName = customApp.label,
                                            badge = "Installed",
                                            isDefault = defaultUpiPackage == customApp.packageName,
                                            canSetDefault = true,
                                            icon = Icons.AutoMirrored.Filled.OpenInNew,
                                            accentColor = ZenithTheme.accentIndigo,
                                            onToggleDefault = {
                                                val next = if (defaultUpiPackage == customApp.packageName) null else customApp.packageName
                                                UpiService.setDefaultUpiAppPackage(context, next)
                                                defaultUpiPackage = next
                                            },
                                            onClick = { launchUpiApp(customApp.packageName) }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                OutlinedButton(
                                    onClick = { modalStep = UpiModalStep.ENTER_EXPENSE },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                        .testTag("btn_enter_upi_directly"),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, ZenithTheme.borderColor)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp), tint = ZenithTheme.accentCyan)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Enter Expense Directly", fontSize = 12.sp, color = ZenithTheme.textPrimary)
                                }
                            }
                        }

                        UpiModalStep.ENTER_EXPENSE -> {
                            Column {
                                Surface(
                                    color = ZenithTheme.accentGreen.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, ZenithTheme.accentGreen.copy(alpha = 0.35f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = ZenithTheme.accentGreen,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "Payment complete? Log the expense below to keep your ledger accurate.",
                                            fontSize = 11.5.sp,
                                            color = ZenithTheme.textPrimary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                OutlinedTextField(
                                    value = enteredAmount,
                                    onValueChange = {
                                        enteredAmount = it
                                        amountError = null
                                    },
                                    label = { Text("Amount ($currencySymbol)") },
                                    placeholder = { Text("0.00") },
                                    singleLine = true,
                                    isError = amountError != null,
                                    supportingText = amountError?.let { { Text(it, color = ZenithTheme.accentRed) } },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("upi_expense_amount_input"),
                                    shape = RoundedCornerShape(14.dp)
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                OutlinedTextField(
                                    value = enteredTitle,
                                    onValueChange = { enteredTitle = it },
                                    label = { Text("Title / Payee") },
                                    placeholder = { Text("e.g. Lunch, Grocery, Chai") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("upi_expense_title_input"),
                                    shape = RoundedCornerShape(14.dp)
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                UpiCategoryChipsRow(
                                    categories = categories,
                                    selectedCategory = selectedCategory,
                                    onSelect = { selectedCategory = it }
                                )

                                Spacer(modifier = Modifier.height(20.dp))

                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        val amt = enteredAmount.toDoubleOrNull()
                                        if (amt == null || amt <= 0.0) {
                                            amountError = "Please enter a valid amount"
                                            return@Button
                                        }
                                        val finalTitle = enteredTitle.trim().ifBlank { "UPI Payment" }
                                        onSaveTransaction(
                                            finalTitle,
                                            amt,
                                            selectedCategory,
                                            null,
                                            detectedUtr
                                        )
                                        Toast.makeText(context, "Saved $currencySymbol$amt for $finalTitle", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .testTag("btn_save_upi_expense"),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ZenithTheme.solidGreen,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Save Expense & Proceed", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpiCategoryChipsRow(
    categories: List<String>,
    selectedCategory: String,
    onSelect: (String) -> Unit
) {
    Text(
        text = "CATEGORY",
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = ZenithTheme.textSecondary
    )
    Spacer(modifier = Modifier.height(6.dp))
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(categories) { cat ->
            val isSelected = selectedCategory == cat
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(cat) },
                label = { Text(cat, fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = ZenithTheme.accentCyan.copy(alpha = 0.22f),
                    selectedLabelColor = ZenithTheme.textPrimary,
                    labelColor = ZenithTheme.textSecondary
                ),
                shape = RoundedCornerShape(10.dp)
            )
        }
    }
}

@Composable
fun UpiAppRedirectTile(
    appName: String,
    badge: String,
    icon: ImageVector,
    accentColor: Color,
    isDefault: Boolean = false,
    canSetDefault: Boolean = false,
    onToggleDefault: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = ZenithTheme.cardVariantSurface.copy(alpha = 0.72f),
        border = BorderStroke(
            1.dp,
            if (isDefault) ZenithTheme.accentGreen.copy(alpha = 0.65f) else ZenithTheme.borderColor
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = appName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = ZenithTheme.textPrimary
                    )
                    Text(
                        text = "Tap to open scanner & pay",
                        fontSize = 10.5.sp,
                        color = ZenithTheme.textSecondary
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canSetDefault && onToggleDefault != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isDefault) ZenithTheme.accentGreen.copy(alpha = 0.18f) else ZenithTheme.cardSurface,
                        border = BorderStroke(
                            1.dp,
                            if (isDefault) ZenithTheme.accentGreen.copy(alpha = 0.5f) else ZenithTheme.borderColor
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onToggleDefault() }
                    ) {
                        Text(
                            text = if (isDefault) "✓ Default" else "Set Default",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDefault) ZenithTheme.accentGreen else ZenithTheme.textSecondary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                } else {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = accentColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = badge,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = ZenithTheme.textMuted,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
