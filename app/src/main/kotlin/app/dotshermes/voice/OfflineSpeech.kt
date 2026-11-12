package app.dotshermes.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * La parole hors ligne, avec sherpa-onnx (k2-fsa, Apache-2.0) :
 * - une voix naturelle (KittenTTS nano 0.8, Apache-2.0, 31 Mo, huit voix) : chaque mascotte a la sienne ;
 * - la dictée hors ligne (Moonshine tiny, MIT, 124 Mo) : les vocaux transcrits sur le téléphone,
 *   sans clé ni quota (comptes « sur ce téléphone », et en secours quand Groq refuse).
 * Les deux se téléchargent une fois, depuis les réglages ; rien n'est inclus dans l'APK.
 */
class OfflineSpeech(private val context: Context, private val scope: CoroutineScope) {
    enum class Pack { Voice, Typing }

    sealed interface State {
        data object Missing : State
        data class Downloading(val fraction: Float) : State
        data object Ready : State
        data object Failed : State
    }

    private class Part(val url: String, val name: String, val size: Long, val sha256: String?)

    private val root = File(context.filesDir, "speech-models")
    private fun dir(pack: Pack) = File(root, if (pack == Pack.Voice) "kitten-nano-en-v0_8-int8" else "moonshine-tiny-en-int8")

    private val parts = mapOf(
        Pack.Voice to listOf(Part("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-nano-en-v0_8-int8.tar.bz2",
            "kitten.tar.bz2", 31_200_000, "6fa5be852612ce761094ba74ee6123b4fc4acfefa79bf64dc63acae4a83af2fd")),
        Pack.Typing to MOONSHINE_REV.let { rev ->
            val base = "https://huggingface.co/csukuangfj/sherpa-onnx-moonshine-tiny-en-int8/resolve/$rev/"
            listOf(
                Part(base + "preprocess.onnx", "preprocess.onnx", 6_800_738, "f33addce61a143460fe753b5ee5b7db255e5140b5b779c065b94f6c83ff0bf4e"),
                Part(base + "encode.int8.onnx", "encode.int8.onnx", 18_249_187, "8774dfba578de027ec6595c2c654a0836434489bc963a0db124a7f181f571acb"),
                Part(base + "uncached_decode.int8.onnx", "uncached_decode.int8.onnx", 53_216_096, "216737000dd5881a17aa043f6bbd286add33e4c3b0ae257153e2ec15438bdc41"),
                Part(base + "cached_decode.int8.onnx", "cached_decode.int8.onnx", 45_264_830, "2aff28bba6a03d8dcf5c9feac45462629bae37317442299f28115ad09da773f6"),
                Part(base + "tokens.txt", "tokens.txt", 436_688, null),
            )
        },
    )

    /** Taille annoncée dans les réglages. */
    fun sizeBytes(pack: Pack) = parts.getValue(pack).sumOf { it.size }

    private val _states = MutableStateFlow(Pack.entries.associateWith { if (ready(it)) State.Ready else State.Missing })
    val states: StateFlow<Map<Pack, State>> = _states.asStateFlow()
    private val jobs = HashMap<Pack, Job>()
    private val http by lazy { OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

    fun ready(pack: Pack) = File(dir(pack), DONE).isFile

    private fun set(pack: Pack, state: State) = _states.update { it + (pack to state) }

    fun download(pack: Pack) {
        if (jobs[pack]?.isActive == true || ready(pack)) return
        jobs[pack] = scope.launch(Dispatchers.IO) {
            val target = dir(pack)
            val temp = File(root, pack.name + ".part").apply { deleteRecursively(); mkdirs() }
            try {
                val total = sizeBytes(pack).toFloat()
                var done = 0L
                for (part in parts.getValue(pack)) {
                    val file = File(temp, part.name)
                    val digest = MessageDigest.getInstance("SHA-256")
                    http.newCall(Request.Builder().url(part.url).build()).execute().use { response ->
                        check(response.isSuccessful) { "HTTP ${response.code}" }
                        checkNotNull(response.body).byteStream().use { input ->
                            file.outputStream().use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    if (!isActive) return@launch
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n); digest.update(buffer, 0, n); done += n
                                    set(pack, State.Downloading((done / total).coerceIn(0f, 0.99f)))
                                }
                            }
                        }
                    }
                    part.sha256?.let { check(digest.digest().joinToString("") { b -> "%02x".format(b) } == it) { "checksum" } }
                }
                // La voix arrive en archive tar.bz2 (modèle + données de prononciation espeak-ng) : on la déplie.
                if (pack == Pack.Voice) untar(File(temp, "kitten.tar.bz2"), temp).also { File(temp, "kitten.tar.bz2").delete() }
                val unpacked = if (pack == Pack.Voice) File(temp, "kitten-nano-en-v0_8-int8") else temp
                target.deleteRecursively()
                check(unpacked.renameTo(target) || unpacked.copyRecursively(target, overwrite = true))
                File(target, DONE).writeText("ok")
                temp.deleteRecursively()
                set(pack, State.Ready)
            } catch (_: Exception) {
                temp.deleteRecursively()
                set(pack, State.Failed)
            }
        }
    }

    fun cancel(pack: Pack) { jobs.remove(pack)?.cancel(); File(root, pack.name + ".part").deleteRecursively(); set(pack, if (ready(pack)) State.Ready else State.Missing) }

    fun delete(pack: Pack) {
        cancel(pack)
        synchronized(this) { if (pack == Pack.Voice) { tts?.release(); tts = null } else { recognizer?.release(); recognizer = null } }
        dir(pack).deleteRecursively()
        set(pack, State.Missing)
    }

    private fun untar(archive: File, into: File) {
        val base = into.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val out = File(into, entry.name)
                // Jamais d'écriture hors du dossier (chemins « ../ » d'une archive piégée).
                require(out.canonicalPath.startsWith(base)) { "bad entry" }
                if (entry.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { tar.copyTo(it) } }
            }
        }
    }

    // ---------------------------------------------------------------- voix naturelle

    private var tts: OfflineTts? = null
    private var track: AudioTrack? = null
    private var speakJob: Job? = null
    private val ttsLock = Mutex()

    @Synchronized private fun engineTts(): OfflineTts = tts ?: dir(Pack.Voice).let { d ->
        OfflineTts(null, OfflineTtsConfig(model = OfflineTtsModelConfig(
            kitten = OfflineTtsKittenModelConfig(model = File(d, "model.int8.onnx").path, voices = File(d, "voices.bin").path,
                tokens = File(d, "tokens.txt").path, dataDir = File(d, "espeak-ng-data").path),
            numThreads = 2,
        ))).also { tts = it }
    }

    /** Lit [text] avec la voix [speaker] (0 à 7) ; [done] est appelé à la fin, ou en cas d'échec. */
    fun speak(text: String, speaker: Int, speed: Float, done: () -> Unit) {
        stopSpeaking()
        speakJob = scope.launch(Dispatchers.Default) {
            try {
                ttsLock.withLock {
                    val engine = engineTts()
                    // Phrase par phrase : la première se fait entendre sans attendre la synthèse du reste.
                    for (sentence in text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }) {
                        if (!isActive) break
                        val audio = engine.generate(sentence, speaker, speed)
                        if (!isActive) break
                        play(audio.samples, audio.sampleRate)
                    }
                }
            } catch (_: Exception) {
            } finally { withContext(Dispatchers.Main) { done() } }
        }
    }

    private suspend fun play(samples: FloatArray, rate: Int) = withContext(Dispatchers.IO) {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 4).build()
        track = t
        try {
            t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            t.play()
            // Attente de la fin de la lecture (durée de l'extrait), interrompue si on coupe.
            val ms = samples.size * 1000L / rate
            var waited = 0L
            while (waited < ms + 150 && isActive && track === t) { kotlinx.coroutines.delay(50); waited += 50 }
        } finally { runCatching { t.stop() }; t.release(); if (track === t) track = null }
    }

    fun stopSpeaking() { speakJob?.cancel(); speakJob = null; runCatching { track?.stop() } }

    // ---------------------------------------------------------------- dictée hors ligne

    private var recognizer: OfflineRecognizer? = null
    private val sttLock = Mutex()

    @Synchronized private fun engineStt(): OfflineRecognizer = recognizer ?: dir(Pack.Typing).let { d ->
        OfflineRecognizer(null, OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(preprocessor = File(d, "preprocess.onnx").path, encoder = File(d, "encode.int8.onnx").path,
                    uncachedDecoder = File(d, "uncached_decode.int8.onnx").path, cachedDecoder = File(d, "cached_decode.int8.onnx").path),
                tokens = File(d, "tokens.txt").path, numThreads = 2,
            ),
        )).also { recognizer = it }
    }

    /** Transcrit un vocal (AAC/MP4 du micro) sur le téléphone ; null si la dictée n'est pas téléchargée. */
    suspend fun transcribe(audio: File): String? = withContext(Dispatchers.Default) {
        if (!ready(Pack.Typing)) return@withContext null
        val samples = decodeMono16k(audio)
        sttLock.withLock {
            val engine = engineStt()
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(samples, 16_000)
                engine.decode(stream)
                engine.getResult(stream).text.trim()
            } finally { stream.release() }
        }
    }

    companion object {
        private const val DONE = ".ready"
        private const val MOONSHINE_REV = "bf2b762c076d8ea61e2af0b3851c9564fb77552e"

        /** Décode un fichier audio (AAC) en échantillons mono 16 kHz, valeurs entre -1 et 1. */
        internal fun decodeMono16k(file: File): FloatArray {
            val extractor = MediaExtractor().apply { setDataSource(file.path) }
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0); codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val pcm = java.io.ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            try {
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)!!
                        val bytes = ByteArray(info.size); buf.get(bytes); pcm.write(bytes)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally { codec.stop(); codec.release(); extractor.release() }
            // PCM 16 bits entrelacé → mono → 16 kHz (interpolation linéaire).
            val shorts = java.nio.ByteBuffer.wrap(pcm.toByteArray()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val frames = shorts.remaining() / channels
            val mono = FloatArray(frames) { f -> var s = 0f; for (c in 0 until channels) s += shorts.get(f * channels + c); s / channels / 32768f }
            if (rate == 16_000) return mono
            val outLen = (frames.toLong() * 16_000 / rate).toInt()
            return FloatArray(outLen) { i ->
                val pos = i.toDouble() * rate / 16_000; val a = pos.toInt().coerceAtMost(frames - 1); val b = (a + 1).coerceAtMost(frames - 1)
                (mono[a] + (mono[b] - mono[a]) * (pos - a)).toFloat()
            }
        }
    }
}
