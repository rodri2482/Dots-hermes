package app.dotshermes.data.direct

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.dotshermes.AppContainer
import app.dotshermes.push.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Routines, à la manière de n8n ou Zapier mais en langage naturel : un déclencheur (une heure, certains
 * jours) et une consigne que Claydot exécute avec ses outils (Gmail, …), puis une notification avec
 * le résultat. Créées dans la conversation (« tous les matins à 8 h, résume mes e-mails non lus »),
 * toujours après accord de l'utilisateur ; gérées dans les réglages.
 */
data class Routine(
    val id: String,
    val accountId: String,
    val name: String,
    val prompt: String,
    /** Heure locale, minutes depuis minuit. */
    val minuteOfDay: Int,
    /** Jours ISO (1 = lundi … 7 = dimanche) ; tous les jours si la liste est complète. */
    val days: Set<Int>,
    val enabled: Boolean = true,
    val lastRunMs: Long = 0,
    val lastResult: String = "",
) {
    /** Prochain passage après [now] (ou null si aucun jour n'est choisi). */
    fun nextRun(now: ZonedDateTime): ZonedDateTime? {
        if (days.isEmpty()) return null
        val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        for (offset in 0..7) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            val at = ZonedDateTime.of(LocalDateTime.of(date, time), now.zone)
            if (date.dayOfWeek.value in days && at.isAfter(now)) return at
        }
        return null
    }

    fun scheduleLabel(): String {
        val time = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
        val when_ = when (days) {
            ALL_DAYS -> "every day"
            WEEKDAYS -> "weekdays"
            setOf(6, 7) -> "weekends"
            else -> days.sorted().joinToString(", ") { DayOfWeek.of(it).name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
        }
        return "$when_ · $time"
    }

    companion object {
        val ALL_DAYS = (1..7).toSet()
        val WEEKDAYS = (1..5).toSet()

        /** « daily », « weekdays », « weekends » ou « mon,wed,fri ». */
        fun parseDays(text: String): Set<Int> {
            val t = text.trim().lowercase()
            return when (t) {
                "", "daily", "every day", "everyday" -> ALL_DAYS
                "weekdays", "weekday" -> WEEKDAYS
                "weekends", "weekend" -> setOf(6, 7)
                else -> t.split(',', ' ', ';').mapNotNull { part ->
                    DayOfWeek.entries.firstOrNull { it.name.lowercase().startsWith(part.trim().take(3)) && part.trim().length >= 2 }?.value
                }.toSet()
            }
        }

        /** « 08:00 », « 8:30 », « 7 » ; null si illisible. */
        fun parseTime(text: String): Int? {
            val m = Regex("^\\s*(\\d{1,2})(?::(\\d{2}))?\\s*$").find(text) ?: return null
            val h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            return if (h in 0..23 && min in 0..59) h * 60 + min else null
        }
    }
}

/** Les routines, gardées en clair dans les préférences (des consignes, aucune donnée personnelle). */
class RoutineStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("routines", Context.MODE_PRIVATE)
    private val _routines = MutableStateFlow(load())
    val routines: StateFlow<List<Routine>> = _routines.asStateFlow()

    private fun load(): List<Routine> = try {
        val rows = JSONArray(prefs.getString("list", "[]"))
        List(rows.length()) { i ->
            val r = rows.getJSONObject(i)
            Routine(r.getString("id"), r.getString("account"), r.getString("name"), r.getString("prompt"), r.getInt("minute"),
                r.getJSONArray("days").let { d -> List(d.length()) { d.getInt(it) }.toSet() }, r.optBoolean("enabled", true),
                r.optLong("last_run"), r.optString("last_result"))
        }
    } catch (_: JSONException) { emptyList() }

    private fun save(list: List<Routine>) {
        _routines.value = list
        prefs.edit { putString("list", JSONArray().apply {
            list.forEach { r -> put(JSONObject().put("id", r.id).put("account", r.accountId).put("name", r.name).put("prompt", r.prompt)
                .put("minute", r.minuteOfDay).put("days", JSONArray(r.days.sorted())).put("enabled", r.enabled)
                .put("last_run", r.lastRunMs).put("last_result", r.lastResult.take(500))) }
        }.toString()) }
    }

    fun get(id: String) = _routines.value.firstOrNull { it.id == id }

    fun add(routine: Routine) { save(_routines.value + routine); RoutineWorker.schedule(context, routine) }

    fun setEnabled(id: String, on: Boolean) {
        save(_routines.value.map { if (it.id == id) it.copy(enabled = on) else it })
        get(id)?.let { if (on) RoutineWorker.schedule(context, it) else RoutineWorker.cancel(context, id) }
    }

    fun delete(id: String) { save(_routines.value.filterNot { it.id == id }); RoutineWorker.cancel(context, id) }

    fun deleteAccount(accountId: String) = _routines.value.filter { it.accountId == accountId }.forEach { delete(it.id) }

    fun recordRun(id: String, result: String) =
        save(_routines.value.map { if (it.id == id) it.copy(lastRunMs = System.currentTimeMillis(), lastResult = result) else it })

    /** Au démarrage de l'app : chaque routine active retrouve son prochain passage. */
    fun rescheduleAll() = _routines.value.filter { it.enabled }.forEach { RoutineWorker.schedule(context, it) }
}

/** Exécute une routine à l'heure dite, puis programme la suivante. */
class RoutineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    /** Notification discrète « Routine en cours » : la routine peut travailler longtemps sans être coupée. */
    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        val routine = inputData.getString(KEY_ID)?.let { AppContainer.routines.get(it) }
        return RoutineAlarms.foregroundInfo(applicationContext, routine?.name.orEmpty())
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        val store = AppContainer.routines
        val routine = store.get(id)?.takeIf { it.enabled } ?: return Result.success()
        runCatching { setForeground(getForegroundInfo()) }
        val repo = AppContainer.agents.repo(routine.accountId) as? DirectChatRepository
        val reply = repo?.runRoutine("${routine.name}: ${routine.prompt}")
        if (reply == null && runAttemptCount < 2) return Result.retry()
        val text = reply?.takeIf { it.isNotBlank() } ?: "Couldn't run this routine (no connection or the account is busy)."
        store.recordRun(id, text)
        Notifications.showAgentMessage(applicationContext, routine.accountId, "message", "${routine.name}\n$text")
        store.get(id)?.let { schedule(applicationContext, it) }
        return Result.success()
    }

    companion object {
        private const val KEY_ID = "routine"
        private fun name(id: String) = "routine-$id"

        /** Prochain passage : une alarme à l'heure exacte (voir [RoutineAlarms]). */
        fun schedule(context: Context, routine: Routine) = RoutineAlarms.schedule(context, routine)

        /** L'heure est venue : la routine part tout de suite, en priorité, dès qu'il y a du réseau. */
        fun runNow(context: Context, id: String) {
            val request = OneTimeWorkRequestBuilder<RoutineWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(id), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, id: String) {
            RoutineAlarms.cancel(context, id)
            WorkManager.getInstance(context).cancelUniqueWork(name(id))
        }
    }
}

/** Outils de la conversation : créer (après accord), lister et supprimer des routines. */
class RoutineTools(
    private val store: RoutineStore,
    private val accountId: () -> String?,
    private val confirm: suspend (title: String, detail: String) -> Boolean,
) : DirectTools {
    override fun definitions(): JSONArray? = if (accountId() == null) null else JSONArray()
        .put(fn("create_routine", "Create a recurring routine that runs a task on a schedule and notifies the user with the result " +
            "(like an n8n/Zapier automation). The prompt is what you will do at each run, e.g. 'Summarise my unread emails from today'. " +
            "The user confirms before it is saved.",
            JSONObject()
                .put("name", str("Short name, e.g. 'Morning email summary'"))
                .put("prompt", str("The task to perform at each run, written as an instruction to yourself"))
                .put("time", str("Local time HH:MM, 24h"))
                .put("days", str("'daily', 'weekdays', 'weekends' or a list like 'mon,wed,fri'")),
            "name", "prompt", "time"))
        .put(fn("list_routines", "List the user's routines with their schedule and last result.", JSONObject()))
        .put(fn("delete_routine", "Delete a routine by id (from list_routines).", JSONObject().put("id", str("Routine id")), "id"))

    override fun label(call: ApiToolCall) = ToolKeys.of("routine")

    override suspend fun run(call: ApiToolCall): String {
        val account = accountId() ?: return err("no account")
        val args = try { JSONObject(call.arguments.ifBlank { "{}" }) } catch (_: JSONException) { JSONObject() }
        return when (call.name) {
            "create_routine" -> {
                val minute = Routine.parseTime(args.optString("time")) ?: return err("time must be HH:MM")
                val days = Routine.parseDays(args.optString("days"))
                if (days.isEmpty()) return err("days not understood")
                val routine = Routine(UUID.randomUUID().toString().take(8), account, args.optString("name").trim().take(60).ifEmpty { "Routine" },
                    args.optString("prompt").trim().take(600).ifEmpty { return err("prompt is required") }, minute, days)
                if (!confirm(ToolKeys.title("routine", routine.name), "${routine.scheduleLabel()}\n${routine.prompt}")) return JSONObject().put("status", "cancelled").toString()
                store.add(routine)
                JSONObject().put("status", "created").put("id", routine.id).put("schedule", routine.scheduleLabel()).toString()
            }
            "list_routines" -> JSONObject().put("routines", JSONArray().apply {
                store.routines.value.filter { it.accountId == account }.forEach { r ->
                    put(JSONObject().put("id", r.id).put("name", r.name).put("schedule", r.scheduleLabel()).put("enabled", r.enabled)
                        .put("prompt", r.prompt).put("last_result", r.lastResult.take(200)))
                }
            }).toString()
            "delete_routine" -> {
                val r = store.get(args.optString("id"))?.takeIf { it.accountId == account } ?: return err("no routine with that id")
                if (!confirm(ToolKeys.title("delete", r.name), r.scheduleLabel())) return JSONObject().put("status", "cancelled").toString()
                store.delete(r.id)
                JSONObject().put("status", "deleted").toString()
            }
            else -> err("unknown tool")
        }
    }

    private fun err(message: String) = JSONObject().put("error", message).toString()
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun fn(name: String, description: String, properties: JSONObject, vararg required: String) = JSONObject()
        .put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required.toList()))))
}


/**
 * Heure exacte des routines : AlarmManager les réveille à la minute près, même en veille (WorkManager seul
 * les décalait parfois de plusieurs minutes). Sans l'autorisation « alarmes exactes », Android les
 * déclenche au plus près qu'il peut.
 */
object RoutineAlarms {
    private const val CHANNEL = "claydot_routines"
    private const val EXTRA_ID = "routine"

    private fun intent(context: Context, id: String) = android.app.PendingIntent.getBroadcast(
        context, id.hashCode(), android.content.Intent(context, RoutineAlarmReceiver::class.java).putExtra(EXTRA_ID, id),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
    )

    fun canBeExact(context: Context): Boolean {
        val alarms = context.getSystemService(android.app.AlarmManager::class.java) ?: return false
        return android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    }

    fun schedule(context: Context, routine: Routine) {
        if (!routine.enabled) return
        val next = routine.nextRun(ZonedDateTime.now(ZoneId.systemDefault()))?.toInstant()?.toEpochMilli() ?: return
        val alarms = context.getSystemService(android.app.AlarmManager::class.java) ?: return
        val pending = intent(context, routine.id)
        if (canBeExact(context)) alarms.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next, pending)
        else alarms.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next, pending)
    }

    fun cancel(context: Context, id: String) {
        context.getSystemService(android.app.AlarmManager::class.java)?.cancel(intent(context, id))
    }

    fun idFrom(intent: android.content.Intent?) = intent?.getStringExtra(EXTRA_ID)

    fun foregroundInfo(context: Context, name: String): androidx.work.ForegroundInfo {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(android.app.NotificationChannel(CHANNEL,
                context.getString(app.dotshermes.R.string.routine_channel), android.app.NotificationManager.IMPORTANCE_LOW))
        }
        val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(app.dotshermes.R.drawable.ic_stat_hermes)
            .setContentTitle(context.getString(app.dotshermes.R.string.routine_running))
            .setContentText(name)
            .setOngoing(true).setSilent(true)
            .build()
        return if (android.os.Build.VERSION.SDK_INT >= 29) androidx.work.ForegroundInfo(4242, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else androidx.work.ForegroundInfo(4242, notification)
    }
}

/** Réveil d'une routine à son heure ; et après un redémarrage du téléphone, toutes les routines sont reprogrammées. */
class RoutineAlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent?) {
        when (intent?.action) {
            android.content.Intent.ACTION_BOOT_COMPLETED, android.content.Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> AppContainer.routines.rescheduleAll()
            else -> RoutineAlarms.idFrom(intent)?.let { RoutineWorker.runNow(context, it) }
        }
    }
}
