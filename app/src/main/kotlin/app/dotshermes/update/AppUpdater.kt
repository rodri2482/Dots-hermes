package app.dotshermes.update

import androidx.core.content.edit
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.dotshermes.BuildConfig
import app.dotshermes.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val TAG = "AppUpdater"

/** Où en est la mise à jour de l'app : ce que montrent le dialogue, les réglages et la notification. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    /** Android doit d'abord autoriser Hermes à installer des applis (une seule fois). */
    data class NeedsPermission(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** APK transmis à Android : il demande confirmation, ou installe directement si Hermes en est l'installateur. */
    data class Installing(val release: Release) : UpdateState
    data class Failed(val release: Release?, val reason: String) : UpdateState
}

/**
 * Mises à jour hors Play Store : la dernière release GitHub du dépôt [BuildConfig.UPDATE_REPO]
 * est comparée à la version installée ; son APK est téléchargé, vérifié (SHA-256 publié par la CI)
 * puis confié à [PackageInstaller]. Android refuse de lui-même un APK signé avec une autre clé.
 *
 * Désactivé dans les builds de debug (leur clé diffère de celle des releases : l'installation échouerait)
 * et quand aucun dépôt n'est configuré (fork, build local).
 */
class AppUpdater(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(2, TimeUnit.MINUTES).build(),
) {
    val enabled: Boolean = BuildConfig.UPDATE_REPO.isNotBlank() && !BuildConfig.DEBUG
    val currentVersion: String = BuildConfig.VERSION_NAME

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** La notification « mise à jour disponible » a été touchée : l'interface doit ouvrir le dialogue. */
    private val _promptRequested = MutableStateFlow(false)
    val promptRequested: StateFlow<Boolean> = _promptRequested.asStateFlow()

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val busy = Mutex()

    fun requestPrompt() {
        _promptRequested.value = true
    }

    fun clearPrompt() {
        _promptRequested.value = false
    }

    /** Hermes a-t-il le droit d'installer des applis (réglage « sources inconnues ») ? */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /**
     * Interroge GitHub (au plus une fois par heure, sauf [force]) et renvoie la release plus récente
     * que la version installée, ou null. Ne touche pas à un téléchargement en cours.
     */
    suspend fun check(force: Boolean = false): Release? {
        if (!enabled) return null
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) {
            return (_state.value as? UpdateState.Available)?.release
        }
        if (_state.value.isWorking()) return null
        if (force) _state.value = UpdateState.Checking
        val release = try {
            withContext(Dispatchers.IO) { fetchLatest() }
        } catch (e: IOException) {
            Log.w(TAG, "Vérification impossible", e)
            if (force) _state.value = UpdateState.Failed(null, context.getString(R.string.update_error_check))
            return null
        }
        prefs.edit { putLong(KEY_LAST_CHECK, now) }
        return if (release != null && isNewer(release.version, currentVersion)) {
            _state.value = UpdateState.Available(release)
            release
        } else {
            _state.value = if (force) UpdateState.UpToDate else UpdateState.Idle
            null
        }
    }

    /** Le dialogue ne revient pas pour une version écartée (la notification et les réglages, si). */
    fun shouldPrompt(release: Release): Boolean = prefs.getString(KEY_DISMISSED, null) != release.version

    fun dismiss(release: Release) {
        prefs.edit { putString(KEY_DISMISSED, release.version) }
    }

    /** La notification « mise à jour disponible » ne part qu'une fois par version. */
    fun markNotified(release: Release): Boolean {
        if (prefs.getString(KEY_NOTIFIED, null) == release.version) return false
        prefs.edit { putString(KEY_NOTIFIED, release.version) }
        return true
    }

    /** Télécharge, vérifie et installe [release]. À appeler depuis l'interface (l'utilisateur l'a demandé). */
    suspend fun install(release: Release) {
        if (!busy.tryLock()) return
        try {
            if (!canInstall()) {
                _state.value = UpdateState.NeedsPermission(release)
                return
            }
            _state.value = UpdateState.Downloading(release, 0f)
            val apk = withContext(Dispatchers.IO) { download(release) }
            _state.value = UpdateState.Installing(release)
            withContext(Dispatchers.IO) { commit(apk) }
        } catch (e: IOException) {
            Log.w(TAG, "Mise à jour impossible", e)
            _state.value = UpdateState.Failed(release, e.message ?: context.getString(R.string.update_error_download))
        } finally {
            busy.unlock()
        }
    }

    /** Écran système « Installer des applis inconnues » pour Hermes ; au retour, on relance [install]. */
    fun permissionSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Résultat de la session d'installation (voir [InstallStatusReceiver]). */
    internal fun onInstallFailed(message: String?) {
        val release = (_state.value as? UpdateState.Installing)?.release
        _state.value = UpdateState.Failed(release, message ?: context.getString(R.string.update_error_install))
    }

    /** L'utilisateur a fermé la fenêtre de confirmation d'Android : la mise à jour reste proposée. */
    internal fun onInstallAborted() {
        (_state.value as? UpdateState.Installing)?.let { _state.value = UpdateState.Available(it.release) }
    }

    // -- Réseau et installation -------------------------------------------------------------------

    private fun fetchLatest(): Release? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null // pas encore de release
            if (!response.isSuccessful) throw IOException("GitHub HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            val assets = json.optJSONArray("assets") ?: return null
            val all = List(assets.length()) { assets.getJSONObject(it) }
            val apk = all.firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
            val apkName = apk.optString("name")
            return Release(
                version = json.optString("tag_name").removePrefix("v"),
                apkUrl = apk.optString("browser_download_url"),
                apkSize = apk.optLong("size"),
                sha256Url = all.firstOrNull { it.optString("name") == "$apkName.sha256" }?.optString("browser_download_url"),
                pageUrl = json.optString("html_url"),
            )
        }
    }

    private fun download(release: Release): File {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val target = File(dir, "claydot-${release.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException(context.getString(R.string.update_error_download))
            val body = response.body ?: throw IOException(context.getString(R.string.update_error_download))
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0) _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        release.sha256Url?.let { url ->
            // Fichier `sha256sum` : « <empreinte>  <nom> ».
            val expected = client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
                .trim().substringBefore(' ').lowercase()
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expected.isNotEmpty() && expected != actual) {
                target.delete()
                throw IOException(context.getString(R.string.update_error_checksum))
            }
        }
        return target
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Android 12+ : sans confirmation si Hermes est déjà l'installateur de lui-même (après une
            // première mise à jour par ce module) ; sinon Android affiche sa fenêtre habituelle.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Mutable : Android y ajoute le statut et, au besoin, l'intent de confirmation.
            val status = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallStatusReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(status.intentSender)
        }
    }

    private fun UpdateState.isWorking() =
        this is UpdateState.Downloading || this is UpdateState.Installing || this == UpdateState.Checking

    private companion object {
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_DISMISSED = "dismissed_version"
        const val KEY_NOTIFIED = "notified_version"
        const val CHECK_INTERVAL_MS = 60 * 60 * 1000L
    }
}
