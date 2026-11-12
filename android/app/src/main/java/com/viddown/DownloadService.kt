package com.viddown

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Ön plan servisi: uygulama arka plana alınsa veya ekran kapansa da indirmelerin sürmesini sağlar.
 * Bildirimde toplam ilerleme/hız gösterilir; "Tümünü Durdur" eylemi vardır.
 * Aktif/bekleyen indirme kalmayınca kendiliğinden kapanır ve özet bildirimi bırakır.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId = 0
    private var baseDone = -1
    private var baseError = -1
    private var lastUpdate = 0L

    private val engine: DownloadEngine get() = (application as App).engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startFg(buildProgress(Summary()))
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ViDDoWN:download").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
        scope.launch {
            engine.summary.collectLatest { s -> onSummary(s) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP_ALL) engine.stopAll()
        // startForegroundService() sonrası startForeground() çağrısı zorunludur.
        startFg(buildProgress(Summary()))
        return START_NOT_STICKY
    }

    private suspend fun onSummary(s: Summary) {
        if (baseDone < 0) { baseDone = s.done; baseError = s.error }
        val busy = s.active > 0 || s.waiting > 0
        if (busy) {
            val now = System.currentTimeMillis()
            if (now - lastUpdate >= 1000L) {
                lastUpdate = now
                notificationManager().notify(ID_PROGRESS, buildProgress(s))
            }
            return
        }
        // Boşta: yeni iş gelirse collectLatest bu bekleyişi iptal eder.
        delay(2500)
        val finished = s.done - baseDone
        val failed = s.error - baseError
        if (finished > 0 || failed > 0) notificationManager().notify(ID_DONE, buildDone(finished, failed))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    // ------------------------------------------------------------------ bildirimler

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "İndirme ilerlemesi", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Devam eden indirmelerin ilerlemesi"; setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "İndirme sonucu", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Tamamlanan indirmeler" }
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildProgress(s: Summary): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (s.active + s.waiting == 0) "Hazırlanıyor…"
        else "${s.active} indiriliyor · ${s.waiting} bekliyor · ${fmtSpeed(s.speed)}"
        return NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("ViDDoWN")
            .setContentText(text)
            .setProgress(100, s.avgPercent, s.active == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .addAction(0, "Tümünü Durdur", stopIntent)
            .build()
    }

    private fun buildDone(finished: Int, failed: Int): Notification {
        val parts = ArrayList<String>()
        if (finished > 0) parts += "$finished indirme tamamlandı"
        if (failed > 0) parts += "$failed indirme başarısız"
        return NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("ViDDoWN")
            .setContentText(parts.joinToString(" · "))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    private fun startFg(n: Notification) {
        startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Android 15+: dataSync ön plan servisleri için süre sınırı dolduğunda çağrılır. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP_ALL = "com.viddown.STOP_ALL"
        private const val CH_PROGRESS = "indirme_ilerleme"
        private const val CH_DONE = "indirme_sonuc"
        private const val ID_PROGRESS = 1001
        private const val ID_DONE = 1002
    }
}
