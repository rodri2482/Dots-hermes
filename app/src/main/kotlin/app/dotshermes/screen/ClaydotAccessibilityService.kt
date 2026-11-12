package app.dotshermes.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.net.toUri
import app.dotshermes.data.direct.PhoneActions
import app.dotshermes.data.direct.ScreenDriver
import app.dotshermes.data.direct.ScreenElement
import app.dotshermes.data.direct.ScreenSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Service d'accessibilité de Claydot : il ne fait rien de lui-même. Il ne réagit à aucun événement,
 * ne garde rien ; il sert seulement à lire l'écran et à toucher quand une tâche a été acceptée
 * (voir ScreenTools). L'utilisateur l'active lui-même dans les réglages d'accessibilité d'Android.
 * Pendant une tâche, un curseur montre où Claydot touche et une pastille « Arrêter » reste en haut.
 */
class ClaydotAccessibilityService : AccessibilityService() {
    internal var overlay: ScreenOverlay? = null

    override fun onServiceConnected() {
        instance = this
        overlay = ScreenOverlay(this) { onStopRequested() }
        _running.value = true
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean { stopped(); return super.onUnbind(intent) }
    override fun onDestroy() { stopped(); super.onDestroy() }
    private fun stopped() {
        overlay?.dispose(); overlay = null
        if (instance === this) instance = null
        _running.value = false
    }

    companion object {
        @Volatile internal var instance: ClaydotAccessibilityService? = null
        private val _running = MutableStateFlow(false)
        /** Activé dans les réglages Android (et branché). */
        val running: StateFlow<Boolean> = _running.asStateFlow()
        /** « Arrêter » dans la pastille : branché par AppContainer (retire l'accord, arrête la réponse). */
        @Volatile var onStopRequested: () -> Unit = {}
    }
}

/** Le pilote réel : l'arbre d'accessibilité de la fenêtre active, réduit aux éléments utiles. */
class AccessibilityScreenDriver(private val context: Context, private val phone: PhoneActions) : ScreenDriver {
    private companion object { const val SCREEN_NOTIFICATION = 4243 }
    /** Les nœuds de la dernière lecture, par numéro. */
    @Volatile private var nodes: Map<Int, AccessibilityNodeInfo> = emptyMap()
    private val main = Handler(Looper.getMainLooper())

    private val service get() = ClaydotAccessibilityService.instance

    override fun connected() = service != null

    override fun showActive(on: Boolean) {
        main.post { service?.overlay?.setActive(on) }
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        if (!on) { manager?.cancel(SCREEN_NOTIFICATION); return }
        runCatching { manager?.notify(SCREEN_NOTIFICATION, app.dotshermes.data.direct.RoutineAlarms.liveNotification(context,
            context.getString(app.dotshermes.R.string.screen_overlay_working), "")) }
    }

    private fun showTap(r: Rect, long: Boolean = false) { main.post { service?.overlay?.tapAt(r.exactCenterX(), r.exactCenterY(), long) } }

    override fun snapshot(): ScreenSnapshot? {
        val root = service?.rootInActiveWindow ?: return null
        val found = LinkedHashMap<Int, AccessibilityNodeInfo>()
        val elements = ArrayList<ScreenElement>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || elements.size >= 200 || !node.isVisibleToUser) return
            val text = listOfNotNull(node.text?.toString(), node.contentDescription?.toString(), node.hintText?.toString()?.let { "hint: $it" })
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" · ")
            val role = when {
                node.isEditable -> "edit"
                node.isClickable || node.isCheckable -> if (node.isCheckable) (if (node.isChecked) "checked" else "unchecked") else "button"
                node.isScrollable -> "scroll"
                else -> "text"
            }
            if (text.isNotEmpty() || role != "text") {
                val id = elements.size + 1
                val r = Rect().also(node::getBoundsInScreen)
                elements += ScreenElement(id, if (node.isPassword) "" else text, role, "${r.left},${r.top},${r.right},${r.bottom}", node.isPassword)
                found[id] = node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        nodes = found
        return ScreenSnapshot(root.packageName?.toString().orEmpty(), elements)
    }

    private fun gesture(path: Path, durationMs: Long): Boolean =
        service?.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build(), null, null) == true

    override fun tap(id: Int): Boolean {
        val node = nodes[id] ?: return false
        val r = Rect().also(node::getBoundsInScreen)
        showTap(r)
        // Le texte n'est souvent pas lui-même cliquable : on remonte au parent qui l'est.
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        if (r.isEmpty) return false
        return gesture(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 60)
    }

    override fun longPress(id: Int): Boolean {
        val node = nodes[id] ?: return false
        val r = Rect().also(node::getBoundsInScreen)
        showTap(r, long = true)
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isLongClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true) return true
        if (r.isEmpty) return false
        return gesture(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 700)
    }

    override fun type(id: Int, text: String): Boolean {
        val node = nodes[id]?.takeIf { it.isEditable && !it.isPassword } ?: return false
        showTap(Rect().also(node::getBoundsInScreen))
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun scroll(down: Boolean): Boolean {
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        nodes.values.firstOrNull { it.isScrollable }?.let { node ->
            showTap(Rect().also(node::getBoundsInScreen))
            if (node.performAction(action)) return true
        }
        return swipe(if (down) "up" else "down")
    }

    /** Glissement du doigt vers [direction] (« up » fait monter le contenu, comme le doigt). */
    override fun swipe(direction: String): Boolean {
        val m = context.resources.displayMetrics
        val (w, h) = m.widthPixels.toFloat() to m.heightPixels.toFloat()
        val (from, to) = when (direction) {
            "up" -> (w / 2 to h * 0.7f) to (w / 2 to h * 0.3f)
            "down" -> (w / 2 to h * 0.3f) to (w / 2 to h * 0.7f)
            "left" -> (w * 0.8f to h / 2) to (w * 0.2f to h / 2)
            "right" -> (w * 0.2f to h / 2) to (w * 0.8f to h / 2)
            else -> return false
        }
        main.post { service?.overlay?.swipe(from.first, from.second, to.first, to.second) }
        return gesture(Path().apply { moveTo(from.first, from.second); lineTo(to.first, to.second) }, 300)
    }

    override fun press(key: String): Boolean {
        val action = when (key) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "enter" -> return enter()
            else -> return false
        }
        return service?.performGlobalAction(action) == true
    }

    /** Touche Entrée / Rechercher du clavier, sur le champ qui a le focus. */
    private fun enter(): Boolean {
        val field = service?.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        showTap(Rect().also(field::getBoundsInScreen))
        return android.os.Build.VERSION.SDK_INT >= 30 &&
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    override fun launch(name: String): String? {
        if (name.isEmpty()) return null
        val (label, intent) = phone.findApp(name) ?: return null
        return try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); label } catch (_: Exception) { null }
    }

    override suspend fun screenshot(): ByteArray? = capture()?.let(::jpeg)

    /** Réduite (1280 px au plus) et en JPEG : légère dans la conversation. */
    private fun jpeg(bitmap: android.graphics.Bitmap): ByteArray {
        val scale = 1280f / maxOf(bitmap.width, bitmap.height)
        val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        return java.io.ByteArrayOutputStream().use { out -> small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out); out.toByteArray() }
    }

    override suspend fun markedScreenshot(): ByteArray? {
        val shot = snapshot() ?: return null
        val bitmap = capture()?.copy(android.graphics.Bitmap.Config.ARGB_8888, true) ?: return null
        val canvas = android.graphics.Canvas(bitmap)
        val density = context.resources.displayMetrics.density
        val box = android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 2.5f * density; isAntiAlias = true }
        val tag = android.graphics.Paint().apply { style = android.graphics.Paint.Style.FILL }
        val label = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 12f * density; isFakeBoldText = true; isAntiAlias = true }
        val palette = intArrayOf(0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFFB8C00.toInt(), 0xFF8E24AA.toInt(), 0xFF00897B.toInt())
        for (e in shot.elements) {
            val p = e.bounds.split(',').mapNotNull { it.toIntOrNull() }
            if (p.size != 4 || p[2] <= p[0] || p[3] <= p[1]) continue
            val color = palette[e.id % palette.size]
            box.color = color; tag.color = color
            canvas.drawRect(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), box)
            val text = e.id.toString()
            val w = label.measureText(text) + 6 * density
            val h = label.textSize + 4 * density
            canvas.drawRect(p[0].toFloat(), p[1].toFloat(), p[0] + w, p[1] + h, tag)
            canvas.drawText(text, p[0] + 3 * density, p[1] + label.textSize, label)
        }
        return jpeg(bitmap)
    }

    override fun screenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(android.view.WindowManager::class.java)
        return if (android.os.Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            else context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }

    override fun tapAt(x: Float, y: Float, long: Boolean): Boolean {
        showTap(Rect(x.toInt() - 1, y.toInt() - 1, x.toInt() + 1, y.toInt() + 1), long)
        return gesture(Path().apply { moveTo(x, y) }, if (long) 700 else 60)
    }

    override fun swipeBetween(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        main.post { service?.overlay?.swipe(x1, y1, x2, y2) }
        return gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 350)
    }

    override fun typeFocused(text: String): Boolean {
        val field = service?.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && !it.isPassword }
            ?: nodes.values.firstOrNull { it.isEditable && !it.isPassword } ?: return false
        showTap(Rect().also(field::getBoundsInScreen))
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Capture de l'écran entier (Android 11+), en image modifiable ; null si refusée. */
    private suspend fun capture(): android.graphics.Bitmap? {
        val s = service ?: return null
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        val bitmap = kotlinx.coroutines.suspendCancellableCoroutine<android.graphics.Bitmap?> { cont ->
            s.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, s.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val copy = runCatching {
                        android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    result.hardwareBuffer.close()
                    cont.resumeWith(Result.success(copy))
                }
                override fun onFailure(errorCode: Int) { cont.resumeWith(Result.success(null)) }
            })
        }
        return bitmap
    }

    override fun openUrl(url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }
}
