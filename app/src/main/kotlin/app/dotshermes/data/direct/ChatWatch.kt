package app.dotshermes.data.direct

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Un message vu dans une notification de messagerie (WhatsApp) : la discussion, l'auteur, le texte, l'heure. */
data class ChatPost(val app: String, val chat: String, val sender: String, val text: String, val timeMs: Long, val group: Boolean)

/**
 * Le journal des messages de discussion, tiré des notifications (WhatsApp et WhatsApp Business) : de quoi
 * faire l'appel d'un groupe ou relire un fil sans ouvrir l'app, même téléphone verrouillé. Gardé sur le
 * téléphone seulement, trois jours au plus ; vidé en retirant l'accès aux notifications.
 */
object ChatLog {
    val WATCHED = setOf("com.whatsapp", "com.whatsapp.w4b")
    private const val KEEP_MS = 3 * 24 * 3_600_000L
    private const val MAX_POSTS = 3_000
    private val posts = ArrayList<ChatPost>()
    /** Le bouton « Répondre » de la dernière notification de chaque discussion (tant qu'elle est affichée). */
    private val replies = HashMap<String, Notification.Action>()
    private var file: File? = null

    @Synchronized fun attach(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "chat-log.json")
        runCatching {
            val rows = JSONArray(file!!.readText())
            for (i in 0 until rows.length()) rows.optJSONObject(i)?.let { o ->
                posts += ChatPost(o.optString("app"), o.optString("chat"), o.optString("sender"), o.optString("text"), o.optLong("time"), o.optBoolean("group"))
            }
        }
    }

    @Synchronized fun add(context: Context, new: List<ChatPost>, reply: Notification.Action?) {
        attach(context)
        var changed = false
        for (p in new) {
            if (posts.none { it.chat == p.chat && it.sender == p.sender && it.timeMs == p.timeMs && it.text == p.text }) { posts += p; changed = true }
        }
        new.firstOrNull()?.let { p -> reply?.let { replies[key(p.chat)] = it } }
        if (!changed) return
        val cutoff = System.currentTimeMillis() - KEEP_MS
        posts.removeAll { it.timeMs < cutoff }
        while (posts.size > MAX_POSTS) posts.removeAt(0)
        save()
    }

    @Synchronized fun clear(context: Context) { attach(context); posts.clear(); replies.clear(); save() }

    private fun save() = runCatching {
        file?.writeText(JSONArray().apply {
            posts.forEach { p -> put(JSONObject().put("app", p.app).put("chat", p.chat).put("sender", p.sender).put("text", p.text.take(500))
                .put("time", p.timeMs).put("group", p.group)) }
        }.toString())
    }

    private fun key(chat: String) = chat.trim().lowercase()

    /** Les discussions connues dont le nom ressemble à [name] (le nom exact d'abord). */
    @Synchronized fun findChat(context: Context, name: String): String? {
        attach(context)
        val wanted = key(name)
        val chats = posts.map { it.chat }.distinct()
        return chats.firstOrNull { key(it) == wanted } ?: chats.firstOrNull { wanted in key(it) || key(it) in wanted }
    }

    @Synchronized fun messages(context: Context, chat: String, sinceMs: Long): List<ChatPost> {
        attach(context)
        return posts.filter { key(it.chat) == key(chat) && it.timeMs >= sinceMs }.sortedBy { it.timeMs }
    }

    @Synchronized fun knownChats(context: Context): List<String> { attach(context); return posts.map { it.chat }.distinct() }

    @Synchronized fun replyAction(chat: String): Notification.Action? = replies[key(chat)]

    /** Envoie [text] avec le bouton « Répondre » de la notification (marche téléphone verrouillé). */
    fun reply(context: Context, chat: String, text: String): Boolean {
        val action = replyAction(chat) ?: return false
        val inputs = action.remoteInputs ?: return false
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs, fill, results)
        return try { action.actionIntent.send(context, 0, fill); true } catch (_: PendingIntent.CanceledException) {
            synchronized(this) { replies.remove(key(chat)) }; false
        }
    }

    /**
     * L'appel : chaque personne qui a écrit dans la discussion entre [from] et [to], une seule fois,
     * dans l'ordre de son premier message, avec l'heure. Calculé ici, pas par le modèle : pas d'oubli.
     */
    fun attendance(posts: List<ChatPost>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, String>> =
        posts.filter { it.sender.isNotBlank() && it.sender != "You" && it.sender != "Vous" }
            .groupBy { it.sender }.map { (name, list) -> name to list.minOf { it.timeMs } }
            .sortedBy { it.second }
            .map { (name, time) -> name to Instant.ofEpochMilli(time).atZone(zone).toLocalTime().toString().take(5) }

    fun startOfToday(zone: ZoneId = ZoneId.systemDefault()) = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

    /** L'utilisateur a donné à Claydot l'accès aux notifications (réglage Android). */
    fun accessGranted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.contains(ComponentName(context, ChatNotificationListener::class.java).flattenToString())
    }
}

/** Lit les notifications de WhatsApp (rien d'autre) pour le journal des discussions ; n'agit jamais seul. */
class ChatNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { ChatLog.attach(this); activeNotifications?.forEach(::read) }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { sbn?.let(::read) }

    private fun read(sbn: StatusBarNotification) {
        if (sbn.packageName !in ChatLog.WATCHED) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val extras = n.extras
        val chat = (style?.conversationTitle ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.substringBefore(" (")?.trim().orEmpty()
        if (chat.isEmpty()) return
        val group = style?.isGroupConversation ?: (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null)
        val posts = style?.messages?.mapNotNull { m ->
            val text = m.text?.toString() ?: return@mapNotNull null
            ChatPost(sbn.packageName, chat, m.person?.name?.toString() ?: chat, text, m.timestamp, group)
        } ?: listOfNotNull(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let {
            ChatPost(sbn.packageName, chat, chat, it, sbn.postTime, group)
        })
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        ChatLog.add(this, posts, reply)
    }
}
