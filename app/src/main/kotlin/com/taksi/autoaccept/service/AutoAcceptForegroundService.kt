package com.taksi.autoaccept.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.taksi.autoaccept.R
import com.taksi.autoaccept.core.model.FilterSettings
import com.taksi.autoaccept.core.rules.RuleEngine
import com.taksi.autoaccept.data.SettingsRepository
import com.taksi.autoaccept.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * "Calisiyor" durumunu tasiyan on plan servisi.
 *
 * Cagrilari bu servis yakalamaz; onu erisilebilirlik servisi yapar ve tek
 * anahtari [FilterSettings.enabled]. Bu servisin isi ucu:
 *  - kullaniciya arka planda calistigini gosteren kalici bir bildirim,
 *  - bildirimden tek dokunusla durdurma,
 *  - surecin bellek baskisinda oldurulme ihtimalini dusurmek.
 *
 * Bu yuzden servis baslatilamazsa islevsellik bozulmaz, yalnizca gorunurluk
 * kaybolur.
 */
class AutoAcceptForegroundService : Service() {

    private lateinit var repository: SettingsRepository
    private val scope = CoroutineScope(SupervisorJob())
    private var observing = false

    override fun onCreate() {
        super.onCreate()
        repository = SettingsRepository(applicationContext)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Kullanici bildirimden durdurdu: tek anahtari kapat, sonra kendini bitir.
            scope.launch {
                repository.update { it.copy(enabled = false) }
                stopSelf()
            }
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(FilterSettings(enabled = true), 0))
        observeSettings()
        return START_STICKY
    }

    /** Ayar degistikce bildirimi tazele; kapatilirsa kendini durdur. */
    private fun observeSettings() {
        if (observing) return
        observing = true
        combine(repository.settings, repository.counters) { settings, counters ->
            settings to counters.acceptsToday
        }
            .onEach { (settings, acceptsToday) ->
                if (!settings.enabled) {
                    stopSelf()
                    return@onEach
                }
                notificationManager().notify(NOTIFICATION_ID, buildNotification(settings, acceptsToday))
            }
            .launchIn(scope)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(settings: FilterSettings, acceptsToday: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AutoAcceptForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (settings.dryRun) "Deneme modu — düğmeye basılmıyor" else "Otomatik kabul çalışıyor"
            )
            .setContentText(summary(settings, acceptsToday))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary(settings, acceptsToday)))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Durdur", stop)
            .build()
    }

    private fun summary(settings: FilterSettings, acceptsToday: Int): String {
        val range = when {
            settings.maxAmount > 0.0 ->
                "${RuleEngine.format(settings.minAmount)}–${RuleEngine.format(settings.maxAmount)} TL"

            settings.minAmount > 0.0 -> "${RuleEngine.format(settings.minAmount)} TL ve üzeri"
            else -> "tutar sınırı yok"
        }
        return buildString {
            append(range)
            if (settings.maxDistanceKm > 0.0) {
                append(" · en fazla ${RuleEngine.format(settings.maxDistanceKm)} km")
            }
            append(" · bugün $acceptsToday kabul")
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Otomatik kabul durumu",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Otomatik kabulün çalıştığını gösteren kalıcı bildirim"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.taksi.autoaccept.action.STOP"
        private const val CHANNEL_ID = "oto_kabul_durum"
        private const val NOTIFICATION_ID = 1

        /** Basarisiz olursa yutulur: bildirim gorunmez ama kabul islevi etkilenmez. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AutoAcceptForegroundService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, AutoAcceptForegroundService::class.java))
            }
        }
    }
}
