package com.gios.walltakerchanger


import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat


class Service : Service() {
    private val channelId = "walltaker_changer_service"

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        startForeground(1, serviceNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Updater.update(this)
        return START_STICKY
    }


    override fun onDestroy() {
        Updater.stop()
    }

    private fun serviceNotification() =
        NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle("Walltaker Changer is connected")
            .setContentText("Listening for wallpaper updates")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val notificationManager =
                        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    val channel = NotificationChannel(
                        channelId,
                        "Walltaker Changer service",
                        NotificationManager.IMPORTANCE_LOW
                    )
                    notificationManager.createNotificationChannel(channel)
                }
            }
            .build()
}
