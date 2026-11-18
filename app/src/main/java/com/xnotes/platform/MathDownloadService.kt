package com.xnotes.platform

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/** Keeps the process alive while [MathOcr] downloads a model, so closing the app does not stop it. */
class MathDownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        // Once asked to start in the foreground it has to, even when the download already ended.
        val shown = runCatching { ServiceCompat.startForeground(this, MathOcr.NOTIFICATION_ID, MathOcr.notification(this), type) }.isSuccess
        if (shown) MathOcr.attached(this) else finish()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        finish()
    }

    override fun onDestroy() {
        MathOcr.detached(this)
        super.onDestroy()
    }

    internal fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
