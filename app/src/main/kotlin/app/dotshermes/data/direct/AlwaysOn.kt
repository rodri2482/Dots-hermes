package app.dotshermes.data.direct

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import app.dotshermes.R

/**
 * « Toujours actif (24 h/24) » : un petit service de premier plan, sans travail propre, qui garde le
 * processus de Claydot en vie pour que l'écoute de WhatsApp et les routines « message » ne soient jamais
 * mises en sommeil (Samsung, Xiaomi… coupent volontiers les apps en arrière-plan). Une notification
 * discrète le signale ; il repart après un redémarrage du téléphone si l'utilisateur l'a activé.
 */
class AlwaysOnService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this), type) }
            .onFailure { stopSelf(); return START_NOT_STICKY }
        // Rebranche l'écoute de WhatsApp au passage, si Android l'avait coupée.
        ChatLog.keepListening(this)
        return START_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 4244
        private const val CHANNEL = "claydot_always_on"
        private const val PREFS = "always-on"

        fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)

        fun set(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("on", on) }
            if (on) start(context) else context.stopService(Intent(context, AlwaysOnService::class.java))
        }

        /** Au démarrage de l'app ou du téléphone : relancé si l'utilisateur l'a activé. */
        fun start(context: Context) {
            if (!enabled(context)) return
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, AlwaysOnService::class.java)) }
        }

        private fun notification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.always_on_channel), NotificationManager.IMPORTANCE_MIN))
            }
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                android.app.PendingIntent.getActivity(context, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
            }
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_hermes)
                .setContentTitle(context.getString(R.string.always_on_notification))
                .setContentIntent(open)
                .setOngoing(true).setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        }

        /**
         * Le guide « Don't kill my app » (projet libre urbandroid-team/dont-kill-my-app) pour la marque du
         * téléphone : les réglages qui empêchent Samsung, Xiaomi, Huawei… d'arrêter Claydot.
         */
        fun guideUrl(): String {
            val brand = Build.MANUFACTURER.lowercase().replace(" ", "-")
            val known = setOf("samsung", "xiaomi", "huawei", "honor", "oneplus", "oppo", "vivo", "realme", "motorola", "nokia",
                "sony", "asus", "google", "lenovo", "meizu", "tecno", "infinix", "itel", "blackview", "wiko")
            return "https://dontkillmyapp.com/" + if (brand in known) brand else ""
        }
    }
}
