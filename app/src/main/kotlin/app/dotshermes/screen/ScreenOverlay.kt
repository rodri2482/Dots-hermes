package app.dotshermes.screen

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import app.dotshermes.R

/**
 * Ce que l'utilisateur voit pendant que Claydot utilise l'écran : un curseur (rond orange) qui glisse
 * jusqu'à chaque endroit touché avec une onde au moment du toucher, et une pastille en haut
 * « Claydot utilise ton écran · Arrêter ». Hors de l'app, la pastille s'agrandit : la dernière phrase de
 * Claydot et sa question ouverte, avec ses réponses (on lit et on répond sans revenir dans Claydot).
 * Deux fenêtres d'accessibilité : le curseur ne capte aucun toucher, seule la pastille est touchable.
 */
internal class ScreenOverlay(private val context: Context, private val onStop: () -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val cursor = CursorView(context)
    private var cursorShown = false
    private var pill: LinearLayout? = null
    private var active = false
    private var note: app.dotshermes.data.direct.LiveNote? = null
    private var appShown = true
    private var wide = false
    /** Cette tâche a montré la pastille (écran piloté, question) : sa réponse finale peut s'y afficher. */
    private var sawTask = false
    private val clearDone = Runnable { if (note?.done == true) { note = null; refresh() } }
    private val hide = Runnable { if (!active) removeCursor() }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    private fun params(touchable: Boolean, width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun addCursor() {
        if (cursorShown) return
        runCatching { windows.addView(cursor, params(false, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            .apply { gravity = Gravity.TOP or Gravity.START }) }.onSuccess { cursorShown = true }
    }

    private fun removeCursor() {
        if (!cursorShown) return
        runCatching { windows.removeView(cursor) }
        cursorShown = false
        cursor.reset()
    }

    /** Tâche en cours (pastille + curseur) ou finie. */
    fun setActive(on: Boolean) {
        active = on
        if (on) addCursor() else { cursor.removeCallbacks(hide); cursor.postDelayed(hide, 1_500) }
        refresh()
    }

    /** Ce que Claydot dit et demande ; [shown] : l'app est à l'écran (le fil montre déjà tout). */
    fun setNote(value: app.dotshermes.data.direct.LiveNote?, shown: Boolean) {
        if (value == null) sawTask = false
        note = value; appShown = shown
        cursor.removeCallbacks(clearDone)
        if (value?.done == true) cursor.postDelayed(clearDone, DONE_SHOWN_MS)
        refresh()
    }

    private fun refresh() {
        val current = note
        if (active || current?.card != null) sawTask = true
        val expanded = !appShown && (current?.card != null || ((active || (sawTask && current?.done == true)) && !current?.text.isNullOrBlank()))
        if (!active && !expanded) { pill?.let { runCatching { windows.removeView(it) } }; pill = null; return }
        val view = pill ?: buildPill().also { v ->
            pill = v
            runCatching { windows.addView(v, panelParams(expanded)) }
            wide = expanded
        }
        if (wide != expanded) { wide = expanded; runCatching { windows.updateViewLayout(view, panelParams(expanded)) } }
        fill(view, if (expanded) current else null)
    }

    private fun panelParams(expanded: Boolean) = params(true,
        if (expanded) context.resources.displayMetrics.widthPixels - dp(24f).toInt() else WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(44f).toInt() }

    /** Le curseur glisse jusqu'à (x, y) puis une onde marque le toucher ; un appui long garde l'onde plus longtemps. */
    fun tapAt(x: Float, y: Float, long: Boolean) {
        addCursor()
        cursor.moveAndTap(x, y, long)
        if (!active) { cursor.removeCallbacks(hide); cursor.postDelayed(hide, 2_500) }
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        addCursor()
        cursor.swipe(x1, y1, x2, y2)
        if (!active) { cursor.removeCallbacks(hide); cursor.postDelayed(hide, 2_500) }
    }

    fun dispose() { active = false; note = null; pill?.let { runCatching { windows.removeView(it) } }; pill = null; removeCursor() }

    private fun buildPill(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padH = dp(14f).toInt(); val padV = dp(8f).toInt()
        setPadding(padH, padV, dp(6f).toInt(), padV)
        background = GradientDrawable().apply { cornerRadius = dp(22f); setColor(Color.argb(240, 24, 24, 28)) }
        elevation = dp(6f)
    }

    /** La ligne d'en-tête (point, texte, Arrêter), puis, hors de l'app, ce que Claydot dit et demande. */
    private fun fill(panel: LinearLayout, shown: app.dotshermes.data.direct.LiveNote?) {
        panel.removeAllViews()
        panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CURSOR_COLOR) }
                layoutParams = LinearLayout.LayoutParams(dp(9f).toInt(), dp(9f).toInt()).apply { marginEnd = dp(8f).toInt() }
            })
            addView(TextView(context).apply {
                text = context.getString(if (active) R.string.screen_overlay_working else R.string.app_name)
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(if (shown != null) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT, if (shown != null) 1f else 0f)
            })
            if (active) addView(button(context.getString(R.string.screen_overlay_stop), Color.argb(255, 214, 59, 47)) { onStop() })
            else if (shown?.done == true) addView(button(context.getString(R.string.action_close), Color.argb(90, 255, 255, 255)) { note = null; refresh() })
        })
        if (shown == null) return
        if (shown.text.isNotBlank() && shown.card == null) panel.addView(TextView(context).apply {
            text = shown.text.trim()
            setTextColor(Color.argb(235, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 5; ellipsize = android.text.TextUtils.TruncateAt.START
            setPadding(0, dp(8f).toInt(), dp(8f).toInt(), dp(4f).toInt())
        })
        val card = shown.card ?: run {
            if (shown.done) panel.addView(answer(context.getString(R.string.card_open_app), filled = false) { openApp() })
            return
        }
        panel.addView(TextView(context).apply {
            text = app.dotshermes.data.direct.ToolKeys.splitTitle(card.question).second
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); paint.isFakeBoldText = true
            setPadding(0, dp(10f).toInt(), dp(8f).toInt(), dp(2f).toInt())
        })
        if (card.detail.isNotBlank()) panel.addView(TextView(context).apply {
            text = card.detail; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.argb(200, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, dp(8f).toInt(), dp(4f).toInt())
        })
        val answers = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6f).toInt(), dp(8f).toInt(), 0) }
        // Réponses d'une question (choix simple) et « Terminé » d'une main passée ; un accord ou un accès se décide dans l'app.
        if (card.kind == app.dotshermes.data.model.CardKind.Question && !card.multi) card.options.forEach { option ->
            answers.addView(answer(option) { reply(card.id, app.dotshermes.data.direct.CardReply.Answer(option)) })
        }
        if (card.kind == app.dotshermes.data.model.CardKind.Handover) {
            answers.addView(answer(context.getString(R.string.takeover_done_answer)) {
                reply(card.id, app.dotshermes.data.direct.CardReply.Answer(app.dotshermes.data.direct.ChatCards.DONE))
            })
        }
        answers.addView(answer(context.getString(R.string.card_open_app), filled = false) { openApp() })
        panel.addView(answers)
    }

    /** Un toucher juste après un geste de Claydot vient peut-être de Claydot lui-même : ignoré. */
    private fun reply(id: String, value: app.dotshermes.data.direct.CardReply) {
        if (android.os.SystemClock.uptimeMillis() - lastAutomatedTouch < AUTOMATED_GUARD_MS) return
        app.dotshermes.data.direct.ChatCards.host?.reply(id, value)
    }

    private fun openApp() {
        if (android.os.SystemClock.uptimeMillis() - lastAutomatedTouch < AUTOMATED_GUARD_MS) return
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        }
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        paint.isFakeBoldText = true
        background = GradientDrawable().apply { cornerRadius = dp(16f); setColor(color) }
        setPadding(dp(12f).toInt(), dp(5f).toInt(), dp(12f).toInt(), dp(5f).toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10f).toInt() }
        setOnClickListener { onClick() }
    }

    private fun answer(label: String, filled: Boolean = true, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        minHeight = dp(44f).toInt()
        setTextColor(if (filled) Color.rgb(24, 24, 28) else Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        paint.isFakeBoldText = true
        background = GradientDrawable().apply {
            cornerRadius = dp(22f)
            if (filled) setColor(Color.WHITE) else { setColor(Color.TRANSPARENT); setStroke(dp(1f).toInt(), Color.argb(120, 255, 255, 255)) }
        }
        setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f).toInt() }
        setOnClickListener { onClick() }
    }

    companion object {
        val CURSOR_COLOR = Color.rgb(255, 138, 61)
        /** Dernier geste envoyé par Claydot (toucher, glisser) : ses propres gestes ne répondent jamais à une carte. */
        @Volatile var lastAutomatedTouch = 0L
        private const val AUTOMATED_GUARD_MS = 1_500L
        private const val DONE_SHOWN_MS = 15_000L
    }
}

/** Le curseur : un rond orange cerclé de blanc, qui se déplace et lance une onde à chaque toucher. */
private class CursorView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ScreenOverlay.CURSOR_COLOR }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3 * density }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * density }
    private var x = -1f; private var y = -1f
    private var waveProgress = 1f
    private var mover: ValueAnimator? = null
    private var waver: ValueAnimator? = null

    fun reset() { mover?.cancel(); waver?.cancel(); x = -1f; y = -1f; waveProgress = 1f }

    private fun glide(toX: Float, toY: Float, durationMs: Long, then: () -> Unit) {
        mover?.cancel()
        if (x < 0) { x = toX; y = toY; invalidate(); then(); return }
        val (fx, fy) = x to y
        mover = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs; interpolator = DecelerateInterpolator()
            addUpdateListener { x = fx + (toX - fx) * it.animatedFraction; y = fy + (toY - fy) * it.animatedFraction; invalidate() }
            doOnEnd(then)
            start()
        }
    }

    fun moveAndTap(toX: Float, toY: Float, long: Boolean) = glide(toX, toY, 260) { pulse(if (long) 900 else 500) }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) = glide(x1, y1, 200) { glide(x2, y2, 320) {} }

    private fun pulse(durationMs: Long) {
        waver?.cancel()
        waver = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener { waveProgress = it.animatedFraction; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (x < 0) return
        if (waveProgress < 1f) {
            wave.color = ScreenOverlay.CURSOR_COLOR
            wave.alpha = ((1f - waveProgress) * 220).toInt()
            canvas.drawCircle(x, y, (14 + 26 * waveProgress) * density, wave)
        }
        canvas.drawCircle(x, y + 2 * density, 12 * density, shadow)
        canvas.drawCircle(x, y, 11 * density, fill)
        canvas.drawCircle(x, y, 11 * density, ring)
    }
}

private fun ValueAnimator.doOnEnd(block: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
    private var cancelled = false
    override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
    override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) block() }
})
