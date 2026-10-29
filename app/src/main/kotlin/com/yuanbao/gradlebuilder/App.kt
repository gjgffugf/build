package com.yuanbao.gradlebuilder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "构建任务",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "显示当前 Gradle 构建进度" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "gradle_builder_channel"

        @Volatile
        lateinit var instance: App
            private set

        val ctx: Context get() = instance
    }
}
