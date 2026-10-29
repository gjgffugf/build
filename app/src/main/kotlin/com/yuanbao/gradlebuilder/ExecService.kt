package com.yuanbao.gradlebuilder

import android.content.pm.ServiceInfo
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService

/** 仅用于构建期间保活 + 通知，真正进程由 ExecEngine 持有（同进程单例共享） */
class ExecService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("Gradle 构建中")
            .setContentText("正在执行构建任务，请勿清除后台")
            .setSmallIcon(R.drawable.ic_stat_build)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
