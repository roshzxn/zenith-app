package com.zenith.app

import com.zenith.app.data.upi.ExtractedUpiPayment
import com.zenith.app.data.upi.UpiNotificationParser
import org.junit.Assert.*
import org.junit.Test

class UpiNotificationParserTest {

    @Test
    fun testGooglePayPaymentNotification() {
        val parsed = UpiNotificationParser.parse(
            packageName = "com.google.android.apps.nbu.paisa.user",
            title = "Paid ₹350.00",
            text = "Paid ₹350.00 to Swiggy. UPI Ref: 424598123456"
        )
        assertNotNull(parsed)
        assertEquals(350.0, parsed!!.amount, 0.01)
        assertEquals("Swiggy", parsed.payeeName)
        assertEquals("424598123456", parsed.upiTransactionId)
        assertEquals("Google Pay", parsed.sourceApp)
        assertEquals("Food & Dining", parsed.detectedCategory)
        assertTrue(parsed.isDebit)
    }

    @Test
    fun testPhonePePaymentNotification() {
        val parsed = UpiNotificationParser.parse(
            packageName = "com.phonepe.app",
            title = "Payment Successful",
            text = "Payment of ₹500 to Indian Oil successful. Debited from State Bank of India - 1234."
        )
        assertNotNull(parsed)
        assertEquals(500.0, parsed!!.amount, 0.01)
        assertEquals("Indian Oil", parsed.payeeName)
        assertEquals("PhonePe", parsed.sourceApp)
        assertEquals("Transportation", parsed.detectedCategory)
    }

    @Test
    fun testPaytmPaymentNotification() {
        val parsed = UpiNotificationParser.parse(
            packageName = "net.one97.paytm",
            title = "Money Sent",
            text = "Money sent: ₹1,250 sent to BigBasket. UPI Ref: 987654321012"
        )
        assertNotNull(parsed)
        assertEquals(1250.0, parsed!!.amount, 0.01)
        assertEquals("BigBasket", parsed.payeeName)
        assertEquals("987654321012", parsed.upiTransactionId)
        assertEquals("Paytm", parsed.sourceApp)
        assertEquals("Shopping", parsed.detectedCategory)
    }

    @Test
    fun testCredPaymentNotification() {
        val parsed = UpiNotificationParser.parse(
            packageName = "com.dreamplug.androidapp",
            title = "Payment Successful",
            text = "₹350 paid to Starbucks via CRED UPI. UPI Ref: 112233445566"
        )
        assertNotNull(parsed)
        assertEquals(350.0, parsed!!.amount, 0.01)
        assertEquals("Starbucks", parsed.payeeName)
        assertEquals("112233445566", parsed.upiTransactionId)
        assertEquals("CRED", parsed.sourceApp)
        assertEquals("Food & Dining", parsed.detectedCategory)
    }

    @Test
    fun testHdfcBankNotification() {
        val parsed = UpiNotificationParser.parse(
            packageName = "com.snapwork.hdfc",
            title = "HDFC Bank Alert",
            text = "Alert: Rs 450.00 debited from a/c **1234 on 06-09-26 via UPI to Zomato (UPI Ref: 424598123456)"
        )
        assertNotNull(parsed)
        assertEquals(450.0, parsed!!.amount, 0.01)
        assertEquals("Zomato", parsed.payeeName)
        assertEquals("424598123456", parsed.upiTransactionId)
        assertEquals("HDFC Bank", parsed.sourceApp)
        assertEquals("Food & Dining", parsed.detectedCategory)
    }

    @Test
    fun testSbiNotification() {
        val parsed = UpiNotificationParser.parse(
            packageName = "com.sbi.lotusintouch",
            title = "SBI Alert",
            text = "Dear SBI User, A/C 1234 debited by 250.0 on 06Sep26 by UPI/424598123456/Uber."
        )
        assertNotNull(parsed)
        assertEquals(250.0, parsed!!.amount, 0.01)
        assertEquals("424598123456", parsed.upiTransactionId)
        assertEquals("SBI YONO", parsed.sourceApp)
    }

    @Test
    fun testNonTransactionNotificationsAreIgnored() {
        val otpNotif = UpiNotificationParser.parse(
            packageName = "com.google.android.apps.nbu.paisa.user",
            title = "Google Pay",
            text = "Your OTP for Google Pay verification is 849201. Do not share this with anyone."
        )
        assertNull(otpNotif)

        val offerNotif = UpiNotificationParser.parse(
            packageName = "com.phonepe.app",
            title = "Special Offer",
            text = "Get 10% cashback on your next electricity bill payment."
        )
        assertNull(offerNotif)

        val requestNotif = UpiNotificationParser.parse(
            packageName = "net.one97.paytm",
            title = "Payment Request",
            text = "Ramesh has requested ₹500 from you on Paytm."
        )
        assertNull(requestNotif)
    }

    @Test
    fun testDeduplicationKey() {
        val payment1 = ExtractedUpiPayment(
            amount = 350.0,
            payeeName = "Swiggy",
            upiTransactionId = "424598123456",
            sourceApp = "Google Pay"
        )

        val payment2 = ExtractedUpiPayment(
            amount = 350.0,
            payeeName = "SWIGGY",
            upiTransactionId = "424598123456",
            sourceApp = "HDFC Bank"
        )

        assertEquals(payment1.deduplicationKey, payment2.deduplicationKey)
        assertEquals("utr_424598123456", payment1.deduplicationKey)
    }

    @Test
    fun testBankSmsWithOtpSecurityFooterIsParsed() {
        val sms = UpiNotificationParser.parse(
            packageName = "com.google.android.apps.messaging",
            title = "AD-HDFCBK",
            text = "Amt Sent Rs.450.00 From HDFC Bank A/C *1234 To Swiggy On 25-11-26 UPI Ref No. 424598123456. Not You? Never share OTP/PIN."
        )
        assertNotNull(sms)
        assertEquals(450.0, sms!!.amount, 0.01)
        assertEquals("Swiggy", sms.payeeName)
        assertEquals("424598123456", sms.upiTransactionId)
        assertEquals("HDFC Bank", sms.sourceApp)
        assertEquals("Food & Dining", sms.detectedCategory)
        assertTrue(sms.isDebit)
    }

    @Test
    fun testSbiSmsTrfToDottedDoctorAndRefNo() {
        val sms = UpiNotificationParser.parse(
            packageName = "com.android.mms",
            title = "VM-SBIUPI",
            text = "Dear UPI user A/C X1234 debited by 180.00 on date 25Nov26 trf to Dr. Sharma Refno 556677889900. If not u? call 1800111109."
        )
        assertNotNull(sms)
        assertEquals(180.0, sms!!.amount, 0.01)
        assertEquals("Dr. Sharma", sms.payeeName)
        assertEquals("556677889900", sms.upiTransactionId)
        assertEquals("SBI", sms.sourceApp)
        assertEquals("Healthcare", sms.detectedCategory)
        assertTrue(sms.isDebit)
    }

    @Test
    fun testOtpSmsWithTransactionAmountAndPersonalChatSmsAreIgnored() {
        val otpSms = UpiNotificationParser.parse(
            packageName = "com.google.android.apps.messaging",
            title = "AD-HDFCBK",
            text = "Your OTP is 849201 for transaction of Rs.500.00 at Flipkart on A/C **1234. Never share OTP."
        )
        assertNull(otpSms)

        val chatSms = UpiNotificationParser.parse(
            packageName = "com.google.android.apps.messaging",
            title = "+91 98765 43210",
            text = "Hey I paid ₹500 for movie tickets yesterday, send me ₹250 later"
        )
        assertNull(chatSms)
    }

    @Test
    fun testModernGpayPayzappAndVpaPayeeCleanup() {
        val gpayModern = UpiNotificationParser.parse(
            packageName = "com.google.android.apps.nbu.paisa.user",
            title = "Blue Tokai Coffee",
            text = "Paid ₹280.00 using HDFC Bank 1234"
        )
        assertNotNull(gpayModern)
        assertEquals(280.0, gpayModern!!.amount, 0.01)
        assertEquals("Blue Tokai Coffee", gpayModern.payeeName)
        assertEquals("Google Pay", gpayModern.sourceApp)
        assertEquals("Food & Dining", gpayModern.detectedCategory)

        val payzapp = UpiNotificationParser.parse(
            packageName = "com.enstage.wibmo.hdfc",
            title = "Payment Successful",
            text = "Paid ₹640.00 to VPA apollo.pharmacy@okaxis (UPI Ref No. 112233445566)"
        )
        assertNotNull(payzapp)
        assertEquals(640.0, payzapp!!.amount, 0.01)
        assertEquals("apollo.pharmacy@okaxis", payzapp.upiId)
        assertEquals("Apollo pharmacy", payzapp.payeeName)
        assertEquals("112233445566", payzapp.upiTransactionId)
        assertEquals("PayZapp", payzapp.sourceApp)
        assertEquals("Healthcare", payzapp.detectedCategory)
    }

    @Test
    fun testCreditDetectionAndNonRefundableDebit() {
        val gpayCredit = UpiNotificationParser.parse(
            packageName = "com.google.android.apps.nbu.paisa.user",
            title = "Ramesh paid you ₹500",
            text = "Money received ₹500.00 from Ramesh"
        )
        assertNotNull(gpayCredit)
        assertFalse(gpayCredit!!.isDebit)
        assertEquals(500.0, gpayCredit.amount, 0.01)
        assertEquals("Ramesh", gpayCredit.payeeName)

        val phonePeCredit = UpiNotificationParser.parse(
            packageName = "com.phonepe.app",
            title = "Money Received",
            text = "Ananya has sent you ₹750.00"
        )
        assertNotNull(phonePeCredit)
        assertFalse(phonePeCredit!!.isDebit)
        assertEquals(750.0, phonePeCredit.amount, 0.01)
        assertEquals("Ananya", phonePeCredit.payeeName)

        val nonRefundableDebit = UpiNotificationParser.parse(
            packageName = "com.phonepe.app",
            title = "Payment Successful",
            text = "Paid ₹1,200.00 to MakeMyTrip (non-refundable flight booking)"
        )
        assertNotNull(nonRefundableDebit)
        assertTrue(nonRefundableDebit!!.isDebit)
        assertEquals("MakeMyTrip", nonRefundableDebit.payeeName)
    }
}
