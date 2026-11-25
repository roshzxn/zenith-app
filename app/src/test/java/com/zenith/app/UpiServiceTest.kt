package com.zenith.app

import android.app.Activity
import android.content.Intent
import com.zenith.app.data.upi.GatewayVerificationStatus
import com.zenith.app.data.upi.PaymentFlowType
import com.zenith.app.data.upi.UpiPaymentBus
import com.zenith.app.data.upi.UpiPaymentInfo
import com.zenith.app.data.upi.UpiPaymentStatus
import com.zenith.app.data.upi.UpiService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpiServiceTest {

    // ------------------------------------------------------------
    // VPA VALIDATION
    // ------------------------------------------------------------

    @Test
    fun testValidVpaFormats() {

        assertTrue(
            UpiService.isValidVpa(
                "someone@okhdfcbank"
            )
        )

        assertTrue(
            UpiService.isValidVpa(
                "9876543210@paytm"
            )
        )

        assertTrue(
            UpiService.isValidVpa(
                "user.name@okaxis"
            )
        )

        assertTrue(
            UpiService.isValidVpa(
                "store_name-1@icici"
            )
        )

        assertTrue(
            UpiService.isValidVpa(
                "merchant@apl"
            )
        )

        assertTrue(
            UpiService.isValidVpa(
                "john+zenith@upi"
            )
        )

        // Invalid VPAs
        assertFalse(
            UpiService.isValidVpa("")
        )

        assertFalse(
            UpiService.isValidVpa(
                "invalid-vpa-without-at"
            )
        )

        assertFalse(
            UpiService.isValidVpa(
                "@bank"
            )
        )

        assertFalse(
            UpiService.isValidVpa(
                "user@"
            )
        )
    }

    // ------------------------------------------------------------
    // STANDARD UPI QR PARSING
    // ------------------------------------------------------------

    @Test
    fun testStandardUpiQrParsing() {

        val payload =
            "upi://pay?" +
            "pa=merchant@okhdfcbank" +
            "&pn=SuperMarket" +
            "&am=250.50" +
            "&cu=INR" +
            "&tn=Groceries" +
            "&tr=TXN12345"

        val parsed =
            UpiService.parseQrPayload(payload)

        assertNotNull(parsed)

        assertEquals(
            "merchant@okhdfcbank",
            parsed?.payeeAddress
        )

        assertEquals(
            "SuperMarket",
            parsed?.payeeName
        )

        assertEquals(
            "250.50",
            parsed?.amount
        )

        assertEquals(
            "INR",
            parsed?.currency
        )

        assertEquals(
            "Groceries",
            parsed?.note
        )

        assertEquals(
            "TXN12345",
            parsed?.txnRef
        )
    }

    // ------------------------------------------------------------
    // UPPERCASE / MIXED CASE QR
    // ------------------------------------------------------------

    @Test
    fun testUppercaseAndMixedCaseUpiQrParsing() {

        val payload =
            "UPI://PAY?" +
            "PA=store@paytm" +
            "&PN=Retail+Store" +
            "&AM=100.00" +
            "&CU=INR" +
            "&TN=Payment"

        val parsed =
            UpiService.parseQrPayload(payload)

        assertNotNull(parsed)

        assertEquals(
            "store@paytm",
            parsed?.payeeAddress
        )

        assertEquals(
            "Retail Store",
            parsed?.payeeName
        )

        assertEquals(
            "100.00",
            parsed?.amount
        )

        assertEquals(
            "INR",
            parsed?.currency
        )
    }

    // ------------------------------------------------------------
    // BARE VPA
    // ------------------------------------------------------------

    @Test
    fun testBareVpaParsing() {

        val parsed =
            UpiService.parseQrPayload(
                "grocery.store@okaxis"
            )

        assertNotNull(parsed)

        assertEquals(
            "grocery.store@okaxis",
            parsed?.payeeAddress
        )

        assertEquals(
            "",
            parsed?.amount
        )
    }

    // ------------------------------------------------------------
    // EMBEDDED UPI URL
    // ------------------------------------------------------------

    @Test
    fun testEmbeddedUpiUrlInText() {

        val payload =
            "Please pay using this link: " +
            "upi://pay?" +
            "pa=canteen@icici" +
            "&am=40.00" +
            "&pn=Campus+Canteen"

        val parsed =
            UpiService.parseQrPayload(payload)

        assertNotNull(parsed)

        assertEquals(
            "canteen@icici",
            parsed?.payeeAddress
        )

        assertEquals(
            "40.00",
            parsed?.amount
        )

        assertEquals(
            "Campus Canteen",
            parsed?.payeeName
        )
    }

    // ------------------------------------------------------------
    // INVALID PAYLOAD
    // ------------------------------------------------------------

    @Test
    fun testInvalidPayloadReturnsNull() {

        assertNull(
            UpiService.parseQrPayload("")
        )

        assertNull(
            UpiService.parseQrPayload("   ")
        )

        assertNull(
            UpiService.parseQrPayload(
                "https://google.com"
            )
        )

        assertNull(
            UpiService.parseQrPayload(
                "Hello World 123"
            )
        )
    }

    // ------------------------------------------------------------
    // BUILD PAYMENT URI
    // ------------------------------------------------------------

    @Test
    fun testBuildPaymentUri() {

        val info =
            UpiPaymentInfo(
                payeeAddress =
                    "friend@okhdfcbank",

                payeeName =
                    "Friend",

                amount =
                    "500",

                note =
                    "Dinner split"
            )

        val uri =
            UpiService.buildPaymentUri(info)

        // Basic URI checks
        assertEquals(
            "upi",
            uri.scheme
        )

        assertEquals(
            "pay",
            uri.authority
        )

        // Payee
        assertEquals(
            "friend@okhdfcbank",
            uri.getQueryParameter("pa")
        )

        // Name
        assertEquals(
            "Friend",
            uri.getQueryParameter("pn")
        )

        // IMPORTANT:
        // 500 is normalized to 500.00
        assertEquals(
            "500.00",
            uri.getQueryParameter("am")
        )

        // Currency
        assertEquals(
            "INR",
            uri.getQueryParameter("cu")
        )

        // Note
        assertEquals(
            "Dinner split",
            uri.getQueryParameter("tn")
        )
    }

    // ------------------------------------------------------------
    // AMOUNT NORMALIZATION
    // ------------------------------------------------------------

    @Test
    fun testAmountNormalization() {

        // Integer amount
        val info1 =
            UpiPaymentInfo(
                payeeAddress =
                    "test@okaxis",
                amount =
                    "2"
            )

        val uri1 =
            UpiService.buildPaymentUri(info1)

        assertEquals(
            "2.00",
            uri1.getQueryParameter("am")
        )

        // One decimal
        val info2 =
            UpiPaymentInfo(
                payeeAddress =
                    "test@okaxis",
                amount =
                    "2.5"
            )

        val uri2 =
            UpiService.buildPaymentUri(info2)

        assertEquals(
            "2.50",
            uri2.getQueryParameter("am")
        )

        // Already two decimals
        val info3 =
            UpiPaymentInfo(
                payeeAddress =
                    "test@okaxis",
                amount =
                    "25.75"
            )

        val uri3 =
            UpiService.buildPaymentUri(info3)

        assertEquals(
            "25.75",
            uri3.getQueryParameter("am")
        )

        // Large amount
        val info4 =
            UpiPaymentInfo(
                payeeAddress =
                    "test@okaxis",
                amount =
                    "1000"
            )

        val uri4 =
            UpiService.buildPaymentUri(info4)

        assertEquals(
            "1000.00",
            uri4.getQueryParameter("am")
        )
    }

    // ------------------------------------------------------------
    // ZERO / NEGATIVE AMOUNT
    // ------------------------------------------------------------

    @Test
    fun testInvalidAmountIsNotAdded() {

        val zeroInfo =
            UpiPaymentInfo(
                payeeAddress =
                    "test@okaxis",
                amount =
                    "0"
            )

        val zeroUri =
            UpiService.buildPaymentUri(zeroInfo)

        assertNull(
            zeroUri.getQueryParameter("am")
        )

        val negativeInfo =
            UpiPaymentInfo(
                payeeAddress =
                    "test@okaxis",
                amount =
                    "-10"
            )

        val negativeUri =
            UpiService.buildPaymentUri(
                negativeInfo
            )

        assertNull(
            negativeUri.getQueryParameter("am")
        )
    }

    // ------------------------------------------------------------
    // EMPTY AMOUNT
    // ------------------------------------------------------------

    @Test
    fun testEmptyAmountIsAllowed() {

        val info =
            UpiPaymentInfo(
                payeeAddress =
                    "test@okaxis",
                amount =
                    ""
            )

        val uri =
            UpiService.buildPaymentUri(info)

        assertNull(
            uri.getQueryParameter("am")
        )

        assertEquals(
            "test@okaxis",
            uri.getQueryParameter("pa")
        )

        assertEquals(
            "INR",
            uri.getQueryParameter("cu")
        )
    }

    // ------------------------------------------------------------
    // PAYMENT URI WITH ALL PARAMETERS
    // ------------------------------------------------------------

    @Test
    fun testBuildPaymentUriWithAllParameters() {

        val info =
            UpiPaymentInfo(
                payeeAddress =
                    "merchant@okaxis",

                payeeName =
                    "Test Merchant",

                amount =
                    "99.5",

                currency =
                    "INR",

                note =
                    "Test payment",

                txnRef =
                    "REF123",

                merchantCode =
                    "5411"
            )

        val uri =
            UpiService.buildPaymentUri(info)

        assertEquals(
            "merchant@okaxis",
            uri.getQueryParameter("pa")
        )

        assertEquals(
            "Test Merchant",
            uri.getQueryParameter("pn")
        )

        assertEquals(
            "99.50",
            uri.getQueryParameter("am")
        )

        assertEquals(
            "INR",
            uri.getQueryParameter("cu")
        )

        assertEquals(
            "Test payment",
            uri.getQueryParameter("tn")
        )

        assertEquals(
            "REF123",
            uri.getQueryParameter("tr")
        )

        assertEquals(
            "5411",
            uri.getQueryParameter("mc")
        )
    }

    // ------------------------------------------------------------
    // MAP RESULT - NULL DATA
    // ------------------------------------------------------------

    @Test
    fun testMapResultDoesNotLockoutUser() {

        val result =
            UpiService.mapResult(
                0,
                null
            )

        assertTrue(
            result.launched
        )

        assertFalse(
            result.cancelled
        )

        assertEquals(
            UpiPaymentStatus.INITIATED,
            result.status
        )

        assertNull(
            result.returnedTxnRef
        )
    }

    // ------------------------------------------------------------
    // MAP RESULT - SUCCESS
    // ------------------------------------------------------------

    @Test
    fun testMapResultSuccess() {

        val successIntent =
            Intent().apply {

                putExtra(
                    "response",
                    "txnId=123" +
                    "&responseCode=00" +
                    "&Status=SUCCESS" +
                    "&txnRef=REF999"
                )
            }

        val result =
            UpiService.mapResult(
                -1,
                successIntent
            )

        assertTrue(
            result.launched
        )

        assertFalse(
            result.cancelled
        )

        assertEquals(
            UpiPaymentStatus.SUCCESSFUL,
            result.status
        )

        assertEquals(
            "REF999",
            result.returnedTxnRef
        )
    }

    // ------------------------------------------------------------
    // MAP RESULT - PENDING
    // ------------------------------------------------------------

    @Test
    fun testMapResultPending() {

        val pendingIntent =
            Intent().apply {

                putExtra(
                    "response",
                    "txnId=123" +
                    "&Status=SUBMITTED" +
                    "&txnRef=PENDING123"
                )
            }

        val result =
            UpiService.mapResult(
                -1,
                pendingIntent
            )

        assertTrue(
            result.launched
        )

        assertEquals(
            UpiPaymentStatus.PENDING,
            result.status
        )

        assertEquals(
            "PENDING123",
            result.returnedTxnRef
        )
    }

    // ------------------------------------------------------------
    // MAP RESULT - FAILED
    // ------------------------------------------------------------

    @Test
    fun testMapResultFailed() {

        val failedIntent =
            Intent().apply {

                putExtra(
                    "response",
                    "txnId=123" +
                    "&Status=FAILED" +
                    "&txnRef=FAIL123"
                )
            }

        val result =
            UpiService.mapResult(
                -1,
                failedIntent
            )

        assertTrue(
            result.launched
        )

        assertEquals(
            UpiPaymentStatus.FAILED,
            result.status
        )

        assertEquals(
            "FAIL123",
            result.returnedTxnRef
        )
    }

    // ------------------------------------------------------------
    // MAP RESULT - CANCELLED
    // ------------------------------------------------------------

    @Test
    fun testMapResultCancelled() {

        val cancelledIntent =
            Intent()

        val result =
            UpiService.mapResult(
                android.app.Activity.RESULT_CANCELED,
                cancelledIntent
            )

        assertTrue(
            result.launched
        )

        assertTrue(
            result.cancelled
        )

        assertEquals(
            UpiPaymentStatus.CANCELLED,
            result.status
        )
    }

    // ------------------------------------------------------------
    // GOOGLE PAY PACKAGE
    // ------------------------------------------------------------

    @Test
    fun testGooglePayPackageName() {

        assertEquals(
            "com.google.android.apps.nbu.paisa.user",
            UpiService.GOOGLE_PAY_PACKAGE
        )
    }

    // ------------------------------------------------------------
    // PHONEPE PACKAGE
    // ------------------------------------------------------------

    @Test
    fun testPhonePePackageName() {

        assertEquals(
            "com.phonepe.app",
            UpiService.PHONEPE_PACKAGE
        )
    }

    // ------------------------------------------------------------
    // PAYTM PACKAGE
    // ------------------------------------------------------------

    @Test
    fun testPaytmPackageName() {

        assertEquals(
            "net.one97.paytm",
            UpiService.PAYTM_PACKAGE
        )
    }

    // ------------------------------------------------------------
    // BHIM PACKAGE
    // ------------------------------------------------------------

    @Test
    fun testBhimPackageName() {

        assertEquals(
            "in.org.npci.upiapp",
            UpiService.BHIM_PACKAGE
        )
    }

    // ------------------------------------------------------------
    // DEBUG PAYMENT URI
    // ------------------------------------------------------------

    @Test
    fun testDebugPaymentUri() {

        val info =
            UpiPaymentInfo(
                payeeAddress =
                    "9159222103-2@ybl",

                payeeName =
                    "ARUN ROSHAN GJ",

                amount =
                    "2"
            )

        val debugUri =
            UpiService.getDebugPaymentUri(
                info
            )

        assertTrue(
            debugUri.startsWith(
                "upi://pay"
            )
        )

        assertTrue(
            debugUri.contains(
                "am=2.00"
            )
        )

        assertTrue(
            debugUri.contains(
                "cu=INR"
            )
        )
    }

    // ------------------------------------------------------------
    // BHARATQR (EMVCo) PARSING
    // ------------------------------------------------------------

    @Test
    fun testBharatQrDynamicParsing() {
        val emvcoPayload =
            "00020101021226390012org.npci.upi0119merchant@okhdfcbank5204541153033565406250.005802IN5912Super Market6009Bengaluru6304ABCD"

        val parsed = UpiService.parseQrPayload(emvcoPayload)
        assertNotNull(parsed)
        assertEquals("merchant@okhdfcbank", parsed?.payeeAddress)
        assertEquals("Super Market", parsed?.payeeName)
        assertEquals("250.00", parsed?.amount)
        assertTrue(parsed?.isDynamic == true)
    }

    @Test
    fun testBharatQrStaticParsing() {
        val emvcoPayload =
            "00020101021126390012org.npci.upi0119merchant@okhdfcbank5204541153033565802IN5912Super Market6009Bengaluru6304ABCD"

        val parsed = UpiService.parseQrPayload(emvcoPayload)
        assertNotNull(parsed)
        assertEquals("merchant@okhdfcbank", parsed?.payeeAddress)
        assertEquals("Super Market", parsed?.payeeName)
        assertEquals("", parsed?.amount)
        assertFalse(parsed?.isDynamic == true)
    }

    // ------------------------------------------------------------
    // WEB-WRAPPED UPI PARSING
    // ------------------------------------------------------------

    @Test
    fun testWebWrappedUpiUrlParsing() {
        val webUrl = "https://upiqr.in/pay?pa=store@okaxis&pn=Store%20ABC&am=120.00"
        val parsed = UpiService.parseQrPayload(webUrl)
        assertNotNull(parsed)
        assertEquals("store@okaxis", parsed?.payeeAddress)
        assertEquals("Store ABC", parsed?.payeeName)
        assertEquals("120.00", parsed?.amount)
        assertTrue(parsed?.isDynamic == true)
    }

    @Test
    fun testEmbeddedEncodedUpiUrlParsing() {
        val webUrl = "https://example.com/checkout?target=upi%3A%2F%2Fpay%3Fpa%3Dfastpay%40ybl%26pn%3DFast%2BPay%26am%3D99.50"
        val parsed = UpiService.parseQrPayload(webUrl)
        assertNotNull(parsed)
        assertEquals("fastpay@ybl", parsed?.payeeAddress)
        assertEquals("Fast Pay", parsed?.payeeName)
        assertEquals("99.50", parsed?.amount)
        assertTrue(parsed?.isDynamic == true)
    }

    @Test
    fun testSanitizedAmountWithCommas() {
        val payload = "upi://pay?pa=shop@upi&pn=Grocery&am=1,250.00"
        val parsed = UpiService.parseQrPayload(payload)
        assertNotNull(parsed)
        assertEquals("1250.00", parsed?.amount)
    }

    @Test
    fun testVpaEdgeCasesAndAmountLimits() {
        assertFalse(UpiService.isValidVpa("9876543210"))
        assertFalse(UpiService.isValidVpa("user.@okaxis"))
        assertFalse(UpiService.isValidVpa("user@.okaxis"))
        assertFalse(UpiService.isValidVpa("a..b@ybl"))

        val validP2p = UpiService.validatePaymentInfo(
            UpiPaymentInfo(payeeAddress = "friend@oksbi", payeeName = "Friend", amount = "99999")
        )
        assertTrue(validP2p.isValid)
        assertTrue(validP2p.isP2pUnverifiedWarning)

        val overP2pCap = UpiService.validatePaymentInfo(
            UpiPaymentInfo(payeeAddress = "friend@oksbi", payeeName = "Friend", amount = "150000")
        )
        assertFalse(overP2pCap.isValid)

        val validMerchant = UpiService.validatePaymentInfo(
            UpiPaymentInfo(payeeAddress = "hospital@icici", payeeName = "Apollo", amount = "150000", merchantCode = "8062")
        )
        assertTrue(validMerchant.isValid)
        assertFalse(validMerchant.isP2pUnverifiedWarning)
    }

    @Test
    fun testMandatoryPayeeNameFallbackAndCommaAmountInBuildPaymentUri() {
        val info = UpiPaymentInfo(
            payeeAddress = "rahul.sharma@okaxis",
            payeeName = "???",
            amount = "1,250.5",
            currency = "inr",
            note = "Lunch   split!"
        )
        val uri = UpiService.buildPaymentUri(info)
        assertEquals("rahul sharma", uri.getQueryParameter("pn"))
        assertEquals("1250.50", uri.getQueryParameter("am"))
        assertEquals("INR", uri.getQueryParameter("cu"))
        assertEquals("Lunch split", uri.getQueryParameter("tn"))
    }

    @Test
    fun testSecurityDeclineAndContradictoryResponseCodeHandling() {
        // Explicit GPay / NPCI security decline response
        val declinedIntent = Intent().apply {
            putExtra("response", "txnId=YBL1234&responseCode=U16&Status=FAILURE&message=Declined for security reasons")
        }
        val declined = UpiService.mapResult(Activity.RESULT_OK, declinedIntent)
        assertEquals(UpiPaymentStatus.FAILED, declined.status)
        assertTrue(declined.isSecurityDecline)
        assertEquals("U16", declined.responseCode)

        // Malformed or spoofed Status=SUCCESS combined with non-00 error responseCode must fail
        val contradictionIntent = Intent().apply {
            putExtra("response", "txnId=YBL9999&responseCode=U16&Status=SUCCESS")
        }
        val rejected = UpiService.mapResult(Activity.RESULT_OK, contradictionIntent)
        assertEquals(UpiPaymentStatus.FAILED, rejected.status)
        assertTrue(rejected.isSecurityDecline)
    }

    @Test
    fun testPendingStatusAndApprovalRefExtraction() {
        val pendingIntent = Intent().apply {
            putExtra("response", "txnId=ICI1111&Status=PENDING&ApprovalRefNo=432198765432")
        }
        val res = UpiService.mapResult(Activity.RESULT_OK, pendingIntent)
        assertEquals(UpiPaymentStatus.PENDING, res.status)
        assertEquals("432198765432", res.approvalRefNo)
        assertEquals("432198765432", res.returnedTxnRef)
    }

    @Test
    fun testGatewayOrderNeverTrustsClientStatusAlone() {
        val clientSuccess = UpiService.mapResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra("response", "txnId=GW1&responseCode=00&Status=SUCCESS&txnRef=ORD_777")
            }
        )
        assertEquals(UpiPaymentStatus.SUCCESSFUL, clientSuccess.status)

        // Without server verification, GATEWAY_ORDER must stay PENDING
        val unverified = UpiService.enforceGatewayVerification(
            result = clientSuccess,
            flowType = PaymentFlowType.GATEWAY_ORDER,
            expectedOrderTxnRef = "ORD_777",
            verifier = null
        )
        assertEquals(UpiPaymentStatus.PENDING, unverified.status)
        assertTrue(unverified.requiresServerVerification)

        // When server verification rejects
        val rejected = UpiService.enforceGatewayVerification(
            result = clientSuccess,
            flowType = PaymentFlowType.GATEWAY_ORDER,
            expectedOrderTxnRef = "ORD_777",
            verifier = { _, _ -> GatewayVerificationStatus.REJECTED }
        )
        assertEquals(UpiPaymentStatus.FAILED, rejected.status)

        // When server webhook confirms VERIFIED
        val verified = UpiService.enforceGatewayVerification(
            result = clientSuccess,
            flowType = PaymentFlowType.GATEWAY_ORDER,
            expectedOrderTxnRef = "ORD_777",
            verifier = { _, _ -> GatewayVerificationStatus.VERIFIED }
        )
        assertEquals(UpiPaymentStatus.SUCCESSFUL, verified.status)
        assertFalse(verified.requiresServerVerification)
    }

    @Test
    fun testAtomicDuplicatePreventionAndMaskedDiagnostics() {
        val uniqueUtr = "UTR${System.currentTimeMillis()}"
        assertTrue(UpiPaymentBus.tryClaimRecording(425.0, uniqueUtr))
        assertFalse(UpiPaymentBus.tryClaimRecording(425.0, uniqueUtr.lowercase()))

        val uri = UpiService.buildPaymentUri(
            UpiPaymentInfo(
                payeeAddress = "9876543210@okhdfcbank",
                payeeName = "Merchant Store",
                amount = "250.00"
            )
        )
        val logged = UpiService.sanitizeUriForLog(uri)
        assertFalse(logged.contains("9876543210"))
        assertTrue(logged.contains("98***10@okhdfcbank"))

        val withLiteralSpace = UpiService.parseQrPayload("upi://pay?pa=shop@upi&pn=Tea Stall&am=20.00")
        assertEquals("Tea Stall", withLiteralSpace?.payeeName)
        assertEquals("20.00", withLiteralSpace?.amount)
    }
}