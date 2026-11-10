package app.dotshermes.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import app.dotshermes.AppContainer
import app.dotshermes.MainActivity
import app.dotshermes.R
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.displayName
import app.dotshermes.data.AgentStore
import app.dotshermes.data.model.MAIN_CHAT
import app.dotshermes.ui.dots.appearance
import app.dotshermes.ui.dot.art
import androidx.compose.ui.graphics.toArgb

/**
 * Notifications de conversation : une par agent, empilée façon messagerie, avec réponse rapide.
 * Chaque notification (et chacun de ses boutons) porte l'agent qui a écrit : la réponse et le feu
 * vert repartent vers lui, jamais vers l'agent affiché à l'écran.
 */
object Notifications {
    private const val CHANNEL = "hermes_messages"
    /** Fil de l'agent historique : le même identifiant qu'avant le multi-agents. */
    private const val PRIMARY_CONVERSATION_ID = 1
    const val KEY_REPLY = "reply_text"
    const val EXTRA_CHAT = "chat"
    const val EXTRA_AGENT = "agent"

    private val Accent = 0xFFFF8A3D.toInt()
    private val MarkdownMarks = Regex("""\*\*|`|^#{1,6}\s+""", RegexOption.MULTILINE)

    fun createChannel(context: Context) {
        // Recréé à chaque lancement : le nom et la description suivent la langue du téléphone.
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.notif_channel_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Message d'un dot. Tous les dots partagent un seul fil de notification, chacun sous son nom :
     * [title] vient du gateway pour un sous-dot (vide pour le principal, dont le nom est choisi ici).
     */
    fun showAgentMessage(context: Context, agent: String, kind: String, body: String, chat: String = MAIN_CHAT, title: String = "") {
        val text = when (kind) {
            "approval" -> "🔐 $body"
            "clarify" -> "❓ $body"
            else -> body
        }.replace(MarkdownMarks, "")
        post(context, agent, alert = true, chat = chat) { style -> style.addMessage(text, System.currentTimeMillis(), sender(context, agent, chat, title)) }
    }

    /** La réponse rapide s'ajoute au fil de la notification, sans re-sonner. */
    fun showOwnReply(context: Context, agent: String, text: String, delivered: Boolean, chat: String = MAIN_CHAT) {
        val shown = if (delivered) text else context.getString(R.string.notif_reply_not_sent, text)
        post(context, agent, alert = false, chat = chat) { style -> style.addMessage(shown, System.currentTimeMillis(), null as Person?) }
    }

    /** L'app revient à l'écran : les fils de tous les agents s'effacent. */
    fun clear(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(PRIMARY_CONVERSATION_ID)
        AppContainer.agents.store.agents.value.forEach { manager.cancel(conversationId(it.id)) }
    }

    /** Un fil par agent ; 100..1123 pour les autres, loin de la mise à jour (2) et des feux verts (1000+). */
    private fun conversationId(agent: String) =
        if (agent == AgentStore.PRIMARY_ID) PRIMARY_CONVERSATION_ID else 100 + (agent.hashCode() and 0x3FF)

    /**
     * Demande de feu vert : notification à part (une par commande), avec ses deux boutons.
     * La commande s'affiche en entier une fois dépliée : on décide en connaissance de cause.
     */
    fun showApproval(context: Context, agent: String, approvalId: String, command: String) {
        if (!canNotify(context)) return
        fun answer(approved: Boolean) = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_hermes, context.getString(if (approved) R.string.action_approve else R.string.action_decline),
            PendingIntent.getBroadcast(
                context, (approvalId + approved).hashCode(),
                Intent(context, ApprovalReceiver::class.java)
                    .putExtra(EXTRA_AGENT, agent)
                    .putExtra(ApprovalReceiver.EXTRA_APPROVAL_ID, approvalId)
                    .putExtra(ApprovalReceiver.EXTRA_APPROVED, approved),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).setShowsUserInterface(false).build()
        val text = command.replace(MarkdownMarks, "")
        val notification = approvalBuilder(context, agent, context.getString(R.string.notif_approval_title, agentName(agent)), text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(answer(false))
            .addAction(answer(true))
            .setAutoCancel(false)
            .build()
        notifySafely(context, approvalNotificationId(approvalId), notification)
    }

    /** Juste après l'appui : les boutons disparaissent pendant l'envoi (pas de double réponse). */
    fun showApprovalPending(context: Context, agent: String, approvalId: String, approved: Boolean) {
        if (!canNotify(context)) return
        val notification = approvalBuilder(
            context, agent,
            context.getString(if (approved) R.string.notif_approval_approving else R.string.notif_approval_declining),
            context.getString(R.string.notif_approval_sending, agentName(agent)),
        )
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        notifySafely(context, approvalNotificationId(approvalId), notification)
    }

    /** Résultat : confirmation qui s'efface seule, ou invitation à ouvrir l'app si l'envoi a échoué. */
    fun showApprovalOutcome(context: Context, agent: String, approvalId: String, approved: Boolean, delivered: Boolean) {
        if (!canNotify(context)) return
        val builder = if (delivered) {
            approvalBuilder(
                context, agent,
                context.getString(if (approved) R.string.notif_approval_approved else R.string.notif_approval_declined),
                context.getString(if (approved) R.string.notif_approval_approved_text else R.string.notif_approval_declined_text, agentName(agent)),
            ).setTimeoutAfter(4_000)
        } else {
            approvalBuilder(context, agent, context.getString(R.string.notif_approval_not_sent), context.getString(R.string.notif_approval_not_sent_text))
        }
        notifySafely(context, approvalNotificationId(approvalId), builder.setOnlyAlertOnce(true).build())
    }

    /** Réglé ailleurs (dans l'app, sur un autre appareil) : la notification n'a plus lieu d'être. */
    fun cancelApproval(context: Context, approvalId: String) =
        NotificationManagerCompat.from(context).cancel(approvalNotificationId(approvalId))

    private fun approvalNotificationId(approvalId: String) = 1_000 + (approvalId.hashCode() and 0x0FFFFFFF)

    private fun approvalBuilder(context: Context, agent: String, title: String, text: String) = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_hermes)
        .setColor(Accent)
        .setContentTitle(title)
        .setContentText(text)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(openApp(context, agent))

    private fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notifySafely(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // La permission peut être révoquée entre le contrôle et l'appel au système.
        }
    }

    /** Ouvre l'app sur l'agent qui demande le feu vert (code de requête propre à l'agent). */
    private fun openApp(context: Context, agent: String) = PendingIntent.getActivity(
        context, 50_000 + conversationId(agent),
        Intent(context, MainActivity::class.java).putExtra(EXTRA_AGENT, agent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** [chat] : conversation du dernier message ; « Répondre » et l'ouverture de l'app y mènent. */
    private fun post(context: Context, agent: String, alert: Boolean, chat: String, add: (NotificationCompat.MessagingStyle) -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val id = conversationId(agent)
        val style = currentStyle(context, id) ?: NotificationCompat.MessagingStyle(me(context))
        add(style)

        // Un code de requête par agent : avec FLAG_UPDATE_CURRENT, un code commun ferait répondre
        // le fil d'un agent avec les extras (agent, conversation) du dernier agent notifié.
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_AGENT, agent).putExtra(EXTRA_CHAT, chat)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Mutable : le système doit pouvoir y glisser le texte saisi (RemoteInput).
        val reply = PendingIntent.getBroadcast(
            context, id, Intent(context, ReplyReceiver::class.java).putExtra(EXTRA_AGENT, agent).putExtra(EXTRA_CHAT, chat),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(R.drawable.ic_stat_hermes, context.getString(R.string.action_reply), reply)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.composer_placeholder, dotName(agent, chat))).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setColor(Accent)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .addAction(replyAction)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .build()
        notifySafely(context, id, notification)
    }

    /** Reprend le fil déjà affiché de cet agent pour y empiler le nouveau message. */
    private fun currentStyle(context: Context, id: Int): NotificationCompat.MessagingStyle? {
        val active = context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == id } ?: return null
        return NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(active.notification)
    }

    private fun me(context: Context) = Person.Builder().setName(context.getString(R.string.notif_me)).setKey("me").build()

    /** Avatar mis en cache pour une mascotte + couleur ; recalculé seulement si l'apparence change. */
    private val avatarCache = java.util.concurrent.ConcurrentHashMap<String, IconCompat>()

    /**
     * L'expéditeur : le dot principal sous le nom, la mascotte et la couleur choisis dans l'app, ou un
     * sous-dot avec les siens (connus si l'app tourne ; sinon son nom envoyé par le gateway suffit).
     */
    private fun sender(context: Context, agent: String, chat: String, title: String): Person {
        val main = AppContainer.agents.appearance(agent).state.value
        val look = AppContainer.agents.repo(agent).dots.value.firstOrNull { it.id == chat }?.appearance(main) ?: main
        val name = if (chat == MAIN_CHAT) main.displayName else title.ifBlank { look.displayName }
        val key = "${look.species.name}/${look.palette.id}"
        val icon = avatarCache[key] ?: IconCompat.createWithBitmap(avatar(context, look)).also { avatarCache[key] = it }
        return Person.Builder().setName(name).setKey("$agent/$chat").setBot(true).setIcon(icon).build()
    }

    private fun dotName(agent: String, chat: String): String =
        AppContainer.agents.repo(agent).dots.value.firstOrNull { it.id == chat }?.name ?: agentName(agent)

    private fun agentName(agent: String) = AppContainer.agents.appearance(agent).state.value.displayName

    /** Avatar : le dot en pâte à modeler (corps, yeux, sourire) recadré sur un disque clair. */
    private fun avatar(context: Context, look: DotAppearance): Bitmap {
        val art = look.species.art
        val size = 192
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color = look.palette.light.toArgb()
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        // Calques de 640 px : carré centré sur le corps, un peu d'air autour.
        val mid = ((art.bodyTop + art.bodyBottom) / 2 * 640).toInt()
        val src = Rect(110, mid - 210, 530, mid + 210)
        val dst = Rect(0, 0, size, size)
        for (layer in listOf(art.body(look.palette.id), art.eyesOpen, art.mouthSmile)) {
            BitmapFactory.decodeResource(context.resources, layer)?.let { canvas.drawBitmap(it, src, dst, paint) }
        }
        return out
    }
}
