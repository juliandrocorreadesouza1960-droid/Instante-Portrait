package com.instantportrait.exif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Mantém o processo classificado como uso legítimo de câmera enquanto a captura
 * contínua está ativa. Em OEMs agressivos (ex.: Motorola), isso reduz o risco de
 * o sistema matar o app no meio da sessão — complementa [FLAG_KEEP_SCREEN_ON] e
 * expo-keep-awake (que não evitam políticas de bateria da fabricante).
 */
class CaptureKeepAliveService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    try {
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val ch = NotificationChannel(
          CHANNEL_ID,
          "Captura contínua",
          NotificationManager.IMPORTANCE_LOW
        ).apply {
          setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
      }

      val smallIcon = applicationInfo.icon.takeIf { it != 0 }
        ?: android.R.drawable.ic_menu_camera

      val notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("AutoFrame")
        .setContentText("Captura em curso — pode minimizar, não feche o app.")
        .setSmallIcon(smallIcon)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

      if (Build.VERSION.SDK_INT >= 34) {
        startForeground(
          NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
    } catch (e: Exception) {
      Log.e(TAG, "startForeground failed", e)
      stopSelf()
      return START_NOT_STICKY
    }

    return START_STICKY
  }

  override fun onDestroy() {
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
      }
    }
    super.onDestroy()
  }

  companion object {
    private const val TAG = "CaptureKeepAlive"
    private const val CHANNEL_ID = "autoframe_capture_v1"
    private const val NOTIFICATION_ID = 71042

    fun start(ctx: Context) {
      val app = ctx.applicationContext
      val i = Intent(app, CaptureKeepAliveService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        app.startForegroundService(i)
      } else {
        @Suppress("DEPRECATION")
        app.startService(i)
      }
    }

    fun stop(ctx: Context) {
      val app = ctx.applicationContext
      app.stopService(Intent(app, CaptureKeepAliveService::class.java))
    }
  }
}
