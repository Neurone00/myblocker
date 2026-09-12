package com.neurone.myblocker

import android.app.Application
import com.neurone.myblocker.stats.StatsStore
import com.neurone.myblocker.system.ListUpdateJobService
import com.neurone.myblocker.system.Notifications

class MyBlockerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        StatsStore.init(this)
        ListUpdateJobService.schedule(this)
    }
}
