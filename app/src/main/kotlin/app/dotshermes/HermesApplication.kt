package app.dotshermes

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.dotshermes.push.Notifications
import app.dotshermes.update.UpdateNotifications
import app.dotshermes.update.UpdateWorker

/**
 * Point d'entrée du processus (y compris réveillé par une routine ou l'écoute de WhatsApp) :
 * on y prépare le conteneur, les canaux de notifications, les routines et la présence.
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

        // Premier plan = au moins une activité visible (les notifications de réponse ne s'affichent qu'en arrière-plan).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onVisibility(true)
            override fun onStop(owner: LifecycleOwner) = onVisibility(false)
        })
    }

    private fun onVisibility(foreground: Boolean) {
        AppContainer.inForeground = foreground
        AppContainer.chatRepository.setForeground(foreground)
        if (foreground) Notifications.clear(this)
    }
}
