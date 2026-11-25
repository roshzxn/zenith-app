package com.zenith.app

import android.app.Application
import com.zenith.app.data.network.SupabaseClientConfig

class ZenithApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        SupabaseClientConfig.init(this)
    }

    companion object {
        lateinit var instance: ZenithApplication
            private set
    }
}
