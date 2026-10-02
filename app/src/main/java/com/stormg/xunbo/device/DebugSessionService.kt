package com.stormg.xunbo.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.LifecycleService
import com.stormg.xunbo.MainActivity
import com.stormg.xunbo.R
import com.stormg.xunbo.XunboApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DebugSessionService : LifecycleService() {
    private val container get() = (application as XunboApplication).container
    private var starting: Job? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("debug", "硬件调试会话", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == "STOP") {
            container.stopSession()
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, DebugSessionService::class.java).setAction("STOP"),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification.Builder(this, "debug")
                .setSmallIcon(R.drawable.ic_launcher).setContentTitle("寻播：手动调试会话")
                .setContentText("相机运行中；点击停止释放设备").setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cameraType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
                val usbType = if (container.usb.isReady) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
                val types = cameraType or usbType
                startForeground(1, notification, types)
            } else {
                startForeground(1, notification)
            }
            if (starting == null) {
                starting =
                    container.scope.launch {
                        try {
                            container.beginSession(this@DebugSessionService)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            container.serviceError.value = "SESSION_START_FAILED"
                            stopSelf()
                        }
                    }
            }
        } catch (_: Exception) {
            container.serviceError.value = "FOREGROUND_START_FAILED"
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        container.debug.stop()
        starting?.cancel()
        container.scope.launch {
            container.endSession(this@DebugSessionService)
        }
        super.onDestroy()
    }
}
