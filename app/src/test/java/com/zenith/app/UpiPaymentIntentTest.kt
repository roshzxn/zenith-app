package com.zenith.app

import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.PatternMatcher
import com.zenith.app.data.upi.UpiPaymentInfo
import com.zenith.app.data.upi.UpiService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpiPaymentIntentTest {

    @Suppress("DEPRECATION")
    private fun registerFakeUpiHandler(packageName: String, appLabel: String) {
        val context = RuntimeEnvironment.getApplication()
        val shadowPm = Shadows.shadowOf(context.packageManager)

        val pkgInfo = android.content.pm.PackageInfo().apply {
            this.packageName = packageName
            this.applicationInfo = android.content.pm.ApplicationInfo().apply {
                this.packageName = packageName
                this.nonLocalizedLabel = appLabel
            }
        }
        shadowPm.installPackage(pkgInfo)

        val activityInfo = android.content.pm.ActivityInfo().apply {
            this.packageName = packageName
            this.name = "$packageName.UpiPayActivity"
            this.applicationInfo = pkgInfo.applicationInfo
        }
        val component = android.content.ComponentName(packageName, activityInfo.name)
        shadowPm.addOrUpdateActivity(activityInfo)

        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("upi")
            addDataAuthority("pay", null)
        }
        shadowPm.addIntentFilterForActivity(component, filter)
    }

    @Test
    fun testP2pPaymentUriDoesNotIncludeSyntheticTr() {
        val info = UpiPaymentInfo(
            payeeAddress = "user@oksbi",
            payeeName = "Arun Kumar",
            amount = "250.00",
            currency = "INR",
            note = "Split bill"
        )
        val uri = UpiService.buildPaymentUri(info)

        assertEquals("upi", uri.scheme)
        assertEquals("pay", uri.authority)
        assertEquals("user@oksbi", uri.getQueryParameter("pa"))
        assertEquals("Arun Kumar", uri.getQueryParameter("pn"))
        assertEquals("250.00", uri.getQueryParameter("am"))
        assertEquals("INR", uri.getQueryParameter("cu"))
        assertEquals("Split bill", uri.getQueryParameter("tn"))
        // tr MUST be absent for P2P transactions to avoid NPCI rejection
        assertNull(uri.getQueryParameter("tr"))
        assertNull(uri.getQueryParameter("mc"))
    }

    @Test
    fun testPayeeNameAndNoteSanitization() {
        val info = UpiPaymentInfo(
            payeeAddress = "store@paytm",
            payeeName = "Dinner & Drinks / Food #1!",
            amount = "120.50",
            note = "Party @ John's House (100% fun)"
        )
        val uri = UpiService.buildPaymentUri(info)

        val pn = uri.getQueryParameter("pn")
        assertNotNull(pn)
        // Punctuation like &, /, #, ! should be stripped to avoid PSP risk blocks
        assertFalse(pn!!.contains("&"))
        assertFalse(pn.contains("/"))
        assertFalse(pn.contains("#"))

        val tn = uri.getQueryParameter("tn")
        assertNotNull(tn)
        assertFalse(tn!!.contains("@"))
        assertFalse(tn.contains("%"))
    }

    @Test
    fun testPreservesMerchantQrCryptographicSignatureAndMetadata() {
        val storeQr = "upi://pay?pa=supermarket@hdfcbank&pn=FreshMart&mc=5411&tr=ORD9988776655&mode=02&orgid=180001&sign=MEYCIQC...=="

        val parsed = UpiService.parseQrPayload(storeQr)
        assertNotNull(parsed)
        assertEquals("FreshMart", parsed?.payeeName)
        assertEquals("supermarket@hdfcbank", parsed?.payeeAddress)
        assertEquals(storeQr, parsed?.rawUri)

        // Now build the payment URI with a user-entered amount of 450.00
        val paymentUri = UpiService.buildQrPaymentUri(parsed!!.rawUri!!, 450.00)

        assertEquals("supermarket@hdfcbank", paymentUri.getQueryParameter("pa"))
        assertEquals("FreshMart", paymentUri.getQueryParameter("pn"))
        assertEquals("5411", paymentUri.getQueryParameter("mc"))
        assertEquals("ORD9988776655", paymentUri.getQueryParameter("tr"))
        assertEquals("02", paymentUri.getQueryParameter("mode"))
        assertEquals("180001", paymentUri.getQueryParameter("orgid"))
        assertEquals("MEYCIQC...==", paymentUri.getQueryParameter("sign"))
        assertEquals("450.00", paymentUri.getQueryParameter("am"))
    }

    @Test
    fun testBuildUpiScannerIntentGenericChooser() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val intent = UpiService.buildUpiScannerIntent(context, null)
        assertNotNull(intent)
        assertEquals(android.content.Intent.ACTION_CHOOSER, intent.action)
        val targetIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(android.content.Intent.EXTRA_INTENT, android.content.Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(android.content.Intent.EXTRA_INTENT)
        }
        assertNotNull(targetIntent)
        assertEquals(android.content.Intent.ACTION_VIEW, targetIntent?.action)
        assertEquals("upi://pay", targetIntent?.dataString)
    }

    @Test
    fun testBuildUpiScannerIntentForGooglePay() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val intent = UpiService.buildUpiScannerIntent(context, UpiService.GOOGLE_PAY_PACKAGE)
        assertNotNull(intent)
        // Without package installed in robolectric, falls back to chooser or package intent
        assertNotNull(intent.action)
    }

    @Test
    fun testAppInstallationHelpersDoNotCrash() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        assertFalse(UpiService.isGooglePayInstalled(context))
        assertFalse(UpiService.isPhonePeInstalled(context))
        assertFalse(UpiService.isPaytmInstalled(context))
        assertEquals(UpiService.BHIM_PACKAGE.let { false }, UpiService.isBhimInstalled(context))
    }

    @Test
    fun testGooglePayPhonePePaytmAndOtherAppsInitiationWithPackageResolution() {
        val context = RuntimeEnvironment.getApplication()
        registerFakeUpiHandler(UpiService.GOOGLE_PAY_PACKAGE, "Google Pay")
        registerFakeUpiHandler(UpiService.PHONEPE_PACKAGE, "PhonePe")
        registerFakeUpiHandler(UpiService.PAYTM_PACKAGE, "Paytm")
        registerFakeUpiHandler("in.org.npci.upiapp", "BHIM")
        registerFakeUpiHandler(context.packageName, "Zenith Self") // must be excluded

        val installed = UpiService.installedUpiApps(context)
        val packages = installed.map { it.packageName }
        assertTrue(packages.contains(UpiService.GOOGLE_PAY_PACKAGE))
        assertTrue(packages.contains(UpiService.PHONEPE_PACKAGE))
        assertTrue(packages.contains(UpiService.PAYTM_PACKAGE))
        assertTrue(packages.contains(UpiService.BHIM_PACKAGE))
        assertFalse(packages.contains(context.packageName))
        assertEquals(packages.distinct().size, packages.size)

        val info = UpiPaymentInfo(
            payeeAddress = "coffee@okicici",
            payeeName = "Coffee Shop",
            amount = "120",
            note = "Latte"
        )

        val gpayIntent = UpiService.buildGooglePayIntent(info, context)
        assertEquals(Intent.ACTION_VIEW, gpayIntent.action)
        assertEquals(UpiService.GOOGLE_PAY_PACKAGE, gpayIntent.`package`)
        assertEquals(0, gpayIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)

        val phonePeIntent = UpiService.buildPhonePeIntent(info, context)
        assertEquals(Intent.ACTION_VIEW, phonePeIntent.action)
        assertEquals(UpiService.PHONEPE_PACKAGE, phonePeIntent.`package`)
        assertEquals(0, phonePeIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)

        val bhimIntent = UpiService.buildPayIntent(info, targetPackage = UpiService.BHIM_PACKAGE, context = context)
        assertEquals(Intent.ACTION_VIEW, bhimIntent.action)
        assertEquals(UpiService.BHIM_PACKAGE, bhimIntent.`package`)
        assertEquals(0, bhimIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun testPreservesRawEncodedSignAndNormalizesUppercaseScheme() {
        val signedQr = "UPI://PAY?pa=shop@hdfcbank&pn=Shop%20Name&mc=5411&tr=REF123&mode=02&orgid=159002&am=250.00&cu=INR&sign=AbC%2BdE%2Ff%3D%3D"
        val parsed = UpiService.parseQrPayload(signedQr)
        assertNotNull(parsed)
        assertEquals("upi://pay?pa=shop@hdfcbank&pn=Shop%20Name&mc=5411&tr=REF123&mode=02&orgid=159002&am=250.00&cu=INR&sign=AbC%2BdE%2Ff%3D%3D", parsed?.rawUri)

        // Same dynamic amount must leave raw signature bytes 100% untouched
        val rebuiltUri = UpiService.buildQrPaymentUri(parsed!!.rawUri!!, 250.00)
        val uriString = rebuiltUri.toString()
        assertTrue(uriString.startsWith("upi://pay?"))
        assertTrue(uriString.contains("sign=AbC%2BdE%2Ff%3D%3D"))
        assertTrue(rebuiltUri.getQueryParameter("pn") == "Shop Name")
    }

    @Test
    fun testBharatQrPreservesMerchantCodeAndReferenceInRawUri() {
        val emvco = "00020101021226390012org.npci.upi0119merchant@okhdfcbank5204541153033565406150.005802IN5912Super Market62160508TXN889906304ABCD"
        val parsed = UpiService.parseQrPayload(emvco)
        assertNotNull(parsed)
        assertEquals("5411", parsed?.merchantCode)
        assertEquals("TXN88990", parsed?.txnRef)
        val qrUri = UpiService.buildQrPaymentUri(parsed!!.rawUri!!, 150.00)
        assertEquals("5411", qrUri.getQueryParameter("mc"))
        assertEquals("TXN88990", qrUri.getQueryParameter("tr"))
        assertEquals("150.00", qrUri.getQueryParameter("am"))
    }
}
