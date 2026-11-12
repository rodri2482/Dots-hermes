package app.dotshermes.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.dotshermes.ui.dot.DotSpecies
import java.util.Locale

/**
 * La voix de chaque mascotte. Avec une clé Fish Audio : une voix publique de Fish Audio choisie pour son
 * caractère (pas une imitation de personne réelle). Sans clé : la voix du téléphone, avec une hauteur et
 * un débit propres à la mascotte, pour que chacune sonne différemment.
 *
 * - Galet (orange, pétase ailé, le messager) : vif et joyeux → « Verity », voix jeune, énergique et amicale.
 * - Nuage (bleu ciel, antenne) : doux et rêveur → « Selene », voix calme et apaisante.
 * - Pavé (raisin, pousse) : posé et solide → « Slax », voix claire, nette et mesurée.
 */
data class MascotVoice(val fishId: String, val fishName: String, val pitch: Float, val rate: Float,
    /** Voix naturelle hors ligne (KittenTTS, 8 voix : pair = homme, impair = femme) et sa vitesse. */
    val offlineSpeaker: Int, val offlineSpeed: Float)

fun mascotVoice(species: DotSpecies): MascotVoice = when (species) {
    DotSpecies.Galet -> MascotVoice("711cf3ed00ab441a8f54a45058047b7a", "Verity", pitch = 1.22f, rate = 1.08f, offlineSpeaker = 6, offlineSpeed = 1.08f)
    DotSpecies.Nuage -> MascotVoice("b347db033a6549378b48d00acb0d06cd", "Selene", pitch = 1.1f, rate = 0.92f, offlineSpeaker = 3, offlineSpeed = 0.95f)
    DotSpecies.Pave -> MascotVoice("c5f56a6cc2ec4fa8920cb4c5889a3fb7", "Slax", pitch = 0.82f, rate = 0.96f, offlineSpeaker = 4, offlineSpeed = 0.95f)
}

/** Choix « voix de la mascotte » : la voix suit la mascotte choisie (réglage par défaut). */
const val MASCOT_VOICE = "mascot"

/** La synthèse vocale du téléphone (hors ligne, gratuite), préparée une fois puis réutilisée. */
internal class DeviceVoice(context: Context) {
    private var ready = false
    private var pending: (() -> Unit)? = null
    private var onDone: (() -> Unit)? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (!ready) android.util.Log.w("ClaydotVoice", "phone speech engine unavailable: $status")
        if (ready) pending?.invoke() else onDone?.invoke()
        pending = null
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onDone?.invoke() }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { onDone?.invoke() }
            override fun onError(utteranceId: String?, errorCode: Int) {
                android.util.Log.w("ClaydotVoice", "phone speech failed: $errorCode"); onDone?.invoke()
            }
        })
    }

    fun speak(text: String, voice: MascotVoice, done: () -> Unit) {
        onDone = done
        val go = {
            tts.language = Locale.getDefault()
            tts.setPitch(voice.pitch)
            tts.setSpeechRate(voice.rate)
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "claydot-" + System.nanoTime())
            if (result != TextToSpeech.SUCCESS) { android.util.Log.w("ClaydotVoice", "phone speech refused: $result"); onDone?.invoke() }
            Unit
        }
        if (ready) go() else pending = go
    }

    fun stop() { pending = null; runCatching { tts.stop() } }
}
