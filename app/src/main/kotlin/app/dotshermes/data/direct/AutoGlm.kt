package app.dotshermes.data.direct

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Agent de téléphone AutoGLM (Open-AutoGLM, zai-org, Apache-2.0) : un modèle de vision entraîné pour
 * piloter Android. À chaque étape il reçoit une capture d'écran et rend une seule action
 * (`do(action="Tap", element=[x,y])`, coordonnées de 0 à 1000) ; Claydot l'exécute avec son service
 * d'accessibilité. Le modèle (9 milliards de paramètres) tourne chez un hébergeur (Z.ai ou Novita AI),
 * avec la clé de l'utilisateur, chiffrée sur le téléphone comme les autres clés.
 */
class AutoGlm(context: Context, private val vault: DirectAccountStorage, client: OkHttpClient = OkHttpClient()) {
    enum class Host(val label: String, val baseUrl: String, val model: String, val keysUrl: String) {
        ZAI("Z.ai", "https://api.z.ai/api/paas/v4", "autoglm-phone-multilingual", "https://z.ai/manage-apikey/apikey-list"),
        NOVITA("Novita AI", "https://api.novita.ai/openai", "zai-org/autoglm-phone-9b-multilingual", "https://novita.ai/settings/key-management"),
    }

    private val prefs = context.getSharedPreferences("autoglm", Context.MODE_PRIVATE)
    private val http = client.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()
    private val labels = context.packageManager

    data class Settings(val host: Host, val keySaved: Boolean)
    private val _settings = MutableStateFlow(Settings(
        Host.entries.firstOrNull { it.name == prefs.getString("host", null) } ?: Host.ZAI,
        runCatching { !vault.key(VAULT_ID).isNullOrBlank() }.getOrDefault(false)))
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun setHost(host: Host) { prefs.edit { putString("host", host.name) }; _settings.value = _settings.value.copy(host = host) }
    suspend fun saveKey(key: String) = withContext(Dispatchers.IO) { if (key.isNotBlank()) { vault.saveKey(VAULT_ID, key.trim()); _settings.value = _settings.value.copy(keySaved = true) } }
    suspend fun forgetKey() = withContext(Dispatchers.IO) { vault.remove(VAULT_ID); _settings.value = _settings.value.copy(keySaved = false) }
    fun configured() = _settings.value.keySaved

    /**
     * Mène [task] jusqu'au bout (25 étapes au plus) sur l'écran. [blocked] refuse certaines apps,
     * [allowed] devient faux quand l'utilisateur touche « Arrêter ». Renvoie un compte rendu pour Claydot.
     */
    suspend fun run(task: String, driver: ScreenDriver, blocked: (String) -> String?, allowed: () -> Boolean): JSONObject = withContext(Dispatchers.IO) {
        val key = vault.key(VAULT_ID) ?: return@withContext JSONObject().put("error", "AutoGLM key missing: add it in Settings › Phone")
        val host = _settings.value.host
        val (w, h) = driver.screenSize() ?: return@withContext JSONObject().put("error", "screen size unknown")
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
        val steps = JSONArray()
        for (step in 1..MAX_STEPS) {
            if (!allowed()) return@withContext result("stopped", "The user stopped the task.", steps)
            val shot = driver.screenshot() ?: return@withContext result("error", "screenshot not available (Android 11+ needed)", steps)
            val pkg = driver.snapshot()?.packageName.orEmpty()
            blocked(pkg)?.let { return@withContext result("blocked", it, steps) }
            val app = runCatching { labels.getApplicationLabel(labels.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            val info = "** Screen Info **\n\n" + JSONObject().put("current_app", app).toString()
            // Seule la dernière capture part : les précédentes sont retirées (comme dans Open-AutoGLM).
            for (i in 0 until messages.length()) messages.optJSONObject(i)?.takeIf { it.optString("role") == "user" }?.let { m ->
                (m.opt("content") as? JSONArray)?.let { parts -> m.put("content", (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }
                    .filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }) }
            }
            messages.put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", if (step == 1) "$task\n\n$info" else info))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                    .put("url", "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(shot))))))
            val reply = request(host, key, messages) ?: return@withContext result("error", "AutoGLM did not answer (check the key and the host)", steps)
            val action = actionOf(reply)
            messages.put(JSONObject().put("role", "assistant").put("content", reply))
            steps.put(action.take(160))
            if (action.startsWith("finish")) {
                val message = Regex("""message\s*=\s*["'](.*)["']\s*\)""", RegexOption.DOT_MATCHES_ALL).find(action)?.groupValues?.get(1) ?: "Done."
                return@withContext result("done", message, steps)
            }
            when (val outcome = execute(action, driver, w, h)) {
                null -> {}
                else -> return@withContext result(outcome.first, outcome.second, steps)
            }
            delay(SETTLE_MS)
        }
        result("unfinished", "Stopped after $MAX_STEPS steps.", steps)
    }

    private fun result(status: String, message: String, steps: JSONArray) =
        JSONObject().put("status", status).put("message", message).put("steps", steps.length()).put("actions", steps)

    /** Exécute une action ; null pour continuer, sinon (statut, message) pour s'arrêter. */
    private suspend fun execute(action: String, driver: ScreenDriver, w: Int, h: Int): Pair<String, String>? {
        val name = Regex("""action\s*=\s*"([^"]+)"""").find(action)?.groupValues?.get(1) ?: return "error" to "Unreadable action: $action"
        fun point(label: String): Pair<Float, Float>? = Regex("""$label\s*=\s*\[\s*(\d+)\s*,\s*(\d+)\s*]""").find(action)?.let { m ->
            m.groupValues[1].toFloat() / 1000f * w to m.groupValues[2].toFloat() / 1000f * h
        }
        fun string(label: String) = Regex("""$label\s*=\s*"((?:[^"\\]|\\.)*)"""").find(action)?.groupValues?.get(1)?.replace("\\\"", "\"")
        when (name) {
            "Launch" -> { driver.launch(string("app").orEmpty()) }
            "Tap" -> {
                // Action sensible (paiement, suppression…) : AutoGLM joint un message ; on laisse la main à l'utilisateur.
                string("message")?.let { return "needs_user" to it }
                val (x, y) = point("element") ?: return "error" to "Tap without coordinates"
                driver.tapAt(x, y)
            }
            "Double Tap" -> point("element")?.let { (x, y) -> driver.tapAt(x, y); delay(120); driver.tapAt(x, y) }
            "Long Press" -> point("element")?.let { (x, y) -> driver.tapAt(x, y, long = true) }
            "Type", "Type_Name" -> driver.typeFocused(string("text").orEmpty())
            "Swipe" -> { val a = point("start"); val b = point("end"); if (a != null && b != null) driver.swipeBetween(a.first, a.second, b.first, b.second) }
            "Back" -> driver.press("back")
            "Home" -> driver.press("home")
            "Wait" -> delay(((string("duration")?.filter { it.isDigit() }?.toLongOrNull() ?: 2L).coerceIn(1, 10)) * 1_000)
            "Take_over" -> return "needs_user" to (string("message") ?: "The user needs to take over (sign-in or check).")
            else -> {} // Note, Call_API, Interact : rien à faire sur l'écran.
        }
        return null
    }

    private fun request(host: Host, key: String, messages: JSONArray): String? {
        val body = JSONObject().put("model", host.model).put("messages", messages).put("max_tokens", 3000)
            .put("temperature", 0.0).put("top_p", 0.85).put("frequency_penalty", 0.2)
        val request = Request.Builder().url(host.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return runCatching {
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return null
                JSONObject(r.body?.string().orEmpty()).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val VAULT_ID = "phone-agent-autoglm"
        private const val MAX_STEPS = 25
        private const val SETTLE_MS = 1_000L

        /** L'action d'une réponse : le contenu de `<answer>…</answer>`, ou le dernier `do(…)` / `finish(…)`. */
        internal fun actionOf(reply: String): String {
            Regex("<answer>(.*?)</answer>", RegexOption.DOT_MATCHES_ALL).find(reply)?.let { return it.groupValues[1].trim() }
            val at = maxOf(reply.lastIndexOf("do(action="), reply.lastIndexOf("finish("))
            return if (at >= 0) reply.substring(at).trim() else reply.trim()
        }

        /** Consigne système d'Open-AutoGLM (prompts_en.py, Apache-2.0), avec la date du jour. */
        internal fun systemPrompt(): String = "The current date: " + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd, EEEE", Locale.ENGLISH)) + """

# Setup
You are a professional Android operation agent assistant that can fulfill the user's high-level instructions. Given a screenshot of the Android interface at each step, you first analyze the situation, then plan the best course of action using Python-style pseudo-code.

# More details about the code
Your response format must be structured as follows:

Think first: Use <think>...</think> to analyze the current screen, identify key elements, and determine the most efficient action.
Provide the action: Use <answer>...</answer> to return a single line of pseudo-code representing the operation.

Your output should STRICTLY follow the format:
<think>
[Your thought]
</think>
<answer>
[Your operation code]
</answer>

- **Tap**
  Perform a tap action on a specified screen area. The element is a list of 2 integers, representing the coordinates of the tap point.
  **Example**:
  <answer>
  do(action="Tap", element=[x,y])
  </answer>
- **Type**
  Enter text into the currently focused input field.
  **Example**:
  <answer>
  do(action="Type", text="Hello World")
  </answer>
- **Swipe**
  Perform a swipe action with start point and end point.
  **Examples**:
  <answer>
  do(action="Swipe", start=[x1,y1], end=[x2,y2])
  </answer>
- **Long Press**
  Perform a long press action on a specified screen area.
  You can add the element to the action to specify the long press area. The element is a list of 2 integers, representing the coordinates of the long press point.
  **Example**:
  <answer>
  do(action="Long Press", element=[x,y])
  </answer>
- **Launch**
  Launch an app. Try to use launch action when you need to launch an app. Check the instruction to choose the right app before you use this action.
  **Example**:
  <answer>
  do(action="Launch", app="Settings")
  </answer>
- **Back**
  Press the Back button to navigate to the previous screen.
  **Example**:
  <answer>
  do(action="Back")
  </answer>
- **Finish**
  Terminate the program and optionally print a message.
  **Example**:
  <answer>
  finish(message="Task completed.")
  </answer>


REMEMBER:
- Think before you act: Always analyze the current UI and the best course of action before executing any step, and output in <think> part.
- Only ONE LINE of action in <answer> part per response: Each step must contain exactly one line of executable code.
- Generate execution code strictly according to format requirements.
"""
    }
}
