package app.dotshermes

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.dotshermes.push.Notifications
import app.dotshermes.update.UpdateNotifications
import app.dotshermes.update.UpdateWorker
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Point d'entrée du processus, y compris quand FCM le réveille en arrière-plan sans activité :
 * on y prépare le conteneur, le canal de notifications, la présence et le jeton push.
 */
class HermesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
        Notifications.createChannel(this)
        UpdateNotifications.createChannel(this)
        UpdateWorker.schedule(this)
        // Routines : chaque routine active retrouve son prochain passage (après une mise à jour, un redémarrage…).
        AppContainer.routines.rescheduleAll()
        // Écoute de WhatsApp (appel, routines « message ») : rebranchée si Android l'avait coupée.
        app.dotshermes.data.direct.ChatLog.keepListening(this)
        app.dotshermes.data.direct.AlwaysOnService.start(this)

        // Premier plan = au moins une activité visible. Le gateway ne pousse que quand l'app est cachée.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onVisibility(true)
            override fun onStop(owner: LifecycleOwner) = onVisibility(false)
        })

        // Construite sans google-services.json, l'app n'a pas Firebase : tout marche, sans push.
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                AppContainer.chatRepository.setPushToken(token)
            }
        }
    }

    private fun onVisibility(foreground: Boolean) {
        AppContainer.inForeground = foreground
        AppContainer.chatRepository.setForeground(foreground)
        if (foreground) Notifications.clear(this)
    }
}
