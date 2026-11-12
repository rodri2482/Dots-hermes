package app.dotshermes

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Ce qui arrive de l'extérieur pour la conversation : un texte ou un lien partagé, une photo partagée,
 * ou le widget « Demander à Claydot » (champ de saisie ouvert). L'écran de discussion le prend une fois.
 */
data class Incoming(val text: String = "", val image: Uri? = null, val focus: Boolean = false)

object IncomingContent {
    val pending = MutableStateFlow<Incoming?>(null)
    const val EXTRA_ASK = "app.claydot.ASK"
}
