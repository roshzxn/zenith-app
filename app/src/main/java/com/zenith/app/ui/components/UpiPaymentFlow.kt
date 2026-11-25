package com.zenith.app.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zenith.app.data.upi.ExtractedUpiPayment
import com.zenith.app.data.upi.PaymentFlowType
import com.zenith.app.data.upi.UpiApp
import com.zenith.app.data.upi.UpiGatewayVerifier
import com.zenith.app.data.upi.UpiIntentResult
import com.zenith.app.data.upi.UpiPaymentBus
import com.zenith.app.data.upi.UpiPaymentInfo
import com.zenith.app.data.upi.UpiPaymentStatus
import com.zenith.app.data.upi.UpiService
import com.zenith.app.ui.theme.*
import java.util.Locale

private const val UPI_FLOW_LOG_TAG = "ZenithUpiFlow"

/**
 * Request to start a UPI payment. The parent form sets this after validation.
 */
data class UpiPayRequest(
    val amount: Double,
    val purpose: String,
    val vpa: String,
    val targetPackage: String? = null,
    val payeeName: String? = null,
    val rawQrUri: String? = null,
    val merchantCode: String? = null,
    val originalTxnRef: String? = null,
    val flowType: PaymentFlowType = PaymentFlowType.P2P_INTENT,
    val gatewayVerifier: UpiGatewayVerifier? = null,
    val launchDirectly: Boolean = false
)

/**
 * Shared "pay via UPI" flow used by every UPI entry point:
 * 1) Direct UPI App Redirect (opens chosen GPay/PhonePe/Paytm/BHIM cleanly without P2P security blocks,
 *    copies payee details to clipboard, listens for return / notification debit, and prompts to note it).
 * 2) Standard/Merchant UPI URI Intent (`ACTION_VIEW` `upi://pay`) with strict response validation.
 *
 * Security invariants:
 * - Never marks a payment successful merely because a UPI app launched.
 * - Handles SUCCESSFUL, PENDING, FAILED (including security declines), CANCELLED, and INITIATED separately.
 * - Never allows saving FAILED or CANCELLED payments as settled expenses.
 * - Enforces server-side verification when [UpiPayRequest.flowType] == [PaymentFlowType.GATEWAY_ORDER].
 */
@Composable
fun UpiPaymentFlow(
    payRequest: UpiPayRequest?,
    currencySymbol: String,
    onSaveConfirmed: (upiTransactionId: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showAppPicker by remember { mutableStateOf(false) }
    var directRedirectMode by remember { mutableStateOf(false) }
    var awaitingDirectAppReturn by remember { mutableStateOf(false) }
    var directAppLabel by remember { mutableStateOf("UPI app") }
    var showPaymentConfirm by remember { mutableStateOf(false) }
    var upiIntentResult by remember { mutableStateOf<UpiIntentResult?>(null) }
    var returnedTxnRef by remember { mutableStateOf<String?>(null) }
    var matchedNotification by remember { mutableStateOf<ExtractedUpiPayment?>(null) }
    var lastTargetPackage by remember { mutableStateOf<String?>(null) }
    var hasSavedCurrentRequest by remember(payRequest) { mutableStateOf(false) }
    var requestStartedAt by remember(payRequest) { mutableStateOf(System.currentTimeMillis()) }

    val upiApps = remember { UpiService.installedUpiApps(context) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val rawMapped = UpiService.mapResult(result.resultCode, result.data)
        val req = payRequest
        val mapped = if (req != null && req.flowType == PaymentFlowType.GATEWAY_ORDER) {
            UpiService.enforceGatewayVerification(
                result = rawMapped,
                flowType = req.flowType,
                expectedOrderTxnRef = req.originalTxnRef,
                verifier = req.gatewayVerifier
            )
        } else {
            rawMapped
        }
        upiIntentResult = mapped
        if (!mapped.returnedTxnRef.isNullOrBlank()) {
            returnedTxnRef = mapped.returnedTxnRef
        }
        try {
            Log.d(
                UPI_FLOW_LOG_TAG,
                "UPI app returned status=${mapped.status}, code=${mapped.responseCode ?: "-"}, securityDecline=${mapped.isSecurityDecline}"
            )
        } catch (_: Throwable) {
        }
        showPaymentConfirm = true
    }

    // When returning to Zenith after a direct UPI app redirect, prompt user cleanly to note the expense
    DisposableEffect(lifecycleOwner, payRequest) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingDirectAppReturn && payRequest != null) {
                awaitingDirectAppReturn = false
                upiIntentResult = UpiIntentResult(
                    launched = true,
                    cancelled = false,
                    returnedTxnRef = returnedTxnRef,
                    status = UpiPaymentStatus.INITIATED,
                    message = "Returned from $directAppLabel. Confirm only if the payment succeeded to note it in Zenith."
                )
                showPaymentConfirm = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun copyPayeeToClipboard(payeeText: String): Boolean {
        val clean = payeeText.trim()
        if (clean.isBlank()) return false
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("UPI Recipient", clean))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun launchDirectUpiApp(targetPkg: String?, appLabel: String = "UPI app") {
        val request = payRequest ?: return
        val copied = copyPayeeToClipboard(request.vpa)
        val formattedAmt = String.format(Locale.US, "%.2f", request.amount)
        val hintText = if (copied) {
            "Copied ${request.vpa.trim()} — pay $currencySymbol$formattedAmt in $appLabel, then return to note it."
        } else {
            "Complete $currencySymbol$formattedAmt payment in $appLabel, then return to Zenith to note it."
        }
        Toast.makeText(context, hintText, Toast.LENGTH_LONG).show()

        directAppLabel = appLabel
        lastTargetPackage = targetPkg
        awaitingDirectAppReturn = true
        showAppPicker = false
        directRedirectMode = false
        try {
            val directIntent = UpiService.buildUpiScannerIntent(context, targetPkg)
            context.startActivity(directIntent)
        } catch (_: Exception) {
            awaitingDirectAppReturn = false
            Toast.makeText(context, "Could not launch $appLabel directly.", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchPayment(targetPackage: String?) {
        val request = payRequest ?: return
        lastTargetPackage = targetPackage
        val payeeDisplayName = request.payeeName?.takeIf { it.isNotBlank() } ?: request.purpose.trim()
        val info = UpiPaymentInfo(
            payeeAddress = request.vpa.trim(),
            payeeName = payeeDisplayName,
            amount = String.format(Locale.US, "%.2f", request.amount),
            currency = "INR",
            note = request.purpose.trim(),
            txnRef = request.originalTxnRef.orEmpty(), // Never fabricate synthetic tr for P2P
            merchantCode = request.merchantCode.orEmpty(),
            rawUri = request.rawQrUri
        )

        val validation = UpiService.validatePaymentInfo(info)
        if (!validation.isValid) {
            Toast.makeText(context, validation.errorMessage ?: "Invalid UPI payment request.", Toast.LENGTH_SHORT).show()
            showAppPicker = false
            onDismiss()
            return
        }

        val uri: Uri = try {
            if (!request.rawQrUri.isNullOrBlank()) {
                UpiService.buildQrPaymentUri(request.rawQrUri, request.amount)
            } else {
                UpiService.buildPaymentUri(info)
            }
        } catch (e: IllegalArgumentException) {
            Toast.makeText(context, e.message ?: "Invalid payment details.", Toast.LENGTH_SHORT).show()
            showAppPicker = false
            onDismiss()
            return
        }

        try {
            Log.d(
                UPI_FLOW_LOG_TAG,
                "Launching UPI intent pkg=${targetPackage ?: "CHOOSER"} uri=${UpiService.sanitizeUriForLog(uri)}"
            )
        } catch (_: Throwable) {
        }

        val intent = UpiService.buildPayIntentWithUri(uri, targetPackage, useChooser = true, context = context)
        showAppPicker = false
        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No UPI app available to handle this payment.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(payRequest) {
        if (payRequest != null) {
            returnedTxnRef = null
            matchedNotification = null
            upiIntentResult = null
            showPaymentConfirm = false
            awaitingDirectAppReturn = false
            requestStartedAt = System.currentTimeMillis()
            directRedirectMode = payRequest.launchDirectly
            if (!payRequest.targetPackage.isNullOrBlank()) {
                showAppPicker = false
                if (payRequest.launchDirectly) {
                    val label = upiApps.firstOrNull { it.packageName == payRequest.targetPackage }?.label ?: "UPI app"
                    launchDirectUpiApp(payRequest.targetPackage, label)
                } else {
                    launchPayment(payRequest.targetPackage)
                }
            } else {
                showAppPicker = true
            }
        }
    }

    LaunchedEffect(Unit) {
        UpiPaymentBus.detectedPayments.collect { payment ->
            val req = payRequest
            if (req != null && payment.isDebit && payment.timestamp >= requestStartedAt - 5_000L) {
                if (kotlin.math.abs(payment.amount - req.amount) < 0.05) {
                    matchedNotification = payment
                    if (!payment.upiTransactionId.isNullOrBlank()) {
                        returnedTxnRef = payment.upiTransactionId
                    }
                }
            }
        }
    }

    // --- UPI APP PICKER ---
    if (showAppPicker) {
        val request = payRequest
        if (request != null) {
            Dialog(onDismissRequest = {
                showAppPicker = false
                onDismiss()
            }) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ZenithTheme.cardSurface,
                    border = BorderStroke(1.dp, ZenithTheme.borderColor),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("upi_app_picker_dialog")
                ) {
                    Column(
                        modifier = Modifier
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = if (directRedirectMode) "Choose UPI App" else "Pay with",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZenithTheme.textPrimary
                        )
                        Text(
                            text = if (directRedirectMode) {
                                "Opens your UPI app directly (works with mobile, UPI ID & QR) and notes it when you return."
                            } else {
                                "Select an installed UPI app on this device."
                            },
                            fontSize = 12.sp,
                            color = ZenithTheme.textSecondary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        if (upiApps.isEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = ZenithTheme.accentAmber.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, ZenithTheme.accentAmber.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "No UPI app found on this device.",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ZenithTheme.textPrimary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Install Google Pay, PhonePe or BHIM to pay via UPI. You can still record manual UPI expenses.",
                                        fontSize = 11.sp,
                                        color = ZenithTheme.textSecondary
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            val store = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=upi%20payment"))
                                            try { context.startActivity(store) } catch (_: Exception) { }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(40.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ZenithTheme.solidIndigo,
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Text("Open Play Store", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (!directRedirectMode) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = ZenithTheme.accentCyan.copy(alpha = 0.12f),
                                        border = BorderStroke(1.dp, ZenithTheme.accentCyan.copy(alpha = 0.4f)),
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { launchPayment(null) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = ZenithTheme.accentCyan, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = "All UPI apps",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ZenithTheme.textPrimary
                                            )
                                            Spacer(modifier = Modifier.weight(1f))
                                            Text("System chooser", fontSize = 11.sp, color = ZenithTheme.textSecondary)
                                        }
                                    }
                                }
                                upiApps.forEach { app: UpiApp ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = ZenithTheme.cardVariantSurface,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                if (directRedirectMode) {
                                                    launchDirectUpiApp(app.packageName, app.label)
                                                } else {
                                                    launchPayment(app.packageName)
                                                }
                                            }
                                            .testTag("upi_app_${app.packageName}")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = ZenithTheme.accentIndigo, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = app.label,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = ZenithTheme.textPrimary
                                            )
                                            Spacer(modifier = Modifier.weight(1f))
                                            if (directRedirectMode) {
                                                Text("Open & note", fontSize = 11.sp, color = ZenithTheme.accentGreen)
                                            }
                                        }
                                    }
                                }
                                if (!directRedirectMode) {
                                    TextButton(
                                        onClick = { directRedirectMode = true },
                                        modifier = Modifier.fillMaxWidth().testTag("btn_switch_to_direct_redirect")
                                    ) {
                                        Text(
                                            "Avoid security declines? Open UPI app directly instead",
                                            fontSize = 11.5.sp,
                                            color = ZenithTheme.accentCyan
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

    // --- PAYMENT RESULT & STATE-SPECIFIC HANDLING ---
    if (showPaymentConfirm) {
        val request = payRequest
        if (request != null) {
            val resultState = upiIntentResult
            val effectiveStatus = when {
                resultState?.status == UpiPaymentStatus.FAILED -> UpiPaymentStatus.FAILED
                resultState?.status == UpiPaymentStatus.PENDING -> UpiPaymentStatus.PENDING
                resultState?.requiresServerVerification == true -> UpiPaymentStatus.PENDING
                resultState?.status == UpiPaymentStatus.SUCCESSFUL -> UpiPaymentStatus.SUCCESSFUL
                matchedNotification != null -> UpiPaymentStatus.SUCCESSFUL
                resultState?.status == UpiPaymentStatus.CANCELLED -> UpiPaymentStatus.CANCELLED
                else -> UpiPaymentStatus.INITIATED
            }

            Dialog(onDismissRequest = { }) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ZenithTheme.cardSurface,
                    border = BorderStroke(1.dp, ZenithTheme.borderColor),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("upi_result_dialog")
                ) {
                    Column(
                        modifier = Modifier
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val statusIcon = when (effectiveStatus) {
                            UpiPaymentStatus.SUCCESSFUL -> Icons.Default.Check
                            UpiPaymentStatus.PENDING -> Icons.Default.HourglassTop
                            UpiPaymentStatus.FAILED -> Icons.Default.ErrorOutline
                            UpiPaymentStatus.CANCELLED -> Icons.Default.Cancel
                            UpiPaymentStatus.INITIATED -> Icons.Default.AccountBalanceWallet
                        }
                        val statusColor = when (effectiveStatus) {
                            UpiPaymentStatus.SUCCESSFUL -> ZenithTheme.accentGreen
                            UpiPaymentStatus.PENDING -> ZenithTheme.accentAmber
                            UpiPaymentStatus.FAILED -> ZenithTheme.accentRed
                            UpiPaymentStatus.CANCELLED -> ZenithTheme.textSecondary
                            UpiPaymentStatus.INITIATED -> ZenithTheme.accentIndigo
                        }
                        val statusTitle = when (effectiveStatus) {
                            UpiPaymentStatus.SUCCESSFUL ->
                                if (matchedNotification != null && resultState?.status != UpiPaymentStatus.SUCCESSFUL) {
                                    "Payment Detected"
                                } else {
                                    "Payment Successful"
                                }
                            UpiPaymentStatus.PENDING -> "Payment Pending"
                            UpiPaymentStatus.FAILED ->
                                if (resultState?.isSecurityDecline == true) {
                                    "Declined for Security"
                                } else {
                                    "Payment Failed"
                                }
                            UpiPaymentStatus.CANCELLED -> "Payment Cancelled"
                            UpiPaymentStatus.INITIATED -> "Confirm Payment Status"
                        }
                        val statusDesc = when (effectiveStatus) {
                            UpiPaymentStatus.SUCCESSFUL ->
                                resultState?.message?.takeIf { resultState.status == UpiPaymentStatus.SUCCESSFUL }
                                    ?: "Confirmed bank/UPI debit via ${matchedNotification?.sourceApp ?: "UPI app"}."
                            UpiPaymentStatus.PENDING ->
                                resultState?.message?.takeIf { it.isNotBlank() }
                                    ?: "Transaction submitted and awaiting bank confirmation. Wait for confirmation before recording."
                            UpiPaymentStatus.FAILED ->
                                resultState?.message?.takeIf { it.isNotBlank() }
                                    ?: "Payment was declined or reported as failed by the UPI app."
                            UpiPaymentStatus.CANCELLED ->
                                "The payment was cancelled in the UPI app. No expense was recorded."
                            UpiPaymentStatus.INITIATED ->
                                resultState?.message?.takeIf { it.isNotBlank() }
                                    ?: "Your UPI app returned without an automatic status. Only save if the transfer succeeded in your UPI app."
                        }

                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = statusTitle,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = statusDesc,
                            fontSize = 12.5.sp,
                            color = ZenithTheme.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "$currencySymbol${String.format(Locale.US, "%.2f", request.amount)}",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            color = ZenithTheme.textPrimary
                        )
                        if (request.purpose.isNotBlank()) {
                            Text(
                                text = request.purpose,
                                fontSize = 13.sp,
                                color = ZenithTheme.textSecondary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        if (request.vpa.isNotBlank()) {
                            Text(
                                text = request.vpa,
                                fontSize = 12.sp,
                                color = ZenithTheme.textMuted,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        if (!returnedTxnRef.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = ZenithTheme.accentIndigo.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, ZenithTheme.accentIndigo.copy(alpha = 0.35f)),
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text(
                                    text = "Ref / UTR: $returnedTxnRef",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ZenithTheme.accentIndigo,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        when (effectiveStatus) {
                            UpiPaymentStatus.FAILED -> {
                                Button(
                                    onClick = {
                                        showPaymentConfirm = false
                                        val pkg = lastTargetPackage
                                        if (pkg != null) {
                                            val label = upiApps.firstOrNull { it.packageName == pkg }?.label ?: "UPI app"
                                            launchDirectUpiApp(pkg, label)
                                        } else {
                                            directRedirectMode = true
                                            showAppPicker = true
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(46.dp).testTag("btn_upi_open_direct"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ZenithTheme.solidIndigo,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Open UPI App Directly & Note", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        showPaymentConfirm = false
                                        showAppPicker = false
                                        onDismiss()
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("btn_upi_cancel")
                                ) {
                                    Text("Close (Not Paid)", fontSize = 13.sp, color = ZenithTheme.textSecondary)
                                }
                            }

                            UpiPaymentStatus.CANCELLED -> {
                                OutlinedButton(
                                    onClick = {
                                        showPaymentConfirm = false
                                        directRedirectMode = false
                                        showAppPicker = true
                                    },
                                    modifier = Modifier.fillMaxWidth().height(44.dp).testTag("btn_upi_retry"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Choose Another UPI App", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        showPaymentConfirm = false
                                        showAppPicker = false
                                        onDismiss()
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("btn_upi_cancel")
                                ) {
                                    Text("Close", fontSize = 13.sp, color = ZenithTheme.textSecondary)
                                }
                            }

                            UpiPaymentStatus.PENDING -> {
                                Button(
                                    onClick = {
                                        showPaymentConfirm = false
                                        showAppPicker = false
                                        onDismiss()
                                    },
                                    modifier = Modifier.fillMaxWidth().height(46.dp).testTag("btn_upi_pending_ack"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ZenithTheme.solidAmber,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text("Done — Wait for Bank Confirmation", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }

                            UpiPaymentStatus.SUCCESSFUL, UpiPaymentStatus.INITIATED -> {
                                val ctaColor = if (effectiveStatus == UpiPaymentStatus.SUCCESSFUL) {
                                    ZenithTheme.solidGreen
                                } else {
                                    ZenithTheme.solidIndigo
                                }
                                val ctaLabel = if (effectiveStatus == UpiPaymentStatus.SUCCESSFUL) {
                                    "Save Expense to Ledger"
                                } else {
                                    "Yes, Paid — Note in Zenith"
                                }

                                Button(
                                    onClick = {
                                        if (!hasSavedCurrentRequest) {
                                            hasSavedCurrentRequest = true
                                            showPaymentConfirm = false
                                            onSaveConfirmed(returnedTxnRef)
                                        }
                                    },
                                    enabled = !hasSavedCurrentRequest,
                                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("btn_upi_save"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ctaColor,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(ctaLabel, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                if (effectiveStatus == UpiPaymentStatus.INITIATED) {
                                    OutlinedButton(
                                        onClick = {
                                            showPaymentConfirm = false
                                            directRedirectMode = true
                                            showAppPicker = true
                                        },
                                        modifier = Modifier.fillMaxWidth().height(42.dp).testTag("btn_upi_open_direct"),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Open UPI App Again", fontSize = 12.5.sp)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                }

                                TextButton(
                                    onClick = {
                                        showPaymentConfirm = false
                                        showAppPicker = false
                                        onDismiss()
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("btn_upi_cancel")
                                ) {
                                    Text("No / Declined — Don't Save", fontSize = 13.sp, color = ZenithTheme.textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
