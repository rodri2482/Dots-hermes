package app.dotshermes.data.direct

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.app.SearchManager
import android.media.AudioManager
import android.net.Uri
import androidx.core.net.toUri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.provider.CalendarContract
import androidx.core.content.edit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Un accès Android que Claydot demande au moment où il en a besoin (au lieu de réglages à l'avance) :
 * contacts et SMS, notifications WhatsApp, contrôle de l'écran, écoute toute la journée.
 */
enum class PhoneAccess(val icon: String) { Contacts("sms"), Notifications("message"), Screen("screen"), AllDay("routine") }


/**
 * Actions sur le téléphone que Claydot peut proposer : réveil, minuteur, ouvrir une app, événement
 * d'agenda. Rien ne part sans un appui sur « Faire » dans la fenêtre de confirmation ; aucune action
 * ne lit ni n'envoie de données, et un e-mail ne peut pas en déclencher une sans cette confirmation.
 */
class PhoneActions(
    private val context: Context,
    /** L'écran (service d'accessibilité) : finir une lecture que l'app de musique n'a pas lancée seule. */
    private val screen: () -> ScreenDriver? = { null },
) : DirectTools {
    private val prefs = context.getSharedPreferences("phone-actions", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) { prefs.edit { putBoolean("enabled", on) }; _enabled.value = on }

    override fun definitions(): JSONArray? = if (!_enabled.value) null else JSONArray()
        .put(function("set_alarm", "Set an alarm on the user's phone (the user confirms first).",
            props("hour" to int("0-23"), "minute" to int("0-59"), "label" to str("Optional alarm label")), "hour", "minute"))
        .put(function("set_timer", "Start a countdown timer on the user's phone (the user confirms first).",
            props("seconds" to int("Duration in seconds, 1 to 86400"), "label" to str("Optional label")), "seconds"))
        .put(function("phone_info", "Read the phone's status: battery, charging, storage, network, volume, model, Android version " +
            "and the installed apps. Read-only.", props()))
        .put(function("open_app", "Open an app installed on the user's phone, by its name. Opens right away; use the screen tools to continue inside it.",
            props("name" to str("App name as shown on the home screen, e.g. 'WhatsApp'")), "name"))
        .put(function("add_calendar_event",
            "Prepare a calendar event; the phone's calendar opens pre-filled so the user can save it (the user confirms first).",
            props("title" to str("Event title"), "start" to str("Local start, ISO 8601 like 2026-11-12T15:00"),
                "end" to str("Optional local end, ISO 8601"), "location" to str("Optional location")), "title", "start"))
        .put(function("play_music",
            "Play a song, artist, album or playlist in a music app on the user's phone; it starts playing right away. " +
                "Use it whenever the user asks to play or put on music.",
            props("query" to str("What to play, with the artist when known, e.g. 'Unavailable Davido' or 'Davido'"),
                "title" to str("Song title alone when known, e.g. 'Unavailable'"),
                "artist" to str("Artist alone when known, e.g. 'Davido'"),
                "app" to str("Optional app the user named: ${MUSIC_APPS.joinToString { it.name }}")), "query"))
        .put(function("media_control", "Control what is playing on the phone (any music or video app): pause, resume, skip, volume.",
            props("action" to JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("pause", "play", "next", "previous", "volume_up", "volume_down")))), "action"))

    /**
     * Carte « Claydot veut… » dans le fil (Accepter / Refuser, aussi visibles l'un que l'autre) : true seulement si
     * l'utilisateur accepte. Pas de conversation en cours pour poser la carte : refus.
     */
    suspend fun ask(title: String, detail: String): Boolean {
        // Routine en cours, dont l'utilisateur a approuvé ce genre d'action à sa création.
        ToolKeys.splitTitle(title).first?.let(ToolKeys::kind)?.let { kind -> if (kind in autoApprove) return true }
        val host = ChatCards.host ?: return false
        val card = app.dotshermes.data.model.ChatCard(ChatCards.newId(), app.dotshermes.data.model.CardKind.Confirm, title, detail, custom = false).bounded()
        val reply = host.ask(card, ChatCards.QUESTION_TIMEOUT_MS)
        return reply?.status == app.dotshermes.data.model.CardStatus.Answered && reply.answer == ChatCards.APPROVE
    }

    /** L'accès est-il déjà donné ? */
    fun hasAccess(access: PhoneAccess): Boolean = when (access) {
        PhoneAccess.Contacts -> MessagingTools.PERMISSIONS.all {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        PhoneAccess.Notifications -> ChatLog.accessGranted(context)
        PhoneAccess.Screen -> screen()?.connected() == true
        PhoneAccess.AllDay -> AlwaysOnService.enabled(context)
    }

    /**
     * Demande [access] par une carte dans le fil : « Continuer » ouvre le dialogue ou le réglage Android (l'écran
     * de l'app s'en charge) ; aucun bouton du fil ne donne l'accès lui-même. La tâche reprend seulement quand
     * l'accès est vraiment donné (vérifié), sinon elle continue en le sachant refusé.
     */
    suspend fun requestAccess(access: PhoneAccess, reason: String = ""): Boolean {
        if (hasAccess(access)) return true
        val (title, detail) = when (access) {
            PhoneAccess.Contacts -> app.dotshermes.R.string.access_contacts_title to app.dotshermes.R.string.access_contacts_detail
            PhoneAccess.Notifications -> app.dotshermes.R.string.access_notifications_title to app.dotshermes.R.string.access_notifications_detail
            PhoneAccess.Screen -> app.dotshermes.R.string.access_screen_title to app.dotshermes.R.string.access_screen_detail
            PhoneAccess.AllDay -> app.dotshermes.R.string.access_allday_title to app.dotshermes.R.string.access_allday_detail
        }
        val text = context.getString(detail).let { base -> reason.trim().take(200).takeIf { it.isNotEmpty() }?.let { "$it\n$base" } ?: base }
        val card = app.dotshermes.data.model.ChatCard(ChatCards.newId(), app.dotshermes.data.model.CardKind.Access,
            ToolKeys.title(access.icon, context.getString(title)), text, access = access.name, custom = false).bounded()
        // Écoute toute la journée : le service de Claydot lui-même, que l'utilisateur vient d'accepter.
        return ChatCards.askAccess(card, onContinue = { if (access == PhoneAccess.AllDay) AlwaysOnService.set(context, true) }) { hasAccess(access) }
    }

    override fun label(call: ApiToolCall) = when (call.name) {
        "set_alarm" -> ToolKeys.of("alarm"); "set_timer" -> ToolKeys.of("timer"); "open_app" -> ToolKeys.of("app")
        "add_calendar_event" -> ToolKeys.of("calendar"); "play_music", "media_control" -> ToolKeys.of("music"); "phone_info" -> ToolKeys.of("phone")
        else -> ToolKeys.of("phone")
    }

    override suspend fun run(call: ApiToolCall): String {
        val args = try { JSONObject(call.arguments.ifBlank { "{}" }) } catch (_: JSONException) { JSONObject() }
        // La musique part tout de suite (comme un assistant vocal) : rien d'irréversible, on l'arrête d'un geste.
        if (call.name == "play_music") return withContext(Dispatchers.Main) {
            playMusic(args.optString("query").trim(), args.optString("app").trim(), args.optString("title").trim(), args.optString("artist").trim())
        }
        if (call.name == "media_control") return withContext(Dispatchers.Main) { mediaControl(args.optString("action")) }
        if (call.name == "phone_info") return withContext(Dispatchers.IO) { phoneInfo() }
        // Ouvrir une app ne change rien : elle s'ouvre tout de suite, sans fenêtre d'accord.
        if (call.name == "open_app") {
            val wanted = args.optString("name").trim()
            val app = findApp(wanted) ?: return result("invalid", "No installed app is called '$wanted'.")
            return withContext(Dispatchers.Main) {
                try { context.startActivity(app.second.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); result("done", "Opened ${app.first}.") }
                catch (_: Exception) { result("unavailable", "${app.first} could not open.") }
            }
        }
        val plan = try { plan(call.name, args) } catch (e: IllegalArgumentException) { return result("invalid", e.message) }
            ?: return result("unknown_tool", call.name)
        val ok = ask(plan.title, plan.detail)
        if (!ok) return result("cancelled", "The user declined. Do not retry unless they ask again.")
        return withContext(Dispatchers.Main) {
            try {
                context.startActivity(plan.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                result("done", plan.title)
            } catch (_: ActivityNotFoundException) {
                result("unavailable", "No app on this phone can do that.")
            } catch (_: SecurityException) {
                result("unavailable", "Android refused the action.")
            }
        }
    }

    private class Plan(val title: String, val detail: String, val intent: Intent)

    private fun plan(name: String, a: JSONObject): Plan? = when (name) {
        "set_alarm" -> {
            val hour = a.optInt("hour", -1).also { require(it in 0..23) { "hour must be 0-23" } }
            val minute = a.optInt("minute", -1).also { require(it in 0..59) { "minute must be 0-59" } }
            val label = a.optString("label").take(80)
            Plan(ToolKeys.title("alarm", "%02d:%02d".format(hour, minute)), label, Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .apply { if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label) }
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        }
        "set_timer" -> {
            // Les petits modèles écrivent parfois « minutes » ou « 1 minute » : on accepte ces formes aussi.
            val seconds = timerSeconds(a).also { require(it in 1..86_400) { "seconds must be 1-86400" } }
            val label = a.optString("label").take(80)
            Plan(ToolKeys.title("timer", formatDuration(seconds)), label, Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .apply { if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label) }
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        }
        "open_app" -> {
            val wanted = a.optString("name").trim().also { require(it.isNotEmpty()) { "name is required" } }
            val app = findApp(wanted) ?: throw IllegalArgumentException("No installed app is called '$wanted'.")
            Plan(ToolKeys.title("app", app.first), "", app.second)
        }
        "add_calendar_event" -> {
            val title = a.optString("title").trim().take(120).also { require(it.isNotEmpty()) { "title is required" } }
            val start = parseLocal(a.optString("start")) ?: throw IllegalArgumentException("start must be ISO 8601, e.g. 2026-11-12T15:00")
            val end = parseLocal(a.optString("end"))?.takeIf { it.isAfter(start) } ?: start.plusHours(1)
            val location = a.optString("location").take(120)
            val zone = ZoneId.systemDefault()
            Plan(ToolKeys.title("calendar", title), start.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)) +
                (if (location.isNotBlank()) " · $location" else ""),
                Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, title)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.atZone(zone).toInstant().toEpochMilli())
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.atZone(zone).toInstant().toEpochMilli())
                    .apply { if (location.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location) })
        }
        else -> null
    }

    /**
     * Lance la lecture dans l'app de musique choisie (celle nommée, sinon la dernière utilisée, sinon la
     * première installée). Certaines apps (Spotify sans compte Premium, YouTube) n'ouvrent que les
     * résultats : si rien ne joue au bout de quelques secondes et que l'écran est accessible, Claydot
     * touche lui-même la chanson, puis « Lecture » — dans cette app seulement, c'est la demande de l'utilisateur.
     */
    private suspend fun playMusic(query: String, requested: String, title: String = "", artist: String = ""): String {
        if (query.isEmpty()) return result("invalid", "query is required")
        val pm = context.packageManager
        val installed = MUSIC_APPS.filter { pm.getLaunchIntentForPackage(it.packageName) != null }
        val app = chooseMusicApp(installed, requested, prefs.getString("music_app", null))
            ?: return result("unavailable", "No music app is installed (Spotify, YouTube Music, Boomplay, Audiomack, YouTube…).")
        if (requested.isNotEmpty()) prefs.edit { putString("music_app", app.packageName) }
        val extras = searchExtras(query, title, artist)
        val others = installed.filter { it != app }.joinToString { it.name }
        val audio = context.getSystemService(AudioManager::class.java)
        fun answer(status: String, detail: String) = JSONObject().put("status", status).put("app", app.name).put("query", query)
            .put("detail", detail).put("other_music_apps", others).toString()
        // 1. L'app tourne déjà : on demande la lecture à sa session média (comme Android Auto ou l'Assistant),
        //    sans quitter Claydot ni toucher l'écran.
        if (playFromSession(app.packageName, query, extras, audio, waitForSession = false)) return answer("playing", "Playback started in ${app.name}, in the background.")
        // 2. Sinon on l'ouvre avec « lire depuis une recherche » (titre et artiste à part : les apps choisissent mieux).
        val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(app.packageName).putExtras(extras)
        val search = app.searchUrl?.let { Intent(Intent.ACTION_VIEW, (it + Uri.encode(query)).toUri()).setPackage(app.packageName) }
        val launched = listOfNotNull(play, search).firstOrNull { intent ->
            intent.resolveActivity(pm) != null && runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        } ?: return result("unavailable", "${app.name} could not open.")
        // La lecture a-t-elle démarré toute seule ? Une app froide (Spotify) met plusieurs secondes à démarrer.
        if (launched === play && waitForMusic(audio, 6_000)) { backToClaydot(); return answer("playing", "Playback started in ${app.name}.") }
        // 3. L'app est ouverte mais ne joue pas (simple page de résultats) : sa session média existe maintenant.
        if (playFromSession(app.packageName, query, extras, audio)) { backToClaydot(); return answer("playing", "Playback started in ${app.name}.") }
        // Pas d'accès à l'écran : retour dans le fil, où une carte le propose (« Continuer » ouvre le réglage Android).
        // Accordé, la recherche se rouvre et Claydot touche la chanson ; sinon l'utilisateur la touche lui-même.
        val driver = screen()?.takeIf { it.connected() } ?: run {
            backToClaydot()
            if (!requestAccess(PhoneAccess.Screen, context.getString(MUSIC_NEEDS_SCREEN, app.name))) return@run null
            runCatching { context.startActivity(launched.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            kotlinx.coroutines.delay(1_500)
            screen()?.takeIf { it.connected() }
        } ?: return answer("opened_not_playing", "${app.name} opened on '$query' but did not start playing, and screen access was not given. " +
            "Tell the user in one short sentence to tap the song in ${app.name}.")
        // Rien ne joue : on touche le bon résultat (ou « Lecture » sur la page ouverte), trois essais au plus.
        var tappedRow = false
        repeat(4) {
            // Les résultats se chargent : un court temps avant chaque lecture de l'écran.
            kotlinx.coroutines.delay(700)
            val shot = driver.snapshot()?.takeIf { it.packageName == app.packageName } ?: return@repeat
            val target = pickPlayTarget(shot.elements, query, preferPlayButton = tappedRow) ?: return@repeat
            if (!driver.tap(target.id)) return@repeat
            tappedRow = tappedRow || !isPlayButton(target.text)
            if (waitForMusic(audio, 4_000)) { backToClaydot(); return answer("playing", "Playback started in ${app.name} (Claydot tapped '${target.text.take(60)}').") }
        }
        return answer("opened_not_playing", "${app.name} shows '$query' but playback did not start. Ask the user to tap the song.")
    }

    /** L'état du téléphone, en lecture seule (aucune autorisation nécessaire). */
    private fun phoneInfo(): String {
        val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) }
        val plugged = (battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val gb = 1024.0 * 1024 * 1024
        val net = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = net?.activeNetwork?.let(net::getNetworkCapabilities)
        val network = when {
            caps == null -> "offline"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "connected"
        }
        val audio = context.getSystemService(AudioManager::class.java)
        val apps = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(context.packageManager).toString() }.distinct().sorted()
        return JSONObject()
            .put("battery_percent", level).put("charging", plugged)
            .put("storage_free_gb", "%.1f".format(stat.availableBytes / gb)).put("storage_total_gb", "%.1f".format(stat.totalBytes / gb))
            .put("network", network)
            .put("music_volume", audio?.let { "${it.getStreamVolume(AudioManager.STREAM_MUSIC)}/${it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" })
            .put("music_playing", audio?.isMusicActive == true)
            .put("ringer", when (audio?.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" })
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("android", android.os.Build.VERSION.RELEASE)
            .put("installed_apps", JSONArray(apps.take(200)))
            .toString()
    }

    /** Attend qu'une app joue de la musique (true) ou [timeoutMs]. */
    /** Les données d'une recherche musicale : requête, et titre / artiste à part quand on les connaît. */
    private fun searchExtras(query: String, title: String, artist: String) = android.os.Bundle().apply {
        putString(SearchManager.QUERY, query)
        putString(MediaStore.EXTRA_MEDIA_FOCUS, when {
            title.isNotEmpty() -> MediaStore.Audio.Media.ENTRY_CONTENT_TYPE
            artist.isNotEmpty() -> MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
            else -> "vnd.android.cursor.item/*"
        })
        if (title.isNotEmpty()) putString(MediaStore.EXTRA_MEDIA_TITLE, title)
        if (artist.isNotEmpty()) putString(MediaStore.EXTRA_MEDIA_ARTIST, artist)
    }

    /**
     * La session média de l'app (visible grâce à l'accès aux notifications de Claydot) : « jouer cette
     * recherche » lui est envoyé directement. Attend un peu que la session apparaisse après l'ouverture.
     */
    private suspend fun playFromSession(pkg: String, query: String, extras: android.os.Bundle, audio: AudioManager?, waitForSession: Boolean = true): Boolean {
        if (!ChatLog.accessGranted(context)) return false
        val sessions = context.getSystemService(android.media.session.MediaSessionManager::class.java) ?: return false
        val listener = android.content.ComponentName(context, ChatNotificationListener::class.java)
        var controller: android.media.session.MediaController? = null
        for (i in 0 until if (waitForSession) 10 else 1) {
            controller = runCatching { sessions.getActiveSessions(listener) }.getOrNull()?.firstOrNull { it.packageName == pkg }
            if (controller != null) break
            kotlinx.coroutines.delay(300)
        }
        val c = controller ?: return false
        val actions = c.playbackState?.actions ?: 0L
        // Session qui n'annonce rien (fréquent) : on essaie quand même ; elle ignore la demande au pire.
        if (actions != 0L && actions and android.media.session.PlaybackState.ACTION_PLAY_FROM_SEARCH == 0L) return false
        runCatching { c.transportControls.playFromSearch(query, extras) }.onFailure { return false }
        var waited = 0L
        while (waited < 7_000) {
            kotlinx.coroutines.delay(300); waited += 300
            if (c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING || audio?.isMusicActive == true) return true
        }
        return false
    }

    /** La musique joue : on revient à la conversation au lieu de rester dans l'app de musique. */
    private fun backToClaydot() {
        runCatching {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }
    }

    private suspend fun waitForMusic(audio: AudioManager?, timeoutMs: Long): Boolean {
        if (audio == null) return false
        var waited = 0L
        while (waited < timeoutMs) {
            kotlinx.coroutines.delay(300); waited += 300
            if (audio.isMusicActive) return true
        }
        return false
    }

    private fun mediaControl(action: String): String {
        val audio = context.getSystemService(AudioManager::class.java) ?: return result("unavailable", "No audio service.")
        val key = when (action) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "volume_up", "volume_down" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (action == "volume_up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                return result("done", action)
            }
            else -> return result("invalid", "unknown action")
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return result("done", action)
    }

    /** L'app dont le nom ressemble le plus à [wanted] parmi celles de l'écran d'accueil. */
    internal fun findApp(wanted: String): Pair<String, Intent>? {
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val target = wanted.lowercase()
        val best = launchable.map { it to it.loadLabel(pm).toString() }
            .filter { (_, label) -> label.lowercase().contains(target) || target.contains(label.lowercase()) }
            .minByOrNull { (_, label) -> if (label.equals(wanted, ignoreCase = true)) 0 else label.length }
            ?: return null
        val info = best.first.activityInfo
        return best.second to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(info.packageName, info.name)
    }

    private fun result(status: String, detail: String?) = JSONObject().put("status", status).put("detail", detail.orEmpty()).toString()

    private fun props(vararg p: Pair<String, JSONObject>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
    private fun int(d: String) = JSONObject().put("type", "integer").put("description", d)
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun function(name: String, description: String, properties: JSONObject, vararg required: String) = JSONObject()
        .put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", description).put("parameters",
            JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required.toList()))))

    /** Une app de musique connue : son nom, son paquet, et une recherche par lien si la lecture directe échoue. */
    data class MusicApp(val name: String, val packageName: String, val searchUrl: String?)

    companion object {
        /** Genres d'actions approuvés d'avance pour la routine qui s'exécute (vide hors routine). */
        /** Le réglage Android qui donne [access] (Contacts passe par la demande d'autorisation de l'app). */
        fun settingsIntent(context: Context, access: PhoneAccess): Intent? = when (access) {
            PhoneAccess.Contacts -> null
            PhoneAccess.Notifications -> if (android.os.Build.VERSION.SDK_INT >= 30)
                Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    android.content.ComponentName(context, ChatNotificationListener::class.java).flattenToString())
                else Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            PhoneAccess.Screen -> Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            PhoneAccess.AllDay -> if (ChatLog.neverSleeps(context)) null
                else Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
        }

        @Volatile var autoApprove: Set<String> = emptySet()

        private val MUSIC_NEEDS_SCREEN = app.dotshermes.R.string.music_needs_screen

        /** Par ordre de préférence quand l'utilisateur n'en nomme aucune. */
        val MUSIC_APPS = listOf(
            MusicApp("Spotify", "com.spotify.music", "spotify:search:"),
            MusicApp("YouTube Music", "com.google.android.apps.youtube.music", "https://music.youtube.com/search?q="),
            MusicApp("Boomplay", "com.afmobi.boomplayer", null),
            MusicApp("Audiomack", "com.audiomack", null),
            MusicApp("Apple Music", "com.apple.android.music", null),
            MusicApp("Deezer", "deezer.android.app", null),
            MusicApp("YouTube", "com.google.android.youtube", "https://www.youtube.com/results?search_query="),
            MusicApp("SoundCloud", "com.soundcloud.android", null),
        )

        private val PLAY_LABELS = setOf("play", "shuffle play", "play all", "play artist", "lecture", "lire", "tout lire")

        internal fun isPlayButton(text: String): Boolean {
            val t = text.trim().lowercase()
            return t in PLAY_LABELS || t.startsWith("play ")
        }

        /**
         * Ce qu'il faut toucher pour lancer [query] : la ligne de résultat qui en reprend le plus de mots
         * (hors champ de recherche et haut de l'écran), ou le bouton « Lecture » une fois sur la page du titre.
         */
        internal fun pickPlayTarget(elements: List<ScreenElement>, query: String, preferPlayButton: Boolean): ScreenElement? {
            val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 && it !in setOf("by", "the", "song", "music", "de", "la", "le") }
            val height = elements.maxOfOrNull { it.bounds.split(",").getOrNull(3)?.toIntOrNull() ?: 0 } ?: 0
            fun top(e: ScreenElement) = e.bounds.split(",").getOrNull(1)?.toIntOrNull() ?: 0
            // Jamais les boutons d'à côté d'un titre : « Plus d'options », cœur de la bibliothèque, partage, son coupé…
            val usable = elements.filter { e ->
                e.role != "edit" && !e.password && !e.text.startsWith("hint:") && top(e) > height * 0.12 &&
                    NOT_PLAY_TARGETS.none { it in e.text.lowercase() }
            }
            // « Lecture » seulement dans le haut de l'écran : en bas, c'est le mini-lecteur (l'ancien titre) ou un aperçu vidéo.
            val playButton = usable.firstOrNull { isPlayButton(it.text) && top(it) < height * 0.65 }
            fun score(e: ScreenElement) = words.count { it in e.text.lowercase() }
            val texts = usable.filter { !isPlayButton(it.text) }
            // Une ligne de résultat : son titre, plus la ligne « Titre • Artiste » juste à côté.
            val row = texts.filter { score(it) > 0 }.map { e ->
                e to score(e) + (texts.filter { it !== e && kotlin.math.abs(top(it) - top(e)) <= height / 14 }.maxOfOrNull(::score) ?: 0)
            }.maxByOrNull { (_, total) -> total }?.first
            return if (preferPlayButton) playButton ?: row else row ?: playButton
        }

        private val NOT_PLAY_TARGETS = listOf("more options", "library", "added", "add ", "like", "share", "download", "unmute", "mute",
            "hide", "remove", "cancel", "clear", "filter", "follow", "plus d'options", "bibliothèque", "partager", "télécharger")

        /** L'app nommée par l'utilisateur, sinon sa dernière préférence, sinon la première installée. */
        internal fun chooseMusicApp(installed: List<MusicApp>, requested: String, preferredPackage: String?): MusicApp? {
            val wanted = requested.lowercase().replace(" ", "")
            if (wanted.isNotEmpty()) installed.firstOrNull { it.name.lowercase().replace(" ", "") == wanted }
                ?.let { return it }
            if (wanted.isNotEmpty()) installed.firstOrNull { wanted in it.name.lowercase().replace(" ", "") || it.name.lowercase().replace(" ", "") in wanted }
                ?.let { return it }
            return installed.firstOrNull { it.packageName == preferredPackage } ?: installed.firstOrNull()
        }

        internal fun parseLocal(text: String): LocalDateTime? = text.trim().takeIf { it.isNotEmpty() }?.let {
            try { LocalDateTime.parse(it.removeSuffix("Z").substringBefore('+').let { s -> if (s.length == 16) "$s:00" else s }) }
            catch (_: Exception) { null }
        }

        /** Durée d'un minuteur : « seconds », sinon « minutes »/« hours », sinon un texte comme « 1 minute » ou « 90s ». */
        internal fun timerSeconds(a: JSONObject): Int {
            a.optInt("seconds", 0).takeIf { it > 0 }?.let { return it }
            val minutes = a.optDouble("minutes", 0.0); val hours = a.optDouble("hours", 0.0)
            if (minutes > 0 || hours > 0) return (minutes * 60 + hours * 3600).toInt()
            val text = listOf("duration", "time", "seconds", "length").firstNotNullOfOrNull { a.optString(it).takeIf(String::isNotBlank) }?.lowercase() ?: return 0
            var total = 0.0
            Regex("""(\d+(?:[.,]\d+)?)\s*(h|hour|hours|m|min|mins|minute|minutes|s|sec|secs|second|seconds)?""").findAll(text).forEach { m ->
                val n = m.groupValues[1].replace(',', '.').toDouble()
                total += when (m.groupValues[2]) { "h", "hour", "hours" -> n * 3600; "m", "min", "mins", "minute", "minutes" -> n * 60; else -> n }
            }
            return total.toInt()
        }

        internal fun formatDuration(seconds: Int): String {
            val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
            return listOfNotNull(h.takeIf { it > 0 }?.let { "${it} h" }, m.takeIf { it > 0 }?.let { "$it min" }, s.takeIf { it > 0 }?.let { "$it s" })
                .joinToString(" ").ifEmpty { "0 s" }
        }
    }
}

/** Plusieurs boîtes à outils derrière une seule : définitions réunies, appels routés par nom. */
class CombinedTools(private val parts: List<DirectTools>) : DirectTools {
    override fun definitions(): JSONArray? {
        val all = JSONArray()
        parts.forEach { part -> part.definitions()?.let { defs -> for (i in 0 until defs.length()) all.put(defs.get(i)) } }
        return all.takeIf { it.length() > 0 }
    }

    private fun owner(call: ApiToolCall): DirectTools? = parts.firstOrNull { part ->
        val defs = part.definitions() ?: return@firstOrNull false
        (0 until defs.length()).any { defs.optJSONObject(it)?.optJSONObject("function")?.optString("name") == call.name }
    }

    override fun label(call: ApiToolCall) = owner(call)?.label(call) ?: "…"
    override fun finished() = parts.forEach { it.finished() }
    override suspend fun run(call: ApiToolCall): String {
        // Noms appris ailleurs (Llama : « brave_search ») : la recherche web de Claydot répond.
        val named = if (call.name in SearchAliases) call.copy(name = "search_web") else call
        return owner(named)?.run(named) ?: JSONObject().put("error", "unknown tool").toString()
    }

    private companion object { val SearchAliases = setOf("brave_search", "web_search", "google_search", "search", "internet_search") }
}
