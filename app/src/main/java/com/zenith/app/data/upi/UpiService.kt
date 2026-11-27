package com.zenith.app.data.upi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLDecoder
import java.util.Locale

/**
 * Parsed UPI payment information.
 *
 * Standard UPI parameters:
 * pa = Payee VPA
 * pn = Payee name
 * am = Amount
 * cu = Currency
 * tn = Transaction note
 * tr = Transaction reference
 * mc = Merchant category code
 */
data class UpiPaymentInfo(
    val payeeAddress: String,
    val payeeName: String = "",
    val amount: String = "",
    val currency: String = "INR",
    val note: String = "",
    val txnRef: String = "",
    val merchantCode: String = "",
    val rawUri: String? = null
) {
    /**
     * Returns true if the QR code explicitly contained a non-zero payment amount (Dynamic QR).
     * Returns false for Static QRs where the amount must be entered by the user.
     */
    val isDynamic: Boolean
        get() = amount.isNotBlank() && (amount.replace(",", "").toDoubleOrNull() ?: 0.0) > 0.0

    /**
     * True when merchant metadata (MCC and/or signed payload) accompanies this payment.
     */
    val isVerifiedMerchantFormat: Boolean
        get() = merchantCode.trim().let { it.length == 4 && it.all(Char::isDigit) && it != "0000" } ||
            (rawUri?.contains("sign=", ignoreCase = true) == true)
}

/**
 * Distinguishes personal P2P / QR intents from server-backed merchant gateway orders.
 */
enum class PaymentFlowType {
    P2P_INTENT,
    MERCHANT_QR_INTENT,
    GATEWAY_ORDER
}

/**
 * Verification state returned by an authenticated server/webhook check for gateway transactions.
 */
enum class GatewayVerificationStatus {
    NOT_APPLICABLE,
    PENDING_SERVER_VERIFICATION,
    VERIFIED,
    REJECTED
}

/**
 * Contract for server-side verification of payment gateway transactions.
 * Client-side Intent status must never be trusted alone for gateway orders.
 */
fun interface UpiGatewayVerifier {
    fun verifyOrderStatus(expectedTxnRef: String, clientStatus: UpiPaymentStatus): GatewayVerificationStatus
}

data class UpiValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null,
    val isP2pUnverifiedWarning: Boolean = false
)

/**
 * Possible states of a UPI payment.
 */
enum class UpiPaymentStatus {
    INITIATED,
    PENDING,
    FAILED,
    CANCELLED,
    SUCCESSFUL
}

/**
 * Result returned after coming back from a UPI application.
 */
data class UpiIntentResult(
    val launched: Boolean,
    val cancelled: Boolean,
    val returnedTxnRef: String?,
    val status: UpiPaymentStatus = UpiPaymentStatus.INITIATED,
    val message: String = "",
    val responseCode: String? = null,
    val approvalRefNo: String? = null,
    val txnId: String? = null,
    val isSecurityDecline: Boolean = false,
    val requiresServerVerification: Boolean = false
) {
    val needsConfirmation: Boolean
        get() = launched && !cancelled && status == UpiPaymentStatus.INITIATED
}

/**
 * UPI payment service.
 *
 * This class:
 * - Validates VPAs, amounts, and NPCI URI parameters
 * - Parses UPI QR data (Standard upi://pay, BharatQR EMVCo, Web-wrapped) while preserving signatures
 * - Creates compliant upi://pay URLs and resolves installed UPI apps cleanly
 * - Validates and maps UPI Intent responses (SUCCESS, PENDING, FAILED, CANCELLED)
 * - Logs redacted diagnostics without exposing full VPAs, UTRs, PINs, or credentials
 */
object UpiService {

    private const val TAG = "ZenithUpiService"
    private const val UPI_SCHEME = "upi"
    private const val UPI_AUTHORITY = "pay"
    private val MAX_SINGLE_TXN_LIMIT = BigDecimal("100000.00")
    private val MAX_VERIFIED_MERCHANT_LIMIT = BigDecimal("500000.00")

    private val SECURITY_DECLINE_CODES = setOf("U16", "RM", "SEC", "RISK", "ZM", "XK")
    private val SUCCESS_RESPONSE_CODES = setOf("00", "0", "SUCCESS")
    private val INVALID_REF_LITERALS = setOf("", "null", "na", "n/a", "none", "undefined", "-")

    private fun logDebug(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Throwable) {
        }
    }

    private fun logWarn(msg: String) {
        try {
            Log.w(TAG, msg)
        } catch (_: Throwable) {
        }
    }

    /**
     * Masks a VPA for safe debug logging (e.g. "9876543210@ybl" -> "98***10@ybl").
     */
    fun maskVpa(vpa: String?): String {
        val clean = vpa?.trim().orEmpty()
        val atIdx = clean.indexOf('@')
        if (atIdx <= 0) return "***"
        val user = clean.substring(0, atIdx)
        val handle = clean.substring(atIdx)
        val maskedUser = when {
            user.length <= 2 -> "${user.first()}*"
            user.length <= 4 -> "${user.first()}**${user.last()}"
            else -> "${user.take(2)}***${user.takeLast(2)}"
        }
        return "$maskedUser$handle"
    }

    /**
     * Produces a redacted representation of a UPI payment URI safe for diagnostics.
     */
    fun sanitizeUriForLog(uri: Uri): String {
        val pa = uri.getQueryParameter("pa")
        val am = uri.getQueryParameter("am") ?: "-"
        val cu = uri.getQueryParameter("cu") ?: "-"
        val hasPn = !uri.getQueryParameter("pn").isNullOrBlank()
        val hasTn = !uri.getQueryParameter("tn").isNullOrBlank()
        val hasTr = !uri.getQueryParameter("tr").isNullOrBlank()
        val hasMc = !uri.getQueryParameter("mc").isNullOrBlank()
        val hasSign = !uri.getQueryParameter("sign").isNullOrBlank()
        return "${uri.scheme}://${uri.authority}?pa=${maskVpa(pa)}&am=$am&cu=$cu&pn=$hasPn&tn=$hasTn&tr=$hasTr&mc=$hasMc&sign=$hasSign"
    }

    /**
     * VPA validation.
     *
     * Examples:
     * someone@okhdfcbank
     * 9876543210@paytm
     * merchant@oksbi
     */
    private val VPA_REGEX = Regex(
        "^[a-zA-Z0-9][a-zA-Z0-9.\\-_+]{1,255}@[a-zA-Z0-9.\\-_]{2,64}$"
    )

    // ------------------------------------------------------------
    // VPA & REQUEST VALIDATION
    // ------------------------------------------------------------

    fun isValidVpa(vpa: String): Boolean {
        val trimmed = vpa.trim()
        if (!VPA_REGEX.matches(trimmed)) return false
        val local = trimmed.substringBefore("@")
        val handle = trimmed.substringAfter("@")
        if (local.endsWith(".") || local.endsWith("-")) return false
        if (handle.startsWith(".") || handle.endsWith(".") || handle.startsWith("-") || handle.endsWith("-")) return false
        return !trimmed.contains("..")
    }

    fun isStandardUpiAmountWithinLimit(amount: String): Boolean {
        val norm = normalizeAmount(amount) ?: return false
        return BigDecimal(norm) <= MAX_SINGLE_TXN_LIMIT
    }

    fun validatePaymentInfo(info: UpiPaymentInfo): UpiValidationResult {
        val cleanPa = info.payeeAddress.trim()
        if (!isValidVpa(cleanPa)) {
            return UpiValidationResult(
                isValid = false,
                errorMessage = "Enter a valid UPI ID (e.g. name@bank)."
            )
        }
        if (info.amount.isNotBlank()) {
            val normalized = normalizeAmount(info.amount)
                ?: return UpiValidationResult(
                    isValid = false,
                    errorMessage = "Enter a valid positive amount up to ₹5,00,000."
                )
            val decimal = BigDecimal(normalized)
            val maxAllowed = if (info.isVerifiedMerchantFormat) MAX_VERIFIED_MERCHANT_LIMIT else MAX_SINGLE_TXN_LIMIT
            if (decimal > maxAllowed) {
                return UpiValidationResult(
                    isValid = false,
                    errorMessage = if (info.isVerifiedMerchantFormat) {
                        "Amount exceeds the ₹5,00,000 UPI merchant transaction limit."
                    } else {
                        "Amount exceeds the standard ₹1,00,000 peer-to-peer UPI limit."
                    }
                )
            }
        }
        return UpiValidationResult(
            isValid = true,
            isP2pUnverifiedWarning = !info.isVerifiedMerchantFormat
        )
    }

    // ------------------------------------------------------------
    // AMOUNT & TEXT NORMALIZATION
    // ------------------------------------------------------------

    /**
     * Converts the entered amount into a standard 2-decimal format.
     *
     * Examples:
     *
     * "2"       -> "2.00"
     * "2.5"     -> "2.50"
     * "1,250"   -> "1250.00"
     * "100.75"  -> "100.75"
     */
    private fun normalizeAmount(amount: String): String? {
        val cleaned = amount
            .replace("₹", "")
            .replace("Rs.", "", ignoreCase = true)
            .replace("INR", "", ignoreCase = true)
            .replace(",", "")
            .trim()

        if (cleaned.isBlank()) {
            return null
        }

        return try {
            val value = BigDecimal(cleaned)
            if (value <= BigDecimal.ZERO || value > MAX_VERIFIED_MERCHANT_LIMIT) {
                null
            } else {
                value
                    .setScale(2, RoundingMode.HALF_UP)
                    .toPlainString()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun sanitizeTextParam(raw: String, maxLength: Int): String {
        return raw.trim()
            .filter { it.isLetterOrDigit() || it.isWhitespace() || it == '-' || it == '.' }
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.', '-')
            .take(maxLength)
            .trim()
    }

    private fun fallbackPayeeNameFromVpa(vpa: String): String {
        val prefix = vpa.trim().substringBefore("@")
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
        val cleaned = sanitizeTextParam(prefix, 50)
        return cleaned.ifBlank { "UPI Payee" }
    }

    private fun normalizeValidMcc(mc: String): String? {
        val cleaned = mc.trim()
        return if (cleaned.length == 4 && cleaned.all(Char::isDigit) && cleaned != "0000") {
            cleaned
        } else {
            null
        }
    }

    private fun sanitizeTxnRef(tr: String): String? {
        val cleaned = tr.trim()
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
            .take(35)
        return cleaned.ifBlank { null }
    }

    // ------------------------------------------------------------
    // BUILD UPI PAYMENT URI
    // ------------------------------------------------------------

    /**
     * Builds a compliant UPI URI:
     *
     * upi://pay?pa=...&pn=...&am=2.00&cu=INR&tn=...
     *
     * Note:
     * - Never injects synthetic `tr` on unverified P2P transfers.
     * - Always includes a sanitized mandatory `pn`.
     */
    fun buildPaymentUri(info: UpiPaymentInfo): Uri {
        val cleanPa = info.payeeAddress.trim()
        require(isValidVpa(cleanPa)) { "Invalid Payee UPI ID (VPA)" }

        val builder = Uri.Builder()
            .scheme(UPI_SCHEME)
            .authority(UPI_AUTHORITY)
            .appendQueryParameter("pa", cleanPa)

        // Mandatory Payee Name (NPCI spec: max 50 chars, clean alphanumeric + spaces/hyphens/dots)
        val cleanPn = sanitizeTextParam(info.payeeName, 50)
            .ifBlank { fallbackPayeeNameFromVpa(cleanPa) }
        builder.appendQueryParameter("pn", cleanPn)

        // Amount (positive decimal with 2 fraction digits)
        if (info.amount.isNotBlank()) {
            val normalizedAmount = normalizeAmount(info.amount)
            if (normalizedAmount != null) {
                builder.appendQueryParameter("am", normalizedAmount)
            }
        }

        // Currency (NPCI standard uppercase ISO code)
        val normalizedCurrency = info.currency.trim()
            .uppercase(Locale.ROOT)
            .takeIf { it.length == 3 && it.all(Char::isLetter) }
            ?: "INR"
        builder.appendQueryParameter("cu", normalizedCurrency)

        // Transaction note (sanitized max 80 chars; preserves intent without special characters that trigger PSP blocks)
        if (info.note.isNotBlank()) {
            val cleanNote = sanitizeTextParam(info.note, 80).ifBlank { "Payment" }
            builder.appendQueryParameter("tn", cleanNote)
        }

        // Transaction reference: ONLY include if explicitly supplied by merchant QR/order.
        // Never fabricate synthetic tr for P2P transfers.
        sanitizeTxnRef(info.txnRef)?.let { validTr ->
            builder.appendQueryParameter("tr", validTr)
        }

        // Merchant category code (4-digit non-0000 MCC)
        normalizeValidMcc(info.merchantCode)?.let { validMc ->
            builder.appendQueryParameter("mc", validMc)
        }

        val uri = builder.build()
        logDebug("Built UPI URI: ${sanitizeUriForLog(uri)}")
        return uri
    }

    /**
     * Normalizes only scheme/host ("upi://pay") and stops at line breaks without breaking
     * on literal spaces inside payee names or notes.
     */
    private fun extractNormalizedRawUpiUri(raw: String): String {
        val trimmed = raw.trim()
        val upiIndex = trimmed.indexOf("upi://pay", ignoreCase = true)
        if (upiIndex < 0) return trimmed
        val afterIndex = trimmed.substring(upiIndex)
        val endIdx = afterIndex.indexOfFirst { it == '\n' || it == '\r' }
        val cleanSegment = (if (endIdx > 0) afterIndex.substring(0, endIdx) else afterIndex).trim()
        return "upi://pay" + cleanSegment.substring("upi://pay".length)
    }

    /**
     * Builds a payment URI for a scanned QR code while preserving original encoded
     * cryptographic signatures (sign=...), orgid, mode, and merchant parameters byte-for-byte.
     */
    fun buildQrPaymentUri(rawUri: String, amount: Double?): Uri {
        val normalizedRaw = extractNormalizedRawUpiUri(rawUri)
        val parsedUri = Uri.parse(normalizedRaw)
        if (amount == null || amount <= 0.0 || amount.isNaN() || amount.isInfinite()) {
            logDebug("Built QR URI (as-is): ${sanitizeUriForLog(parsedUri)}")
            return parsedUri
        }

        val normalizedAmount = normalizeAmount(String.format(Locale.US, "%.2f", amount))
            ?: return parsedUri

        val qIndex = normalizedRaw.indexOf('?')
        if (qIndex < 0) {
            return parsedUri
        }

        val base = normalizedRaw.substring(0, qIndex)
        val rawQuery = normalizedRaw.substring(qIndex + 1)
        val rawPairs = rawQuery.split("&").filter { it.isNotBlank() }

        val existingAmRaw = rawPairs.firstOrNull { pair ->
            pair.substringBefore("=").equals("am", ignoreCase = true)
        }?.substringAfter("=", "")

        // If Dynamic QR already has the exact matching amount, return untouched to preserve sign= byte-for-byte
        if (existingAmRaw != null && normalizeAmount(existingAmRaw) == normalizedAmount) {
            logDebug("Preserving exact signed/dynamic QR URI: ${sanitizeUriForLog(parsedUri)}")
            return parsedUri
        }

        val hasSign = rawPairs.any { it.substringBefore("=").equals("sign", ignoreCase = true) }
        val isOverridingExplicitSignedAmount = hasSign &&
            existingAmRaw != null &&
            normalizeAmount(existingAmRaw) != normalizedAmount
        val updatedPairs = mutableListOf<String>()
        var amUpdated = false
        var hasCurrency = false

        for (pair in rawPairs) {
            val key = pair.substringBefore("=")
            if (key.equals("cu", ignoreCase = true)) {
                hasCurrency = true
            }
            if (isOverridingExplicitSignedAmount && key.equals("sign", ignoreCase = true)) {
                // Modifying a signed dynamic QR's non-empty amount invalidates the original cryptographic signature
                continue
            }
            if (key.equals("am", ignoreCase = true)) {
                updatedPairs.add("$key=$normalizedAmount")
                amUpdated = true
            } else {
                // Keep all other raw encoded params (sign, orgid, mode, pn, etc.) byte-identical
                updatedPairs.add(pair)
            }
        }
        if (!amUpdated) {
            updatedPairs.add("am=$normalizedAmount")
        }
        if (!hasCurrency && !hasSign) {
            updatedPairs.add("cu=INR")
        }

        val rebuiltUri = Uri.parse("$base?${updatedPairs.joinToString("&")}")
        logDebug("Built QR URI (updated amount, hasSign=$hasSign): ${sanitizeUriForLog(rebuiltUri)}")
        return rebuiltUri
    }

    // ------------------------------------------------------------
    // GENERIC PAYMENT INTENT
    // ------------------------------------------------------------

    /**
     * Checks whether a given app package can resolve the exact payment Intent.
     */
    fun isPackageCapableOfIntent(
        context: Context,
        packageName: String,
        intent: Intent
    ): Boolean {
        return try {
            val targeted = Intent(intent).setPackage(packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    targeted,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                ).isNotEmpty()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(targeted, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Creates a UPI payment Intent using an already built or preserved URI.
     * IMPORTANT: Never adds FLAG_ACTIVITY_NEW_TASK so StartActivityForResult receives the callback.
     */
    fun buildPayIntentWithUri(
        uri: Uri,
        targetPackage: String? = null,
        useChooser: Boolean = true,
        context: Context? = null
    ): Intent {
        if (targetPackage.isNullOrBlank()) {
            val baseIntent = Intent(Intent.ACTION_VIEW, uri)
            return if (useChooser) {
                Intent.createChooser(baseIntent, "Pay with any UPI App")
            } else {
                baseIntent
            }
        }

        val baseIntent = Intent(Intent.ACTION_VIEW, uri)
        val canHandlePackage = context?.let { isPackageCapableOfIntent(it, targetPackage, baseIntent) } ?: true
        return if (canHandlePackage) {
            Intent(Intent.ACTION_VIEW, uri).setPackage(targetPackage)
        } else {
            logWarn("Target UPI package $targetPackage cannot handle payment URI; falling back to chooser.")
            Intent.createChooser(baseIntent, "Pay with any UPI App")
        }
    }

    /**
     * Creates a UPI payment Intent.
     *
     * If targetPackage is empty:
     *     Shows the Android UPI app chooser.
     *
     * If targetPackage is provided:
     *     Opens that specific UPI application.
     */
    fun buildPayIntent(
        info: UpiPaymentInfo,
        targetPackage: String? = null,
        useChooser: Boolean = true,
        context: Context? = null
    ): Intent {
        val uri = buildPaymentUri(info)
        return buildPayIntentWithUri(uri, targetPackage, useChooser, context)
    }

    // ------------------------------------------------------------
    // GENERIC UPI CHOOSER
    // ------------------------------------------------------------

    /**
     * Opens the Android UPI app chooser.
     */
    fun buildGenericChooserIntent(
        info: UpiPaymentInfo,
        title: String = "Pay with any UPI App"
    ): Intent {
        val uri = buildPaymentUri(info)
        val intent = Intent(Intent.ACTION_VIEW, uri)
        return Intent.createChooser(intent, title)
    }

    // ------------------------------------------------------------
    // CHECK INSTALLED UPI APPS
    // ------------------------------------------------------------

    /**
     * Returns all installed applications that can handle:
     *
     * upi://pay
     */
    fun installedUpiApps(
        context: Context
    ): List<UpiApp> {
        val probe = Intent(Intent.ACTION_VIEW).setData(Uri.parse("upi://pay"))
        val pm = context.packageManager

        return try {
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    probe,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            }

            resolveInfos
                .mapNotNull { resolveInfo ->
                    val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                    if (packageName == context.packageName) return@mapNotNull null

                    val label = try {
                        resolveInfo.loadLabel(pm).toString().ifBlank {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                pm.getApplicationLabel(
                                    pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                                ).toString()
                            } else {
                                @Suppress("DEPRECATION")
                                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                            }
                        }
                    } catch (_: Exception) {
                        packageName
                    }

                    UpiApp(
                        packageName = packageName,
                        label = label
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase(Locale.ROOT) }
        } catch (e: Exception) {
            logWarn("Error querying installed UPI apps: ${e.message}")
            emptyList()
        }
    }

    /**
     * Checks whether at least one UPI application is installed.
     */
    fun isAnyUpiAppInstalled(
        context: Context
    ): Boolean {
        return installedUpiApps(context).isNotEmpty()
    }

    // ------------------------------------------------------------
    // QR CODE PARSER (Standard UPI, BharatQR EMVCo & Web-wrapped)
    // ------------------------------------------------------------

    /**
     * Parses TLV (Tag-Length-Value) encoded strings used in EMVCo and BharatQR specifications.
     */
    fun parseEmvCoTlv(data: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var index = 0
        while (index + 4 <= data.length) {
            val tag = data.substring(index, index + 2)
            val length = data.substring(index + 2, index + 4).toIntOrNull() ?: break
            index += 4
            if (index + length > data.length) break
            val value = data.substring(index, index + length)
            map[tag] = value
            index += length
        }
        return map
    }

    /**
     * Parses BharatQR (EMVCo specification) format commonly printed on Indian POS
     * terminals, card swipe machines, and retail soundboxes (starts with 000201...).
     */
    fun parseBharatQr(raw: String): UpiPaymentInfo? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("000201")) return null

        val tags = parseEmvCoTlv(trimmed)
        if (tags.isEmpty()) return null

        var vpa: String? = null
        var merchantCode = tags["52"]?.trim().orEmpty()

        // In NPCI BharatQR, tags 26 to 51 define merchant account information for UPI
        for (tagKey in listOf("26", "27", "28", "29", "30", "31")) {
            val merchantData = tags[tagKey] ?: continue
            val subTags = parseEmvCoTlv(merchantData)
            val candidateVpa = subTags["01"]
            if (!candidateVpa.isNullOrBlank() && isValidVpa(candidateVpa)) {
                vpa = candidateVpa.trim()
                if (merchantCode.isBlank()) {
                    merchantCode = subTags["02"]?.trim().orEmpty()
                }
                break
            }
            for (subVal in subTags.values) {
                if (isValidVpa(subVal)) {
                    vpa = subVal.trim()
                    break
                }
            }
            if (vpa != null) break
        }

        if (vpa == null) {
            for (value in tags.values) {
                val candidate = value.split(Regex("[\\s:?&=;,/|]")).firstOrNull { isValidVpa(it.trim()) }
                if (candidate != null) {
                    vpa = candidate.trim()
                    break
                }
            }
        }

        if (vpa.isNullOrBlank() || !isValidVpa(vpa)) return null

        val merchantName = tags["59"]?.trim().orEmpty()
        val rawAmount = tags["54"]?.trim().orEmpty()
        val amount = normalizeAmount(rawAmount) ?: ""
        val currency = when (tags["53"]?.trim()) {
            "356", null, "" -> "INR"
            else -> "INR"
        }

        var note = ""
        var ref = ""
        tags["62"]?.let { addl ->
            val addlTags = parseEmvCoTlv(addl)
            ref = addlTags["05"] ?: addlTags["01"] ?: ""
            note = addlTags["08"] ?: addlTags["03"] ?: ""
        }

        val cleanName = merchantName.ifBlank { "UPI Merchant" }
        val info = UpiPaymentInfo(
            payeeAddress = vpa,
            payeeName = cleanName,
            amount = amount,
            currency = currency,
            note = note,
            txnRef = ref,
            merchantCode = merchantCode
        )
        // Preserve full merchant metadata (mc, tr, tn) in rawUri
        val rawUri = buildPaymentUri(info).toString()
        return info.copy(rawUri = rawUri)
    }

    /**
     * Parses standard upi://pay URLs with query parameters.
     */
    fun parseUpiUrl(upiUrlString: String): UpiPaymentInfo? {
        if (!upiUrlString.contains("upi://pay", ignoreCase = true)) return null
        return try {
            val uriString = extractNormalizedRawUpiUri(upiUrlString)
            val uri = Uri.parse(uriString)
            val queryParamMap = mutableMapOf<String, String>()
            uri.queryParameterNames.forEach { name ->
                uri.getQueryParameter(name)?.let { value ->
                    queryParamMap[name.lowercase(Locale.ROOT)] = value
                }
            }

            val pa = queryParamMap["pa"]?.trim()
            if (!pa.isNullOrBlank() && isValidVpa(pa)) {
                val pn = (queryParamMap["pn"] ?: "").replace("+", " ").trim()
                val rawAm = (queryParamMap["am"] ?: "").replace(",", "").replace("₹", "").replace("Rs.", "").trim()
                val am = if (rawAm.isBlank()) "" else (normalizeAmount(rawAm) ?: rawAm)
                val cu = (queryParamMap["cu"] ?: "INR").uppercase(Locale.ROOT)
                val tn = (queryParamMap["tn"] ?: "").replace("+", " ").trim()
                val tr = queryParamMap["tr"]?.trim() ?: ""
                val mc = queryParamMap["mc"]?.trim() ?: ""
                UpiPaymentInfo(
                    payeeAddress = pa,
                    payeeName = pn,
                    amount = am,
                    currency = cu,
                    note = tn,
                    txnRef = tr,
                    merchantCode = mc,
                    rawUri = uriString
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses web-wrapped URLs (e.g. https://upiqr.in/..., https://pay.google.com/...)
     * that contain UPI parameters or percent-encoded UPI intents.
     */
    fun parseWebWrappedUpi(url: String): UpiPaymentInfo? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return null
        }
        return try {
            val uri = Uri.parse(trimmed)
            for (paramName in uri.queryParameterNames) {
                val paramVal = uri.getQueryParameter(paramName) ?: continue
                val decoded = try { URLDecoder.decode(paramVal, "UTF-8") } catch (_: Exception) { paramVal }
                if (decoded.contains("upi://pay", ignoreCase = true)) {
                    val embedded = parseUpiUrl(decoded)
                    if (embedded != null) return embedded
                }
            }

            val paramMap = mutableMapOf<String, String>()
            uri.queryParameterNames.forEach { key ->
                uri.getQueryParameter(key)?.let { value ->
                    paramMap[key.lowercase(Locale.ROOT)] = value
                }
            }
            val pa = paramMap["pa"]?.trim()
            if (!pa.isNullOrBlank() && isValidVpa(pa)) {
                val pn = (paramMap["pn"] ?: "").replace("+", " ").trim()
                val rawAm = paramMap["am"]?.replace(",", "")?.replace("₹", "")?.replace("Rs.", "")?.trim() ?: ""
                val am = if (rawAm.isBlank()) "" else (normalizeAmount(rawAm) ?: rawAm)
                val cu = (paramMap["cu"] ?: "INR").uppercase(Locale.ROOT)
                val tn = (paramMap["tn"] ?: "").replace("+", " ").trim()
                val tr = paramMap["tr"]?.trim() ?: ""
                val mc = paramMap["mc"]?.trim() ?: ""
                val info = UpiPaymentInfo(
                    payeeAddress = pa,
                    payeeName = pn,
                    amount = am,
                    currency = cu,
                    note = tn,
                    txnRef = tr,
                    merchantCode = mc
                )
                val rawUri = buildPaymentUri(info).toString()
                info.copy(rawUri = rawUri)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses raw QR content supporting:
     * 1. BharatQR (EMVCo standard)
     * 2. Standard upi://pay?... URLs
     * 3. Web-wrapped payment URLs (https://...)
     * 4. Embedded UPI links inside text
     * 5. Bare VPA
     */
    fun parseQrPayload(
        raw: String
    ): UpiPaymentInfo? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        // 1. BharatQR (EMVCo format starting with 000201)
        val bharatQrResult = parseBharatQr(trimmed)
        if (bharatQrResult != null) return bharatQrResult

        // 2. Standard UPI URI (upi://pay?...)
        val upiResult = parseUpiUrl(trimmed)
        if (upiResult != null) return upiResult

        // 3. Web-wrapped UPI URL (https://...)
        val webResult = parseWebWrappedUpi(trimmed)
        if (webResult != null) return webResult

        // 4. Search for VPA inside plain text
        if (trimmed.contains("@")) {
            val tokens = trimmed.split(Regex("[\\s:?&=;,/|]"))
            val candidate = tokens.firstOrNull { isValidVpa(it.trim()) }
            if (candidate != null) {
                return UpiPaymentInfo(
                    payeeAddress = candidate.trim(),
                    payeeName = "UPI Merchant"
                )
            }
        }

        // 5. Bare VPA
        return if (isValidVpa(trimmed)) {
            UpiPaymentInfo(payeeAddress = trimmed)
        } else {
            null
        }
    }

    // ------------------------------------------------------------
    // HANDLE UPI RESULT & GATEWAY VERIFICATION
    // ------------------------------------------------------------

    private fun cleanRefValue(value: String?): String? {
        val clean = value?.trim().orEmpty()
        return if (clean.lowercase(Locale.ROOT) in INVALID_REF_LITERALS) null else clean
    }

    /**
     * Handles the result returned from a UPI application.
     *
     * IMPORTANT:
     * - Never assumes SUCCESS solely from launching a UPI app or empty data.
     * - Validates Status alongside responseCode across both `response` string and Intent extras.
     * - Separates SUCCESSFUL, PENDING, FAILED (including bank/app security declines), CANCELLED, and INITIATED.
     */
    fun mapResult(
        resultCode: Int,
        data: Intent?
    ): UpiIntentResult {
        if (data == null) {
            logDebug("UPI return with resultCode=$resultCode and null Intent data")
            return UpiIntentResult(
                launched = true,
                cancelled = false,
                returnedTxnRef = null,
                status = UpiPaymentStatus.INITIATED,
                message = "Returned from UPI app. Please confirm whether the payment was completed."
            )
        }

        val rawResponseStr = data.getStringExtra("response")?.trim().orEmpty()
        val decodedResponseStr = if (rawResponseStr.contains("%3D", ignoreCase = true) || rawResponseStr.contains("%26", ignoreCase = true)) {
            try { URLDecoder.decode(rawResponseStr, "UTF-8") } catch (_: Exception) { rawResponseStr }
        } else {
            rawResponseStr
        }

        val params = decodedResponseStr
            .split("&")
            .mapNotNull { entry ->
                val parts = entry.split("=", limit = 2)
                if (parts.size == 2) {
                    parts[0].trim().lowercase(Locale.ROOT) to parts[1].trim()
                } else {
                    null
                }
            }
            .toMap()

        val statusStr = (
            params["status"]
                ?: data.getStringExtra("Status")
                ?: data.getStringExtra("status")
                ?: ""
            ).trim().lowercase(Locale.ROOT)

        val responseCode = (
            params["responsecode"]
                ?: data.getStringExtra("responseCode")
                ?: ""
            ).trim().uppercase(Locale.ROOT).ifBlank { null }

        val appMessage = (
            params["message"]
                ?: params["errormessage"]
                ?: data.getStringExtra("message")
                ?: data.getStringExtra("error")
                ?: ""
            ).trim()

        val securitySignalText = "$appMessage ${responseCode.orEmpty()} $decodedResponseStr".lowercase(Locale.ROOT)
        val isSecurityDecline = (responseCode != null && responseCode in SECURITY_DECLINE_CODES) ||
            securitySignalText.contains("security") ||
            securitySignalText.contains("risk") ||
            securitySignalText.contains("declined for security") ||
            securitySignalText.contains("policy")

        // Enforce responseCode consistency: never treat Status=SUCCESS with an error responseCode as SUCCESSFUL
        val hasValidSuccessCode = responseCode == null || responseCode in SUCCESS_RESPONSE_CODES

        val status = when {
            statusStr == "success" && hasValidSuccessCode ->
                UpiPaymentStatus.SUCCESSFUL

            statusStr == "submitted" || statusStr == "pending" || statusStr == "in_progress" ->
                UpiPaymentStatus.PENDING

            statusStr == "failed" || statusStr == "failure" || statusStr == "declined" || statusStr == "rejected" || statusStr == "error" ||
                (statusStr == "success" && !hasValidSuccessCode) ->
                UpiPaymentStatus.FAILED

            statusStr == "cancelled" || statusStr == "canceled" || statusStr == "aborted" ->
                UpiPaymentStatus.CANCELLED

            resultCode == Activity.RESULT_CANCELED ->
                UpiPaymentStatus.CANCELLED

            else ->
                UpiPaymentStatus.INITIATED
        }

        val approvalRefNo = cleanRefValue(params["approvalrefno"])
            ?: cleanRefValue(data.getStringExtra("ApprovalRefNo"))
            ?: cleanRefValue(data.extras?.getString("ApprovalRefNo"))

        val txnId = cleanRefValue(params["txnid"])
            ?: cleanRefValue(data.getStringExtra("txnId"))
            ?: cleanRefValue(data.extras?.getString("txnId"))

        val txnRef = cleanRefValue(params["txnref"])
            ?: cleanRefValue(data.extras?.getString("upitxnid"))
            ?: cleanRefValue(data.extras?.getString("txnRef"))
            ?: cleanRefValue(data.getStringExtra("upitxnid"))
            ?: cleanRefValue(data.getStringExtra("txnRef"))
            ?: approvalRefNo
            ?: txnId

        val message = when (status) {
            UpiPaymentStatus.SUCCESSFUL ->
                "Payment verified successfully!"

            UpiPaymentStatus.PENDING ->
                "Payment submitted and pending bank confirmation. Wait for bank confirmation before recording."

            UpiPaymentStatus.FAILED ->
                if (isSecurityDecline) {
                    "Declined by your UPI app or bank for security reasons (unsigned third-party P2P intent blocked). Open Google Pay / PhonePe directly and pay via mobile number, UPI ID, or QR scan."
                } else {
                    "Payment failed${if (appMessage.isNotBlank()) ": $appMessage" else " as reported by the UPI app."}"
                }

            UpiPaymentStatus.CANCELLED ->
                "Payment was cancelled."

            else ->
                "Returned from UPI app. Please confirm whether the payment was completed."
        }

        logDebug(
            "UPI result mapped: resultCode=$resultCode, status=$status, responseCode=${responseCode ?: "-"}, " +
                "securityDecline=$isSecurityDecline, hasRef=${txnRef != null}"
        )

        return UpiIntentResult(
            launched = true,
            cancelled = status == UpiPaymentStatus.CANCELLED,
            returnedTxnRef = txnRef,
            status = status,
            message = message,
            responseCode = responseCode,
            approvalRefNo = approvalRefNo,
            txnId = txnId,
            isSecurityDecline = isSecurityDecline
        )
    }

    /**
     * Enforces authenticated server-side / webhook verification for payment-gateway orders.
     * Never trusts client-side Intent extras alone when [flowType] == [PaymentFlowType.GATEWAY_ORDER].
     */
    fun enforceGatewayVerification(
        result: UpiIntentResult,
        flowType: PaymentFlowType,
        expectedOrderTxnRef: String?,
        verifier: UpiGatewayVerifier? = null
    ): UpiIntentResult {
        if (flowType != PaymentFlowType.GATEWAY_ORDER) {
            return result
        }
        val orderRef = expectedOrderTxnRef?.trim().orEmpty()
        if (orderRef.isBlank() || verifier == null) {
            return if (result.status == UpiPaymentStatus.SUCCESSFUL || result.status == UpiPaymentStatus.PENDING) {
                result.copy(
                    status = UpiPaymentStatus.PENDING,
                    requiresServerVerification = true,
                    message = "Gateway payment requires authenticated server-side / webhook verification before settlement."
                )
            } else {
                result.copy(requiresServerVerification = true)
            }
        }

        return when (verifier.verifyOrderStatus(orderRef, result.status)) {
            GatewayVerificationStatus.VERIFIED -> result.copy(
                status = UpiPaymentStatus.SUCCESSFUL,
                returnedTxnRef = result.returnedTxnRef ?: orderRef,
                requiresServerVerification = false,
                message = "Payment verified via authenticated server check."
            )
            GatewayVerificationStatus.PENDING_SERVER_VERIFICATION, GatewayVerificationStatus.NOT_APPLICABLE -> result.copy(
                status = UpiPaymentStatus.PENDING,
                requiresServerVerification = true,
                message = "Awaiting gateway webhook confirmation from server."
            )
            GatewayVerificationStatus.REJECTED -> result.copy(
                status = UpiPaymentStatus.FAILED,
                requiresServerVerification = false,
                message = "Gateway server verification rejected or did not confirm this transaction."
            )
        }
    }

    // ------------------------------------------------------------
    // UPI APP PACKAGE NAMES
    // ------------------------------------------------------------

    const val GOOGLE_PAY_PACKAGE =
        "com.google.android.apps.nbu.paisa.user"

    const val PHONEPE_PACKAGE =
        "com.phonepe.app"

    const val PAYTM_PACKAGE =
        "net.one97.paytm"

    const val BHIM_PACKAGE =
        "in.org.npci.upiapp"

    // ------------------------------------------------------------
    // GOOGLE PAY
    // ------------------------------------------------------------

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks whether Google Pay is installed.
     */
    fun isGooglePayInstalled(
        context: Context
    ): Boolean = isPackageInstalled(context, GOOGLE_PAY_PACKAGE)

    /**
     * Builds a Google Pay Intent.
     *
     * If Google Pay is installed and supports upi://pay:
     *     Opens Google Pay directly.
     *
     * Otherwise:
     *     Opens the normal UPI chooser / fallback intent.
     */
    fun buildGooglePayIntent(
        info: UpiPaymentInfo,
        context: Context? = null
    ): Intent {
        val uri = buildPaymentUri(info)
        val isGPayAvailable = context?.let {
            isPackageInstalled(it, GOOGLE_PAY_PACKAGE) &&
                isPackageCapableOfIntent(it, GOOGLE_PAY_PACKAGE, Intent(Intent.ACTION_VIEW, uri))
        } ?: true

        return if (isGPayAvailable) {
            Intent(Intent.ACTION_VIEW, uri).setPackage(GOOGLE_PAY_PACKAGE)
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }
    }

    // ------------------------------------------------------------
    // PHONEPE
    // ------------------------------------------------------------

    fun buildPhonePeIntent(
        info: UpiPaymentInfo,
        context: Context? = null
    ): Intent {
        val uri = buildPaymentUri(info)
        val isInstalled = context?.let {
            isPackageInstalled(it, PHONEPE_PACKAGE) &&
                isPackageCapableOfIntent(it, PHONEPE_PACKAGE, Intent(Intent.ACTION_VIEW, uri))
        } ?: true

        return if (isInstalled) {
            Intent(Intent.ACTION_VIEW, uri).setPackage(PHONEPE_PACKAGE)
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }
    }

    // ------------------------------------------------------------
    // PAYTM
    // ------------------------------------------------------------

    fun buildPaytmIntent(
        info: UpiPaymentInfo,
        context: Context? = null
    ): Intent {
        val uri = buildPaymentUri(info)
        val isInstalled = context?.let {
            isPackageInstalled(it, PAYTM_PACKAGE) &&
                isPackageCapableOfIntent(it, PAYTM_PACKAGE, Intent(Intent.ACTION_VIEW, uri))
        } ?: true

        return if (isInstalled) {
            Intent(Intent.ACTION_VIEW, uri).setPackage(PAYTM_PACKAGE)
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }
    }

    // ------------------------------------------------------------
    // INSTALLED STATUS HELPERS
    // ------------------------------------------------------------

    fun isPhonePeInstalled(context: Context): Boolean = isPackageInstalled(context, PHONEPE_PACKAGE)

    fun isPaytmInstalled(context: Context): Boolean = isPackageInstalled(context, PAYTM_PACKAGE)

    fun isBhimInstalled(context: Context): Boolean = isPackageInstalled(context, BHIM_PACKAGE)

    // ------------------------------------------------------------
    // UPI SCANNER INTENT LAUNCHER
    // ------------------------------------------------------------

    /**
     * Builds an Intent to launch a UPI app's scanner or payment interface.
     *
     * Supports:
     * - PhonePe: phonepe://scan deep link or launch intent
     * - Paytm: paytmmp://pay_flow?featuretype=scanner or launch intent
     * - Google Pay: upi://pay with GPay package or launch intent
     * - BHIM: upi://pay with BHIM package or launch intent
     * - Generic / Chooser: upi://pay chooser
     */
    fun buildUpiScannerIntent(
        context: Context,
        targetPackage: String? = null
    ): Intent {
        val pm = context.packageManager

        when (targetPackage) {
            PHONEPE_PACKAGE -> {
                // Try PhonePe scanner deep link
                try {
                    val scanUri = Uri.parse("phonepe://scan")
                    val scanIntent = Intent(Intent.ACTION_VIEW, scanUri).apply {
                        setPackage(PHONEPE_PACKAGE)
                    }
                    if (scanIntent.resolveActivity(pm) != null) {
                        return scanIntent
                    }
                } catch (e: Exception) {
                    // Fallback
                }

                try {
                    val qrUri = Uri.parse("phonepe://qr")
                    val qrIntent = Intent(Intent.ACTION_VIEW, qrUri).apply {
                        setPackage(PHONEPE_PACKAGE)
                    }
                    if (qrIntent.resolveActivity(pm) != null) {
                        return qrIntent
                    }
                } catch (e: Exception) {
                    // Fallback
                }

                pm.getLaunchIntentForPackage(PHONEPE_PACKAGE)?.let { return it }
            }
            PAYTM_PACKAGE -> {
                // Try Paytm scanner deep link
                try {
                    val paytmScanUri = Uri.parse("paytmmp://pay_flow?featuretype=scanner")
                    val paytmScanIntent = Intent(Intent.ACTION_VIEW, paytmScanUri).apply {
                        setPackage(PAYTM_PACKAGE)
                    }
                    if (paytmScanIntent.resolveActivity(pm) != null) {
                        return paytmScanIntent
                    }
                } catch (e: Exception) {
                    // Fallback
                }

                try {
                    val paytmWalletScanUri = Uri.parse("paytmmp://cash_wallet?featuretype=scanner")
                    val paytmWalletScanIntent = Intent(Intent.ACTION_VIEW, paytmWalletScanUri).apply {
                        setPackage(PAYTM_PACKAGE)
                    }
                    if (paytmWalletScanIntent.resolveActivity(pm) != null) {
                        return paytmWalletScanIntent
                    }
                } catch (e: Exception) {
                    // Fallback
                }

                pm.getLaunchIntentForPackage(PAYTM_PACKAGE)?.let { return it }
            }
            GOOGLE_PAY_PACKAGE -> {
                pm.getLaunchIntentForPackage(GOOGLE_PAY_PACKAGE)?.let { return it }
            }
            BHIM_PACKAGE -> {
                pm.getLaunchIntentForPackage(BHIM_PACKAGE)?.let { return it }
            }
            else -> {
                if (!targetPackage.isNullOrBlank()) {
                    pm.getLaunchIntentForPackage(targetPackage)?.let { return it }
                }
            }
        }

        // Target package explicitly provided? Try package scanner deep link / launch intent.
        // Generic (null) -> show Android UPI app chooser across installed UPI apps.
        if (targetPackage == null) {
            val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay"))
            return Intent.createChooser(genericIntent, "Open UPI App")
        }

        // Direct fallback if launcher exists for requested package
        val installed = installedUpiApps(context)
        val firstApp = installed.firstOrNull { it.packageName == targetPackage }
        if (firstApp != null) {
            pm.getLaunchIntentForPackage(firstApp.packageName)?.let { return it }
        }

        val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay"))
        return Intent.createChooser(genericIntent, "Open UPI App")
    }

    private const val UPI_PREFS_NAME = "zenith_upi_prefs"
    private const val KEY_DEFAULT_UPI_PACKAGE = "default_upi_package"

    fun getDefaultUpiAppPackage(context: Context): String? {
        val pkg = context.getSharedPreferences(UPI_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT_UPI_PACKAGE, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return if (isPackageInstalled(context, pkg)) pkg else null
    }

    fun setDefaultUpiAppPackage(context: Context, packageName: String?) {
        context.getSharedPreferences(UPI_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (packageName.isNullOrBlank()) remove(KEY_DEFAULT_UPI_PACKAGE)
                else putString(KEY_DEFAULT_UPI_PACKAGE, packageName.trim())
            }
            .apply()
    }

    // ------------------------------------------------------------
    // PLAY STORE FALLBACK
    // ------------------------------------------------------------

    /**
     * Opens the Play Store page of a UPI app.
     */
    fun marketUri(
        packageName: String
    ): Uri {

        return Uri.parse(
            "market://details?id=$packageName"
        )
    }

    // ------------------------------------------------------------
    // SYSTEM SETTINGS
    // ------------------------------------------------------------

    fun systemSettingsIntent(): Intent {

        return Intent(
            Settings.ACTION_SETTINGS
        )
    }

    // ------------------------------------------------------------
    // DEBUG HELPER
    // ------------------------------------------------------------

    /**
     * Useful for debugging.
     *
     * Example output:
     *
     * UPI URI:
     * upi://pay?pa=xxx%40ybl&pn=ARUN&am=2.00&cu=INR
     */
    fun getDebugPaymentUri(
        info: UpiPaymentInfo
    ): String {

        return buildPaymentUri(info).toString()
    }
}

/**
 * Represents an installed UPI application.
 */
data class UpiApp(
    val packageName: String,
    val label: String
)