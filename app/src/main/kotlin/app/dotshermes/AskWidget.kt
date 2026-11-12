package app.dotshermes

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Widget « Demander à Claydot » : un toucher ouvre la conversation, le champ de saisie prêt. */
class AskWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(IncomingContent.EXTRA_ASK, true)
        val tap = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val views = RemoteViews(context.packageName, R.layout.widget_ask).apply { setOnClickPendingIntent(R.id.widget_root, tap) }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
