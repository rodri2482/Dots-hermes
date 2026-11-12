package app.dotshermes.data.direct

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Création d'images avec le modèle d'images d'OpenAI (celui de ChatGPT), par la clé d'un compte OpenAI de
 * l'utilisateur. L'image revient comme celles de la recherche d'images : posée au-dessus de la réponse. La clé ne
 * sort jamais de cette requête ; ni elle ni la consigne ne sont écrites dans un journal.
 */
class ImageGen(private val http: OkHttpClient = client) {
    suspend fun generate(key: String, prompt: String): String = withContext(Dispatchers.IO) {
        if (key.isBlank() || key.any { it <= ' ' || it >= '\u007f' }) return@withContext error("no OpenAI key")
        val body = JSONObject().put("model", MODEL).put("prompt", prompt.take(1_000)).put("size", "1024x1024").put("n", 1).toString()
        val request = Request.Builder().url("https://api.openai.com/v1/images/generations")
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext error(when (response.code) {
                    401 -> "the OpenAI key was refused"; 429 -> "OpenAI quota or rate limit reached"
                    400 -> "OpenAI refused this prompt"; else -> "OpenAI image service unavailable (${response.code})"
                })
                val b64 = JSONObject(text).optJSONArray("data")?.optJSONObject(0)?.optString("b64_json").orEmpty()
                if (b64.isEmpty()) return@withContext error("no image returned")
                JSONObject().put("status", "created").put("model", MODEL)
                    .put(WebTools.IMAGES_KEY, JSONArray().put(JSONObject().put("jpeg", b64).put("caption", prompt.take(120)).put("source", "")))
                    .toString()
            }
        } catch (e: Exception) { error("network error: ${e.javaClass.simpleName}") }
    }

    private fun error(reason: String) = JSONObject().put("error", reason)
        .put("note", "Tell the user in one short sentence that the picture could not be made, and why.").toString()

    companion object {
        const val MODEL = "gpt-image-1"
        private val client = OkHttpClient.Builder().callTimeout(150, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).build()
    }
}
