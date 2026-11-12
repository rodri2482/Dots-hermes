package app.dotshermes

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.content.Intent
import android.os.Bundle
import app.dotshermes.push.Notifications
import app.dotshermes.update.UpdateNotifications
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.dotshermes.ui.HermesApp

class MainActivity : ComponentActivity() {

    /** Notification touchée : on ouvre la conversation du dot qui a écrit. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openChatFrom(intent)
    }

    private fun openChatFrom(intent: Intent?) {
        // L'agent d'abord (sa conversation est choisie sur sa propre connexion, pas sur celle affichée).
        val agent = intent?.getStringExtra(Notifications.EXTRA_AGENT)
        val chat = intent?.getStringExtra(Notifications.EXTRA_CHAT)
        if (agent != null && AppContainer.agents.store.get(agent) != null) {
            AppContainer.agents.select(agent)
            chat?.let { AppContainer.agents.repo(agent).selectChat(it) }
        } else {
            chat?.let { AppContainer.chatRepository.selectChat(it) }
        }
        // Partage depuis une autre app (texte, lien, photo) ou widget : le champ de saisie le reçoit.
        when {
            intent?.action == Intent.ACTION_SEND -> {
                val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
                    .filter { it.isNotBlank() }.distinct().joinToString("\n")
                @Suppress("DEPRECATION")
                val image = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri)?.takeIf { intent.type?.startsWith("image/") == true }
                if (text.isNotBlank() || image != null) IncomingContent.pending.value = Incoming(text.take(4_000), image, focus = true)
            }
            intent?.getBooleanExtra(IncomingContent.EXTRA_ASK, false) == true -> IncomingContent.pending.value = Incoming(focus = true)
        }
        // Notification « mise à jour disponible » : l'interface ouvre le dialogue.
        if (intent?.getBooleanExtra(UpdateNotifications.EXTRA_OPEN_UPDATE, false) == true) AppContainer.updater.requestPrompt()
    }

    // Refus sans conséquence : l'app marche, on ne reçoit simplement pas de push.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // Barres système transparentes à icônes sombres : l'interface reste claire de bord à bord.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { HermesApp() }
        openChatFrom(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
