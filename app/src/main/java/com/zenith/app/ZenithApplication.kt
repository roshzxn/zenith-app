package com.zenith.app

import android.app.Application
import com.zenith.app.data.network.SupabaseClientConfig
import com.zenith.app.data.notifications.ReminderAlertEngine
import com.zenith.app.data.upi.MerchantCategoryRules
import com.zenith.app.data.upi.PendingUpiDetectionStore
import com.zenith.app.data.upi.UpiNotificationListenerService

class ZenithApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        SupabaseClientConfig.init(this)
        MerchantCategoryRules.init(this)
        PendingUpiDetectionStore.init(this)
        UpiNotificationListenerService.ensureChannel(this)
        ReminderAlertEngine.ensureChannel(this)
        ReminderAlertEngine.scheduleDailyCheck(this)
    }

    companion object {
        lateinit var instance: ZenithApplication
            private set
    }
}
