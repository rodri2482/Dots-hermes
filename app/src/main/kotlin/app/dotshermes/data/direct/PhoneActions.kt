package app.dotshermes.data.direct

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
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

/** Une action proposée par Claydot, en attente de l'accord de l'utilisateur. */
class PendingPhoneAction(val title: String, val detail: String, internal val answer: CompletableDeferred<Boolean>) {
    fun confirm() { answer.complete(true) }
    fun cancel() { answer.complete(false) }
}

/**
 * Actions sur le téléphone que Claydot peut proposer : réveil, minuteur, ouvrir une app, événement
 * d'agenda. Rien ne part sans un appui sur « Faire » dans la fenêtre de confirmation ; aucune action
 * ne lit ni n'envoie de données, et un e-mail ne peut pas en déclencher une sans cette confirmation.
 */
class PhoneActions(private val context: Context) : DirectTools {
    private val prefs = context.getSharedPreferences("phone-actions", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _pending = MutableStateFlow<PendingPhoneAction?>(null)
    /** L'action à confirmer maintenant (la fenêtre de l'app l'affiche) ; null = rien en attente. */
    val pending: StateFlow<PendingPhoneAction?> = _pending.asStateFlow()

    fun setEnabled(on: Boolean) { prefs.edit { putBoolean("enabled", on) }; _enabled.value = on }

    override fun definitions(): JSONArray? = if (!_enabled.value) null else JSONArray()
        .put(function("set_alarm", "Set an alarm on the user's phone (the user confirms first).",
            props("hour" to int("0-23"), "minute" to int("0-59"), "label" to str("Optional alarm label")), "hour", "minute"))
        .put(function("set_timer", "Start a countdown timer on the user's phone (the user confirms first).",
            props("seconds" to int("Duration in seconds, 1 to 86400"), "label" to str("Optional label")), "seconds"))
        .put(function("open_app", "Open an app installed on the user's phone, by its name (the user confirms first).",
            props("name" to str("App name as shown on the home screen, e.g. 'WhatsApp'")), "name"))
        .put(function("add_calendar_event",
            "Prepare a calendar event; the phone's calendar opens pre-filled so the user can save it (the user confirms first).",
            props("title" to str("Event title"), "start" to str("Local start, ISO 8601 like 2026-11-12T15:00"),
                "end" to str("Optional local end, ISO 8601"), "location" to str("Optional location")), "title", "start"))

    /** Fenêtre « Claydot veut… » : true si l'utilisateur appuie sur « Faire » (2 min, sinon refus). */
    suspend fun ask(title: String, detail: String): Boolean {
        val action = PendingPhoneAction(title, detail, CompletableDeferred())
        _pending.value = action
        return try { withTimeoutOrNull(120_000) { action.answer.await() } ?: false } finally {
            if (_pending.value === action) _pending.value = null
        }
    }

    override fun label(call: ApiToolCall) = when (call.name) {
        "set_alarm" -> "⏰…"; "set_timer" -> "⏱…"; "open_app" -> "📱…"; "add_calendar_event" -> "📅…"; else -> "…"
    }

    override suspend fun run(call: ApiToolCall): String {
        val args = try { JSONObject(call.arguments.ifBlank { "{}" }) } catch (_: JSONException) { JSONObject() }
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
            Plan("⏰ %02d:%02d".format(hour, minute), label, Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .apply { if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label) }
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        }
        "set_timer" -> {
            val seconds = a.optInt("seconds", 0).also { require(it in 1..86_400) { "seconds must be 1-86400" } }
            val label = a.optString("label").take(80)
            Plan("⏱ ${formatDuration(seconds)}", label, Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .apply { if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label) }
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        }
        "open_app" -> {
            val wanted = a.optString("name").trim().also { require(it.isNotEmpty()) { "name is required" } }
            val app = findApp(wanted) ?: throw IllegalArgumentException("No installed app is called '$wanted'.")
            Plan("📱 ${app.first}", "", app.second)
        }
        "add_calendar_event" -> {
            val title = a.optString("title").trim().take(120).also { require(it.isNotEmpty()) { "title is required" } }
            val start = parseLocal(a.optString("start")) ?: throw IllegalArgumentException("start must be ISO 8601, e.g. 2026-11-12T15:00")
            val end = parseLocal(a.optString("end"))?.takeIf { it.isAfter(start) } ?: start.plusHours(1)
            val location = a.optString("location").take(120)
            val zone = ZoneId.systemDefault()
            Plan("📅 $title", start.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)) +
                (if (location.isNotBlank()) " · $location" else ""),
                Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, title)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.atZone(zone).toInstant().toEpochMilli())
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.atZone(zone).toInstant().toEpochMilli())
                    .apply { if (location.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location) })
        }
        else -> null
    }

    /** L'app dont le nom ressemble le plus à [wanted] parmi celles de l'écran d'accueil. */
    private fun findApp(wanted: String): Pair<String, Intent>? {
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

    companion object {
        internal fun parseLocal(text: String): LocalDateTime? = text.trim().takeIf { it.isNotEmpty() }?.let {
            try { LocalDateTime.parse(it.removeSuffix("Z").substringBefore('+').let { s -> if (s.length == 16) "$s:00" else s }) }
            catch (_: Exception) { null }
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
    override suspend fun run(call: ApiToolCall): String =
        owner(call)?.run(call) ?: JSONObject().put("error", "unknown tool").toString()
}
