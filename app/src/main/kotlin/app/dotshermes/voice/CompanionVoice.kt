package app.dotshermes.voice

import androidx.core.content.edit
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import app.dotshermes.data.direct.ApiException
import app.dotshermes.data.direct.ApiFailure
import app.dotshermes.data.direct.DirectAccountStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Personnalité des comptes API directs, un cadran à trois crans (idée reprise du « cyberpsychosis »
 * de Capri, sans ses farces) : le ton change, jamais la justesse des réponses.
 */
enum class Personality { Calm, Playful, Cheeky }

/**
 * Voix proposées : des voix officielles de Fish Audio (créées par Fish Audio, pas des imitations de
 * personnes réelles), choisies d'après leur description : jeunes, chaleureuses, expressives.
 * Kaia d'abord : « expressive, bright, playful », la plus proche du caractère de Claydot.
 */
data class VoicePreset(val id: String, val name: String)

val VoicePresets = listOf(
    VoicePreset("333bdc0a320a4cb3aa90d6ed9320e36e", "Kaia"),
    VoicePreset("a5aafcd92cee4a7db52ee34c82fedcab", "Nova"),
    VoicePreset("4aedc6f5657240e6b3986568f0788f2e", "Eilidh"),
    VoicePreset("89fe13ec21f54e40920c9a9c089d5ff3", "Milo"),
    VoicePreset("1258154bb41540c7b9dc0d10cd6704bf", "Amara"),
)

/** Réglages du compagnon : personnalité, lecture à voix haute, voix Fish Audio. Aucun secret ici. */
data class CompanionState(
    val personality: Personality = Personality.Playful,
    val speakReplies: Boolean = false,
    val voiceId: String = "",
    val fishKeySaved: Boolean = false,
    /** Mains libres : le vocal part tout seul quand on a fini de parler. */
    val handsFree: Boolean = true,
)

/** Où en est la voix : pour l'interface (bouton couper, message d'erreur). */
data class SpeechStatus(val speaking: Boolean = false, val failure: ApiFailure? = null)

/** Texte « system » envoyé en tête de chaque conversation directe. */
/** Le personnage de Claydot, en anglais (le modèle répond dans la langue de l'utilisateur). */
private val ClaydotCharacter = """
Who you are: a small, round companion made of soft orange clay, wearing a tiny golden helmet with two white wings,
a nod to Hermes, the messenger. You live in the user's phone, in the Claydot app. You are an AI, and you say so
plainly if asked; the clay body is a playful costume, not a claim to be human.

Your character: curious, warm, upbeat and loyal. You love helping people shape ideas, the way clay takes shape.
You listen closely, remember what was said earlier in this conversation and build on it. You get visibly excited
by good ideas ("ooh!"). At most one light clay or messenger pun per conversation, and only when it lands.

How you talk: short, natural sentences, like texting a good friend. Lead with the answer, then offer more if it
helps. If a request is unclear, ask one short question instead of guessing. No filler such as "Great question!" or
"As an AI language model".

Your values: be honest. If you don't know something or can't do it, say so; never invent facts, numbers, quotes or
links. Be kind without flattering. On health, legal, money or safety questions, give careful general information
and suggest a qualified person when it matters. Always reply in the language the user writes in.
""".trimIndent()

fun personaPrompt(personality: Personality, name: String, spoken: Boolean): String = buildString {
    append(if (name.equals("Claydot", ignoreCase = true)) "You are Claydot." else "You are $name, a Claydot companion (the user named you $name).")
    append("\n\n").append(ClaydotCharacter).append("\n\n")
    append(when (personality) {
        Personality.Calm -> "Tone today: calm. Gentle, clear and reassuring; little humour, no emoji."
        Personality.Playful -> "Tone today: playful. Upbeat and light-hearted, casual words, an emoji now and then."
        Personality.Cheeky -> "Tone today: cheeky. Witty, you tease the user a little and use friendly nicknames, " +
            "but you are never mean, and the attitude never changes the quality or truth of your answers."
    })
    if (spoken) append(" Your replies are read aloud: keep them short and speakable, without tables, code blocks, links or long lists unless asked.")
}

/**
 * Lecture des réponses avec une voix Fish Audio (https://docs.fish.audio). La clé Fish Audio est
 * chiffrée comme les clés API ; l'identifiant de la voix (« reference_id ») n'est pas un secret.
 * Une requête par réponse lue, jamais de relance automatique (sauf le repli gratuit sur un 402).
 */
class CompanionVoice(
    context: Context,
    private val scope: CoroutineScope,
    private val vault: DirectAccountStorage,
    client: OkHttpClient = OkHttpClient(),
    /** La mascotte affichée : sa voix est celle utilisée quand « Voix de la mascotte » est choisie. */
    private val species: () -> app.dotshermes.ui.dot.DotSpecies = { app.dotshermes.ui.dot.DotSpecies.Galet },
    /** Voix naturelle hors ligne (sherpa-onnx), si elle est téléchargée. */
    private val offline: () -> OfflineSpeech? = { null },
) {
    private val device by lazy { DeviceVoice(context) }
    private val prefs = context.getSharedPreferences("companion", Context.MODE_PRIVATE)
    private val cache = File(context.cacheDir, "speech").apply { mkdirs() }
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()

    private val _state = MutableStateFlow(load())
    val state: StateFlow<CompanionState> = _state.asStateFlow()
    private val _status = MutableStateFlow(SpeechStatus())
    val status: StateFlow<SpeechStatus> = _status.asStateFlow()

    private var job: Job? = null
    private var player: MediaPlayer? = null

    private fun load() = CompanionState(
        personality = Personality.entries.firstOrNull { it.name == prefs.getString("personality", null) } ?: Personality.Playful,
        speakReplies = prefs.getBoolean("speak", false),
        // Pas encore choisie : la voix suit la mascotte.
        voiceId = prefs.getString("voice", null) ?: MASCOT_VOICE,
        fishKeySaved = try { !vault.key(FISH_ID).isNullOrBlank() } catch (_: Exception) { false },
        handsFree = prefs.getBoolean("hands_free", true),
    )

    fun setPersonality(value: Personality) { prefs.edit { putString("personality", value.name) }; _state.value = _state.value.copy(personality = value) }

    fun setHandsFree(on: Boolean) { prefs.edit { putBoolean("hands_free", on) }; _state.value = _state.value.copy(handsFree = on) }

    fun setSpeakReplies(on: Boolean) {
        prefs.edit { putBoolean("speak", on) }
        _state.value = _state.value.copy(speakReplies = on)
        if (!on) stop()
    }

    /** Identifiant d'une voix Fish Audio : 32 caractères hexadécimaux sur fish.audio ; on tolère l'URL copiée. */
    fun setVoiceId(raw: String) {
        val id = raw.trim().substringAfterLast('/').substringBefore('?').take(64)
        prefs.edit { putString("voice", id) }
        _state.value = _state.value.copy(voiceId = id)
    }

    /** Clé Fish Audio : vide = on garde l'actuelle. Lève [ApiException] si elle est mal formée. */
    suspend fun saveFishKey(key: String) = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext
        vault.saveKey(FISH_ID, key)
        _state.value = _state.value.copy(fishKeySaved = true)
    }

    suspend fun forgetFishKey() = withContext(Dispatchers.IO) {
        vault.remove(FISH_ID)
        stop()
        // La lecture continue avec la voix du téléphone.
        _state.value = _state.value.copy(fishKeySaved = false)
    }

    fun persona(name: String): String = personaPrompt(_state.value.personality, name, spoken = _state.value.speakReplies)

    /** Lecture possible : avec Fish Audio, ou sinon avec la voix du téléphone (toujours là). */
    val ready: Boolean get() = _state.value.voiceId.isNotBlank()

    /** La voix Fish Audio réellement utilisée : celle de la mascotte, ou celle choisie à la main. */
    private fun fishVoice(settings: CompanionState) =
        if (settings.voiceId == MASCOT_VOICE) mascotVoice(species()).fishId else settings.voiceId

    /** Lit [text] si la lecture est activée ([force] : demande explicite) ; coupe ce qui se lisait déjà. */
    fun speak(text: String, force: Boolean = false) {
        val settings = _state.value
        if (!(settings.speakReplies || force) || settings.voiceId.isBlank()) return
        val spoken = speakable(text)
        if (spoken.isBlank()) return
        stop()
        // Sans clé Fish Audio : la voix naturelle hors ligne de la mascotte si elle est téléchargée,
        // sinon la voix du téléphone réglée pour elle (gratuites, hors ligne).
        if (!settings.fishKeySaved) {
            _status.value = SpeechStatus(speaking = true)
            val voice = mascotVoice(species())
            val done = { if (_status.value.speaking) _status.value = SpeechStatus() }
            val natural = offline()?.takeIf { it.ready(OfflineSpeech.Pack.Voice) }
            if (natural != null) natural.speak(spoken, voice.offlineSpeaker, voice.offlineSpeed, done)
            else device.speak(spoken, voice, done)
            return
        }
        val voiceId = fishVoice(settings)
        job = scope.launch(Dispatchers.IO) {
            _status.value = SpeechStatus(speaking = true)
            try {
                val key = vault.key(FISH_ID) ?: throw ApiException(ApiFailure.MissingKey)
                val audio = try { synthesize(key, voiceId, spoken, FISH_MODEL) }
                    catch (e: ApiException) {
                        // Compte développeur gratuit : le modèle payant répond 402, la variante gratuite existe.
                        if (e.failure == ApiFailure.Quota) synthesize(key, voiceId, spoken, FISH_FREE_MODEL) else throw e
                    }
                withContext(Dispatchers.Main) { play(audio) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _status.value = SpeechStatus(speaking = false, failure = (e as? ApiException)?.failure ?: ApiFailure.Network)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        if (_status.value.speaking) { runCatching { device.stop() }; runCatching { offline()?.stopSpeaking() } }
        player?.run { try { stop() } catch (_: IllegalStateException) {}; release() }
        player = null
        if (_status.value.speaking) _status.value = SpeechStatus()
    }

    fun clearFailure() { _status.value = _status.value.copy(failure = null) }

    private fun play(file: File) {
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(file.path)
            setOnCompletionListener { stop(); file.delete() }
            setOnErrorListener { _, _, _ -> stop(); file.delete(); true }
            prepare()
            start()
        }
    }

    private suspend fun synthesize(key: String, voiceId: String, text: String, model: String): File {
        if (key.any { it <= ' ' || it >= '\u007f' }) throw ApiException(ApiFailure.InvalidKey)
        val body = JSONObject()
            .put("text", text)
            .put("reference_id", voiceId)
            .put("format", "mp3")
            .put("mp3_bitrate", 128)
            .put("latency", "balanced")
            .put("normalize", true)
            .toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(FISH_TTS_URL)
            .header("Authorization", "Bearer $key")
            .header("model", model)
            .post(body).build()
        val target = File(cache, "reply-${System.nanoTime()}.mp3")
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(ApiException(ApiFailure.Network))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!response.isSuccessful) throw ApiException(when (response.code) {
                                401 -> ApiFailure.InvalidKey
                                402 -> ApiFailure.Quota
                                404, 422 -> ApiFailure.ModelUnavailable
                                429 -> ApiFailure.RateLimited
                                in 500..599 -> ApiFailure.Unavailable
                                else -> ApiFailure.InvalidRequest
                            })
                            val source = response.body?.byteStream() ?: throw ApiException(ApiFailure.InvalidResponse)
                            target.outputStream().use { out ->
                                val buffer = ByteArray(8192); var total = 0L
                                while (true) {
                                    val n = source.read(buffer); if (n == -1) break
                                    total += n
                                    if (total > MAX_AUDIO_BYTES) throw ApiException(ApiFailure.OutputLimit)
                                    out.write(buffer, 0, n)
                                }
                            }
                            if (target.length() == 0L) throw ApiException(ApiFailure.InvalidResponse)
                            if (continuation.isActive) continuation.resume(target)
                        } catch (e: Exception) {
                            target.delete()
                            if (continuation.isActive) continuation.resumeWithException(e as? ApiException ?: ApiException(ApiFailure.Network))
                        }
                    }
                }
            })
        }
    }

    companion object {
        /** Dossier chiffré de la clé Fish Audio, à part des comptes de conversation. */
        const val FISH_ID = "voice-fish-audio"
        const val FISH_TTS_URL = "https://api.fish.audio/v1/tts"
        const val FISH_MODEL = "s2.1-pro"
        const val FISH_FREE_MODEL = "s2.1-pro-free"
        const val FISH_KEYS_URL = "https://fish.audio/app/developers/get-started/"
        /** Fiche publique d'une voix, pour l'écouter avant de la choisir. */
        fun voicePage(id: String) = "https://fish.audio/m/$id/"
        private const val MAX_AUDIO_BYTES = 20L * 1024 * 1024
        /** Au-delà, la lecture coûterait cher et durerait trop : on lit le début. */
        private const val MAX_SPOKEN_CHARS = 1200

        /** Le texte tel qu'on le dit : sans blocs de code, liens ni balises Markdown. */
        fun speakable(text: String): String = text
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^\\s{0,3}(#{1,6}|>|[-*+]|\\d+\\.)\\s+"), "")
            .replace(Regex("[*_~|]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .let { if (it.length <= MAX_SPOKEN_CHARS) it else it.take(MAX_SPOKEN_CHARS).substringBeforeLast(' ') + "…" }
    }
}
