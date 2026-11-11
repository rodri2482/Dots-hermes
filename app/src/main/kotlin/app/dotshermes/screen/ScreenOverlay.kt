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
 * « Claydot utilise ton écran · Arrêter ». Deux fenêtres d'accessibilité : le curseur ne capte aucun
 * toucher (tout passe au travers), seule la pastille est touchable.
 */
internal class ScreenOverlay(private val context: Context, private val onStop: () -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val cursor = CursorView(context)
    private var cursorShown = false
    private var pill: View? = null
    private var active = false
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
        if (on) {
            addCursor()
            if (pill == null) pill = buildPill().also { view ->
                runCatching { windows.addView(view, params(true, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
                    .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(44f).toInt() }) }
            }
        } else {
            pill?.let { runCatching { windows.removeView(it) } }; pill = null
            cursor.removeCallbacks(hide); cursor.postDelayed(hide, 1_500)
        }
    }

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

    fun dispose() { active = false; pill?.let { runCatching { windows.removeView(it) } }; pill = null; removeCursor() }

    private fun buildPill(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padH = dp(14f).toInt(); val padV = dp(8f).toInt()
        setPadding(padH, padV, dp(6f).toInt(), padV)
        background = GradientDrawable().apply { cornerRadius = dp(22f); setColor(Color.argb(235, 24, 24, 28)) }
        elevation = dp(6f)
        addView(View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CURSOR_COLOR) }
            layoutParams = LinearLayout.LayoutParams(dp(9f).toInt(), dp(9f).toInt()).apply { marginEnd = dp(8f).toInt() }
        })
        addView(TextView(context).apply {
            text = context.getString(R.string.screen_overlay_working)
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        addView(TextView(context).apply {
            text = context.getString(R.string.screen_overlay_stop)
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            paint.isFakeBoldText = true
            background = GradientDrawable().apply { cornerRadius = dp(16f); setColor(Color.argb(255, 214, 59, 47)) }
            setPadding(dp(12f).toInt(), dp(5f).toInt(), dp(12f).toInt(), dp(5f).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10f).toInt() }
            setOnClickListener { onStop() }
        })
    }

    companion object { val CURSOR_COLOR = Color.rgb(255, 138, 61) }
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
