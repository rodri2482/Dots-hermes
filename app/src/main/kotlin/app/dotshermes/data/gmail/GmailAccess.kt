package app.dotshermes.data.gmail

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Pourquoi Gmail ne répond pas : à reconnecter (consentement), pas encore configuré, ou réseau. */
class GmailException(val reason: Reason) : IOException(reason.name) {
    enum class Reason { NotConnected, NeedsConsent, NotSetUp, Network, NotFound }
}

/**
 * Lecture seule de Gmail pour le compagnon, avec l'API d'autorisation de Google (Play Services) :
 * l'utilisateur accepte la portée `gmail.readonly` une fois ; le jeton d'accès (une heure) est
 * redemandé en silence ensuite. Aucun secret dans l'app : Google reconnaît l'app à son nom de paquet
 * et à l'empreinte de sa clé de signature (client OAuth « Android » du projet Google Cloud).
 */
class GmailAccess(private val context: Context, client: OkHttpClient = OkHttpClient()) {
    private val prefs = context.getSharedPreferences("gmail", Context.MODE_PRIVATE)
    private val http = client.newBuilder().followRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    /** Connexion par mot de passe d'application (IMAP) : marche pour tous, sans la vérification Google d'OAuth. */
    private val imap = GmailImap()
    private val vault by lazy { app.dotshermes.data.direct.androidApiVault(context) }
    // Deux enregistrements chiffrés : l'adresse et le mot de passe d'application.
    private fun imapLogin(): Pair<String, String>? = runCatching {
        val email = vault.key(IMAP_EMAIL_ID); val password = vault.key(IMAP_ID)
        if (email.isNullOrBlank() || password.isNullOrBlank()) null else email to password
    }.getOrNull()
    private val _imapEmail = MutableStateFlow(imapLogin()?.first)
    /** L'adresse branchée par mot de passe d'application ; null sinon. */
    val imapEmail: StateFlow<String?> = _imapEmail.asStateFlow()

    /** Vérifie l'adresse et le mot de passe d'application chez Gmail, puis les garde chiffrés. */
    suspend fun connectWithAppPassword(email: String, password: String) = withContext(Dispatchers.IO) {
        imap.check(email, password)
        vault.saveKey(IMAP_EMAIL_ID, email.trim())
        vault.saveKey(IMAP_ID, password.replace(" ", ""))
        _imapEmail.value = email.trim()
        markConnected(true)
    }

    private val _connected = MutableStateFlow(prefs.getBoolean("connected", false))
    /** L'utilisateur a branché Gmail (il peut encore falloir redonner l'accord à Google). */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(GMAIL_READONLY))).build()

    /** Première étape du branchement : soit un jeton tout de suite, soit l'écran d'accord de Google à lancer. */
    suspend fun authorize(): AuthorizationResult = Identity.getAuthorizationClient(context).authorize(request).await()

    /** Résultat de l'écran d'accord de Google. */
    fun resultFrom(data: android.content.Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)

    fun markConnected(on: Boolean, token: String? = null) {
        prefs.edit { putBoolean("connected", on) }
        _connected.value = on
        cached = if (on && token != null) token to System.currentTimeMillis() + TOKEN_LIFE_MS else null
    }

    /** Jeton d'accès gardé en mémoire (Google le donne pour une heure) : pas un aller-retour par requête. */
    @Volatile private var cached: Pair<String, Long>? = null

    /**
     * Prépare le branchement dès l'ouverture des réglages : si l'accord est déjà donné, Gmail est branché
     * aussitôt ; sinon l'écran de Google est prêt et s'ouvre sans attente au toucher.
     */
    suspend fun prepare(): AuthorizationResult? = try {
        authorize().also { r -> if (!r.hasResolution() && r.accessToken != null) markConnected(true, r.accessToken) }
    } catch (e: com.google.android.gms.common.api.ApiException) { throw e } catch (_: Exception) { null }

    /** Débranche aussitôt ici ; l'accès est révoqué chez Google (au mieux) en arrière-plan. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        if (_imapEmail.value != null) {
            runCatching { vault.remove(IMAP_ID) }; runCatching { vault.remove(IMAP_EMAIL_ID) }
            _imapEmail.value = null; markConnected(false); return@withContext
        }
        val token = cached?.first ?: try { silentToken() } catch (_: Exception) { null }
        markConnected(false)
        if (token != null) {
            try {
                http.newCall(Request.Builder().url("https://oauth2.googleapis.com/revoke")
                    .post("token=$token".toRequestBody(FORM)).build()).execute().close()
                GoogleAuthUtil.clearToken(context, token)
            } catch (_: Exception) { /* révocation au mieux : l'accès est de toute façon oublié ici */ }
        }
    }

    private suspend fun silentToken(): String {
        if (!_connected.value) throw GmailException(GmailException.Reason.NotConnected)
        cached?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
        val result = try { authorize() } catch (e: com.google.android.gms.common.api.ApiException) {
            throw GmailException(if (e.statusCode == DEVELOPER_ERROR) GmailException.Reason.NotSetUp else GmailException.Reason.Network)
        }
        if (result.hasResolution()) throw GmailException(GmailException.Reason.NeedsConsent)
        val token = result.accessToken ?: throw GmailException(GmailException.Reason.NeedsConsent)
        cached = token to System.currentTimeMillis() + TOKEN_LIFE_MS
        return token
    }

    /** Recherche Gmail (syntaxe de la barre de recherche) : expéditeur, objet, date et aperçu de chaque message. */
    suspend fun search(query: String, max: Int): JSONArray = withContext(Dispatchers.IO) {
        imapLogin()?.let { (email, password) -> return@withContext imap.search(email, password, query, max) }
        val list = get("messages", mapOf("q" to query, "maxResults" to max.coerceIn(1, 10).toString()))
        val ids = list.optJSONArray("messages") ?: JSONArray()
        val wanted = (0 until ids.length()).mapNotNull { ids.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
        silentToken() // un seul jeton pour toutes les requêtes qui suivent
        // Les messages en parallèle : dix e-mails arrivent dans le temps d'un.
        val rows = kotlinx.coroutines.coroutineScope {
            wanted.map { id ->
                async {
                    val meta = get("messages/$id", mapOf("format" to "metadata"), listOf("From", "Subject", "Date"))
                    JSONObject().put("id", id).put("snippet", meta.optString("snippet"))
                        .apply { headers(meta).forEach { (k, v) -> put(k.lowercase(), v) } }
                }
            }.map { it.await() }
        }
        JSONArray().apply { rows.forEach { put(it) } }
    }

    /** Un message : en-têtes et texte brut (le HTML est réduit à son texte), tronqué. */
    suspend fun read(id: String): JSONObject = withContext(Dispatchers.IO) {
        imapLogin()?.let { (email, password) -> return@withContext imap.read(email, password, id) }
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) throw GmailException(GmailException.Reason.NotFound)
        val full = get("messages/$id", mapOf("format" to "full"))
        val payload = full.optJSONObject("payload") ?: JSONObject()
        val body = (part(payload, "text/plain") ?: part(payload, "text/html")?.let(::htmlToText) ?: full.optString("snippet"))
            .replace(Regex("\\n{3,}"), "\n\n").trim()
        JSONObject().put("id", id).put("body", body.take(MAX_BODY_CHARS))
            .put("truncated", body.length > MAX_BODY_CHARS)
            .apply { headers(full).forEach { (k, v) -> put(k.lowercase(), v) } }
    }

    private suspend fun get(path: String, query: Map<String, String>, metadata: List<String> = emptyList(), retried: Boolean = false): JSONObject {
        val token = silentToken()
        val url = "$API/$path".toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
            metadata.forEach { addQueryParameter("metadataHeaders", it) }
        }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        val (code, text) = try {
            http.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
        } catch (_: IOException) { throw GmailException(GmailException.Reason.Network) }
        return when {
            code == 401 && !retried -> {
                // Jeton expiré dans le cache de Google : on l'oublie et on en redemande un, une fois.
                cached = null
                try { GoogleAuthUtil.clearToken(context, token) } catch (_: Exception) {}
                get(path, query, metadata, retried = true)
            }
            code == 401 || code == 403 -> throw GmailException(GmailException.Reason.NeedsConsent)
            code == 404 -> throw GmailException(GmailException.Reason.NotFound)
            code !in 200..299 -> throw GmailException(GmailException.Reason.Network)
            else -> try { JSONObject(text) } catch (_: Exception) { throw GmailException(GmailException.Reason.Network) }
        }
    }

    companion object {
        const val GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
        private const val IMAP_ID = "gmail-imap"
        private const val IMAP_EMAIL_ID = "gmail-imap-address"
        /** Page Google où l'on crée un mot de passe d'application (validation en deux étapes requise). */
        const val APP_PASSWORDS_URL = "https://myaccount.google.com/apppasswords"
        private const val TOKEN_LIFE_MS = 50 * 60_000L

        /** Empreinte SHA-1 de la clé qui signe cette app : celle à déclarer dans Google Cloud. */
        fun signingSha1(context: Context): String? = try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val signatures = if (android.os.Build.VERSION.SDK_INT >= 28)
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            else pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures
            signatures?.firstOrNull()?.toByteArray()?.let { cert ->
                java.security.MessageDigest.getInstance("SHA-1").digest(cert).joinToString(":") { "%02X".format(it) }
            }
        } catch (_: Exception) { null }
        private const val API = "https://gmail.googleapis.com/gmail/v1/users/me"
        private const val MAX_BODY_CHARS = 6000
        /** CommonStatusCodes.DEVELOPER_ERROR : pas de client OAuth pour ce paquet et cette signature. */
        private const val DEVELOPER_ERROR = 10
        private val FORM = "application/x-www-form-urlencoded".toMediaType()

        private fun headers(message: JSONObject): List<Pair<String, String>> {
            val rows = message.optJSONObject("payload")?.optJSONArray("headers") ?: return emptyList()
            return (0 until rows.length()).mapNotNull { i ->
                val h = rows.optJSONObject(i) ?: return@mapNotNull null
                val name = h.optString("name")
                if (name in setOf("From", "To", "Subject", "Date")) name to h.optString("value").take(300) else null
            }
        }

        /** Premier morceau du type voulu, en descendant dans les pièces jointes multipart. */
        private fun part(node: JSONObject, mime: String): String? {
            if (node.optString("mimeType") == mime) {
                val data = node.optJSONObject("body")?.optString("data").orEmpty()
                if (data.isNotEmpty()) return try {
                    String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
                } catch (_: IllegalArgumentException) { null }
            }
            val parts = node.optJSONArray("parts") ?: return null
            for (i in 0 until parts.length()) parts.optJSONObject(i)?.let { part(it, mime) }?.let { return it }
            return null
        }

        internal fun htmlToText(html: String): String = html
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</li>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
