package com.zenith.app.data.upi

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zenith.app.R
import com.zenith.app.widget.ZenithSummaryWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Background service that intercepts payment notifications from Indian UPI apps,
 * banking apps, and financial SMS alerts in real-time.
 *
 * Privacy & Security:
 * - All parsing is performed 100% on-device.
 * - Only transaction amounts, merchant names, and UTR references are extracted.
 * - Non-transaction notifications (OTPs, personal messages) are immediately discarded.
 */
class UpiNotificationListenerService : NotificationListenerService() {

    private val tag = "UpiNotifListener"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // Cache of recently processed transaction hashes to prevent duplicate logging
        // when both UPI app and Bank emit notifications for the same payment.
        private val processedHashes = ConcurrentHashMap<String, Long>()
        private const val DEDUPLICATION_WINDOW_MS = 300_000L // 5 minutes

        private const val PREFS_NAME = "zenith_upi_prefs"
        private const val KEY_AUTO_DETECT = "upi_auto_detection_enabled"
        private const val KEY_INSTANT_AUTO_SAVE = "upi_instant_auto_save_enabled"
        private const val CHANNEL_ID = "zenith_upi_tracker"

        @Volatile
        var isServiceConnected: Boolean = false
            private set

        /**
         * Checks whether the user has enabled automatic UPI transaction detection inside the app.
         */
        fun isAutoDetectionEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_AUTO_DETECT, true)
        }

        /**
         * Toggles automatic UPI transaction detection inside the app.
         */
        fun setAutoDetectionEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_AUTO_DETECT, enabled).apply()
            if (enabled) {
                ensureListenerBound(context)
            }
        }

        /**
         * Checks whether detected payments should auto-log immediately without requiring 1-tap confirmation.
         */
        fun isInstantAutoSaveEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_INSTANT_AUTO_SAVE, false)
        }

        fun setInstantAutoSaveEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_INSTANT_AUTO_SAVE, enabled).apply()
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "UPI Expense Tracker",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifies when UPI transactions are detected with 1-tap logging"
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }
        }

        /**
         * Checks whether the user has granted Notification Access to this app's listener component.
         */
        fun isPermissionGranted(context: Context): Boolean {
            val packageName = context.packageName
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?: return false
            if (flat.isBlank()) return false

            val expectedComponent = ComponentName(context, UpiNotificationListenerService::class.java)
            return flat.split(":").any { entry ->
                val trimmed = entry.trim()
                val parsed = ComponentName.unflattenFromString(trimmed)
                parsed == expectedComponent ||
                    (parsed?.packageName == packageName &&
                        parsed.className.endsWith("UpiNotificationListenerService"))
            }
        }

        /**
         * Checks whether the listener binder is actively connected inside the app process.
         */
        @Suppress("DEPRECATION")
        fun isListenerActivelyBound(context: Context): Boolean {
            if (isServiceConnected) return true
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return false
                val targetClass = UpiNotificationListenerService::class.java.name
                am.getRunningServices(50).any {
                    it.service.packageName == context.packageName &&
                        it.service.className == targetClass
                }
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Requests Android (API 24+) to rebind the NotificationListenerService when permission is
         * granted but Android's binder disconnected after sideloading, app updates, or OEM kills.
         */
        fun ensureListenerBound(context: Context, forceRebind: Boolean = false): Boolean {
            if (!isPermissionGranted(context)) return false
            if (!forceRebind && isListenerActivelyBound(context)) return true
            return try {
                requestRebind(ComponentName(context, UpiNotificationListenerService::class.java))
                true
            } catch (e: Exception) {
                Log.w("UpiNotifListener", "Unable to request listener rebind: ${e.message}")
                false
            }
        }

        /**
         * Opens the Android Settings screen where the user can enable Notification Access.
         */
        fun openPermissionSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(context, UpiNotificationListenerService::class.java).flattenToString()
                    )
                }
            }
            context.startActivity(intent)
        }

        /**
         * Opens Zenith's App Info screen so Android 13+ sideloaded APK users can tap
         * "Allow restricted settings" if Notification Access is greyed out.
         */
        fun openAppInfoSettings(context: Context) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        fun hasPostNotificationPermission(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        }

        fun notificationIdFor(amount: Double, payeeName: String): Int {
            return (amount.toInt() * 31 + payeeName.hashCode()) and 0x7FFFFFFF
        }

        fun cancelNotificationFor(context: Context, amount: Double, payeeName: String) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(notificationIdFor(amount, payeeName))
            } catch (_: Exception) {
            }
        }

        /**
         * Combines Notification title and body extras (BigText, Text, InboxStyle textLines, SubText)
         * so multi-line UPI and bank alerts are never truncated or missed.
         */
        internal fun extractNotificationPayload(notification: Notification): Pair<String?, String?> {
            val extras = notification.extras ?: return Pair(null, null)
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()

            @Suppress("DEPRECATION")
            val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf { line -> line.isNotBlank() } }
                .orEmpty()

            val parts = linkedSetOf<String>()
            bigText?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            text?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            textLines.forEach { line -> parts.add(line) }
            subText?.takeIf { it.isNotBlank() }?.let { parts.add(it) }

            val combinedBody = parts.joinToString(" ").trim().takeIf { it.isNotBlank() }
            return Pair(title, combinedBody)
        }

        internal fun shouldIgnoreNotification(
            sourcePackage: String,
            ownPackage: String,
            flags: Int
        ): Boolean {
            if (sourcePackage == ownPackage) return true
            if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) return true
            return false
        }

        /**
         * Simulates a test UPI notification for verification and settings preview.
         */
        fun simulateTestPayment(
            context: Context,
            amount: Double = 150.0,
            payeeName: String = "Chai Point",
            sourceApp: String = "Google Pay"
        ) {
            val testPayment = ExtractedUpiPayment(
                amount = amount,
                payeeName = payeeName,
                upiTransactionId = "TEST" + System.currentTimeMillis().toString().takeLast(6),
                sourceApp = sourceApp,
                timestamp = System.currentTimeMillis(),
                detectedCategory = "Food & Dining",
                rawText = "Paid ₹$amount to $payeeName using $sourceApp",
                isDebit = true
            )
            PendingUpiDetectionStore.enqueue(context, testPayment)
            UpiPaymentBus.post(testPayment)
        }

        /**
         * Posts a real test notification in the Android notification shade so users can
         * verify notification channels, permissions, and 1-Tap Save end-to-end.
         */
        fun simulateShadeNotification(
            context: Context,
            amount: Double = 150.0,
            payeeName: String = "Chai Point",
            sourceApp: String = "Google Pay"
        ): Boolean {
            if (!hasPostNotificationPermission(context)) return false
            val testPayment = ExtractedUpiPayment(
                amount = amount,
                payeeName = payeeName,
                upiTransactionId = "SHADE" + System.currentTimeMillis().toString().takeLast(6),
                sourceApp = sourceApp,
                timestamp = System.currentTimeMillis(),
                detectedCategory = "Food & Dining",
                rawText = "Paid ₹$amount to $payeeName using $sourceApp",
                isDebit = true
            )
            PendingUpiDetectionStore.enqueue(context, testPayment)
            postExpenseNotification(context, testPayment)
            return true
        }

        internal fun postExpenseNotification(context: Context, payment: ExtractedUpiPayment) {
            ensureChannel(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notifId = notificationIdFor(payment.amount, payment.payeeName)

            val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("extra_upi_amount", payment.amount)
                putExtra("extra_upi_payee", payment.payeeName)
                putExtra("extra_upi_category", payment.detectedCategory)
                putExtra("extra_upi_utr", payment.upiTransactionId)
                putExtra("extra_upi_vpa", payment.upiId)
                putExtra("extra_upi_app", payment.sourceApp)
                putExtra("extra_upi_is_debit", payment.isDebit)
            }
            val openAppPendingIntent = if (openAppIntent != null) {
                PendingIntent.getActivity(
                    context,
                    notifId,
                    openAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                null
            }

            val saveIntent = Intent(context, NotificationExpenseActionReceiver::class.java).apply {
                action = NotificationExpenseActionReceiver.ACTION_LOG_EXPENSE
                putExtra(NotificationExpenseActionReceiver.EXTRA_TITLE, payment.payeeName)
                putExtra(NotificationExpenseActionReceiver.EXTRA_AMOUNT, payment.amount)
                putExtra(NotificationExpenseActionReceiver.EXTRA_CATEGORY, payment.detectedCategory)
                putExtra(NotificationExpenseActionReceiver.EXTRA_PAYMENT_METHOD, payment.sourceApp)
                putExtra(NotificationExpenseActionReceiver.EXTRA_UPI_ID, payment.upiId)
                putExtra(NotificationExpenseActionReceiver.EXTRA_UTR, payment.upiTransactionId)
                putExtra(NotificationExpenseActionReceiver.EXTRA_DATE_MILLIS, payment.timestamp)
                putExtra(NotificationExpenseActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
                putExtra(NotificationExpenseActionReceiver.EXTRA_IS_DEBIT, payment.isDebit)
            }
            val savePendingIntent = PendingIntent.getBroadcast(
                context,
                notifId + 1,
                saveIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val dismissIntent = Intent(context, NotificationExpenseActionReceiver::class.java).apply {
                action = NotificationExpenseActionReceiver.ACTION_DISMISS_EXPENSE
                putExtra(NotificationExpenseActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
                putExtra(NotificationExpenseActionReceiver.EXTRA_AMOUNT, payment.amount)
                putExtra(NotificationExpenseActionReceiver.EXTRA_UTR, payment.upiTransactionId)
            }
            val dismissPendingIntent = PendingIntent.getBroadcast(
                context,
                notifId + 2,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val prep = if (payment.isDebit) "at" else "from"
            val kindLabel = if (payment.isDebit) "Expense" else "Income"
            val formattedAmount = String.format(java.util.Locale.US, "%,.2f", payment.amount)
            val bodyText = "Detected $kindLabel via ${payment.sourceApp} • Tap 1-Tap Save to log"

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_zenith)
                .setContentTitle("💸 Record ₹$formattedAmount $prep ${payment.payeeName}?")
                .setContentText(bodyText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_input_add, "⚡ 1-Tap Save", savePendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)
            if (openAppPendingIntent != null) {
                builder.setContentIntent(openAppPendingIntent)
            }

            notificationManager.notify(notifId, builder.build())
        }

        internal fun postAutoSavedNotification(context: Context, payment: ExtractedUpiPayment) {
            if (!hasPostNotificationPermission(context)) return
            ensureChannel(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notifId = notificationIdFor(payment.amount, payment.payeeName)
            val prep = if (payment.isDebit) "at" else "from"
            val formattedAmount = String.format(java.util.Locale.US, "%,.2f", payment.amount)
            val kind = if (payment.isDebit) "expense" else "income"

            val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("extra_open_tab", 1)
            }
            val openPending = if (openAppIntent != null) {
                PendingIntent.getActivity(
                    context,
                    notifId + 5,
                    openAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                null
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_zenith)
                .setContentTitle("✓ Auto-saved ₹$formattedAmount $prep ${payment.payeeName}")
                .setContentText("Logged $kind in ${payment.detectedCategory} via ${payment.sourceApp}")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
            if (openPending != null) builder.setContentIntent(openPending)
            notificationManager.notify(notifId, builder.build())
        }
    }

    override fun onCreate() {
        super.onCreate()
        MerchantCategoryRules.init(this)
        PendingUpiDetectionStore.init(this)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isServiceConnected = true
        Log.i(tag, "UPI NotificationListenerService binder connected")
    }

    override fun onListenerDisconnected() {
        isServiceConnected = false
        Log.w(tag, "UPI NotificationListenerService binder disconnected; requesting rebind")
        try {
            if (isPermissionGranted(this)) {
                requestRebind(ComponentName(this, UpiNotificationListenerService::class.java))
            }
        } catch (e: Exception) {
            Log.w(tag, "Rebind request failed in onListenerDisconnected: ${e.message}")
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        if (!isAutoDetectionEnabled(this)) return

        MerchantCategoryRules.init(this)
        PendingUpiDetectionStore.init(this)

        val packageName = sbn.packageName ?: return
        val notification = sbn.notification ?: return
        if (shouldIgnoreNotification(packageName, this.packageName, notification.flags)) return
        if (!UpiNotificationParser.isSupportedPackage(packageName)) return

        val (title, effectiveText) = extractNotificationPayload(notification)
        val payment = UpiNotificationParser.parse(packageName, title, effectiveText) ?: return

        // Check deduplication
        val now = System.currentTimeMillis()
        cleanExpiredHashes(now)

        val key = payment.deduplicationKey
        val lastSeen = processedHashes[key]
        if (lastSeen != null && (now - lastSeen) < DEDUPLICATION_WINDOW_MS) {
            Log.d(tag, "Duplicate payment notification ignored")
            return
        }

        processedHashes[key] = now
        Log.i(
            tag,
            "Extracted UPI ${if (payment.isDebit) "debit" else "credit"} via ${payment.sourceApp} (hasUtr=${!payment.upiTransactionId.isNullOrBlank()})"
        )

        // Skip duplicate logging/alerts if already recorded in-app within 5 mins
        if (UpiPaymentBus.wasRecentlyRecorded(payment.amount, payment.upiTransactionId)) {
            Log.d(tag, "Skipping notification for already-recorded UPI payment")
            return
        }

        // Broadcast to active listeners (e.g., UpiPaymentFlow / UpiQrScanModal)
        UpiPaymentBus.post(payment)

        if (isInstantAutoSaveEnabled(this)) {
            val appContext = applicationContext
            serviceScope.launch {
                val inserted = NotificationExpenseActionReceiver.persistNotificationTransaction(
                    context = appContext,
                    db = com.zenith.app.data.database.CashFlowDatabase.getDatabase(appContext),
                    title = payment.payeeName,
                    amount = payment.amount,
                    category = payment.detectedCategory,
                    paymentMethod = payment.sourceApp,
                    upiId = payment.upiId,
                    utr = payment.upiTransactionId,
                    dateMillis = payment.timestamp,
                    isDebit = payment.isDebit
                )
                if (inserted) {
                    PendingUpiDetectionStore.markResolved(payment.amount, payment.upiTransactionId, appContext)
                    ZenithSummaryWidgetProvider.requestUpdate(appContext)
                    try {
                        postAutoSavedNotification(appContext, payment)
                    } catch (_: Exception) {
                    }
                }
            }
            return
        }

        // Persist in 48-hour recovery queue in case shade notification is swiped away
        PendingUpiDetectionStore.enqueue(this, payment)

        // Post system notification with 1-tap quick action when shade permission is granted
        if (!hasPostNotificationPermission(this)) {
            Log.w(tag, "POST_NOTIFICATIONS denied; skipping shade prompt")
            return
        }

        try {
            postExpenseNotification(this, payment)
        } catch (e: SecurityException) {
            Log.w(tag, "Missing notification permission when posting shade prompt: ${e.message}")
        } catch (e: Exception) {
            Log.w(tag, "Failed to post expense notification: ${e.message}")
        }
    }

    private fun cleanExpiredHashes(now: Long) {
        val iterator = processedHashes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > DEDUPLICATION_WINDOW_MS) {
                iterator.remove()
            }
        }
    }
}
