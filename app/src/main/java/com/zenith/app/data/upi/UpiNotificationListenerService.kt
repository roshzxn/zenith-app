package com.zenith.app.data.upi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Background service that intercepts payment notifications from Indian UPI apps
 * (Google Pay, PhonePe, Paytm, CRED, BHIM) and banking apps in real-time.
 *
 * Privacy & Security:
 * - All parsing is performed 100% on-device.
 * - Only transaction amounts, merchant names, and UTR references are extracted.
 * - Non-transaction notifications (passwords, OTPs, personal messages) are immediately discarded.
 */
class UpiNotificationListenerService : NotificationListenerService() {

    private val tag = "UpiNotifListener"

    companion object {
        // Cache of recently processed transaction hashes to prevent duplicate logging
        // when both UPI app and Bank emit notifications for the same payment.
        private val processedHashes = ConcurrentHashMap<String, Long>()
        private const val DEDUPLICATION_WINDOW_MS = 300_000L // 5 minutes

        private const val PREFS_NAME = "zenith_upi_prefs"
        private const val KEY_AUTO_DETECT = "upi_auto_detection_enabled"

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
        }

        /**
         * Checks whether the user has granted Notification Access to this app.
         */
        fun isPermissionGranted(context: Context): Boolean {
            val packageName = context.packageName
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            return flat != null && flat.contains(packageName)
        }

        /**
         * Opens the Android Settings screen where the user can enable Notification Access.
         */
        fun openPermissionSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
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
            UpiPaymentBus.post(testPayment)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        if (!isAutoDetectionEnabled(this)) return

        val packageName = sbn.packageName ?: return
        if (!UpiNotificationParser.isSupportedPackage(packageName)) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val effectiveText = bigText?.takeIf { it.isNotBlank() } ?: text

        val payment = UpiNotificationParser.parse(packageName, title, effectiveText) ?: return

        // Check deduplication
        val now = System.currentTimeMillis()
        cleanExpiredHashes(now)

        val key = payment.deduplicationKey
        val lastSeen = processedHashes[key]
        if (lastSeen != null && (now - lastSeen) < DEDUPLICATION_WINDOW_MS) {
            Log.d(tag, "Duplicate payment notification ignored: $key")
            return
        }

        processedHashes[key] = now
        Log.i(tag, "Extracted UPI Payment: ${payment.amount} at ${payment.payeeName} via ${payment.sourceApp}")

        // Broadcast to in-app event bus
        UpiPaymentBus.post(payment)

        // Post system notification with 1-tap quick action
        try {
            postExpenseNotification(this, payment)
        } catch (e: Exception) {
            Log.w(tag, "Failed to post expense notification: ${e.message}")
        }
    }

    private fun postExpenseNotification(context: Context, payment: ExtractedUpiPayment) {
        val channelId = "zenith_upi_tracker"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "UPI Expense Tracker",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when UPI expenses are detected with 1-tap logging"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notifId = (payment.amount.toInt() * 31 + payment.payeeName.hashCode()) and 0x7FFFFFFF

        // Intent to open MainActivity
        val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("extra_upi_amount", payment.amount)
            putExtra("extra_upi_payee", payment.payeeName)
            putExtra("extra_upi_category", payment.detectedCategory)
            putExtra("extra_upi_utr", payment.upiTransactionId)
            putExtra("extra_upi_vpa", payment.upiId)
            putExtra("extra_upi_app", payment.sourceApp)
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 1: 1-Tap Save
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
        }
        val savePendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 1,
            saveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 2: Dismiss
        val dismissIntent = Intent(context, NotificationExpenseActionReceiver::class.java).apply {
            action = NotificationExpenseActionReceiver.ACTION_DISMISS_EXPENSE
            putExtra(NotificationExpenseActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 2,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("💸 Record ₹${String.format(java.util.Locale.US, "%,.2f", payment.amount)} at ${payment.payeeName}?")
            .setContentText("Detected via ${payment.sourceApp} • Tap to view or 1-tap save")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_input_add, "⚡ 1-Tap Save", savePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)
            .build()

        notificationManager.notify(notifId, notification)
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
