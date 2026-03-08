package com.forge.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class ForgeApplication : Application() {

    companion object {
        const val BUILD_CHANNEL_ID = "forge_build_channel"
        const val BUILD_NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BUILD_CHANNEL_ID,
                "Build Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows Android app build progress"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}