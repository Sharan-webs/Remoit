package com.remoit.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the job alive even if the user leaves the app (long videos take a while). */
class RenderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: Job? = null
    private var lastNotif = 0L
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotif("Starting", -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val spec = Hub.pending
        if (spec == null) { stopSelf(); return Service.START_NOT_STICKY }
        if (running?.isActive == true) return Service.START_NOT_STICKY
        Hub.pending = null

        val pm = getSystemService(PowerManager::class.java)
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "remoit:render").also { it.acquire(10 * 60 * 60 * 1000L) }

        running = scope.launch {
            val me = coroutineContext[Job]
            try {
                val p = Runner.run(
                    applicationContext, spec,
                    { stage, prog, eta, detail ->
                        Hub.state.value = Hub.State.Running(stage, prog, eta, detail)
                        val now = System.currentTimeMillis()
                        if (now - lastNotif > 1000) {
                            lastNotif = now
                            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(stage, (prog * 100).toInt()))
                        }
                    },
                    { me?.isActive == false }
                )
                Hub.state.value = Hub.State.Done(p)
                notifyDone()
            } catch (e: CancellationException) {
                Hub.state.value = Hub.State.Idle
                throw e
            } catch (e: Throwable) {
                Hub.state.value = Hub.State.Failed(friendly(e))
            } finally {
                try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) { }
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun friendly(e: Throwable): String = when {
        e is OutOfMemoryError -> "Not enough memory on this phone for this file."
        e is IllegalArgumentException -> e.message ?: "Unsupported file."
        e is IllegalStateException -> e.message ?: "The video encoder could not finish."
        e.javaClass.name.startsWith("android.media") ->
            "This phone's video codec could not finish the job (" + (e.message ?: e.javaClass.simpleName) + ")."
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Removing text", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Result ready", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildNotif(text: String, pct: Int): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("remoit is removing the text")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
        if (pct >= 0) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun notifyDone() {
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Your remoit result is ready")
            .setContentText("Tap to preview and download")
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n) } catch (e: Exception) { }
    }

    companion object {
        private const val NOTIF_ID = 4711
        private const val CHANNEL = "render"
        private const val CHANNEL_DONE = "done"
    }
}
