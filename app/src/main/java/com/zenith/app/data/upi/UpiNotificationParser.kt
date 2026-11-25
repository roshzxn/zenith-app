package com.zenith.app.data.upi

import java.util.regex.Pattern

/**
 * Robust, deterministic pattern matcher for extracting UPI transaction details
 * from Android notification titles and text emitted by UPI applications and Indian banks.
 */
object UpiNotificationParser {

    val MONITORED_PACKAGES = mapOf(
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "com.google.android.apps.nbu.paisa.merchant" to "Google Pay Business",
        "com.phonepe.app" to "PhonePe",
        "com.phonepe.app.business" to "PhonePe Business",
        "net.one97.paytm" to "Paytm",
        "com.paytm.business" to "Paytm Business",
        "com.dreamplug.androidapp" to "CRED",
        "in.org.npci.upiapp" to "BHIM",
        "com.amazon.mShop.android.shopping" to "Amazon Pay",
        "in.amazon.mShop.android.shopping" to "Amazon Pay",
        "com.whatsapp" to "WhatsApp Pay",
        "com.naviapp" to "Navi UPI",
        "com.mobikwik_new" to "MobiKwik",
        "com.freecharge.android" to "Freecharge",
        "money.super.upi" to "super.money",
        "money.jupiter" to "Jupiter",
        "com.epi.fi" to "Fi Money",
        "indwin.c3.shareapp" to "slice",
        "in.indwealth" to "INDmoney",
        // Indian Banking & Card Apps
        "com.snapwork.hdfc" to "HDFC Bank",
        "com.hdfcbank.hdfcbankapp" to "HDFC Bank",
        "com.enstage.wibmo.hdfc" to "PayZapp",
        "com.sbi.lotusintouch" to "SBI YONO",
        "com.sbi.SBIFreedomPlus" to "SBI YONO Lite",
        "com.csam.icici.bank.imobile" to "iMobile Pay",
        "com.axis.mobile" to "Axis Mobile",
        "com.msf.kbank.mobile" to "Kotak Bank",
        "com.kotak.bank.mobile" to "Kotak811",
        "com.bankofbaroda.mconnect" to "BOB World",
        "com.pnb.pnbone" to "PNB One",
        "com.canarabank.ai1" to "Canara ai1",
        "com.fedmobile" to "FedMobile",
        // SMS / Messaging apps (strictly gated on bank/UPI SMS transaction structure)
        "com.google.android.apps.messaging" to "SMS Alert",
        "com.android.mms" to "SMS Alert",
        "com.samsung.android.messaging" to "SMS Alert",
        "com.oneplus.mms" to "SMS Alert",
        "com.coloros.mms" to "SMS Alert"
    )

    private val SMS_PACKAGES = setOf(
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.samsung.android.messaging",
        "com.oneplus.mms",
        "com.coloros.mms"
    )

    // Regex for matching currency amounts: ₹350, Rs. 350.00, Rs 500, INR 1,200.50, debited by 250, transfer of 500
    private val AMOUNT_REGEX = Pattern.compile(
        """(?:(?:₹|Rs\.?|INR)\s*|(?:(?:debited|credited)\s+(?:by|for|with|of)?\s*(?:₹|Rs\.?|INR)?\s*)|(?:(?:paid|sent|received|transfer of|payment of|spent)\s+(?:₹|Rs\.?|INR)?\s*))([0-9]{1,3}(?:,[0-9]{2,3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)""",
        Pattern.CASE_INSENSITIVE
    )

    // Regex for matching 12-digit UPI reference numbers / UTR (handles dots/hashes/colons after No/Ref/ID)
    private val UTR_REGEX = Pattern.compile(
        """\b(?:UPI\s*Ref(?:\s*No|\s*ID)?|UTR(?:\s*No)?|Txn\s*ID|Transaction\s*ID|Ref(?:\s*No|\s*ID)?|rrn|UPI)[\s:./#-]*([0-9]{12})\b""",
        Pattern.CASE_INSENSITIVE
    )

    // Standalone 12-digit sequence when delimited in bank SMS
    private val STANDALONE_UTR_REGEX = Pattern.compile(
        """[/:\-]([0-9]{12})(?:[/:\-\s]|$)"""
    )

    // Regex for matching UPI VPAs: example@okhdfcbank
    private val VPA_REGEX = Pattern.compile(
        """\b([a-zA-Z0-9][a-zA-Z0-9.\-_+]{1,255}@[a-zA-Z0-9.\-_]{2,64})\b"""
    )

    private val GENERIC_TITLES = setOf(
        "google pay", "gpay", "phonepe", "paytm", "cred", "bhim", "amazon pay", "whatsapp pay",
        "payzapp", "hdfc bank", "hdfc bank alert", "sbi alert", "sbi yono", "icici bank",
        "axis mobile", "kotak bank", "payment successful", "money sent", "money received",
        "payment alert", "transaction alert", "bank alert", "alert", "sms alert", "messages",
        "upi payment", "notification"
    )

    private val OTP_ISSUING_REGEX = Regex(
        """\b(?:is your otp|otp is|otp for|use otp|enter otp|one[- ]time password|verification code)\b""",
        RegexOption.IGNORE_CASE
    )

    private val SCHEDULED_OR_REQUEST_REGEX = Regex(
        """\b(?:will be debited|yet to be debited|requested money|has requested|request from|payment request|collect request|mandate created|upcoming auto-?pay|login alert|transaction failed|payment failed|unsuccessful)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Checks whether a package is a supported UPI, banking, or SMS alert app.
     */
    fun isSupportedPackage(packageName: String): Boolean {
        return MONITORED_PACKAGES.containsKey(packageName)
    }

    /**
     * Parses notification title and text, returning an ExtractedUpiPayment if
     * a valid debit/credit transaction is detected.
     */
    fun parse(packageName: String, title: String?, text: String?): ExtractedUpiPayment? {
        if (!isSupportedPackage(packageName)) return null

        val combined = "${title.orEmpty()} ${text.orEmpty()}".trim()
        if (combined.isBlank()) return null

        // Ignore OTPs, login alerts, requests to pay, failed txns, and promotional banners
        if (isNonTransactionNotification(combined)) return null

        // SMS apps handle personal chats too: require explicit bank/UPI transaction markers
        if (packageName in SMS_PACKAGES && !isLikelyFinancialSms(combined)) return null

        val appName = resolveSourceApp(packageName, combined)

        // Detect if transaction is debit or credit
        val isDebit = isDebitTransaction(combined)

        // 1. Extract Amount
        val amount = extractAmount(combined) ?: return null
        if (amount <= 0.0) return null

        // 2. Extract UTR / Reference Number
        val utr = extractUtr(combined)

        // 3. Extract VPA (if present)
        val vpa = extractVpa(combined)

        // 4. Extract Payee / Merchant Name
        val payee = extractPayee(combined, title, text, vpa)

        // 5. Smart Category Prediction (learned merchant/VPA rules take priority)
        val category = predictCategory(payee, combined, vpa)

        return ExtractedUpiPayment(
            amount = amount,
            payeeName = payee,
            upiId = vpa,
            upiTransactionId = utr,
            sourceApp = appName,
            timestamp = System.currentTimeMillis(),
            detectedCategory = category,
            rawText = combined,
            isDebit = isDebit
        )
    }

    private fun resolveSourceApp(packageName: String, combined: String): String {
        val mapped = MONITORED_PACKAGES[packageName] ?: "UPI"
        if (packageName !in SMS_PACKAGES) return mapped

        val lower = combined.lowercase()
        return when {
            lower.contains("hdfc") -> "HDFC Bank"
            lower.contains("sbi") -> "SBI"
            lower.contains("icici") -> "ICICI Bank"
            lower.contains("axis") -> "Axis Bank"
            lower.contains("kotak") -> "Kotak Bank"
            lower.contains("bob") || lower.contains("baroda") -> "BOB Bank"
            lower.contains("pnb") -> "PNB Bank"
            lower.contains("canara") -> "Canara Bank"
            lower.contains("federal") -> "Federal Bank"
            lower.contains("yes bank") -> "Yes Bank"
            lower.contains("idfc") -> "IDFC Bank"
            lower.contains("indusind") -> "IndusInd Bank"
            else -> "UPI SMS"
        }
    }

    private fun isLikelyFinancialSms(text: String): Boolean {
        val lower = text.lowercase()
        return containsAny(
            lower,
            "a/c", "acct", "account", "upi", "vpa", "utr", "rrn",
            "imps", "neft", "rtgs", "debited", "credited", "avl bal",
            "avbl bal", "txn", "card xx", "ending "
        )
    }

    private fun isNonTransactionNotification(text: String): Boolean {
        val lower = text.lowercase()
        if (SCHEDULED_OR_REQUEST_REGEX.containsMatchIn(lower)) return true

        // Always reject actual OTP authorization prompts ("Your OTP is 849201", "Enter OTP ... to pay ₹500")
        if (OTP_ISSUING_REGEX.containsMatchIn(lower)) return true

        val hasCompletedTransactionSignal = containsAny(
            lower,
            "paid", "debited", "sent", "transferred", "spent",
            "payment of", "payment to", "payment at", "credited", "received"
        )

        // Allow bank footers like "Never share OTP/PIN" only when paired with a completed txn signal
        if (!hasCompletedTransactionSignal) {
            if (containsAny(lower, "otp", "offer", "cashback", "discount", "reminder")) {
                return true
            }
        }
        return false
    }

    private fun isDebitTransaction(text: String): Boolean {
        val lower = text.lowercase().replace("non-refundable", "")
        // Incoming transfers in GPay/PhonePe can read "X paid you ₹500" or "X sent you ₹500"
        val creditRegex = Regex(
            """\b(?:paid you|sent you|received|credited|refunded|cashback credited)\b""",
            RegexOption.IGNORE_CASE
        )
        return !creditRegex.containsMatchIn(lower)
    }

    fun extractAmount(text: String): Double? {
        val matcher = AMOUNT_REGEX.matcher(text)
        if (matcher.find()) {
            val rawAmount = matcher.group(1)?.replace(",", "") ?: return null
            return rawAmount.toDoubleOrNull()
        }
        return null
    }

    fun extractUtr(text: String): String? {
        val matcher = UTR_REGEX.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)
        }
        val standalone = STANDALONE_UTR_REGEX.matcher(text)
        if (standalone.find()) {
            return standalone.group(1)
        }
        return null
    }

    fun extractVpa(text: String): String? {
        val matcher = VPA_REGEX.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)
        }
        return null
    }

    fun extractPayee(combined: String, title: String?, text: String?, vpa: String?): String {
        val patterns = listOf(
            // "UPI/424598123456/Uber"
            Pattern.compile("""(?:UPI|Ref|rrn)[/:][0-9]+[/:\s]+([^/,\n(]+?)(?=\s*(?:\.\s|\.$|$|[/,\n(]))""", Pattern.CASE_INSENSITIVE),
            // "Paid to XYZ" / "Paid ₹350 to XYZ" / "Payment of ₹500 to XYZ"
            Pattern.compile("""(?:paid(?:\s+[₹Rs\.]*[0-9,.]+)?|payment of\s+[₹Rs\.]*[0-9,.]*|sent\s+[₹Rs\.]*[0-9,.]*)\s+to\s+([^,\n(]+?)(?=\s*(?:\.\s+(?:UPI|Ref|UTR|RRN|Not|If|Call|Avl|Bal|For|Your|Never|Please)|\.$|$|[,\n(]))""", Pattern.CASE_INSENSITIVE),
            // "Money sent to XYZ" / "trf to XYZ" / "transferred to XYZ"
            Pattern.compile("""(?:money sent|transferred|trf)\s+to\s+([^,\n(]+?)(?=\s*(?:\.\s+(?:UPI|Ref|UTR|RRN|Not|If|Call|Avl|Bal|For|Your|Never|Please)|\.$|$|[,\n(]))""", Pattern.CASE_INSENSITIVE),
            // "debited ... via UPI to XYZ" / "to VPA XYZ" / "To XYZ"
            Pattern.compile("""(?:via upi to|to VPA|to)\s+([^,\n(]+?)(?=\s*(?:\.\s+(?:UPI|Ref|UTR|RRN|Not|If|Call|Avl|Bal|For|Your|Never|Please)|\.$|$|[,\n(]))""", Pattern.CASE_INSENSITIVE),
            // "Payment at XYZ"
            Pattern.compile("""payment at\s+([^,\n(]+?)(?=\s*(?:\.\s|\.$|$|[,\n(]))""", Pattern.CASE_INSENSITIVE),
            // "Received from XYZ" / "You received ₹500 from XYZ"
            Pattern.compile("""received(?:\s+[₹Rs\.]*[0-9,.]+)?\s+from\s+([^,\n(]+?)(?=\s*(?:\.\s|\.$|$|[,\n(]))""", Pattern.CASE_INSENSITIVE),
            // "Ananya has sent you ₹500" / "Ramesh paid you ₹500"
            Pattern.compile("""([a-zA-Z][a-zA-Z0-9 .&'-]{1,40}?)\s+(?:has\s+)?(?:sent|paid)\s+you\b""", Pattern.CASE_INSENSITIVE)
        )

        val sourcesToScan = listOfNotNull(text?.takeIf { it.isNotBlank() }, combined)
        for (source in sourcesToScan) {
            for (pattern in patterns) {
                val matcher = pattern.matcher(source)
                if (matcher.find()) {
                    val candidate = matcher.group(1)?.trim() ?: ""
                    if (candidate.contains("@")) continue
                    val cleaned = cleanPayeeName(candidate)
                    if (cleaned.isNotBlank() && !isGenericPayeeToken(cleaned) && cleaned.lowercase() !in GENERIC_TITLES) {
                        return cleaned
                    }
                }
            }
        }

        // Prefer a merchant-like notification title (e.g., GPay puts merchant name in title and "Paid ₹150.00" in text)
        val trimmedTitle = title?.trim().orEmpty()
        if (trimmedTitle.isNotBlank() &&
            !trimmedTitle.contains("₹") &&
            !trimmedTitle.contains("Paid", ignoreCase = true) &&
            !trimmedTitle.contains("Sent", ignoreCase = true) &&
            !trimmedTitle.contains("Received", ignoreCase = true) &&
            trimmedTitle.lowercase() !in GENERIC_TITLES &&
            !looksLikeSmsSenderId(trimmedTitle)
        ) {
            val cleanedTitle = cleanPayeeName(trimmedTitle)
            if (cleanedTitle.isNotBlank()) return cleanedTitle
        }

        // Fallback to VPA prefix
        if (!vpa.isNullOrBlank()) {
            val prefix = vpa.substringBefore("@").replace(".", " ").trim()
            if (prefix.isNotBlank()) {
                return prefix.replaceFirstChar { it.uppercase() }
            }
        }

        return "Merchant"
    }

    private fun looksLikeSmsSenderId(title: String): Boolean {
        return Regex("""^[A-Z]{2}-[A-Z0-9]{4,9}$""").matches(title.uppercase())
    }

    private fun isGenericPayeeToken(value: String): Boolean {
        return value.lowercase() in setOf("vpa", "upi", "bank", "account", "a/c", "you", "merchant")
    }

    private fun cleanPayeeName(raw: String): String {
        return raw
            .replace(Regex("""\s+(?:on\s+[0-9]|successfully|successful|via|from|using|with|upi|ref\s*no|refno|ref|utr|rrn|txn|for|debited|credited|not\s+you).*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[0-9]{10,}"""), "") // Remove phone numbers or account numbers
            .replace(Regex("""[^\w\s.&'-]"""), "") // Keep periods for "Dr. Sharma" or initials
            .trim(' ', '.', '-', ':')
            .takeIf { it.length in 2..50 } ?: ""
    }

    fun predictCategory(payee: String, fullText: String, vpa: String? = null): String {
        MerchantCategoryRules.resolve(payee, vpa)?.let { learned ->
            return learned
        }
        val text = "$payee $fullText".lowercase()

        return when {
            // Food & Dining
            containsAny(text, "swiggy", "zomato", "starbucks", "mcdonald", "kfc", "domino", "pizza", "burger", "chai", "tea", "coffee", "cafe", "bistro", "restaurant", "food", "bakery", "bakes", "hotel", "biryani", "dhaba", "sweets", "kitchen", "canteen") -> "Food & Dining"

            // Transportation
            containsAny(text, "uber", "ola", "rapido", "metro", "petrol", "fuel", "diesel", "indian oil", "bharat petroleum", "shell", "hpcl", "iocl", "irctc", "rail", "flight", "indigo", "air india", "toll", "fastag", "parking", "auto") -> "Transportation"

            // Shopping & Groceries
            containsAny(text, "amazon", "flipkart", "myntra", "meesho", "zara", "h&m", "ajio", "nykaa", "bigbasket", "blinkit", "zepto", "instamart", "freshmart", "supermarket", "grocery", "provision", "mart", "store", "mall", "fashion", "retail") -> "Shopping"

            // Bills & Utilities
            containsAny(text, "bescom", "tneb", "electricity", "water", "gas", "cylinder", "airtel", "jio", "vi", "vodafone", "bsnl", "broadband", "wi-fi", "wifi", "fiber", "recharge", "dth", "tata sky", "sun direct", "dish tv", "postpaid", "bill") -> "Bills & Utilities"

            // Entertainment
            containsAny(text, "bookmyshow", "pvr", "inox", "cinema", "movies", "theatre", "netflix", "prime video", "spotify", "hotstar", "youtube", "gaming", "steam", "playstation") -> "Entertainment"

            // Healthcare & Medicine
            containsAny(text, "apollo", "medplus", "pharmacy", "chemist", "hospital", "clinic", "doctor", "dr.", "medical", "medicos", "dental", "diagnostics", "lab", "1mg", "pharmeasy", "netmeds", "health") -> "Healthcare"

            // Investments & Transfers
            containsAny(text, "zerodha", "groww", "angelone", "upstox", "mutual fund", "sip", "deposit", "investment", "gold", "share") -> "Investments"

            else -> "Other"
        }
    }

    private fun containsAny(source: String, vararg keywords: String): Boolean {
        return keywords.any { source.contains(it) }
    }
}
