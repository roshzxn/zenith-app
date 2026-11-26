package com.zenith.app

import android.app.Application
import com.zenith.app.data.network.SupabaseClientConfig
import com.zenith.app.data.notifications.ReminderAlertEngine
import com.zenith.app.data.upi.MerchantCategoryRules
import com.zenith.app.data.upi.PendingUpiDetectionStore
import com.zenith.app.data.upi.UpiNotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ZenithApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        SupabaseClientConfig.init(this)
        // Warm non-UI stores & alarm scheduling off the main thread to speed cold start
        appScope.launch {
            MerchantCategoryRules.init(this@ZenithApplication)
            PendingUpiDetectionStore.init(this@ZenithApplication)
            UpiNotificationListenerService.ensureChannel(this@ZenithApplication)
            ReminderAlertEngine.ensureChannel(this@ZenithApplication)
            ReminderAlertEngine.scheduleDailyCheck(this@ZenithApplication)
        }
    }

    companion object {
        lateinit var instance: ZenithApplication
            private set
    }
}
