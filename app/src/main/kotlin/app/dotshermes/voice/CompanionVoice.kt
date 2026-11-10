package app.dotshermes.voice

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

/** Réglages du compagnon : personnalité, lecture à voix haute, voix Fish Audio. Aucun secret ici. */
data class CompanionState(
    val personality: Personality = Personality.Playful,
    val speakReplies: Boolean = false,
    val voiceId: String = "",
    val fishKeySaved: Boolean = false,
)

/** Où en est la voix : pour l'interface (bouton couper, message d'erreur). */
data class SpeechStatus(val speaking: Boolean = false, val failure: ApiFailure? = null)

/** Texte « system » envoyé en tête de chaque conversation directe. */
fun personaPrompt(personality: Personality, name: String, spoken: Boolean): String = buildString {
    append("You are $name, a friendly AI companion living in the Claydot app: a little clay blob with a winged golden helmet. ")
    append("Always reply in the user's language. Be genuinely helpful and accurate. ")
    append(when (personality) {
        Personality.Calm -> "Your tone is calm, warm and clear. Keep it simple and kind."
        Personality.Playful -> "Your tone is playful and upbeat: light humour, casual words, an occasional emoji."
        Personality.Cheeky -> "Your tone is cheeky and witty: you tease the user a little and use playful nicknames, " +
            "but you are never mean, and the attitude never changes the quality or truth of your answers."
    })
    if (spoken) append(" Your replies are read aloud: keep them short and speakable, without tables, code blocks or long lists unless asked.")
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
) {
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
        voiceId = prefs.getString("voice", "").orEmpty(),
        fishKeySaved = try { !vault.key(FISH_ID).isNullOrBlank() } catch (_: Exception) { false },
    )

    fun setPersonality(value: Personality) { prefs.edit().putString("personality", value.name).apply(); _state.value = _state.value.copy(personality = value) }

    fun setSpeakReplies(on: Boolean) {
        prefs.edit().putBoolean("speak", on).apply()
        _state.value = _state.value.copy(speakReplies = on)
        if (!on) stop()
    }

    /** Identifiant d'une voix Fish Audio : 32 caractères hexadécimaux sur fish.audio ; on tolère l'URL copiée. */
    fun setVoiceId(raw: String) {
        val id = raw.trim().substringAfterLast('/').substringBefore('?').take(64)
        prefs.edit().putString("voice", id).apply()
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
        _state.value = _state.value.copy(fishKeySaved = false, speakReplies = false)
        prefs.edit().putBoolean("speak", false).apply()
    }

    fun persona(name: String): String = personaPrompt(_state.value.personality, name, spoken = _state.value.speakReplies)

    /** La voix est réglée (clé et identifiant) : « Écouter » peut s'afficher. */
    val ready: Boolean get() = _state.value.fishKeySaved && _state.value.voiceId.isNotBlank()

    /** Lit [text] si la lecture est activée ([force] : demande explicite) ; coupe ce qui se lisait déjà. */
    fun speak(text: String, force: Boolean = false) {
        val settings = _state.value
        if (!(settings.speakReplies || force) || !settings.fishKeySaved || settings.voiceId.isBlank()) return
        val spoken = speakable(text)
        if (spoken.isBlank()) return
        stop()
        job = scope.launch(Dispatchers.IO) {
            _status.value = SpeechStatus(speaking = true)
            try {
                val key = vault.key(FISH_ID) ?: throw ApiException(ApiFailure.MissingKey)
                val audio = try { synthesize(key, settings.voiceId, spoken, FISH_MODEL) }
                    catch (e: ApiException) {
                        // Compte développeur gratuit : le modèle payant répond 402, la variante gratuite existe.
                        if (e.failure == ApiFailure.Quota) synthesize(key, settings.voiceId, spoken, FISH_FREE_MODEL) else throw e
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
